package com.nuvio.tv.core.profile

import androidx.datastore.preferences.core.*
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import kotlinx.coroutines.flow.first
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton
import android.util.Base64

/** A portable salted verifier, learned only after a successful server PIN check or PIN change. */
@Singleton
class PortableProfilePins @Inject constructor(private val factory: ProfileDataStoreFactory) {
    private val hashKey = stringPreferencesKey("verifier")
    private val importedKey = booleanPreferencesKey("imported")
    private val attempts = mutableMapOf<Int, Pair<Int, Long>>()
    private fun store(pid: Int) = factory.get(pid, "portable_profile_pin")
    suspend fun has(pid: Int) = !store(pid).data.first()[hashKey].isNullOrBlank()
    suspend fun isImported(pid: Int) = store(pid).data.first()[importedKey] == true
    suspend fun remember(pid: Int, pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = derive(pin, salt)
        store(pid).edit { it[hashKey] = b64(salt) + ":" + b64(hash); it[importedKey] = false }
    }
    suspend fun clear(pid: Int) { store(pid).edit { it.clear() } }
    suspend fun verify(pid: Int, pin: String): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        val attempt = synchronized(attempts) { attempts[pid] }
        if (attempt != null && attempt.first >= 5 && now - attempt.second < 30_000) return false
        val parts = store(pid).data.first()[hashKey]?.split(':') ?: return false
        if (parts.size != 2) return false
        val ok = MessageDigest.isEqual(derive(pin, Base64.decode(parts[0], Base64.NO_WRAP)), Base64.decode(parts[1], Base64.NO_WRAP))
        synchronized(attempts) {
            if (ok) attempts.remove(pid) else attempts[pid] = ((if (attempt == null || now - attempt.second >= 30_000) 0 else attempt.first) + 1) to now
        }
        return ok
    }
    private fun derive(pin: String, salt: ByteArray): ByteArray {
        return com.nuvio.tv.core.security.PasswordKdf.derive(pin, salt, 600_000)
    }
    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
}
