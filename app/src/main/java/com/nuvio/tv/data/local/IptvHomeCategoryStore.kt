package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nuvio.tv.core.iptv.home.IptvHomeCategorySelection
import com.nuvio.tv.core.profile.ProfileManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the user's selected IPTV categories for the Home screen, scoped per profile in DataStore.
 *
 * Guarantees:
 *  - Selections are stored separately per profile.
 *  - Empty list by default after app update (no categories added automatically per specification).
 *  - Ordered by user preference with D-pad commands.
 */
@Singleton
class IptvHomeCategoryStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    private val gson = Gson()
    private val selectionsKey = stringPreferencesKey("iptv_home_category_selections")

    companion object {
        private const val FEATURE = "iptv_home_categories"
    }

    private fun store(pid: Int = profileManager.activeProfileId.value) = factory.get(pid, FEATURE)

    private val type = object : TypeToken<List<IptvHomeCategorySelection>>() {}.type

    /** Continuous stream of enabled category selections for the currently active profile. */
    val selections: Flow<List<IptvHomeCategorySelection>> = profileManager.activeProfileId.flatMapLatest { pid ->
        selectionsForProfile(pid)
    }

    fun selectionsForProfile(profileId: Int): Flow<List<IptvHomeCategorySelection>> =
        factory.get(profileId, FEATURE).data.map { prefs -> decodeSelections(prefs[selectionsKey]) }

    /** Snapshot read for an explicit profile ID. */
    suspend fun getSelectionsForProfile(profileId: Int): List<IptvHomeCategorySelection> {
        val prefs = factory.get(profileId, FEATURE).data.first()
        return decodeSelections(prefs[selectionsKey])
    }

    /** Save or update the entire list of selections for the active profile. */
    suspend fun saveSelections(selectionsList: List<IptvHomeCategorySelection>) {
        saveSelectionsForProfile(profileManager.activeProfileId.value, selectionsList)
    }

    /** Save selections for a specific profile (used during profile copy and backup restore). */
    suspend fun saveSelectionsForProfile(profileId: Int, selectionsList: List<IptvHomeCategorySelection>) {
        factory.get(profileId, FEATURE).edit { prefs ->
            prefs[selectionsKey] = gson.toJson(selectionsList, type)
        }
    }

    /** Toggle a specific category selection on/off for the active profile. */
    suspend fun toggleCategory(selection: IptvHomeCategorySelection) {
        val pid = profileManager.activeProfileId.value
        val current = getSelectionsForProfile(pid).toMutableList()
        val index = current.indexOfFirst { it.rowKey == selection.rowKey }
        if (index >= 0) {
            val existing = current[index]
            current[index] = existing.copy(enabled = !existing.enabled)
        } else {
            current.add(selection.copy(enabled = true, order = current.size))
        }
        saveSelectionsForProfile(pid, current)
    }

    /** Reorder categories by moving an element from [fromIndex] to [toIndex]. */
    suspend fun reorder(fromIndex: Int, toIndex: Int) {
        val pid = profileManager.activeProfileId.value
        val current = getSelectionsForProfile(pid).toMutableList()
        if (fromIndex !in current.indices || toIndex !in current.indices || fromIndex == toIndex) return
        val item = current.removeAt(fromIndex)
        current.add(toIndex, item)
        val reindexed = current.mapIndexed { i, sel -> sel.copy(order = i) }
        saveSelectionsForProfile(pid, reindexed)
    }

    /** Atomic operations: rapid remote presses never overwrite a stale list. */
    suspend fun toggleForProfile(profileId: Int, selection: IptvHomeCategorySelection) {
        factory.get(profileId, FEATURE).edit { prefs ->
            val current = decodeSelections(prefs[selectionsKey]).sortedBy { it.order }.toMutableList()
            val index = current.indexOfFirst { it.rowKey == selection.rowKey }
            if (index < 0) current.add(selection.copy(enabled = true, order = current.size))
            else current[index] = current[index].copy(enabled = !current[index].enabled)
            prefs[selectionsKey] = gson.toJson(current, type)
        }
    }

    suspend fun moveForProfile(profileId: Int, rowKey: String, direction: Int) {
        require(direction == -1 || direction == 1)
        factory.get(profileId, FEATURE).edit { prefs ->
            val current = decodeSelections(prefs[selectionsKey]).sortedBy { it.order }.toMutableList()
            val visible = current.indices.filter { current[it].enabled }
            val position = visible.indexOfFirst { current[it].rowKey == rowKey }
            if (position < 0 || position + direction !in visible.indices) return@edit
            val a = visible[position]
            val b = visible[position + direction]
            val moved = current[a]
            current[a] = current[b]
            current[b] = moved
            prefs[selectionsKey] = gson.toJson(current.mapIndexed { i, row -> row.copy(order = i) }, type)
        }
    }

    private fun decodeSelections(json: String?): List<IptvHomeCategorySelection> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching { gson.fromJson<List<IptvHomeCategorySelection>>(json, type) }
            .getOrDefault(emptyList())
    }
}
