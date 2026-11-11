package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * An IPTV movie often ships only a portrait poster. The hero must not stretch it behind the whole
 * screen: the poster keeps its usual place and the hero stays dark. A real panorama is still used,
 * and non-IPTV content keeps its historical fallback.
 */
class HeroBackdropPolicyTest {

    private val poster = "https://panel.test/poster.jpg"
    private val backdrop = "https://panel.test/backdrop.jpg"

    @Test
    fun `an IPTV movie with only a poster keeps a dark hero`() {
        val hero = heroBackdropFor(
            backdrop = null,
            landscapePoster = null,
            poster = poster,
            catalogImage = poster,
            isIptvMovie = true
        )

        assertNull("the poster must not become the full-screen backdrop", hero)
    }

    @Test
    fun `an IPTV movie keeps the panorama the provider supplied`() {
        val hero = heroBackdropFor(
            backdrop = backdrop,
            landscapePoster = null,
            poster = poster,
            catalogImage = backdrop,
            isIptvMovie = true
        )

        assertEquals(backdrop, hero)
    }

    @Test
    fun `an IPTV movie uses a landscape poster before any catalog art`() {
        val landscape = "https://panel.test/landscape.jpg"
        val hero = heroBackdropFor(
            backdrop = null,
            landscapePoster = landscape,
            poster = poster,
            catalogImage = poster,
            isIptvMovie = true
        )

        assertEquals(landscape, hero)
    }

    @Test
    fun `non IPTV content keeps the historical fallback`() {
        val hero = heroBackdropFor(
            backdrop = null,
            landscapePoster = null,
            poster = poster,
            catalogImage = poster,
            isIptvMovie = false
        )

        assertEquals(poster, hero)
    }

    @Test
    fun `an IPTV movie falls back to a neighbouring panorama when the row has one`() {
        val rowBackdrop = "https://panel.test/row.jpg"
        val hero = heroBackdropFor(
            backdrop = null,
            landscapePoster = null,
            poster = poster,
            catalogImage = poster,
            isIptvMovie = true,
            fallbackBackdrop = rowBackdrop
        )

        assertEquals(rowBackdrop, hero)
    }

    @Test
    fun `a poster still surfaces through backdropUrl for addon content only`() {
        fun preview(id: String) = MetaPreview(
            id = id,
            type = ContentType.MOVIE,
            rawType = "movie",
            name = "A movie",
            poster = poster,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = "2010",
            imdbRating = null,
            genres = emptyList()
        )

        assertEquals(
            "an IPTV movie never offers its poster as a landscape backdrop",
            null,
            preview("xtream:panel:vod:1").backdropUrl
        )
        assertEquals(poster, preview("tt1375666").backdropUrl)
    }
}
