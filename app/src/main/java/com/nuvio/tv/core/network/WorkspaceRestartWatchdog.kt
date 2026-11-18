package com.nuvio.tv.core.network

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withTimeout

/**
 * A successful restart ends this process. Returning from startActivity is not success:
 * Android may silently refuse a background launch. If still alive, let the caller roll back.
 */
internal suspend fun awaitWorkspaceProcessExit(
    timeoutMillis: Long = 5000,
    launch: () -> Unit,
): Nothing = withTimeout(timeoutMillis) {
    launch()
    awaitCancellation()
}
