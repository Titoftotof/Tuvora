package com.nuvio.tv.domain.model

/**
 * Controls how IPTV is presented in the left sidebar navigation.
 *
 * - [HUB]: single "IPTV" entry opening the tabbed hub (Live TV / Movies / Series).
 * - [SPLIT]: three direct top-level entries ("Live TV", "Movies", "Series"), TiviMate-style.
 */
enum class IptvSidebarMode {
    HUB,
    SPLIT
}
