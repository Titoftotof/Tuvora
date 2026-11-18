package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.nuvio.tv.core.analytics.resolveDiagnosticsEnabled
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton



@Singleton
class SentrySettingsDataStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val dataStore = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
        corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler {
            androidx.datastore.preferences.core.emptyPreferences()
        },
        produceFile = {
            java.io.File(com.nuvio.tv.core.network.BackendWorkspaceStore.deviceContext(context).filesDir, "datastore/sentry_settings.preferences_pb")
        }
    )
    private val enabledKey = booleanPreferencesKey("enabled")

    val enabled: Flow<Boolean> = dataStore.data.map { prefs ->
        resolveDiagnosticsEnabled(prefs[enabledKey])
    }

    suspend fun isEnabled(): Boolean {
        return enabled.first()
    }

    suspend fun setEnabled(value: Boolean) {
        dataStore.edit { prefs ->
            prefs[enabledKey] = value
        }
    }
}
