package com.xnotes.alist

/**
 * Pure path and URL helpers for AList (no Android), so they are unit-testable on the JVM.
 * AList paths are absolute, '/'-separated and unencoded (`/movies/第1课 intro.mp4`).
 */
object AlistPaths {

    /** Extensions the browser lists as playable. Folders are always listed. */
    val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "webm", "m3u8", "mov", "flv", "m4v", "ts")

    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun isVideo(name: String): Boolean = extension(name) in VIDEO_EXTENSIONS

    fun isHls(name: String): Boolean = extension(name) == "m3u8"

    /** `/a/b` from any spelling: leading slash added, duplicate and trailing slashes removed. */
    fun normalize(path: String): String {
        val parts = path.split('/').filter { it.isNotEmpty() && it != "." }
        return "/" + parts.joinToString("/")
    }

    fun join(dir: String, name: String): String = normalize(normalize(dir) + "/" + name)

    fun parent(path: String): String {
        val n = normalize(path)
        if (n == "/") return "/"
        return normalize(n.substringBeforeLast('/'))
    }

    fun name(path: String): String = normalize(path).substringAfterLast('/').ifEmpty { "/" }

    /** Breadcrumbs root first: `/a/b` → [("/", "/"), ("a", "/a"), ("b", "/a/b")]. */
    fun breadcrumbs(path: String): List<Pair<String, String>> {
        val out = mutableListOf("/" to "/")
        var acc = ""
        for (part in normalize(path).split('/').filter { it.isNotEmpty() }) {
            acc += "/$part"
            out += part to acc
        }
        return out
    }

    /** The server base with no trailing slash, `http://` added when no scheme was typed. */
    fun normalizeServer(server: String): String {
        var s = server.trim()
        if (s.isEmpty()) return s
        if (!s.contains("://")) s = "http://$s"
        return s.trimEnd('/')
    }

    /** RFC 3986 percent-encoding of one path segment (UTF-8; unreserved characters kept). */
    fun encodeSegment(segment: String): String {
        val sb = StringBuilder()
        for (b in segment.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xff
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '.' || ch == '_' || ch == '~') {
                sb.append(ch)
            } else {
                sb.append('%').append(HEX[c shr 4]).append(HEX[c and 0xf])
            }
        }
        return sb.toString()
    }

    fun encodePath(path: String): String =
        normalize(path).split('/').joinToString("/") { encodeSegment(it) }

    /**
     * The direct-download URL AList serves for [path]: `{server}/d{path}?sign=…`. The sign is
     * required when the server signs downloads, and harmless to omit when it does not.
     */
    fun downloadUrl(server: String, path: String, sign: String?): String {
        val base = normalizeServer(server) + "/d" + encodePath(path)
        return if (sign.isNullOrEmpty()) base else base + "?sign=" + encodeSegment(sign)
    }

    private val HEX = "0123456789ABCDEF".toCharArray()
}
