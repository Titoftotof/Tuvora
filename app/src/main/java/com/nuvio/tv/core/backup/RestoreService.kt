package com.nuvio.tv.core.backup

import java.io.InputStream

/**
 * Service orchestrating inspection and restoration of a `.tuvora-backup` archive file.
 */
interface RestoreService {

    /** Structured preview of an archive's contents prior to destructive replacement. */
    sealed interface PreviewResult {
        data class Ready(
            val manifestVersion: Int,
            val appVersionName: String,
            val createdAtMs: Long,
            val profileCount: Int,
            val profileNames: List<String>,
            val iptvConnectionCount: Int,
            val debridConnectionsCount: Int,
            val requiresReauthServices: List<String>,
            val archiveSha256: String
        ) : PreviewResult

        data object WrongPassword : PreviewResult
        data object InvalidFormat : PreviewResult
        data class UnsupportedVersion(val foundVersion: Short, val maxSupported: Short) : PreviewResult
        data class Corrupted(val cause: Throwable) : PreviewResult
    }

    /** Outcome of the final restoration step. */
    sealed interface RestoreOutcome {
        data object Success : RestoreOutcome
        data class Failed(val cause: Throwable, val safetyBackupCreated: Boolean) : RestoreOutcome
    }

    /**
     * Inspects [inputStream] with [password] to validate format, authenticity, and return metadata
     * for user confirmation without modifying any local state.
     */
    suspend fun previewBackup(inputStream: InputStream, password: String): PreviewResult

    /**
     * Applies the validated archive from [inputStream] using [password].
     *
     * Invariants:
     *  - Prior to applying, creates an automatic local emergency backup of current state.
     *  - Replaces existing configuration upon successful verification of restored data.
     *  - Re-encrypts all imported credentials under the local device's Android Keystore key.
     *  - Suspends sync operations during installation to avoid pushing partial state to the server.
     */
    suspend fun applyRestore(inputStream: InputStream, password: String, expectedSha256: String): RestoreOutcome
}
