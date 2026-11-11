package com.nuvio.tv.ui.components

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogPosterRatingBadgeTest {
    @Test
    fun missingAndInvalidCatalogScoresAreHidden() {
        listOf(null, 0f, -1f, 10.1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)
            .forEach { assertNull("Unexpected badge for $it", catalogPosterRatingText(it, Locale.US)) }
    }

    @Test
    fun validScoresUseOneDecimalAndTheDisplayLanguage() {
        assertEquals("7,8", catalogPosterRatingText(7.8f, Locale.FRANCE))
        assertEquals("7.8", catalogPosterRatingText(7.8f, Locale.US))
        assertEquals("10,0", catalogPosterRatingText(10f, Locale.FRANCE))
    }
}
