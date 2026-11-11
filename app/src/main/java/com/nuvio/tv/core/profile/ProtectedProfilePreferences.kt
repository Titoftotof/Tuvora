package com.nuvio.tv.core.profile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class ProtectedProfilePreferences(
    context: Context,
    name: String,
    private val keyAlias: String
) {
    private val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val lock = Any()

    fun read(profileId: Int): String? = synchronized(lock) {
        val key = "profile.$profileId"
        val value = preferences.getString(key, null) ?: return@synchronized null
        try {
            val parts = value.split('.', limit = 2)
            require(parts.size == 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(create = false), GCMParameterSpec(128, decode(parts[0])))
            cipher.updateAAD("$keyAlias:$key".toByteArray(Charsets.UTF_8))
            cipher.doFinal(decode(parts[1])).toString(Charsets.UTF_8)
        } catch (e: Exception) {
            // Keep the damaged/unreadable ciphertext on disk instead of silently deleting it.
            // A temporary keystore issue or migration glitch must not wipe user data.
            android.util.Log.w("ProtectedProfilePrefs", "Failed to decrypt entry for profile $profileId in $keyAlias", e)
            null
        }
    }

    fun write(profileId: Int, value: String?) = synchronized(lock) {
        val key = "profile.$profileId"
        val editor = preferences.edit()
        if (value == null) {
            editor.remove(key)
        } else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            cipher.updateAAD("$keyAlias:$key".toByteArray(Charsets.UTF_8))
            val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            editor.putString(key, "${encode(cipher.iv)}.${encode(encrypted)}")
        }
        if (!editor.commit()) throw IOException("Unable to save protected credentials")
    }

    fun hasAny(): Boolean = synchronized(lock) { preferences.all.keys.any { it.startsWith("profile.") } }

    fun clear() = synchronized(lock) {
        if (!preferences.edit().clear().commit()) throw IOException("Unable to clear protected credentials")
    }

    private fun secretKey(create: Boolean = true): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        if (!create) throw java.security.UnrecoverableKeyException("Protected profile key is missing")
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generateKey()
        }
    }

    private fun encode(value: ByteArray): String = Base64.encodeToString(value, Base64.NO_WRAP)
    private fun decode(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)
}
