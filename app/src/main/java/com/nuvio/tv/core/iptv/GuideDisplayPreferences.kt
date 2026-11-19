package com.nuvio.tv.core.iptv

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.guideDisplayStore by preferencesDataStore(name = "tv_guide_display")

/** Device-local display preference: no account sync or network work. */
class GuideDisplayPreferences(context: Context) {
    private val store = context.applicationContext.guideDisplayStore
    private val layoutKey = stringPreferencesKey("layout")
    private val hiddenCategoriesKey = stringSetPreferencesKey("hidden_shortcut_categories")
    private val preferences = store.data.catch { failure ->
        if (failure is IOException) emit(emptyPreferences()) else throw failure
    }

    val layout = preferences.map { prefs -> GuideLayoutPreset.fromStored(prefs[layoutKey]) }
    val hiddenCategories = preferences.map { it[hiddenCategoriesKey].orEmpty() }.distinctUntilChanged()

    suspend fun setCategoryVisible(category: String, visible: Boolean) {
        store.edit { prefs ->
            val hidden = prefs[hiddenCategoriesKey].orEmpty()
            prefs[hiddenCategoriesKey] = if (visible) hidden - category else hidden + category
        }
    }
    suspend fun setLayout(layout: GuideLayoutPreset) { store.edit { it[layoutKey] = layout.name } }
}
