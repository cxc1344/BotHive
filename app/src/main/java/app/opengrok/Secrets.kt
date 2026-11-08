package app.opengrok

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** API keys are stored as "enc:<base64(iv+ciphertext)>", AES-256-GCM, key held in Android Keystore. */
object Secrets {
    private const val ALIAS = "og_api_key"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return g.generateKey()
    }

    private fun encStrict(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return "enc:" + Base64.encodeToString(c.iv + ct, Base64.NO_WRAP)
    }

    private fun decStrict(s: String): String {
        val raw = Base64.decode(s.removePrefix("enc:"), Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
        return String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
    }

    /** Never silently writes an API key in plaintext when Android Keystore is unavailable. */
    fun enc(plain: String): String {
        if (plain.isEmpty()) return ""
        return encStrict(plain)
    }

    /** Legacy plaintext entries remain readable and are encrypted on the next successful save. */
    fun dec(s: String): String {
        if (!s.startsWith("enc:")) return s
        return try { decStrict(s) } catch (_: Exception) { "" }
    }

    fun isKeystoreWorking(): Boolean =
        try { decStrict(encStrict("probe")) == "probe" } catch (_: Exception) { false }
}
