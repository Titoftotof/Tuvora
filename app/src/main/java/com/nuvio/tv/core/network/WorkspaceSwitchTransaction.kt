package com.nuvio.tv.core.network

/** Restore the old selection and unblock sync if a process restart cannot complete. */
internal suspend fun workspaceSwitchTransaction(
    switchAndRestart: suspend () -> Unit,
    rollback: suspend () -> Unit,
    unblock: () -> Unit,
) {
    try {
        switchAndRestart()
    } catch (failure: Exception) {
        try {
            rollback()
        } catch (rollbackFailure: Exception) {
            failure.addSuppressed(rollbackFailure)
        } finally {
            unblock()
        }
        throw failure
    }
}
