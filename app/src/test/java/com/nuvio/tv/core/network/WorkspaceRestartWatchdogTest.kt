package com.nuvio.tv.core.network

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class WorkspaceRestartWatchdogTest {
    @Test fun silentlyBlockedLaunchFailsInsteadOfReportingSuccess() = runTest {
        var launched = false
        val failure = runCatching { awaitWorkspaceProcessExit(50) { launched = true } }.exceptionOrNull()
        assertTrue(launched)
        assertTrue(failure is TimeoutCancellationException)
    }

    @Test fun launcherExceptionIsPropagatedForRollback() = runTest {
        val denied = SecurityException("Launch denied")
        val failure = runCatching { awaitWorkspaceProcessExit { throw denied } }.exceptionOrNull()
        // Coroutine stack-trace recovery may copy exceptions across suspension boundaries.
        assertTrue(failure is SecurityException)
        assertEquals(denied.message, failure!!.message)
    }
}
