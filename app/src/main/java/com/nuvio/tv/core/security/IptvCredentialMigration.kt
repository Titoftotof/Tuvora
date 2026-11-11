package com.nuvio.tv.core.security

import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.data.local.SecurePreferencesDataStore
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** Migration is performed transactionally by the encrypted DataStore boundary, without a
 * completion flag that could hide a partial failure. Reads of inactive profiles retry too. */
@Singleton
class IptvCredentialMigration @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val playlists: com.nuvio.tv.core.iptv.content.M3UFileStore,
    private val content: com.nuvio.tv.core.iptv.content.IptvContentDb,
    private val cwCache: com.nuvio.tv.data.local.ContinueWatchingEnrichmentCache,
    private val matchIndex: com.nuvio.tv.core.iptv.match.XtreamMatchIndex
) {
    suspend fun migrateIfNeeded(profileId: Int) {
        for (feature in SecurePreferencesDataStore.features.keys) {
            factory.get(profileId, feature).data.first()
        }
    }

    suspend fun migrateExistingProfiles() {
        factory.secureExistingCredentials()
    }
}
