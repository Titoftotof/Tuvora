package com.nuvio.tv.core.security

/**
 * Unified secure credential storage. Every secret persisted by the app — IPTV passwords, API keys,
 * OAuth tokens — goes through this interface instead of plain-text DataStore / SharedPreferences.
 *
 * Design:
 *  - AES-256-GCM with an Android Keystore–managed key; a fresh 12-byte nonce on every write.
 *  - Associated data (AAD) binds each ciphertext to its owner: `"v1:{keyAlias}:{profileId}:{service}:{ref}"`.
 *  - On-disk format is versioned (`v1.{base64(nonce)}.{base64(ciphertext+tag)}`) so future schemes
 *    can coexist without a flag-day migration.
 *  - All operations run off the main thread (`Dispatchers.IO`).
 *
 * A [ReadResult] distinguishes "not stored", "stored but the ciphertext is damaged / tampered",
 * and "stored but the Keystore key is permanently gone" so callers can show the right recovery UI
 * instead of silently losing data.
 */
interface SecureCredentialStore {

    /** Outcome of a read attempt. */
    sealed interface ReadResult {
        /** The secret was found and decrypted successfully. */
        data class Success(val value: String) : ReadResult

        /** No entry exists for this (profileId, service, ref) triple. */
        data object NotFound : ReadResult

        /**
         * An entry exists but could not be decrypted: bad format, AAD mismatch, or GCM tag failure.
         * The raw ciphertext is **not** deleted — the caller decides whether to prompt for re-entry
         * or attempt a later retry.
         */
        data class Corrupted(val cause: Throwable) : ReadResult

        /**
         * The Android Keystore key cannot currently be used (missing/invalidated key,
         * authentication required, or Keystore unavailable). Preserve the encrypted payload so
         * a transient failure can be retried. Replacement requires an explicit new value.
         */
        data class KeyUnavailable(val cause: Throwable) : ReadResult
    }

    /** Encrypt a value owned by a transactional store. The caller persists only this envelope. */
    suspend fun seal(profileId: Int, service: String, ref: String, value: String): String =
        throw UnsupportedOperationException("Envelope encryption is unavailable")

    /** Open a persisted envelope without creating a missing key or modifying the payload. */
    suspend fun unseal(profileId: Int, service: String, ref: String, envelope: String): ReadResult =
        throw UnsupportedOperationException("Envelope decryption is unavailable")

    /** Read a secret. Never throws for expected conditions — use the [ReadResult] variants. */
    suspend fun read(profileId: Int, service: String, ref: String): ReadResult

    /**
     * Write (insert or replace) a secret. Generates a fresh nonce and encrypts [value] under the
     * Keystore key. Creates the key on first use.
     */
    suspend fun write(profileId: Int, service: String, ref: String, value: String)

    /** Remove one secret. No-op if it does not exist. */
    suspend fun delete(profileId: Int, service: String, ref: String)

    /** Remove all standalone entries for [profileId]. Transactional envelopes belong to their DataStore. */
    suspend fun deleteAllForProfile(profileId: Int)

    /** True when a standalone entry exists for [profileId] (does not scan DataStore envelopes). */
    suspend fun hasAnyForProfile(profileId: Int): Boolean

    /**
     * Bulk-read standalone (service, ref) entries for [profileId].
     * Transactional envelopes are exported through their owning DataStore's logical API; they
     * are deliberately not duplicated here. Returns an empty map when no standalone entries exist.
     *
     * Entries that fail decryption are omitted from the map and reported via [onDecryptionError].
     */
    suspend fun readAllForProfile(
        profileId: Int,
        onDecryptionError: ((service: String, ref: String, cause: Throwable) -> Unit)? = null
    ): Map<Pair<String, String>, String>
}
