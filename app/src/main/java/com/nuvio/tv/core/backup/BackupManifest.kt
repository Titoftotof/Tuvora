package com.nuvio.tv.core.backup

import com.google.gson.annotations.SerializedName
import com.nuvio.tv.core.iptv.home.IptvHomeCategorySelection

/**
 * Portable versioned manifest representing the entire logical snapshot of an app installation.
 *
 * Exclusions strictly enforced (never included in the manifest):
 *  - Primary account passwords / device-bound sessions (Supabase JWT / refresh tokens).
 *  - Android Keystore private keys (these are device-bound hardware keys).
 *  - Device sync identities / client IDs.
 *  - Transient catalog caches, thumbnail caches, log files, temporary downloads.
 *  - In-flight network operations or pending pairing codes.
 *
 * Inclusions:
 *  - Profile configurations (names, colors, avatar IDs).
 *  - Portable salted PIN verification hashes.
 *  - Home layout and category selections (including IPTV home categories).
 *  - IPTV connections and transferable provider API keys (Debrid, MDBList, etc.).
 *  - Local saved favorites and watch progress.
 */
@androidx.annotation.Keep
data class BackupManifest(
    @SerializedName("manifest_version")
    val manifestVersion: Int = 1,

    @SerializedName("created_at_ms")
    val createdAtMs: Long,

    @SerializedName("app_version_name")
    val appVersionName: String,

    @SerializedName("app_version_code")
    val appVersionCode: Int,

    @SerializedName("profiles")
    val profiles: List<BackupProfileManifest> = emptyList(),

    @SerializedName("global_device_sources")
    val globalDeviceSources: String? = null,

    /** Base64 protobuf of logical preferences, encrypted by the outer archive. */
    @SerializedName("portable_stores")
    val portableStores: Map<String, String> = emptyMap(),

    @SerializedName("playlist_files")
    val playlistFiles: Map<String, String> = emptyMap()
)

@androidx.annotation.Keep
data class BackupProfileManifest(
    @SerializedName("profile_id")
    val profileId: Int,

    @SerializedName("name")
    val name: String,

    @SerializedName("avatar_color_hex")
    val avatarColorHex: String,

    @SerializedName("avatar_id")
    val avatarId: String? = null,

    /**
     * Portable salted PIN hash (`PBKDF2-HMAC-SHA256:salt:hash`), never raw PIN.
     * Restoring a profile preserves lock state without risking plaintext exposure.
     */
    @SerializedName("portable_pin_hash")
    val portablePinHash: String? = null,

    /** Profile-scoped settings exported by [ProfileSettingsSyncService]. */
    @SerializedName("settings_blob")
    val settingsBlob: String? = null,

    /** Configured IPTV accounts/playlists for this profile. */
    @SerializedName("iptv_accounts_json")
    val iptvAccountsJson: String? = null,

    /** IPTV categories selected for Home display. */
    @SerializedName("home_iptv_categories")
    val homeIptvCategories: List<IptvHomeCategorySelection> = emptyList(),

    /** Transferable provider credentials (Debrid, MDBList). */
    @SerializedName("provider_credentials")
    val providerCredentials: Map<String, String> = emptyMap(),

    /** Local favorites / saved library items. */
    @SerializedName("library_snapshot_json")
    val librarySnapshotJson: String? = null,

    /** Local watch progress items. */
    @SerializedName("watch_progress_json")
    val watchProgressJson: String? = null
)
