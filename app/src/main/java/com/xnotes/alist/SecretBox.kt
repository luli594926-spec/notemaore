package com.xnotes.alist

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts small secrets (the AList password and token) with an AES-256-GCM key that never leaves
 * the Android Keystore. The ciphertext (`base64(iv ‖ ct+tag)`) is what goes into SharedPreferences.
 *
 * Chosen over androidx.security:security-crypto's EncryptedSharedPreferences, which is deprecated
 * and would be one more dependency; this is the same primitive it wraps. A blob restored from a
 * backup onto another device cannot be decrypted (the key is device-bound): [open] then returns
 * null and the user simply signs in again.
 */
internal object SecretBox {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "xnotes.alist.secret"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    /** The sealed form of [plain], or null if the keystore is unavailable. "" seals to "". */
    fun seal(plain: String): String? {
        if (plain.isEmpty()) return ""
        return runCatching {
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.ENCRYPT_MODE, key())
            val iv = c.iv
            val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
            val out = ByteArray(1 + iv.size + ct.size)
            out[0] = iv.size.toByte()
            System.arraycopy(iv, 0, out, 1, iv.size)
            System.arraycopy(ct, 0, out, 1 + iv.size, ct.size)
            Base64.encodeToString(out, Base64.NO_WRAP)
        }.getOrNull()
    }

    /** [blob] opened, or null when it is corrupt or sealed by a key this device does not hold. */
    fun open(blob: String?): String? {
        if (blob == null) return null
        if (blob.isEmpty()) return ""
        return runCatching {
            val raw = Base64.decode(blob, Base64.NO_WRAP)
            val ivLen = raw[0].toInt()
            require(ivLen in 12..16 && raw.size > 1 + ivLen)
            val iv = raw.copyOfRange(1, 1 + ivLen)
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            String(c.doFinal(raw, 1 + ivLen, raw.size - 1 - ivLen), Charsets.UTF_8)
        }.getOrNull()
    }
}
