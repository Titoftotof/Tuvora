package com.nuvio.tv.core.network

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.auth.AuthManager
import dagger.Lazy
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/** The backend is immutable until process restart; manifests only control rollout flags. */
@Singleton
class SyncBackendRepository @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val authManager: Lazy<AuthManager>,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutableState = MutableStateFlow(SyncBackendState())
    val state: StateFlow<SyncBackendState> = mutableState.asStateFlow()
    val selectedBackend: SyncBackendConfig get() { ensureLoaded(); return state.value.selectedBackend }

    @Synchronized fun ensureLoaded() {
        if (state.value.isLoaded) return
        val backend = checkNotNull(SyncBackendDefaults.byId(BackendWorkspaceStore.active.backendId))
        mutableState.value = SyncBackendState(selectedBackend = backend, isLoaded = true)
    }

    fun debugSelectableBackends(): List<SyncBackendConfig> =
        listOf(SyncBackendDefaults.nuvio(), SyncBackendDefaults.hosted()).filter { it.isUsableClientConfig() }

    suspend fun refreshFromManifest(): SyncBackendRefreshResult {
        ensureLoaded()
        val url = BuildConfig.SYNC_BACKEND_MANIFEST_URL.trim()
        if (url.isBlank()) return SyncBackendRefreshResult.NotConfigured
        return try {
            require(url.startsWith("https://"))
            val manifest = withContext(Dispatchers.IO) {
                okHttpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Sync manifest failed (${response.code})")
                    json.decodeFromString<SyncBackendManifest>(response.body?.string().orEmpty())
                }
            }
            // Never apply rollout decisions belonging to the other server.
            if (manifest.activeBackend.trim().lowercase() == selectedBackend.id) {
                PlaylistSyncRolloutSignal.raw = manifest.iptvPlaylistV2?.let { mode ->
                    PlaylistV2CohortPolicy.resolveMode(
                        rawMode = mode, percent = manifest.iptvPlaylistV2Percent,
                        platforms = manifest.iptvPlaylistV2Platforms,
                        userId = authManager.get().currentUserId, platform = "tv",
                    )
                }
            }
            SyncBackendRefreshResult.Unchanged
        } catch (_: Exception) {
            Log.w("SyncBackendRepository", "Unable to load playlist rollout manifest")
            mutableState.value = state.value.copy(lastManifestError = "Unable to load sync configuration")
            SyncBackendRefreshResult.Failed("Unable to load sync configuration")
        }
    }
}
