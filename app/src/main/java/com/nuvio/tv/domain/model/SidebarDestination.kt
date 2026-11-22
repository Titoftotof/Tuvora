package com.nuvio.tv.domain.model

/**
 * Every entry the sidebar (Modern panel and Legacy rail) can show.
 *
 * Declared in the domain layer because the preference that hides an entry is stored per profile in
 * [com.nuvio.tv.data.local.LayoutPreferenceDataStore] and resolved by [com.nuvio.tv.SidebarMenuPolicy],
 * both of which are outside the Activity that renders the drawer.
 *
 * DISCOVER is listed last on purpose: its visibility is not driven by the "hidden sidebar entries"
 * preference but by [DiscoverLocation], so [com.nuvio.tv.SidebarMenuPolicy] keeps it out of that set.
 */
enum class SidebarDestination {
    HOME,
    SEARCH,
    LIBRARY,
    IPTV,
    IPTV_LIVE,
    IPTV_MOVIES,
    IPTV_SERIES,
    SPORTS,
    DISCOVER,
    SETTINGS
}
