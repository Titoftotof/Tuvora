package com.nuvio.tv.core.network

import android.content.Context
import com.nuvio.tv.core.auth.AuthManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Switch only at a process boundary. No client or DataStore is ever retargeted in a live process:
 * outstanding responses remain bound to the outgoing backend and its files.
 */
@Singleton
class SyncBackendSwitchService @Inject constructor(
    private val syncBackendRepository: SyncBackendRepository,
    private val authManager: AuthManager,
    @ApplicationContext private val context: Context,
) {
    private val mutex = Mutex()

    suspend fun refreshSelection() = mutex.withLock {
        syncBackendRepository.refreshFromManifest()
        Unit
    }

    suspend fun switchDebugBackend(backend: SyncBackendConfig): Result<Unit> = mutex.withLock {
        // Once started, leaving the screen must not interrupt the durable selection/restart.
        withContext(kotlinx.coroutines.NonCancellable) { runCatching {
            check(backend.isUsableClientConfig()) { "Invalid backend configuration" }
            check(!com.nuvio.tv.core.profile.ConfigurationMutation.active) { "Configuration copy in progress" }
            check(!java.io.File(context.filesDir, "configuration_restore").exists()) { "Restore in progress" }
            if (backend.id == syncBackendRepository.selectedBackend.id) return@runCatching
            BackendWorkspaceStore.beginSwitch()
            workspaceSwitchTransaction(
                switchAndRestart = {
                    authManager.disconnectForWorkspaceSwitch()
                    com.nuvio.tv.data.local.ConfigurationSnapshotGate.mutex.withLock {
                        withContext(Dispatchers.IO) { BackendWorkspaceStore.selectForNextLaunch(context, backend) }
                        WorkspaceRestartActivity.launch(context)
                    }
                },
                rollback = {
                    withContext(Dispatchers.IO) {
                        BackendWorkspaceStore.selectForNextLaunch(context, syncBackendRepository.selectedBackend)
                    }
                },
                unblock = BackendWorkspaceStore::cancelSwitch,
            )
        } }
    }
}
