package com.nuvio.tv

import com.nuvio.tv.domain.model.DiscoverLocation
import com.nuvio.tv.domain.model.SidebarDestination

/**
 * Resolves which destinations the sidebar actually renders.
 *
 * The entries themselves are built in MainActivity (they need Compose string resources and icons),
 * so the ordering and the "never leave the sidebar empty" rule live here as pure functions that JVM
 * tests can cover without an Activity.
 */

/** Entries in drawer order, Discover excluded (the Discover Location setting decides where it goes). */
val SIDEBAR_USER_CONTROLLED_ORDER: List<SidebarDestination> = listOf(
    SidebarDestination.HOME,
    SidebarDestination.SEARCH,
    SidebarDestination.LIBRARY,
    SidebarDestination.IPTV,
    SidebarDestination.SPORTS
)

/** Entries pinned after the user-controlled ones. */
val SIDEBAR_ALWAYS_VISIBLE: List<SidebarDestination> = listOf(SidebarDestination.SETTINGS)

/**
 * Destinations the "Show/Hide sidebar entries" toggles can switch off. Discover is excluded because
 * it is not a drawer-only entry: it can live inside the Search tab.
 */
val SIDEBAR_TOGGLEABLE_DESTINATIONS: List<SidebarDestination> = listOf(
    SidebarDestination.HOME,
    SidebarDestination.SEARCH,
    SidebarDestination.LIBRARY,
    SidebarDestination.IPTV,
    SidebarDestination.SPORTS,
    SidebarDestination.SETTINGS
)

fun SidebarDestination.isToggleable(): Boolean = this in SIDEBAR_TOGGLEABLE_DESTINATIONS

/** Drops anything stored that the toggles are not allowed to hide. */
fun sanitizeHiddenSidebarDestinations(
    hidden: Collection<SidebarDestination>
): Set<SidebarDestination> = hidden.filterTo(linkedSetOf()) { it.isToggleable() }

/**
 * Full ordered list of destinations for the current profile, already filtered.
 *
 * Order: [SIDEBAR_USER_CONTROLLED_ORDER], then Discover (only when it lives in the sidebar itself),
 * then [SIDEBAR_ALWAYS_VISIBLE]. Every entry except Settings can be hidden, but Settings is kept even
 * when the viewer hides it, so the settings screen that restores the entries never becomes unreachable.
 */
fun visibleSidebarDestinations(
    hidden: Collection<SidebarDestination>,
    discoverLocation: DiscoverLocation
): List<SidebarDestination> {
    val sanitized = sanitizeHiddenSidebarDestinations(hidden)
    val ordered = buildList {
        addAll(SIDEBAR_USER_CONTROLLED_ORDER)
        if (discoverLocation == DiscoverLocation.IN_SIDEBAR) add(SidebarDestination.DISCOVER)
        addAll(SIDEBAR_ALWAYS_VISIBLE)
    }
    val visible = ordered.filter { destination ->
        destination !in sanitized || destination in SIDEBAR_ALWAYS_VISIBLE
    }
    // An empty drawer would break LEFT-to-open and the BACK-to-sidebar behaviour, so the policy
    // guarantees at least Settings survives no matter what is stored.
    return visible.ifEmpty { SIDEBAR_ALWAYS_VISIBLE }
}

/**
 * Root routes owned by the sidebar. A hidden entry's route must leave this set, otherwise
 * MainActivity would still treat the screen as a sidebar destination: it would show the rail and
 * hijack BACK for a screen that has no matching drawer entry.
 */
fun sidebarRootRoutes(visible: List<SidebarDestination>): Set<String> =
    visible.mapTo(linkedSetOf(), ::sidebarRouteOf)

/**
 * Route string for an entry. Mirrors the `Screen` route constants; duplicated here so the policy
 * stays free of navigation and UI dependencies, which keeps it JVM unit-testable.
 */
fun sidebarRouteOf(destination: SidebarDestination): String = when (destination) {
    SidebarDestination.HOME -> "home"
    SidebarDestination.SEARCH -> "search"
    SidebarDestination.LIBRARY -> "library"
    SidebarDestination.IPTV -> "xtream_hub"
    SidebarDestination.SPORTS -> "sports_hub"
    SidebarDestination.DISCOVER -> "discover"
    SidebarDestination.SETTINGS -> "settings"
}
