package com.nuvio.tv.data.remote.api

import com.squareup.moshi.Json

data class IptvMovieSearchResponse(
    val results: List<IptvMovieSearchResult> = emptyList(),
    @Json(name = "total_pages") val totalPages: Int = 1
)

data class IptvMovieSearchResult(
    val id: Int,
    val title: String? = null,
    @Json(name = "original_title") val originalTitle: String? = null,
    @Json(name = "release_date") val releaseDate: String? = null
)
