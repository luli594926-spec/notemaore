package com.xnotes.alist

import android.content.Context

/**
 * How to reach the user's AList server. Either [token] (a permanent token copied from AList's
 * admin page) or [username] + [password] (exchanged for a token at sign-in); with neither, the
 * client goes in as AList's guest user, which works when the server allows guests.
 */
data class AlistConfig(
    val server: String = "",
    val username: String = "",
    val password: String = "",
    val token: String = "",
) {
    val isConfigured: Boolean get() = server.isNotBlank()
}

/**
 * Persists [AlistConfig] in its own SharedPreferences file (not the app's settings JSON, so the
 * secrets never end up in that file or in a merge with it). The password and token are sealed with
 * [SecretBox]; the server URL and username are stored plain.
 */
class AlistSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): AlistConfig = AlistConfig(
        server = prefs.getString(KEY_SERVER, "").orEmpty(),
        username = prefs.getString(KEY_USER, "").orEmpty(),
        password = SecretBox.open(prefs.getString(KEY_PASSWORD, null)).orEmpty(),
        token = SecretBox.open(prefs.getString(KEY_TOKEN, null)).orEmpty(),
    )

    /** False when the keystore refused to seal a secret (nothing is written then). */
    fun save(config: AlistConfig): Boolean {
        val pw = SecretBox.seal(config.password) ?: return false
        val tk = SecretBox.seal(config.token) ?: return false
        prefs.edit()
            .putString(KEY_SERVER, AlistPaths.normalizeServer(config.server))
            .putString(KEY_USER, config.username.trim())
            .putString(KEY_PASSWORD, pw)
            .putString(KEY_TOKEN, tk)
            .apply()
        return true
    }

    /** The folder the browser was last in, so it reopens there. */
    var lastPath: String
        get() = prefs.getString(KEY_LAST_PATH, "/") ?: "/"
        set(value) { prefs.edit().putString(KEY_LAST_PATH, AlistPaths.normalize(value)).apply() }

    fun clear() = prefs.edit().clear().apply()

    private companion object {
        const val PREFS = "alist"
        const val KEY_SERVER = "server"
        const val KEY_USER = "username"
        const val KEY_PASSWORD = "password_sealed"
        const val KEY_TOKEN = "token_sealed"
        const val KEY_LAST_PATH = "last_path"
    }
}
