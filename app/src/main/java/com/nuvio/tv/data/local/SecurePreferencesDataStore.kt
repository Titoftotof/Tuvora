package com.nuvio.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.security.SecureCredentialStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * Transactional encryption at the persistence boundary. Consumers see logical values; the delegate,
 * including its shadow copies, sees authenticated envelopes only. Whole JSON strings are protected
 * so unknown fields and credentials embedded in playlist URLs/pending operations are preserved.
 */
internal class SecurePreferencesDataStore(
    private val delegate: DataStore<Preferences>,
    private val credentials: SecureCredentialStore,
    private val profileId: Int,
    private val feature: String,
    private val protectedKeys: Set<String>?
) : DataStore<Preferences> {
    companion object {
        private const val PREFIX = "__tuvora_secure_v1__:"
        internal val features: Map<String, Set<String>?> = mapOf(
            "xtream_accounts" to null,
            "xtream_sync_state" to null,
            "debrid_settings" to setOf("torbox_api_key", "premiumize_api_key", "real_debrid_api_key"),
            "mdblist_settings" to setOf("mdblist_api_key"),
            "trakt_auth_store" to null,
            "animeskip_settings" to setOf("animeskip_client_id"),
            "portable_profile_pin" to null
        )
        internal val legacyFeatures: Set<String> = setOf(
            "iptv_home_categories",
            "player_settings",
            "addon_preferences",
            "plugin_settings",
            "library_preferences",
            "collections",
            "watch_progress_preferences",
            "watch_progress_recent_preferences",
            "watch_progress_archive_preferences",
            "watched_items_preferences"
        )
    }

    private fun protects(name: String) = protectedKeys == null || name in protectedKeys

    override val data: Flow<Preferences> = flow {
        // DataStore serializes this migration with all edits. A failed seal/verification leaves
        // the original on disk, and the next collection retries rather than marking it complete.
        delegate.updateData { raw -> encode(decode(raw), raw) }
        delegate.data.map { decode(it) }.collect { emit(it) }
    }

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
        val stored = delegate.updateData { raw ->
            encode(transform(decode(raw)), raw)
        }
        return decode(stored)
    }

    private suspend fun decode(raw: Preferences): Preferences {
        val result = raw.toMutablePreferences()
        for ((key, value) in raw.asMap()) {
            if (!protects(key.name) || value !is String || !value.startsWith(PREFIX)) continue
            result[stringPreferencesKey(key.name)] = open(key.name, value.removePrefix(PREFIX))
        }
        return result.toPreferences()
    }

    private suspend fun encode(logical: Preferences, previous: Preferences): Preferences {
        val result = logical.toMutablePreferences()
        for ((key, value) in logical.asMap()) {
            if (!protects(key.name) || value !is String) continue
            val old = previous[stringPreferencesKey(key.name)]
            if (old != null && old.startsWith(PREFIX) && open(key.name, old.removePrefix(PREFIX)) == value) {
                result[stringPreferencesKey(key.name)] = old
                continue
            }
            val envelope = credentials.seal(profileId, feature, key.name, value)
            if (open(key.name, envelope) != value) throw IOException("Credential verification failed")
            result[stringPreferencesKey(key.name)] = PREFIX + envelope
        }
        return result.toPreferences()
    }

    private suspend fun open(ref: String, envelope: String): String =
        when (val read = credentials.unseal(profileId, feature, ref, envelope)) {
            is SecureCredentialStore.ReadResult.Success -> read.value
            is SecureCredentialStore.ReadResult.Corrupted -> throw IOException("Encrypted preferences are corrupted", read.cause)
            is SecureCredentialStore.ReadResult.KeyUnavailable -> throw IOException("Preferences key is unavailable", read.cause)
            SecureCredentialStore.ReadResult.NotFound -> throw IOException("Encrypted preferences are unavailable")
        }
}

/**
 * Reads legacy encrypted values for features that were previously protected,
 * unwraps them, and saves them back to disk as plaintext for fast unencrypted access.
 * If unsealing fails, the raw value is preserved and never deleted.
 */
internal class LegacyDecryptingDataStore(
    private val delegate: DataStore<Preferences>,
    private val credentials: SecureCredentialStore,
    private val profileId: Int,
    private val feature: String
) : DataStore<Preferences> {
    companion object {
        private const val PREFIX = "__tuvora_secure_v1__:"
    }

    override val data: Flow<Preferences> = flow {
        try {
            delegate.updateData { raw ->
                val hasEncrypted = raw.asMap().values.any { it is String && it.startsWith(PREFIX) }
                if (hasEncrypted) decode(raw) else raw
            }
        } catch (_: Exception) {
            // Keep reading without failing app launch if migration commit fails
        }
        delegate.data.map { decode(it) }.collect { emit(it) }
    }

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
        return delegate.updateData { raw ->
            val decoded = decode(raw)
            transform(decoded)
        }
    }

    private suspend fun decode(raw: Preferences): Preferences {
        val result = raw.toMutablePreferences()
        for ((key, value) in raw.asMap()) {
            if (value !is String || !value.startsWith(PREFIX)) continue
            when (val read = credentials.unseal(profileId, feature, key.name, value.removePrefix(PREFIX))) {
                is SecureCredentialStore.ReadResult.Success -> {
                    result[stringPreferencesKey(key.name)] = read.value
                }
                else -> {
                    // Do not erase on failure - leave original value intact
                }
            }
        }
        return result.toPreferences()
    }
}
