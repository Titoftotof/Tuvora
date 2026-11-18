package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.UserProfile
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.profileDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "profile_settings",
    corruptionHandler = ReplaceFileCorruptionHandler { ex ->
        android.util.Log.e("ProfileDataStore", "DataStore corrupted: ${ex.message} — resetting to empty")
        emptyPreferences()
    }
)

internal data class ProfileSyncSnapshot(
    val profiles: List<UserProfile>,
    val revision: Long,
    val pending: Boolean
)

@Singleton
class ProfileDataStore internal constructor(
    private val context: Context,
    private val moshi: Moshi,
    private val dataStore: DataStore<Preferences>
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        moshi: Moshi
    ) : this(context, moshi, SnapshotGatedDataStore(context.profileDataStore))

    private val profilesJsonKey = stringPreferencesKey("profiles_json")
    private val profilesRevisionKey = longPreferencesKey("profiles_local_revision")
    private val profilesPendingKey = booleanPreferencesKey("profiles_pending_sync")
    private val activeProfileIdKey = intPreferencesKey("active_profile_id")
    private val hasEverSelectedProfileKey = booleanPreferencesKey("profile_has_ever_selected")
    private val rememberLastProfileEnabledKey = booleanPreferencesKey("remember_last_profile_enabled")
    private val confirmExitEnabledKey = booleanPreferencesKey("confirm_exit_enabled")
    private val startupSplashEnabledKey = booleanPreferencesKey("startup_splash_enabled")

    private val profileListType = Types.newParameterizedType(List::class.java, ProfileJson::class.java)

    val profilesList: Flow<List<UserProfile>> = dataStore.data.map { prefs ->
        val json = prefs[profilesJsonKey]
        if (json != null) {
            parseProfiles(json)
        } else {
            listOf(defaultPrimaryProfile())
        }
    }

    val activeProfileId: Flow<Int> = dataStore.data.map { prefs ->
        prefs[activeProfileIdKey] ?: 1
    }

    val hasEverSelectedProfile: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[hasEverSelectedProfileKey] ?: false
    }

    val rememberLastProfileEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[rememberLastProfileEnabledKey] ?: false
    }

    val confirmExitEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[confirmExitEnabledKey] ?: false
    }

    val startupSplashEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[startupSplashEnabledKey] ?: true
    }

    suspend fun setActiveProfile(id: Int) {
        dataStore.edit { prefs ->
            prefs[activeProfileIdKey] = id
            prefs[hasEverSelectedProfileKey] = true
        }
    }

    suspend fun setRememberLastProfileEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[rememberLastProfileEnabledKey] = enabled
        }
    }

    suspend fun setConfirmExitEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[confirmExitEnabledKey] = enabled
        }
    }

    suspend fun setStartupSplashEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[startupSplashEnabledKey] = enabled
        }
    }

    suspend fun upsertProfile(profile: UserProfile) {
        dataStore.edit { prefs ->
            val current = parseProfiles(prefs[profilesJsonKey]).toMutableList()
            val index = current.indexOfFirst { it.id == profile.id }
            if (index >= 0) {
                current[index] = profile
            } else {
                current.add(profile)
            }
            prefs[profilesJsonKey] = serializeProfiles(current)
            markLocalChange(prefs)
        }
    }

    suspend fun deleteProfile(id: Int) {
        if (id == 1) return
        dataStore.edit { prefs ->
            val current = parseProfiles(prefs[profilesJsonKey]).toMutableList()
            current.removeAll { it.id == id }
            prefs[profilesJsonKey] = serializeProfiles(current)
            markLocalChange(prefs)
            if ((prefs[activeProfileIdKey] ?: 1) == id) {
                prefs[activeProfileIdKey] = 1
            }
        }
    }

    suspend fun replaceAllProfiles(profiles: List<UserProfile>) {
        dataStore.edit { prefs ->
            val normalizedProfiles = normalizeProfiles(profiles)
            prefs[profilesJsonKey] = serializeProfiles(normalizedProfiles)
            markLocalChange(prefs)
            val activeId = prefs[activeProfileIdKey] ?: 1
            if (normalizedProfiles.none { it.id == activeId }) {
                prefs[activeProfileIdKey] = 1
            }
        }
    }

    private fun markLocalChange(prefs: MutablePreferences) {
        prefs[profilesRevisionKey] = (prefs[profilesRevisionKey] ?: 0L) + 1L
        prefs[profilesPendingKey] = true
    }

    internal suspend fun profileSyncSnapshot(): ProfileSyncSnapshot {
        val prefs = dataStore.data.first()
        return ProfileSyncSnapshot(
            parseProfiles(prefs[profilesJsonKey]),
            prefs[profilesRevisionKey] ?: 0L,
            prefs[profilesPendingKey] ?: false
        )
    }

    internal suspend fun markProfilesSynced(revision: Long) {
        dataStore.edit { prefs ->
            // A second edit may have happened while the upload was in flight.
            if ((prefs[profilesRevisionKey] ?: 0L) == revision) prefs[profilesPendingKey] = false
        }
    }

    /** Atomically reject a response fetched before a local edit, even if that edit is now uploaded. */
    internal suspend fun replaceProfilesFromRemote(profiles: List<UserProfile>, revision: Long): Set<Int>? {
        var previousIds: Set<Int>? = null
        dataStore.edit { prefs ->
            if (prefs[profilesPendingKey] == true || (prefs[profilesRevisionKey] ?: 0L) != revision) return@edit
            previousIds = parseProfiles(prefs[profilesJsonKey]).map { it.id }.toSet()
            val normalized = normalizeProfiles(profiles)
            prefs[profilesJsonKey] = serializeProfiles(normalized)
            prefs[profilesRevisionKey] = revision + 1L
            if (normalized.none { it.id == (prefs[activeProfileIdKey] ?: 1) }) prefs[activeProfileIdKey] = 1
        }
        return previousIds
    }

    internal suspend fun backupSnapshot(): Preferences {
        val prefs = dataStore.data.first().toMutablePreferences()
        // Use the existing Moshi schema, including on an untouched first installation.
        if (prefs[profilesJsonKey] == null) prefs[profilesJsonKey] = serializeProfiles(listOf(defaultPrimaryProfile()))
        return prefs.toPreferences()
    }

    suspend fun clearAll() {
        dataStore.edit { prefs ->
            prefs.clear()
        }
    }

    private fun defaultPrimaryProfile() = UserProfile(
        id = 1,
        name = context.getString(R.string.profile_default_name, 1),
        avatarColorHex = "#1E88E5"
    )

    private fun parseProfiles(json: String?): List<UserProfile> {
        if (json.isNullOrBlank()) return listOf(defaultPrimaryProfile())
        return try {
            val adapter = moshi.adapter<List<ProfileJson>>(profileListType)
            val parsed = adapter.fromJson(json) ?: return listOf(defaultPrimaryProfile())
            normalizeProfiles(parsed.map { it.toDomain() })
        } catch (e: Exception) {
            listOf(defaultPrimaryProfile())
        }
    }

    private fun normalizeProfiles(profiles: List<UserProfile>): List<UserProfile> {
        val nonEmpty = profiles.ifEmpty { listOf(defaultPrimaryProfile()) }
        if (nonEmpty.any { it.id == 1 }) return nonEmpty
        return listOf(defaultPrimaryProfile()) + nonEmpty
    }

    private fun serializeProfiles(profiles: List<UserProfile>): String {
        val adapter = moshi.adapter<List<ProfileJson>>(profileListType)
        return adapter.toJson(profiles.map { ProfileJson.fromDomain(it) })
    }
}

@com.squareup.moshi.JsonClass(generateAdapter = true)
internal data class ProfileJson(
    val id: Int,
    val name: String,
    val avatarColorHex: String,
    val usesPrimaryAddons: Boolean = false,
    val usesPrimaryPlugins: Boolean = false,
    val avatarId: String? = null,
    val avatarUrl: String? = null,
    val profileBackgroundId: String? = null,
    val profileBackgroundUrl: String? = null
) {
    fun toDomain() = UserProfile(
        id = id,
        name = name,
        avatarColorHex = avatarColorHex,
        usesPrimaryAddons = usesPrimaryAddons,
        usesPrimaryPlugins = usesPrimaryPlugins,
        avatarId = avatarId,
        avatarUrl = avatarUrl,
        profileBackgroundId = profileBackgroundId,
        profileBackgroundUrl = profileBackgroundUrl
    )

    companion object {
        fun fromDomain(profile: UserProfile) = ProfileJson(
            id = profile.id,
            name = profile.name,
            avatarColorHex = profile.avatarColorHex,
            usesPrimaryAddons = profile.usesPrimaryAddons,
            usesPrimaryPlugins = profile.usesPrimaryPlugins,
            avatarId = profile.avatarId,
            avatarUrl = profile.avatarUrl,
            profileBackgroundId = profile.profileBackgroundId,
            profileBackgroundUrl = profile.profileBackgroundUrl
        )
    }
}
