package com.nuvio.tv.core.backup

import java.io.OutputStream

/**
 * Service orchestrating the creation of a password-encrypted `.tuvora-backup` archive file.
 */
interface BackupService {

    sealed interface BackupResult {
        data class Success(val bytesWritten: Long) : BackupResult
        data class PasswordTooShort(val minLength: Int) : BackupResult
        data class Failed(val cause: Throwable) : BackupResult
    }

    /**
     * Exports the current app configuration into [outputStream] encrypted with [password].
     *
     * Invariants:
     *  - [password] must be at least [BackupFormat.MIN_PASSWORD_LENGTH] characters.
     *  - Key derived using PBKDF2-HMAC-SHA256 with 600,000 iterations and a fresh 16-byte salt.
     *  - Authenticated with AES-256-GCM using the 40-byte header as AAD.
     *  - Secrets decrypted directly into the in-memory manifest; no temporary files holding plaintext.
     *  - Password is never persisted or logged.
     */
    suspend fun exportBackup(password: String, outputStream: OutputStream): BackupResult
}
