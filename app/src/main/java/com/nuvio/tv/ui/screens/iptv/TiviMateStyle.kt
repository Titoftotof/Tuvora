package com.nuvio.tv.ui.screens.iptv

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Shared visual tokens & pre-allocated shapes for the TiviMate-inspired IPTV surfaces
 * (Live TV Guide, Fullscreen Channel/Groups Overlay, Movies & Series browsers).
 *
 * Pre-allocating shapes avoids per-row / per-cell allocations during fast D-pad scrolling
 * on constrained TV boxes (e.g. 32-bit ARM Fire TV).
 */
internal object TiviMateStyle {
    val Background = Color(0xFF131619)
    val PanelBackground = Color(0xFF191D23)
    val HeaderBackground = Color(0xFF171C22)
    val OverlayBackground = Color(0xEB131619)
    val OverlayGroupsBackground = Color(0xF2191E24)
    val OverlayEpgBackground = Color(0xDE161B21)

    val CellSurface = Color(0xFF1C2128)
    val CellLiveSurface = Color(0xFF232D38)
    val CellFillerSurface = Color(0xFF161A1F)

    val FocusSurface = Color(0xFFE4E7EB)
    val FocusContent = Color(0xFF111518)
    val FocusSubContent = Color(0xFF39424E)
    val SelectedSurface = Color(0xFF262F3A)

    val Divider = Color(0x1AFFFFFF)
    val SubtleDivider = Color(0x0EFFFFFF)
    val CardPlaceholder = Color(0xFF1D232A)

    val TextPrimary = Color(0xFFF1F4F8)
    val TextSecondary = Color(0xFF9AA5B1)
    val TextMuted = Color(0xFF6B7785)

    val Accent = Color(0xFF38BDF8)
    val PlayingAccent = Color(0xFF38BDF8)
    val NowLine = Color(0xFFEF4444)
    val RatingBg = Color(0xD9111518)
    val RatingText = Color(0xFFFBBF24)

    val ShapeXs = RoundedCornerShape(3.dp)
    val ShapeSm = RoundedCornerShape(5.dp)
    val ShapeMd = RoundedCornerShape(8.dp)
    val ShapePill = RoundedCornerShape(999.dp)
}
