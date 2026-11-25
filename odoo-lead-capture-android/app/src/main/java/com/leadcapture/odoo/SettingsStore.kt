package com.leadcapture.odoo

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Persists the Odoo connection. The API key is encrypted with an Android Keystore key. */
class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("odoo_settings", Context.MODE_PRIVATE)

    fun load(): OdooConfig = OdooConfig(
        url = prefs.getString(KEY_URL, "").orEmpty(),
        db = prefs.getString(KEY_DB, "").orEmpty(),
        login = prefs.getString(KEY_LOGIN, "").orEmpty(),
        apiKey = prefs.getString(KEY_SECRET, null)?.let { KeystoreBox.decrypt(it) }.orEmpty(),
    )

    fun save(config: OdooConfig) {
        prefs.edit()
            .putString(KEY_URL, config.url.trim())
            .putString(KEY_DB, config.db.trim())
            .putString(KEY_LOGIN, config.login.trim())
            .putString(KEY_SECRET, KeystoreBox.encrypt(config.apiKey.trim()))
            .apply()
    }

    private companion object {
        const val KEY_URL = "url"
        const val KEY_DB = "db"
        const val KEY_LOGIN = "login"
        const val KEY_SECRET = "api_key_enc"
    }
}

private object KeystoreBox {
    private const val ALIAS = "odoo_api_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val b64 = Base64.getEncoder()
        return b64.encodeToString(cipher.iv) + ":" + b64.encodeToString(ct)
    }

    fun decrypt(stored: String): String? = try {
        val (iv, ct) = stored.split(":", limit = 2).map { Base64.getDecoder().decode(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(ct), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }
}
