package com.nuvio.tv

import com.nuvio.tv.domain.model.DiscoverLocation
import com.nuvio.tv.domain.model.SidebarDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SidebarMenuPolicyTest {

    @Test
    fun `default profile shows every entry in drawer order`() {
        assertEquals(
            listOf(
                SidebarDestination.HOME,
                SidebarDestination.SEARCH,
                SidebarDestination.LIBRARY,
                SidebarDestination.IPTV,
                SidebarDestination.SPORTS,
                SidebarDestination.SETTINGS
            ),
            visibleSidebarDestinations(
                hidden = emptySet(),
                discoverLocation = DiscoverLocation.IN_SEARCH
            )
        )
    }

    @Test
    fun `discover is only a drawer entry when it lives in the sidebar`() {
        val inSidebar = visibleSidebarDestinations(emptySet(), DiscoverLocation.IN_SIDEBAR)
        assertEquals(
            listOf(
                SidebarDestination.HOME,
                SidebarDestination.SEARCH,
                SidebarDestination.LIBRARY,
                SidebarDestination.IPTV,
                SidebarDestination.SPORTS,
                SidebarDestination.DISCOVER,
                SidebarDestination.SETTINGS
            ),
            inSidebar
        )
        assertFalse(SidebarDestination.DISCOVER in visibleSidebarDestinations(emptySet(), DiscoverLocation.OFF))
        assertFalse(SidebarDestination.DISCOVER in visibleSidebarDestinations(emptySet(), DiscoverLocation.IN_SEARCH))
    }

    @Test
    fun `hidden entries disappear and keep the documented order`() {
        assertEquals(
            listOf(SidebarDestination.HOME, SidebarDestination.LIBRARY, SidebarDestination.SETTINGS),
            visibleSidebarDestinations(
                hidden = setOf(SidebarDestination.SEARCH, SidebarDestination.IPTV, SidebarDestination.SPORTS),
                discoverLocation = DiscoverLocation.IN_SEARCH
            )
        )
    }

    @Test
    fun `hidden discover is ignored because discover location owns it`() {
        // Discover can live inside Search, so the hidden-entry toggles must never persist it.
        val hidden = sanitizeHiddenSidebarDestinations(
            setOf(SidebarDestination.DISCOVER, SidebarDestination.SPORTS)
        )
        assertEquals(setOf(SidebarDestination.SPORTS), hidden)
        assertTrue(
            SidebarDestination.DISCOVER in visibleSidebarDestinations(hidden, DiscoverLocation.IN_SIDEBAR)
        )
    }

    @Test
    fun `settings survives even when stored as hidden`() {
        // Settings is the screen that restores the other entries, so it stays reachable regardless.
        val visible = visibleSidebarDestinations(
            hidden = setOf(SidebarDestination.HOME, SidebarDestination.SEARCH, SidebarDestination.LIBRARY,
                SidebarDestination.IPTV, SidebarDestination.SPORTS, SidebarDestination.SETTINGS),
            discoverLocation = DiscoverLocation.IN_SEARCH
        )
        assertEquals(listOf(SidebarDestination.SETTINGS), visible)
    }

    @Test
    fun `visible list is never empty`() {
        listOf(DiscoverLocation.OFF, DiscoverLocation.IN_SEARCH, DiscoverLocation.IN_SIDEBAR).forEach { location ->
            assertTrue(visibleSidebarDestinations(SidebarDestination.entries.toSet(), location).isNotEmpty())
        }
    }

    @Test
    fun `root routes follow the visible entries`() {
        val routes = sidebarRootRoutes(
            visibleSidebarDestinations(
                hidden = setOf(SidebarDestination.SPORTS),
                discoverLocation = DiscoverLocation.IN_SIDEBAR
            )
        )
        assertEquals(
            setOf("home", "search", "library", "xtream_hub", "discover", "settings"),
            routes
        )
        assertFalse("sports_hub" in routes)
    }

    @Test
    fun `route constants mirror the navigation screens`() {
        assertEquals("home", sidebarRouteOf(SidebarDestination.HOME))
        assertEquals("search", sidebarRouteOf(SidebarDestination.SEARCH))
        assertEquals("library", sidebarRouteOf(SidebarDestination.LIBRARY))
        assertEquals("xtream_hub", sidebarRouteOf(SidebarDestination.IPTV))
        assertEquals("iptv_live", sidebarRouteOf(SidebarDestination.IPTV_LIVE))
        assertEquals("iptv_movies", sidebarRouteOf(SidebarDestination.IPTV_MOVIES))
        assertEquals("iptv_series", sidebarRouteOf(SidebarDestination.IPTV_SERIES))
        assertEquals("sports_hub", sidebarRouteOf(SidebarDestination.SPORTS))
        assertEquals("discover", sidebarRouteOf(SidebarDestination.DISCOVER))
        assertEquals("settings", sidebarRouteOf(SidebarDestination.SETTINGS))
    }

    @Test
    fun `split IPTV mode expands IPTV into Live TV, Movies, and Series in place`() {
        assertEquals(
            listOf(
                SidebarDestination.HOME,
                SidebarDestination.SEARCH,
                SidebarDestination.LIBRARY,
                SidebarDestination.IPTV_LIVE,
                SidebarDestination.IPTV_MOVIES,
                SidebarDestination.IPTV_SERIES,
                SidebarDestination.SPORTS,
                SidebarDestination.SETTINGS
            ),
            visibleSidebarDestinations(
                hidden = emptySet(),
                discoverLocation = DiscoverLocation.IN_SEARCH,
                iptvSidebarMode = com.nuvio.tv.domain.model.IptvSidebarMode.SPLIT
            )
        )
    }

    @Test
    fun `hiding IPTV hides all three split IPTV entries too`() {
        val visible = visibleSidebarDestinations(
            hidden = setOf(SidebarDestination.IPTV),
            discoverLocation = DiscoverLocation.IN_SEARCH,
            iptvSidebarMode = com.nuvio.tv.domain.model.IptvSidebarMode.SPLIT
        )
        assertFalse(SidebarDestination.IPTV in visible)
        assertFalse(SidebarDestination.IPTV_LIVE in visible)
        assertFalse(SidebarDestination.IPTV_MOVIES in visible)
        assertFalse(SidebarDestination.IPTV_SERIES in visible)
        assertEquals(
            setOf("home", "search", "library", "sports_hub", "settings"),
            sidebarRootRoutes(visible)
        )
    }

    @Test
    fun `every toggleable destination maps to a distinct route`() {
        val routes = SIDEBAR_TOGGLEABLE_DESTINATIONS.map(::sidebarRouteOf)
        assertEquals(routes.size, routes.toSet().size)
    }

    @Test
    fun `resolveInitialStartDestination respects onboarding and startOnLiveTv`() {
        assertEquals(
            "experience_mode_selection",
            resolveInitialStartDestination(
                needsExperienceSelection = true,
                layoutChosen = true,
                startOnLiveTv = true,
                hiddenSidebarDestinations = emptySet(),
                iptvSidebarMode = com.nuvio.tv.domain.model.IptvSidebarMode.SPLIT
            )
        )
        assertEquals(
            "layout_selection",
            resolveInitialStartDestination(
                needsExperienceSelection = false,
                layoutChosen = false,
                startOnLiveTv = true,
                hiddenSidebarDestinations = emptySet(),
                iptvSidebarMode = com.nuvio.tv.domain.model.IptvSidebarMode.SPLIT
            )
        )
        assertEquals(
            "home",
            resolveInitialStartDestination(
                needsExperienceSelection = false,
                layoutChosen = true,
                startOnLiveTv = false,
                hiddenSidebarDestinations = emptySet(),
                iptvSidebarMode = com.nuvio.tv.domain.model.IptvSidebarMode.SPLIT
            )
        )
        assertEquals(
            "iptv_live",
            resolveInitialStartDestination(
                needsExperienceSelection = false,
                layoutChosen = true,
                startOnLiveTv = true,
                hiddenSidebarDestinations = emptySet(),
                iptvSidebarMode = com.nuvio.tv.domain.model.IptvSidebarMode.SPLIT
            )
        )
        assertEquals(
            "xtream_hub",
            resolveInitialStartDestination(
                needsExperienceSelection = false,
                layoutChosen = true,
                startOnLiveTv = true,
                hiddenSidebarDestinations = emptySet(),
                iptvSidebarMode = com.nuvio.tv.domain.model.IptvSidebarMode.HUB
            )
        )
        assertEquals(
            "home",
            resolveInitialStartDestination(
                needsExperienceSelection = false,
                layoutChosen = true,
                startOnLiveTv = true,
                hiddenSidebarDestinations = setOf(SidebarDestination.IPTV),
                iptvSidebarMode = com.nuvio.tv.domain.model.IptvSidebarMode.SPLIT
            )
        )
    }
}
