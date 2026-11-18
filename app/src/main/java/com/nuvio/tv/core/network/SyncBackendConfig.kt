package com.nuvio.tv.core.network

import com.nuvio.tv.BuildConfig
import kotlinx.serialization.Serializable

internal const val SYNC_BACKEND_HOSTED_ID = "hosted"
internal const val SYNC_BACKEND_NUVIO_ID = "nuvio"

@Serializable
data class SyncBackendConfig(
    val id: String,
    val displayName: String,
    val supabaseUrl: String,
    val anonKey: String,
    val avatarPublicBaseUrl: String,
    val schemaVersion: Int = 1,
    val tvLoginWebBaseUrl: String = "",
    val allowedQrOrigins: Set<String> = emptySet(),
) {
    fun acceptsQrUrl(url: String): Boolean = runCatching {
        val uri = java.net.URI(url)
        uri.scheme == "https" && uri.rawUserInfo == null && uri.fragment == null &&
            allowedQrOrigins.any { origin ->
                val trusted = java.net.URI(origin)
                uri.host.equals(trusted.host, ignoreCase = true) &&
                    (if (uri.port == -1) 443 else uri.port) == (if (trusted.port == -1) 443 else trusted.port)
            }
    }.getOrDefault(false)

    val normalizedSupabaseUrl: String
        get() = supabaseUrl.trim().trimEnd('/')

    val normalizedAvatarPublicBaseUrl: String
        get() = avatarPublicBaseUrl.trim().trimEnd('/')

    fun avatarStorageUrl(storagePath: String): String =
        "${normalizedAvatarPublicBaseUrl}/${storagePath.trim().trimStart('/')}"

    fun normalized(): SyncBackendConfig =
        copy(
            id = id.trim().lowercase(),
            supabaseUrl = normalizedSupabaseUrl,
            anonKey = anonKey.trim(),
            avatarPublicBaseUrl = normalizedAvatarPublicBaseUrl,
        )
}

@Serializable
data class SyncBackendManifest(
    val version: Int = 1,
    val activeBackend: String,
    val revision: String = "",
    val forceLogoutOnChange: Boolean = true,
    /** B24 — server-driven rollout of the IPTV-playlist v2 sync ("enabled"|"disabled"|"debug_only";
     *  null leaves the client build default). Lets v2 be turned on/killed without a client release. */
    val iptvPlaylistV2: String? = null,
    /** B24 canary — when "enabled", enrol only this percent (0..100) of accounts (stable-hash bucket). */
    val iptvPlaylistV2Percent: Int? = null,
    /** B24 canary — when "enabled", restrict to these platform tokens (TV uses "tv"). null ⇒ all. */
    val iptvPlaylistV2Platforms: List<String>? = null,
)

data class SyncBackendState(
    val selectedBackend: SyncBackendConfig = SyncBackendDefaults.hosted(),
    val appliedRevision: String = "",
    val isLoaded: Boolean = false,
    val lastManifestError: String? = null,
    val isManualDebugOverride: Boolean = false,
)

sealed interface SyncBackendRefreshResult {
    data object NotConfigured : SyncBackendRefreshResult
    data object Unchanged : SyncBackendRefreshResult
    data class Applied(
        val backend: SyncBackendConfig,
        val revision: String,
    ) : SyncBackendRefreshResult
    data class RequiresLogout(
        val currentBackend: SyncBackendConfig,
        val targetBackend: SyncBackendConfig,
        val revision: String,
        val forceLogout: Boolean,
    ) : SyncBackendRefreshResult
    data class Failed(val message: String) : SyncBackendRefreshResult
}

@Serializable
internal data class StoredSyncBackendSelection(
    val backend: SyncBackendConfig? = null,
    val backendId: String = "",
    val appliedRevision: String = "",
    val manualDebugOverride: Boolean = false,
)

object SyncBackendDefaults {
    // Public website client configuration; never use service_role / sb_secret keys here.
    const val OFFICIAL_URL = "https://qsonncwknzdixurjyqap.supabase.co"
    const val OFFICIAL_PUBLIC_KEY = "sb_publishable_WNnOu03ivQUcqVCxoREBMA_L9sgl94X"
    const val OFFICIAL_LOGIN_URL = "https://qsonncwknzdixurjyqap.functions.supabase.co/tv-login-approve"

    fun hosted(): SyncBackendConfig =
        SyncBackendConfig(
            id = SYNC_BACKEND_HOSTED_ID,
            displayName = "Serveur personnel",
            supabaseUrl = BuildConfig.SUPABASE_URL,
            tvLoginWebBaseUrl = BuildConfig.TV_LOGIN_WEB_BASE_URL,
            allowedQrOrigins = setOfNotNull(runCatching {
                val uri = java.net.URI(BuildConfig.TV_LOGIN_WEB_BASE_URL)
                require(uri.scheme == "https" && uri.host != null)
                "https://${uri.host}" + if (uri.port == -1) "" else ":${uri.port}"
            }.getOrNull()),
            anonKey = BuildConfig.SUPABASE_ANON_KEY,
            avatarPublicBaseUrl = BuildConfig.AVATAR_PUBLIC_BASE_URL
                .trim()
                .ifBlank { "${BuildConfig.SUPABASE_URL.trim().trimEnd('/')}/storage/v1/object/public/avatars" },
            schemaVersion = 1,
        ).normalized()

    fun nuvio(): SyncBackendConfig =
        SyncBackendConfig(
            id = SYNC_BACKEND_NUVIO_ID,
            displayName = "Tuvora officiel",
            supabaseUrl = OFFICIAL_URL,
            anonKey = OFFICIAL_PUBLIC_KEY,
            avatarPublicBaseUrl = "$OFFICIAL_URL/storage/v1/object/public/avatars",
            tvLoginWebBaseUrl = OFFICIAL_LOGIN_URL,
            allowedQrOrigins = setOf("https://qsonncwknzdixurjyqap.functions.supabase.co", "https://tuvora.co"),
            schemaVersion = 1,
        ).normalized()

    fun byId(id: String): SyncBackendConfig? =
        when (id.trim().lowercase()) {
            SYNC_BACKEND_HOSTED_ID -> hosted()
            SYNC_BACKEND_NUVIO_ID -> nuvio()
            else -> null
        }
}

internal fun SyncBackendConfig.hasSameConnectionIdentity(other: SyncBackendConfig): Boolean =
    id == other.id &&
        normalizedSupabaseUrl == other.normalizedSupabaseUrl &&
        anonKey.trim() == other.anonKey.trim() &&
        schemaVersion == other.schemaVersion

internal fun SyncBackendManifest.backendConfigForActiveBackend(): SyncBackendConfig? {
    if (version != 1) return null

    val activeId = activeBackend.trim().lowercase()
    return SyncBackendDefaults.byId(activeId)
        ?.takeIf { it.isUsableClientConfig() }
}

internal fun SyncBackendConfig.isUsableClientConfig(): Boolean =
    id in setOf(SYNC_BACKEND_HOSTED_ID, SYNC_BACKEND_NUVIO_ID) &&
        isHttpsEndpoint(normalizedSupabaseUrl) &&
        anonKey.isNotBlank() &&
        !anonKey.startsWith("<") &&
        !anonKey.startsWith("sb_secret_") &&
        isHttpsEndpoint(normalizedAvatarPublicBaseUrl) &&
        isHttpsEndpoint(tvLoginWebBaseUrl) && acceptsQrUrl(tvLoginWebBaseUrl)

private fun isHttpsEndpoint(url: String): Boolean = runCatching {
    val uri = java.net.URI(url)
    uri.scheme == "https" && !uri.host.isNullOrBlank() &&
        uri.rawUserInfo == null && uri.fragment == null
}.getOrDefault(false)
