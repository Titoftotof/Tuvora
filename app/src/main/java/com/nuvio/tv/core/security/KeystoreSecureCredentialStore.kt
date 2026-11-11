package com.nuvio.tv.core.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production implementation of [SecureCredentialStore] backed by Android Keystore + AES-256-GCM.
 *
 * Storage layout (SharedPreferences):
 *   File name: `secure_credentials_{service}`  (one file per service for blast-radius isolation)
 *   Key:       `v1.{profileId}.{ref}`
 *   Value:     `v1.{base64(nonce)}.{base64(ciphertext_with_tag)}`
 *
 * The Keystore alias is shared across all services (`com.tuvora.credentials.v1`) — there is no
 * per-profile or per-service key, because AES-GCM AAD already binds each ciphertext to its context.
 */
@Singleton
class KeystoreSecureCredentialStore internal constructor(
    private val context: Context,
    private val keyAlias: String
) : SecureCredentialStore {
    @Inject constructor(@ApplicationContext context: Context) : this(context, KEYSTORE_ALIAS)

    companion object {
        private const val TAG = "SecureCredStore"
        private const val KEYSTORE_ALIAS = "com.tuvora.credentials.v1"
        private const val PREFS_PREFIX = "secure_credentials_"
        private const val FORMAT_VERSION = "v1"
        private const val GCM_TAG_BITS = 128
        private const val SEPARATOR = '.'
    }

    // ---- Keystore key management ----------------------------------------------------------------

    /**
     * Returns the AES-256 Keystore key, creating it on first use.
     * @throws KeyPermanentlyInvalidatedException when the key exists but is permanently unusable.
     */
    @Synchronized
    private fun secretKey(create: Boolean = true): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        if (!create) throw UnrecoverableKeyException("Credential key is missing")
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generateKey()
        }
    }

    /**
     * Recognizes a missing, invalidated or temporarily unusable key. Returns `true` when a
     * decryption failure should be classified as
     * [SecureCredentialStore.ReadResult.KeyUnavailable] rather than [SecureCredentialStore.ReadResult.Corrupted].
     */
    private fun isKeyUnavailable(e: Throwable): Boolean {
        return e is KeyPermanentlyInvalidatedException ||
            e is UnrecoverableKeyException ||
            e is android.security.keystore.UserNotAuthenticatedException ||
            e is java.security.KeyStoreException ||
            (e is java.security.InvalidKeyException && e.cause is KeyPermanentlyInvalidatedException)
    }

    // ---- SharedPreferences access ---------------------------------------------------------------

    private fun prefs(service: String): SharedPreferences =
        context.getSharedPreferences("$PREFS_PREFIX$service", Context.MODE_PRIVATE)

    private fun storageKey(profileId: Int, ref: String): String =
        "$FORMAT_VERSION$SEPARATOR$profileId$SEPARATOR$ref"

    /** AAD ties the ciphertext to its owner — replaying it under a different profile/service/ref fails the GCM tag. */
    private fun aad(profileId: Int, service: String, ref: String): ByteArray =
        "$FORMAT_VERSION:$keyAlias:$profileId:$service:$ref".toByteArray(Charsets.UTF_8)

    // ---- Encryption / decryption ----------------------------------------------------------------

    private fun encrypt(plaintext: String, profileId: Int, service: String, ref: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        cipher.updateAAD(aad(profileId, service, ref))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val nonce = cipher.iv
        return "$FORMAT_VERSION$SEPARATOR${b64(nonce)}$SEPARATOR${b64(ciphertext)}"
    }

    private fun decrypt(stored: String, profileId: Int, service: String, ref: String): String {
        val parts = stored.split(SEPARATOR, limit = 3)
        require(parts.size == 3 && parts[0] == FORMAT_VERSION) { "Unknown credential format: ${parts.getOrNull(0)}" }
        val nonce = b64d(parts[1])
        val ciphertext = b64d(parts[2])
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        require(nonce.size == 12) { "Invalid credential nonce" }
        cipher.init(Cipher.DECRYPT_MODE, secretKey(create = false), GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(aad(profileId, service, ref))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    // ---- Interface implementation ---------------------------------------------------------------

    override suspend fun seal(profileId: Int, service: String, ref: String, value: String): String =
        withContext(Dispatchers.IO) { encrypt(value, profileId, service, ref) }

    override suspend fun unseal(profileId: Int, service: String, ref: String, envelope: String): SecureCredentialStore.ReadResult =
        withContext(Dispatchers.IO) {
            try {
                SecureCredentialStore.ReadResult.Success(decrypt(envelope, profileId, service, ref))
            } catch (e: Exception) {
                if (isKeyUnavailable(e)) SecureCredentialStore.ReadResult.KeyUnavailable(e)
                else SecureCredentialStore.ReadResult.Corrupted(e)
            }
        }

    override suspend fun read(profileId: Int, service: String, ref: String): SecureCredentialStore.ReadResult =
        withContext(Dispatchers.IO) {
            try {
                val stored = prefs(service).getString(storageKey(profileId, ref), null)
                    ?: return@withContext SecureCredentialStore.ReadResult.NotFound
                SecureCredentialStore.ReadResult.Success(decrypt(stored, profileId, service, ref))
            } catch (e: Exception) {
                if (isKeyUnavailable(e)) {
                    Log.w(TAG, "Credential key unavailable")
                    SecureCredentialStore.ReadResult.KeyUnavailable(e)
                } else {
                    Log.w(TAG, "Credential could not be decrypted")
                    SecureCredentialStore.ReadResult.Corrupted(e)
                }
            }
        }

    override suspend fun write(profileId: Int, service: String, ref: String, value: String) =
        withContext(Dispatchers.IO) {
            val encrypted = encrypt(value, profileId, service, ref)
            if (!prefs(service).edit().putString(storageKey(profileId, ref), encrypted).commit())
                throw java.io.IOException("Unable to persist credential")
            Unit
        }

    override suspend fun delete(profileId: Int, service: String, ref: String) =
        withContext(Dispatchers.IO) {
            if (!prefs(service).edit().remove(storageKey(profileId, ref)).commit())
                throw java.io.IOException("Unable to remove credential")
            Unit
        }

    override suspend fun deleteAllForProfile(profileId: Int) = withContext(Dispatchers.IO) {
        // Each service file may contain entries for this profile. Scan every secure_credentials_*
        // preferences file. The prefix convention makes enumeration straightforward.
        val prefix = "$FORMAT_VERSION$SEPARATOR$profileId$SEPARATOR"
        credentialPrefsFiles().forEach { sp ->
            val editor = sp.edit()
            var changed = false
            sp.all.keys.filter { it.startsWith(prefix) }.forEach { key ->
                editor.remove(key)
                changed = true
            }
            if (changed && !editor.commit()) throw java.io.IOException("Unable to clear credentials")
        }
    }

    override suspend fun hasAnyForProfile(profileId: Int): Boolean = withContext(Dispatchers.IO) {
        val prefix = "$FORMAT_VERSION$SEPARATOR$profileId$SEPARATOR"
        credentialPrefsFiles().any { sp -> sp.all.keys.any { it.startsWith(prefix) } }
    }

    override suspend fun readAllForProfile(
        profileId: Int,
        onDecryptionError: ((String, String, Throwable) -> Unit)?
    ): Map<Pair<String, String>, String> = withContext(Dispatchers.IO) {
        val result = mutableMapOf<Pair<String, String>, String>()
        val prefix = "$FORMAT_VERSION$SEPARATOR$profileId$SEPARATOR"

        for ((service, sp) in credentialPrefsFilesWithService()) {
            for (key in sp.all.keys) {
                if (!key.startsWith(prefix)) continue
                val ref = key.removePrefix(prefix)
                val stored = sp.getString(key, null) ?: continue
                try {
                    result[service to ref] = decrypt(stored, profileId, service, ref)
                } catch (e: Exception) {
                    Log.w(TAG, "Credential bulk read failed")
                    onDecryptionError?.invoke(service, ref, e)
                }
            }
        }
        result
    }

    // ---- Helpers ---------------------------------------------------------------------------------

    /** All SharedPreferences files matching the `secure_credentials_*` naming convention. */
    private fun credentialPrefsFiles(): List<SharedPreferences> {
        val dir = context.filesDir?.parentFile?.resolve("shared_prefs") ?: return emptyList()
        return (dir.list() ?: emptyArray())
            .filter { it.startsWith(PREFS_PREFIX) && it.endsWith(".xml") }
            .map { context.getSharedPreferences(it.removeSuffix(".xml"), Context.MODE_PRIVATE) }
    }

    private fun credentialPrefsFilesWithService(): List<Pair<String, SharedPreferences>> {
        val dir = context.filesDir?.parentFile?.resolve("shared_prefs") ?: return emptyList()
        return (dir.list() ?: emptyArray())
            .filter { it.startsWith(PREFS_PREFIX) && it.endsWith(".xml") }
            .map { fileName ->
                val prefsName = fileName.removeSuffix(".xml")
                val service = prefsName.removePrefix(PREFS_PREFIX)
                service to context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            }
    }

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun b64d(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)
}
