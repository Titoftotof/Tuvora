package com.nuvio.tv.core.network

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class WorkspaceSwitchTransactionTest {
    @Test fun blockedRestartRestoresSelectionAndAllowsRetry() = runTest {
        var selected = "personal"
        var blocked = true
        val failure = runCatching {
            workspaceSwitchTransaction(
                switchAndRestart = { selected = "official"; awaitWorkspaceProcessExit(50) {} },
                rollback = { selected = "personal" },
                unblock = { blocked = false },
            )
        }.exceptionOrNull()
        assertTrue(failure is TimeoutCancellationException)
        assertEquals("personal", selected)
        assertFalse(blocked)
    }

    @Test fun failedRollbackStillUnblocksAndPreservesOriginalFailure() = runTest {
        val denied = SecurityException("launch denied")
        val diskFull = java.io.IOException("cannot save")
        var blocked = true
        val failure = runCatching {
            workspaceSwitchTransaction(
                switchAndRestart = { throw denied },
                rollback = { throw diskFull },
                unblock = { blocked = false },
            )
        }.exceptionOrNull()
        assertSame(denied, failure)
        assertArrayEquals(arrayOf(diskFull), failure!!.suppressed)
        assertFalse(blocked)
    }
}
