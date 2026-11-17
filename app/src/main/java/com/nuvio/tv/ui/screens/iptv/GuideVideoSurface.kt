package com.nuvio.tv.ui.screens.iptv

import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt

/**
 * One window-sized video canvas for both preview and fullscreen.
 *
 * Resizing a SurfaceView also resizes the native output asynchronously. A new view rectangle can
 * therefore display the previous buffer briefly stretched. Only the presentation scale changes
 * here: the Android owner, surface and renderer keep the same measured dimensions.
 */
@Composable
internal fun GuideVideoSurface(
    owner: FrameLayout,
    keepScreenOn: Boolean,
    modifier: Modifier = Modifier,
) {
    val windowSize = LocalWindowInfo.current.containerSize
    val canvasWidth = windowSize.width.coerceAtLeast(1)
    val canvasHeight = windowSize.height.coerceAtLeast(1)
    Layout(
        modifier = modifier.clipToBounds(),
        content = {
            AndroidView(
                factory = { owner },
                update = { it.keepScreenOn = keepScreenOn },
            )
        },
    ) { measurables, constraints ->
        val video = measurables.single().measure(Constraints.fixed(canvasWidth, canvasHeight))
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        // Uniform scaling also protects the picture during the frame in which the app rail hides.
        val scale = minOf(width.toFloat() / canvasWidth, height.toFloat() / canvasHeight)
        layout(width, height) {
            video.placeWithLayer(
                x = ((width - canvasWidth * scale) / 2).roundToInt(),
                y = ((height - canvasHeight * scale) / 2).roundToInt(),
            ) {
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = scale
                scaleY = scale
            }
        }
    }
}
