package com.nuvio.tv.core.iptv

import com.nuvio.tv.core.iptv.match.IndexedItem
import com.nuvio.tv.core.iptv.match.MatchKind
import com.nuvio.tv.core.iptv.match.XtreamMatchIndex
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tmdb.TmdbEnrichment
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.local.XtreamAccountStore
import com.nuvio.tv.data.remote.api.IptvMovieSearchResponse
import com.nuvio.tv.data.remote.api.IptvMovieSearchResult
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.dto.XtreamVodInfoDto
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaCastMember
import com.nuvio.tv.domain.model.TmdbSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException

/**
 * IPTV movies keep two independent sources of detail: the panel's `get_vod_info` and TMDB.
 * The rules pinned here are the ones a regression would silently break:
 *  - provider detail is kept when TMDB has no id, no unambiguous match, or is unreachable;
 *  - TMDB is only associated through an id or a UNIQUE (title, year) match — never a guess;
 *  - a missing network answer never deletes detail the other source already supplied;
 *  - a portrait provider poster is never promoted to the full-screen backdrop.
 */
class IptvMovieMetadataTest {

    private val profileId = 1
    private val account = XtreamAccount(
        id = "http://panel.test:8080",
        name = "Panel",
        baseUrl = "http://panel.test:8080",
        username = "user",
        password = "pass",
        sourceType = XtreamAccount.SOURCE_XTREAM
    )
    private val movieId = XtreamItemRegistry.vodId(account.id, VOD_ID)

    private val accounts = mockk<XtreamAccountStore>()
    private val client = mockk<XtreamClient>()
    private val index = mockk<XtreamMatchIndex>()
    private val api = mockk<TmdbApi>()
    private val tmdb = mockk<TmdbMetadataService>()
    private val settingsFlow = MutableStateFlow(TmdbSettings(enabled = true, language = "fr"))
    private val settingsStore = mockk<TmdbSettingsDataStore> {
        every { settings } returns settingsFlow
    }
    private val profiles = mockk<ProfileManager> {
        every { activeProfileId } returns MutableStateFlow(profileId)
    }

    private fun metadata() = IptvMovieMetadata(
        accounts = accounts,
        client = client,
        index = index,
        api = api,
        tmdb = tmdb,
        settingsStore = settingsStore,
        profiles = profiles
    )

    private fun item(name: String = "Inception - 2010") = XtreamResolvedItem(
        id = movieId,
        type = ContentType.MOVIE,
        name = name,
        poster = "https://panel.test/poster.jpg",
        streamUrl = "https://panel.test/movie/user/pass/$VOD_ID.mp4",
        kind = XtreamKind.VOD,
        accountId = account.id,
        streamId = VOD_ID
    )

    private fun allowAccount() {
        coEvery { accounts.accountsForProfile(profileId) } returns listOf(account)
    }

    @Test
    fun `provider detail is kept when the panel supplies no TMDB id and no year is known`() = runTest {
        allowAccount()
        coEvery { client.vodDetails(any(), VOD_ID) } returns Result.success(
            vodInfo(plot = "Panel plot", cast = "Tom Hardy, Elliot Page", rating = "8.4", releaseDate = null)
        )
        coEvery { index.item(any(), MatchKind.MOVIE, VOD_ID) } returns null

        val meta = metadata().resolve(item(name = "Inception"))

        // No year anywhere -> a title search could only guess, so TMDB is never asked.
        coVerify(exactly = 0) { api.searchMovies(any(), any(), any(), any()) }
        coVerify(exactly = 0) { tmdb.fetchEnrichment(any(), any(), any()) }
        assertEquals("Panel plot", meta.description)
        assertEquals(listOf("Tom Hardy", "Elliot Page"), meta.cast)
        assertEquals(8.4f, meta.imdbRating ?: 0f)
    }

    @Test
    fun `a movie without an id is associated through a unique title and year match`() = runTest {
        allowAccount()
        coEvery { client.vodDetails(any(), VOD_ID) } returns Result.success(vodInfo(plot = "Panel plot"))
        coEvery { index.item(any(), MatchKind.MOVIE, VOD_ID) } returns null
        coEvery { api.searchMovies(any(), any(), any(), any()) } returns Response.success(
            IptvMovieSearchResponse(
                results = listOf(
                    IptvMovieSearchResult(id = 27205, title = "Inception", releaseDate = "2010-07-15")
                )
            )
        )
        coEvery { tmdb.fetchEnrichment(any(), any(), any()) } returns enrichment(
            description = "TMDB plot",
            backdrop = "https://image.tmdb.test/inception.jpg"
        )

        val meta = metadata().resolve(item())

        coVerify(exactly = 1) { api.searchMovies(any(), "inception", 2010, "fr") }
        coVerify(exactly = 1) { tmdb.fetchEnrichment("27205", ContentType.MOVIE, "fr") }
        assertEquals("TMDB plot", meta.description)
        assertEquals("https://image.tmdb.test/inception.jpg", meta.background)
    }

    @Test
    fun `an ambiguous title match is refused and the provider detail survives`() = runTest {
        allowAccount()
        coEvery { client.vodDetails(any(), VOD_ID) } returns Result.success(
            vodInfo(plot = "Panel plot", cast = "Tom Hardy")
        )
        coEvery { index.item(any(), MatchKind.MOVIE, VOD_ID) } returns null
        // Two same-titled, same-year releases: no association is possible without guessing.
        coEvery { api.searchMovies(any(), any(), any(), any()) } returns Response.success(
            IptvMovieSearchResponse(
                results = listOf(
                    IptvMovieSearchResult(id = 1, title = "Inception", releaseDate = "2010-01-01"),
                    IptvMovieSearchResult(id = 2, title = "Inception", releaseDate = "2010-06-06")
                )
            )
        )

        val meta = metadata().resolve(item())

        coVerify(exactly = 0) { tmdb.fetchEnrichment(any(), any(), any()) }
        assertEquals("Panel plot", meta.description)
        assertEquals(listOf("Tom Hardy"), meta.cast)
    }

    @Test
    fun `an unreachable TMDB never removes the provider detail`() = runTest {
        allowAccount()
        coEvery { client.vodDetails(any(), VOD_ID) } returns Result.success(
            vodInfo(plot = "Panel plot", cast = "Tom Hardy", rating = "7.9")
        )
        coEvery { index.item(any(), MatchKind.MOVIE, VOD_ID) } returns null
        coEvery { api.searchMovies(any(), any(), any(), any()) } throws IOException("offline")

        val meta = metadata().resolve(item())

        assertEquals("Panel plot", meta.description)
        assertEquals(listOf("Tom Hardy"), meta.cast)
        assertEquals(7.9f, meta.imdbRating ?: 0f)
        assertEquals(2010, meta.releaseInfo?.toIntOrNull())
    }

    @Test
    fun `a TMDB failure leaves already obtained provider fields untouched`() = runTest {
        allowAccount()
        coEvery { client.vodDetails(any(), VOD_ID) } returns Result.success(
            vodInfo(tmdbId = 27205, plot = "Panel plot")
        )
        coEvery { index.item(any(), MatchKind.MOVIE, VOD_ID) } returns null
        coEvery { tmdb.fetchEnrichment(any(), any(), any()) } throws IOException("offline")

        val meta = metadata().resolve(item())

        assertEquals("Panel plot", meta.description)
        // The panel poster stays the poster; the failure cannot blank artwork.
        assertEquals("https://panel.test/poster.jpg", meta.poster)
    }

    @Test
    fun `an unreachable panel still lets TMDB fill the card`() = runTest {
        allowAccount()
        coEvery { client.vodDetails(any(), VOD_ID) } returns Result.failure(IOException("offline"))
        coEvery { index.item(any(), MatchKind.MOVIE, VOD_ID) } returns
            IndexedItem(sid = VOD_ID, name = "Inception", year = 2010, tmdb = 27205, ext = "mp4")
        coEvery { tmdb.fetchEnrichment(any(), any(), any()) } returns enrichment(
            description = "TMDB plot",
            backdrop = "https://image.tmdb.test/inception.jpg"
        )

        val meta = metadata().resolve(item())

        coVerify(exactly = 1) { tmdb.fetchEnrichment("27205", ContentType.MOVIE, "fr") }
        assertEquals("TMDB plot", meta.description)
        assertEquals("https://image.tmdb.test/inception.jpg", meta.background)
    }

    @Test
    fun `TMDB turned off keeps the provider detail and makes no request`() = runTest {
        settingsFlow.value = TmdbSettings(enabled = false, language = "fr")
        allowAccount()
        coEvery { client.vodDetails(any(), VOD_ID) } returns Result.success(
            vodInfo(tmdbId = 27205, plot = "Panel plot")
        )
        coEvery { index.item(any(), MatchKind.MOVIE, VOD_ID) } returns null

        val meta = metadata().resolve(item())

        coVerify(exactly = 0) { tmdb.fetchEnrichment(any(), any(), any()) }
        coVerify(exactly = 0) { api.searchMovies(any(), any(), any(), any()) }
        assertEquals("Panel plot", meta.description)
    }

    @Test
    fun `a resolved movie is cached and served without new requests`() = runTest {
        allowAccount()
        coEvery { client.vodDetails(any(), VOD_ID) } returns Result.success(vodInfo(plot = "Panel plot"))
        coEvery { index.item(any(), MatchKind.MOVIE, VOD_ID) } returns
            IndexedItem(sid = VOD_ID, name = "Inception", year = 2010, tmdb = 27205, ext = "mp4")
        coEvery { tmdb.fetchEnrichment(any(), any(), any()) } returns enrichment(description = "TMDB plot")

        val service = metadata()
        service.resolve(item())
        service.resolve(item())

        coVerify(exactly = 1) { client.vodDetails(any(), VOD_ID) }
        coVerify(exactly = 1) { tmdb.fetchEnrichment(any(), any(), any()) }
    }

    @Test
    fun `a portrait poster is never promoted to the full screen backdrop`() = runTest {
        allowAccount()
        coEvery { client.vodDetails(any(), VOD_ID) } returns Result.success(
            vodInfo(movieImage = "https://panel.test/poster.jpg", backdrop = "https://panel.test/poster.jpg")
        )
        coEvery { index.item(any(), MatchKind.MOVIE, VOD_ID) } returns null
        coEvery { tmdb.fetchEnrichment(any(), any(), any()) } returns null

        val meta = metadata().resolve(item())

        assertEquals("https://panel.test/poster.jpg", meta.poster)
        assertNull("a poster must not be stretched behind the detail page", meta.background)
        assertNull("the poster is not offered as a landscape backdrop either", meta.backdropUrl)
    }

    private fun vodInfo(
        tmdbId: Int? = null,
        movieImage: String? = "https://panel.test/poster.jpg",
        backdrop: String? = null,
        releaseDate: String? = "2010-07-15",
        plot: String? = null,
        cast: String? = null,
        rating: String? = null
    ) = XtreamVodInfoDto(
        tmdbId = tmdbId,
        movieImage = movieImage,
        coverBig = null,
        releaseDate = releaseDate,
        releaseDateAlt = null,
        plot = plot,
        cast = cast,
        rating = rating,
        backdrop = backdrop
    )

    private fun enrichment(
        description: String? = null,
        backdrop: String? = null,
        cast: List<String> = emptyList()
    ) = TmdbEnrichment(
        localizedTitle = null,
        description = description,
        genres = emptyList(),
        backdrop = backdrop,
        logo = null,
        poster = null,
        directorMembers = emptyList(),
        writerMembers = emptyList(),
        castMembers = cast.map { MetaCastMember(name = it) },
        releaseInfo = null,
        rating = null,
        runtimeMinutes = null,
        director = emptyList(),
        writer = emptyList(),
        productionCompanies = emptyList(),
        networks = emptyList(),
        ageRating = null,
        status = null,
        countries = null,
        language = null,
        collectionId = null,
        collectionName = null
    )

    private companion object {
        const val VOD_ID = 1001
    }
}
