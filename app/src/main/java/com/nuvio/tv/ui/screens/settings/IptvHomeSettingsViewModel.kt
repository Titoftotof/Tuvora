package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.iptv.*
import com.nuvio.tv.core.iptv.home.*
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.IptvHomeCategoryStore
import com.nuvio.tv.data.local.XtreamAccountStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class IptvHomeSettingsState(
    val profileId: Int,
    val accounts: List<XtreamAccount> = emptyList(),
    val selections: List<IptvHomeCategorySelection> = emptyList(),
    val categories: List<XtreamCategory> = emptyList(),
    val loading: Boolean = false,
    val categoryOwner: String? = null,
    val loadFailed: Boolean = false,
    val saveFailed: Boolean = false,
    val ready: Boolean = false
)

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class IptvHomeSettingsViewModel @Inject constructor(
    private val profiles: ProfileManager,
    private val accounts: XtreamAccountStore,
    private val selections: IptvHomeCategoryStore,
    private val clients: IptvClientFactory,
    private val layout: com.nuvio.tv.data.local.LayoutPreferenceDataStore
) : ViewModel() {
    private val mutableState = MutableStateFlow(IptvHomeSettingsState(profiles.activeProfileId.value))
    val state = mutableState.asStateFlow()
    private val mutations = Mutex()
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            profiles.activeProfileId.collectLatest { pid ->
                loadJob?.cancel()
                mutableState.value = IptvHomeSettingsState(pid)
                combine(accounts.accounts, selections.selectionsForProfile(pid)) { providers, rows ->
                    providers to rows
                }.collect { (providers, rows) ->
                    mutableState.update { it.copy(accounts = providers.filter { a -> a.enabled },
                        selections = rows.sortedBy { r -> r.order }, ready = true) }
                }
            }
        }
    }

    fun load(accountId: String, type: IptvHomeContentType) {
        val pid = state.value.profileId
        loadJob?.cancel()
        mutableState.update { it.copy(loading = true, loadFailed = false, categories = emptyList(), categoryOwner = "$accountId|${type.key}") }
        loadJob = viewModelScope.launch {
            try {
                val categories = withTimeout(20_000) {
                    val account = accounts.accountsForProfile(pid).first { it.id == accountId && it.enabled }
                    val typeKey = if (type == IptvHomeContentType.MOVIES)
                        com.nuvio.tv.core.iptv.XtreamAccount.TYPE_MOVIES else com.nuvio.tv.core.iptv.XtreamAccount.TYPE_SERIES
                    if (!account.typeEnabled(typeKey)) emptyList() else {
                        val client = clients.clientFor(account)
                        withContext(Dispatchers.IO) {
                            when (type) {
                                IptvHomeContentType.MOVIES -> client.vodCategories(account)
                                IptvHomeContentType.SERIES -> client.seriesCategories(account)
                            }.getOrThrow().filter { account.allowsCategory(typeKey, it.id, it.name) }
                        }
                    }
                }
                if (profiles.activeProfileId.value == pid)
                    mutableState.update { it.copy(categories = categories, loading = false) }
            } catch (_: TimeoutCancellationException) {
                if (profiles.activeProfileId.value == pid) mutableState.update { it.copy(loading = false, loadFailed = true) }
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) {
                if (profiles.activeProfileId.value == pid) mutableState.update { it.copy(loading = false, loadFailed = true) }
            }
        }
    }

    fun toggle(row: IptvHomeCategorySelection) = mutate { pid ->
        if (state.value.selections.any { it.rowKey == row.rowKey && it.enabled } ||
            accounts.accountsForProfile(pid).any { it.id == row.providerId && it.enabled })
            selections.toggleForProfile(pid, row)
    }

    fun move(row: IptvHomeCategorySelection, direction: Int) = mutate { pid ->
        selections.moveForProfile(pid, row.rowKey, direction)
        val keys = selections.getSelectionsForProfile(pid).filter { it.enabled }.sortedBy { it.order }
            .map { com.nuvio.tv.core.contracts.ContributedRows.homeKey(it.rowKey, it.contentType.rawType) }
        val existing = layout.homeCatalogOrderKeys.first()
        val reordered = existing.toMutableList()
        val slots = reordered.indices.filter { reordered[it] in keys }
        slots.forEachIndexed { index, slot -> reordered[slot] = keys[index] }
        reordered.addAll(keys.drop(slots.size))
        if (profiles.activeProfileId.value == pid) layout.setHomeCatalogOrderKeys(reordered)
    }

    private fun mutate(block: suspend (Int) -> Unit) {
        val pid = state.value.profileId
        viewModelScope.launch {
            mutations.withLock {
                if (profiles.activeProfileId.value != pid) return@withLock
                try {
                    block(pid)
                    mutableState.update { it.copy(saveFailed = false) }
                } catch (e: CancellationException) { throw e
                } catch (_: Exception) {
                    if (profiles.activeProfileId.value == pid) mutableState.update { it.copy(saveFailed = true) }
                }
            }
        }
    }
}
