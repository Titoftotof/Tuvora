package com.nuvio.tv.ui.screens

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.mediaserver.ServerCatalog
import com.nuvio.tv.data.mediaserver.ServerItemRef
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.mergeCatalogPage
import com.nuvio.tv.domain.model.nextCatalogSkip
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class ServerCatalogViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val serverCatalog: ServerCatalog,
    private val profileManager: com.nuvio.tv.core.profile.ProfileManager
) : ViewModel() {
    private val collection = ServerItemRef.parse(savedStateHandle.get<String>("itemId"))
    private val connectionId = savedStateHandle.get<String>("addonId")?.let(ServerCatalog::connectionIdFromAddonId)
    private val catalogId = savedStateHandle.get<String>("catalogId")
    private val contributed = savedStateHandle.get<String>("addonId") == com.nuvio.tv.core.contracts.ContributedRows.ADDON_ID
    private val profileId = profileManager.activeProfileId.value
    private var contributedRow: com.nuvio.tv.core.contracts.ContributedHomeRow? = null
    private val _row = MutableStateFlow<CatalogRow?>(null)
    private val _failed = MutableStateFlow(false)
    val row: StateFlow<CatalogRow?> = _row.asStateFlow()
    val failed: StateFlow<Boolean> = _failed.asStateFlow()

    init { retry() }

    fun retry() {
        if (_row.value != null) { loadMore(); return }
        _failed.value = false
        viewModelScope.launch {
            _row.value = loadOrNull {
                when {
                    contributed -> {
                        val source = catalogId?.let { com.nuvio.tv.core.contracts.HomeSectionContributorRegistry.row(it) }
                        contributedRow = source
                        source?.takeUnless { it.loadFailed }?.let {
                            CatalogRow(
                                addonId = com.nuvio.tv.core.contracts.ContributedRows.ADDON_ID,
                                addonName = it.subtitle, addonBaseUrl = "", catalogId = it.key, catalogName = it.title,
                                type = com.nuvio.tv.domain.model.ContentType.fromString(it.rawType),
                                rawType = it.rawType, items = it.items, hasMore = it.hasMore,
                                supportsSkip = true, nextSkip = it.items.size
                            )
                        }
                    }
                    collection != null -> serverCatalog.collectionRow(collection)
                    connectionId != null && catalogId != null -> serverCatalog.row(connectionId, catalogId)
                    else -> null
                }
            }
            if (profileManager.activeProfileId.value != profileId) _row.value = null
            _failed.value = _row.value == null
        }
    }

    fun loadMore() {
        if (profileManager.activeProfileId.value != profileId) {
            _row.value = null
            _failed.value = true
            return
        }
        val current = _row.value ?: return
        if (current.isLoading || !current.hasMore) return
        _failed.value = false
        _row.value = current.copy(isLoading = true)
        viewModelScope.launch {
            val page = loadOrNull {
                val source = contributedRow
                if (source == null) serverCatalog.page(current, current.nextCatalogSkip())
                else com.nuvio.tv.core.contracts.HomeSectionContributorRegistry.loadSourcePage(
                    source.sourceKey, source.listId, current.nextSkip
                )?.let {
                    current.copy(items = it.items, hasMore = it.nextSkip != null,
                        nextSkip = it.nextSkip ?: current.nextSkip + it.items.size)
                }
            }
            if (profileManager.activeProfileId.value != profileId) {
                _row.value = null
                _failed.value = true
                return@launch
            }
            _failed.value = page == null
            _row.update { row ->
                row ?: return@update null
                page?.let(row::mergeCatalogPage)?.copy(isLoading = false) ?: row.copy(isLoading = false)
            }
        }
    }

    private suspend fun loadOrNull(block: suspend () -> CatalogRow?): CatalogRow? =
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
}
