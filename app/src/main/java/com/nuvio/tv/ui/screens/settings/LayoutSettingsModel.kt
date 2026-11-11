package com.nuvio.tv.ui.screens.settings

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.automirrored.filled.ViewSidebar
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.RoundedCorner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.ui.graphics.vector.ImageVector
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.DiscoverLocation
import com.nuvio.tv.domain.model.SidebarDestination
import com.nuvio.tv.isToggleable
import com.nuvio.tv.visibleSidebarDestinations

internal enum class LayoutSection(
    @param:StringRes val title: Int,
    @param:StringRes val description: Int,
    val icon: ImageVector
) {
    HOME_LAYOUT(R.string.layout_section_home, R.string.layout_section_home_desc, Icons.Default.Dashboard),
    HOME_CONTENT(R.string.layout_section_content, R.string.layout_section_content_desc, Icons.Default.ViewAgenda),
    SIDEBAR(R.string.layout_section_sidebar, R.string.layout_section_sidebar_desc, Icons.AutoMirrored.Filled.ViewSidebar),
    CONTINUE_WATCHING(R.string.layout_section_continue_watching, R.string.layout_section_continue_watching_desc, Icons.Default.History),
    FOCUSED_POSTER(R.string.layout_section_focused, R.string.layout_section_focused_desc, Icons.Default.CenterFocusStrong),
    POSTER_CARD(R.string.layout_section_card_style, R.string.layout_section_card_style_desc, Icons.Default.RoundedCorner),
    CARD_DEPTH(R.string.settings_card_depth_title, R.string.layout_section_card_depth_desc, Icons.Default.ViewInAr),
    CUSTOM_POSTERS(R.string.layout_custom_poster_title, R.string.layout_section_custom_poster_desc, Icons.Default.PhotoLibrary),
    DETAIL_PAGE(R.string.layout_section_detail, R.string.layout_section_detail_desc, Icons.Default.Description),
    STREAMS(R.string.layout_section_streams, R.string.layout_section_streams_desc, Icons.AutoMirrored.Filled.ViewList)
}

internal fun visibleLayoutSections(
    essentialMode: Boolean,
    focusedPosterHasOptions: Boolean
): List<LayoutSection> =
    if (essentialMode) {
        listOf(LayoutSection.HOME_LAYOUT)
    } else {
        LayoutSection.entries.filter { section ->
            section != LayoutSection.FOCUSED_POSTER || focusedPosterHasOptions
        }
    }

internal object LayoutSettingsTestTags {
    fun section(section: LayoutSection): String = "layout_section_${section.name.lowercase()}"

    fun sidebarEntry(destination: SidebarDestination): String =
        "layout_sidebar_entry_${destination.name.lowercase()}"
}

/**
 * Label and icon for one sidebar entry row. Mirrors the drawer item MainActivity builds from the same
 * destinations, so the toggles read exactly like the entries they control.
 */
internal data class SidebarEntryUi(
    val destination: SidebarDestination,
    val label: Int,
    val icon: ImageVector
)

internal val sidebarEntryRows: List<SidebarEntryUi> = listOf(
    SidebarEntryUi(SidebarDestination.HOME, R.string.nav_home, Icons.Default.Home),
    SidebarEntryUi(SidebarDestination.SEARCH, R.string.nav_search, Icons.Default.Search),
    SidebarEntryUi(SidebarDestination.LIBRARY, R.string.nav_library, Icons.Default.VideoLibrary),
    SidebarEntryUi(SidebarDestination.IPTV, R.string.nav_iptv, Icons.Default.LiveTv),
    SidebarEntryUi(SidebarDestination.SPORTS, R.string.nav_sports, Icons.Default.SportsSoccer),
    SidebarEntryUi(SidebarDestination.DISCOVER, R.string.nav_discover, Icons.Default.Explore),
    SidebarEntryUi(SidebarDestination.SETTINGS, R.string.nav_settings, Icons.Default.Settings)
)

/**
 * Rows offered in the sidebar section: only destinations the toggles may hide, and Discover only when
 * it actually lives in the sidebar (otherwise its own Discover Location row owns it).
 */
internal fun visibleSidebarEntryRows(
    discoverLocation: DiscoverLocation
): List<SidebarEntryUi> = sidebarEntryRows.filter { row ->
    when (row.destination) {
        SidebarDestination.DISCOVER -> discoverLocation == DiscoverLocation.IN_SIDEBAR
        else -> row.destination.isToggleable()
    }
}

/**
 * A row is locked when it is the last visible sidebar entry: hiding it would leave the sidebar empty,
 * which would break LEFT-to-open and the BACK-to-sidebar behaviour.
 */
internal fun sidebarEntryIsLocked(
    uiState: LayoutSettingsUiState,
    destination: SidebarDestination
): Boolean {
    val visible = visibleSidebarDestinations(
        uiState.hiddenSidebarDestinations,
        uiState.discoverLocation
    )
    return visible.size <= 1 && destination in visible
}
