package com.xnotes.alist

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * A minimal AList v3 client over [HttpURLConnection] (no new HTTP dependency):
 *
 * - `POST /api/auth/login {username, password}` → `data.token`
 * - `POST /api/fs/list {path, password:"", page, per_page, refresh:false}` (Authorization: token)
 * - `POST /api/fs/get {path}` → `data.raw_url`, `data.sign`
 *
 * The token comes from the config when one was pasted, else from signing in (cached for this
 * client's life). A 401 drops a signed-in token and retries once with a fresh sign-in. With no
 * credentials at all requests go without Authorization, i.e. as AList's guest.
 */
class AlistClient(config: AlistConfig) {
    private val config = config.copy(server = AlistPaths.normalizeServer(config.server))
    private val lock = Mutex()
    @Volatile private var signedInToken: String? = null

    val server: String get() = config.server

    /** The token for playback headers on the server's own host, if one is held. */
    val currentToken: String? get() = config.token.ifBlank { null } ?: signedInToken

    suspend fun login(): String = withContext(Dispatchers.IO) {
        val body = JSONObject().put("username", config.username).put("password", config.password)
        AlistJson.token(post("/api/auth/login", body, token = null)).also { signedInToken = it }
    }

    private suspend fun token(): String? {
        if (config.token.isNotBlank()) return config.token.trim()
        if (config.username.isBlank()) return null // guest
        return lock.withLock { signedInToken ?: login() }
    }

    /** POST [path] with auth, re-signing in once on a 401 when the token came from sign-in. */
    private suspend fun authed(path: String, body: JSONObject): String {
        val t = token()
        val first = withContext(Dispatchers.IO) { post(path, body, t) }
        val code = runCatching { JSONObject(first).optInt("code", 200) }.getOrDefault(200)
        if (code == 401 && config.token.isBlank() && config.username.isNotBlank()) {
            signedInToken = null
            val fresh = lock.withLock { login() }
            return withContext(Dispatchers.IO) { post(path, body, fresh) }
        }
        return first
    }

    suspend fun list(path: String, page: Int = 1, perPage: Int = 0, refresh: Boolean = false): AlistListing {
        val dir = AlistPaths.normalize(path)
        val body = JSONObject()
            .put("path", dir)
            .put("password", "")
            .put("page", page)
            .put("per_page", perPage)
            .put("refresh", refresh)
        return AlistJson.listing(dir, authed("/api/fs/list", body))
    }

    suspend fun get(path: String): AlistFile =
        AlistJson.file(authed("/api/fs/get", JSONObject().put("path", AlistPaths.normalize(path))))

    /**
     * The URLs to try for playing [path], best first, resolved **now** (raw URLs expire): the
     * storage's `raw_url`, then the server's own `/d{path}?sign=…` when a sign came back (or no
     * raw URL did). Throws [AlistException] when the server can't be reached or refuses.
     */
    suspend fun playbackUrls(path: String): List<String> {
        val f = get(path)
        val out = ArrayList<String>(2)
        f.rawUrl?.let { raw ->
            // Some drivers answer a server-relative proxy URL.
            out += if (raw.startsWith("/")) server + raw else raw
        }
        if (f.sign != null || out.isEmpty()) out += AlistPaths.downloadUrl(server, path, f.sign)
        return out.distinct()
    }

    /** True when [url] points at the AList server itself (so its token may be sent along). */
    fun isServerUrl(url: String): Boolean =
        server.isNotEmpty() && (url == server || url.startsWith("$server/"))

    private fun post(path: String, body: JSONObject, token: String?): String {
        if (server.isEmpty()) throw AlistException(-1, "No AList server configured")
        val conn = (URL(server + path).openConnection() as HttpURLConnection)
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json;charset=utf-8")
            conn.setRequestProperty("Accept", "application/json")
            if (!token.isNullOrEmpty()) conn.setRequestProperty("Authorization", token)
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            // AList reports most failures as HTTP 200 with a body code; a bare HTTP error has no envelope.
            if (status !in 200..299 && !text.trimStart().startsWith("{")) throw AlistException(status, "HTTP $status")
            return text
        } catch (e: IOException) {
            throw AlistException(-1, e.message ?: e.javaClass.simpleName)
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 15_000
    }
}
