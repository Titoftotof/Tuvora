package com.nuvio.tv.core.iptv.home

import com.google.gson.annotations.SerializedName

/**
 * Represents a user-selected IPTV category to be displayed as a row on the TV Home screen.
 *
 * Requirements:
 *  - Per-profile, per-provider selection.
 *  - Supports both Movies (VOD) and Series.
 *  - The row key uses internal opaque identifiers only: `iptv:{providerId}:{type}:{categoryId}`.
 *    It **never** incorporates user credentials or URLs.
 */
data class IptvHomeCategorySelection(
    /** Internal stable ID of the provider (matches [XtreamAccount.id]). */
    @SerializedName("provider_id")
    val providerId: String,

    /** Display name of the provider to disambiguate rows with the same category name. */
    @SerializedName("provider_name")
    val providerName: String,

    /** Content type: MOVIES or SERIES. */
    @SerializedName("content_type")
    val contentType: IptvHomeContentType,

    /** Category ID from the provider's catalog (or [IptvContentDb]). */
    @SerializedName("category_id")
    val categoryId: String,

    /** Display name of the category (e.g. "Action", "Comédies"). */
    @SerializedName("category_name")
    val categoryName: String,

    /** Whether this row is currently enabled for display on Home. */
    @SerializedName("enabled")
    val enabled: Boolean = true,

    /** Custom display order index on Home (relative to other IPTV rows). */
    @SerializedName("order")
    val order: Int = 0
) {
    /**
     * Stable unique key identifying this row across re-login, profile switches, and settings.
     * Never contains usernames, passwords, or server URLs.
     */
    val rowKey: String
        get() = "iptv:${contentType.key}:${providerKey(providerId)}:$categoryId"

    /**
     * Source key used by [HomeSectionContributor] to route "See All" pages back to this provider.
     */
    val sourceKey: String
        get() = "iptv:${providerKey(providerId)}"

    companion object {
        /** Full digest avoids cross-provider routing collisions without exposing account identities. */
        internal fun providerKey(id: String): String = java.security.MessageDigest.getInstance("SHA-256")
            .digest(id.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

enum class IptvHomeContentType(val key: String, val rawType: String) {
    @SerializedName("movies")
    MOVIES("movies", "movie"),

    @SerializedName("series")
    SERIES("series", "series")
}
