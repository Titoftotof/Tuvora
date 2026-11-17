package com.nuvio.tv.ui.screens.iptv

import android.app.Application
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class GuideVideoSurfaceTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun `preview fullscreen and return keep the native canvas size and owner`() {
        var fullscreen by mutableStateOf(false)
        lateinit var owner: FrameLayout
        val sizes = mutableListOf<Pair<Int, Int>>()
        rule.setContent {
            val context = LocalContext.current
            remember(context) {
                FrameLayout(context).apply {
                    addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
                        if (r - l != oldR - oldL || b - t != oldB - oldT) sizes += (r - l) to (b - t)
                    }
                }.also { owner = it }
            }
            Box(Modifier.fillMaxSize()) {
                GuideVideoSurface(
                    owner = owner,
                    keepScreenOn = true,
                    modifier = Modifier.size(
                        width = if (fullscreen) 280.dp else 140.dp,
                        height = if (fullscreen) 158.dp else 79.dp,
                    ),
                )
            }
        }
        rule.waitForIdle()
        lateinit var initialSize: Pair<Int, Int>
        lateinit var initialParent: android.view.ViewParent
        rule.runOnIdle {
            initialSize = owner.width to owner.height
            initialParent = owner.parent
            assertTrue(owner.width > 0 && owner.height > 0)
            sizes.clear()
            fullscreen = true
        }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(initialSize, owner.width to owner.height)
            assertSame(initialParent, owner.parent)
            fullscreen = false
        }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(initialSize, owner.width to owner.height)
            assertSame(initialParent, owner.parent)
            assertTrue("Presentation changes must not resize the native renderer", sizes.isEmpty())
            assertTrue(owner.keepScreenOn)
        }
    }
}
