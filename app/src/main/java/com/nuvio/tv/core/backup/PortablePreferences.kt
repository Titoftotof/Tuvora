package com.nuvio.tv.core.backup

import android.util.Base64
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import okio.Buffer

/** Explicit portable allowlist: never export SDK sessions, sync journals or device identity. */
object PortablePreferences {
    val features = setOf(
        "theme_settings", "layout_settings", "experience_settings", "player_settings",
        "stream_badge_settings", "trailer_settings", "tmdb_settings", "mdblist_settings",
        "trakt_settings", "debrid_settings", "animeskip_settings", "track_preference",
        "addon_preferences", "xtream_accounts", "iptv_home_categories", "library_preferences",
        "collections", "watched_items_preferences", "watch_progress_preferences",
        "watch_progress_recent_preferences", "watch_progress_archive_preferences",
        "episode_shuffle", "search_history", "discover_selection", "ambient_mode_settings",
        "portable_profile_pin", "plugin_settings"
    )
    fun fileName(profileId: Int, feature: String) =
        (if (profileId == 1) feature else "${feature}_p$profileId") + ".preferences_pb"

    suspend fun encode(prefs: Preferences): String {
        val buffer = Buffer()
        PreferencesSerializer.writeTo(prefs, buffer)
        return Base64.encodeToString(buffer.readByteArray(), Base64.NO_WRAP)
    }

    suspend fun decode(encoded: String): Preferences =
        PreferencesSerializer.readFrom(Buffer().write(Base64.decode(encoded, Base64.NO_WRAP)))
}
