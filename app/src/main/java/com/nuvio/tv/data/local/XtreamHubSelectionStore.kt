package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** What the IPTV hub remembered from the last visit: provider id + section tab name. */
data class RememberedHubSelection(
    val accountId: String? = null,
    val section: String? = null,
)

/**
 * Profile-scoped persistence of the hub's last selection (Fix 1: sticky provider) — the provider
 * dropdown pick and the Live/Movies/Series tab, restored on the next entry. Mirrors
 * [XtreamLiveStore]'s key-in-the-IPTV-DataStore idiom.
 *
 * Deliberately DEVICE-LOCAL UI state: it lives in this DataStore, not on the [com.nuvio.tv.core.iptv.XtreamAccount],
 * precisely so it never rides the account sync — which device you were browsing on which provider
 * is not a fact about the playlist. Category and scroll state stay session-only on purpose.
 */
@Singleton
class XtreamHubSelectionStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    private val accountKey = stringPreferencesKey("xtream_hub_selected_account")
    private val sectionKey = stringPreferencesKey("xtream_hub_selected_section")

    private fun store(pid: Int = profileManager.activeProfileId.value) = factory.get(pid, FEATURE)

    /** Reactive selected provider for the active profile so split IPTV screens stay in sync. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val selectedAccountId: Flow<String?> = profileManager.activeProfileId
        .flatMapLatest { pid ->
            factory.get(pid, FEATURE).data.map { prefs -> prefs[accountKey] }
        }
        .distinctUntilChanged()

    /** One-shot read for the active profile — the hub restores from this at entry. */
    suspend fun read(): RememberedHubSelection {
        val prefs = store().data.first()
        return RememberedHubSelection(accountId = prefs[accountKey], section = prefs[sectionKey])
    }

    /** Persist what is on screen after a user pick — both halves, every time. */
    suspend fun save(accountId: String?, section: String) {
        store().edit { prefs ->
            if (accountId == null) prefs.remove(accountKey) else prefs[accountKey] = accountId
            prefs[sectionKey] = section
        }
    }

    /** Persist only the selected provider (used when a screen's section is fixed by its route). */
    suspend fun saveAccount(accountId: String?) {
        store().edit { prefs ->
            if (accountId == null) prefs.remove(accountKey) else prefs[accountKey] = accountId
        }
    }

    /**
     * Forget the remembered provider when [shouldForget] says so for it (keeps the section tab). The
     * caller owns the decision — a removed playlist's purge passes its pure policy check.
     */
    suspend fun forgetAccountIf(shouldForget: (rememberedAccountId: String?) -> Boolean) {
        store().edit { prefs ->
            if (shouldForget(prefs[accountKey])) prefs.remove(accountKey)
        }
    }

    companion object {
        private const val FEATURE = "xtream_accounts"   // reuse the IPTV datastore file
    }
}
