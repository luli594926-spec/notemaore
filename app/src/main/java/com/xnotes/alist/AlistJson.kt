package com.xnotes.alist

import org.json.JSONObject

/** One entry of a folder listing (`/api/fs/list` → data.content[]). */
data class AlistEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val modified: String,
    val sign: String?,
) {
    val isVideo: Boolean get() = !isDir && AlistPaths.isVideo(name)
}

data class AlistListing(val path: String, val entries: List<AlistEntry>, val total: Int)

/** `/api/fs/get` → data: what playback needs. */
data class AlistFile(val name: String, val isDir: Boolean, val rawUrl: String?, val sign: String?, val size: Long)

/** A non-200 AList `code`, or a transport failure. [code] 401 means the token is bad or expired. */
class AlistException(val code: Int, message: String) : Exception(message)

/**
 * Parses AList v3 response envelopes: `{"code":200,"message":"success","data":{…}}`.
 * Kept separate from the transport so it can be tested with canned bodies.
 */
object AlistJson {

    /** The `data` object of a successful envelope; throws [AlistException] for any other code. */
    fun data(body: String): JSONObject? {
        val root = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw AlistException(-1, "Not an AList response")
        }
        val code = root.optInt("code", -1)
        if (code != 200) throw AlistException(code, root.str("message").ifEmpty { "AList error $code" })
        return root.optJSONObject("data")
    }

    fun token(body: String): String {
        val t = data(body)?.optString("token").orEmpty()
        if (t.isEmpty()) throw AlistException(-1, "Login returned no token")
        return t
    }

    fun listing(dir: String, body: String): AlistListing {
        val d = data(body) ?: return AlistListing(AlistPaths.normalize(dir), emptyList(), 0)
        // An empty folder answers "content": null.
        val arr = d.optJSONArray("content")
        val out = ArrayList<AlistEntry>(arr?.length() ?: 0)
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.str("name")
                if (name.isEmpty()) continue
                out += AlistEntry(
                    name = name,
                    path = AlistPaths.join(dir, name),
                    isDir = o.optBoolean("is_dir", false),
                    size = o.optLong("size", 0L),
                    modified = o.str("modified"),
                    sign = o.str("sign").ifEmpty { null },
                )
            }
        }
        return AlistListing(AlistPaths.normalize(dir), out, d.optInt("total", out.size))
    }

    fun file(body: String): AlistFile {
        val d = data(body) ?: throw AlistException(-1, "Empty fs/get response")
        return AlistFile(
            name = d.str("name"),
            isDir = d.optBoolean("is_dir", false),
            rawUrl = d.str("raw_url").ifEmpty { null },
            sign = d.str("sign").ifEmpty { null },
            size = d.optLong("size", 0L),
        )
    }
}

/** A string field, "" for absent or JSON null (org.json's optString would give "null"). */
private fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key, "")
