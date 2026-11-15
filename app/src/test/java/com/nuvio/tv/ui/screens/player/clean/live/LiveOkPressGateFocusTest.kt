package com.nuvio.tv.ui.screens.player.clean.live

import android.app.Application
import android.view.KeyEvent as AndroidKeyEvent
import android.view.View
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.nuvio.tv.ui.components.player.ControlButton
import com.nuvio.tv.ui.theme.NuvioTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class LiveOkPressGateFocusTest {
    @get:Rule val rule = createComposeRule()
    private var fullscreen by mutableStateOf(true)
    private var controlsVisible by mutableStateOf(false)
    private var pauses = 0
    private lateinit var hostView: View
    private lateinit var pressed: AndroidKeyEvent

    /** Real TV ControlButton and focus transfer, without a network stream or playback engine. */
    private fun setup(guarded: Boolean = true, startFullscreen: Boolean = true) {
        fullscreen = startFullscreen
        rule.setContent {
            hostView = LocalView.current
            val gate = remember { LiveOkPressGate() }
            val root = remember { FocusRequester() }
            val primary = remember { FocusRequester() }
            NuvioTheme {
                Box(Modifier.width(900.dp).height(300.dp)
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
                            pressed = AndroidKeyEvent(event.nativeKeyEvent)
                        }
                        val proposed = if (fullscreen) LiveControlsPolicy.keyAction(
                            key = event.key,
                            isKeyDown = event.type == KeyEventType.KeyDown,
                            controlsVisible = controlsVisible,
                            paused = false,
                            panelOpen = false,
                            isRepeat = event.nativeKeyEvent.repeatCount > 0,
                        ) else LiveControlsPolicy.KeyAction.PASS
                        val action = if (guarded) gate.keyAction(event, fullscreen, proposed) else proposed
                        when (action) {
                            LiveControlsPolicy.KeyAction.SHOW_CONTROLS -> { controlsVisible = true; true }
                            LiveControlsPolicy.KeyAction.CONSUME -> true
                            else -> false
                        }
                    }
                    .focusRequester(root).focusable()
                ) {
                    if (controlsVisible) ControlButton(
                        icon = Icons.Default.Pause,
                        contentDescription = "Pause",
                        onClick = { pauses++ },
                        focusRequester = primary,
                    )
                }
            }
            LaunchedEffect(controlsVisible) {
                if (controlsVisible) primary.requestFocus() else root.requestFocus()
            }
        }
        rule.waitForIdle()
    }

    @Test fun `unprotected reveal can click the newly focused pause button on release`() {
        setup(guarded = false)
        rule.onRoot().performKeyInput { keyDown(Key.DirectionCenter) }
        rule.onNodeWithContentDescription("Pause").assertIsFocused()
        rule.onRoot().performKeyInput { keyUp(Key.DirectionCenter) }
        rule.runOnIdle { assertEquals(1, pauses) }
    }

    @Test fun `revealing controls never pauses but a new deliberate press does`() {
        setup()
        rule.onRoot().performKeyInput { keyDown(Key.DirectionCenter) }
        rule.onNodeWithContentDescription("Pause").assertIsFocused()
        rule.onRoot().performKeyInput { keyUp(Key.DirectionCenter) }
        rule.runOnIdle { assertEquals(0, pauses) }
        rule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        rule.runOnIdle { assertEquals(1, pauses) }
    }

    @Test fun `held reveal consumes repeats after focus moves to pause`() {
        setup()
        rule.onRoot().performKeyInput { keyDown(Key.DirectionCenter) }
        rule.onNodeWithContentDescription("Pause").assertIsFocused()
        rule.runOnIdle {
            repeat(3) { index ->
                hostView.dispatchKeyEvent(AndroidKeyEvent.changeTimeRepeat(pressed, pressed.eventTime + index + 1L, index + 1))
            }
        }
        rule.onRoot().performKeyInput { keyUp(Key.DirectionCenter) }
        rule.runOnIdle { assertEquals(0, pauses) }
        rule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        rule.runOnIdle { assertEquals(1, pauses) }
    }

    @Test fun `promotion while OK is held cannot pause and does not swallow the next click`() {
        setup(startFullscreen = false)
        rule.onRoot().performKeyInput { keyDown(Key.DirectionCenter) }
        rule.runOnIdle { fullscreen = true; controlsVisible = true }
        rule.onNodeWithContentDescription("Pause").assertIsFocused()
        rule.onRoot().performKeyInput { keyUp(Key.DirectionCenter) }
        rule.runOnIdle { assertEquals(0, pauses) }
        rule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        rule.runOnIdle { assertEquals(1, pauses) }
    }
}
