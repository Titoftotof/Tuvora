package com.nuvio.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nuvio.tv.R
import java.util.Locale

internal fun catalogPosterRatingText(rating: Float?, locale: Locale): String? =
    rating?.takeIf { it.isFinite() && it > 0f && it <= 10f }
        ?.let { String.format(locale, "%.1f", it) }

/** Displays the catalog's own score. No metadata lookup or network work belongs in a card. */
@Composable
internal fun CatalogPosterRatingBadge(
    rating: Float?,
    modifier: Modifier = Modifier
) {
    val localeTag = LocalLocale.current.toLanguageTag()
    val locale = remember(localeTag) { Locale.forLanguageTag(localeTag) }
    val text = remember(rating, locale) { catalogPosterRatingText(rating, locale) } ?: return
    val description = stringResource(R.string.catalog_poster_rating_description, text)
    Row(
        modifier = modifier
            .clearAndSetSemantics { contentDescription = description }
            .background(Color.Black.copy(alpha = 0.82f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Star,
            contentDescription = null,
            tint = Color(0xFFFFD54F),
            modifier = Modifier.size(13.dp)
        )
        Text(
            text = text,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}
