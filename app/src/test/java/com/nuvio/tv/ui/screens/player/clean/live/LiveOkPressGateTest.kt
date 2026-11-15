package com.nuvio.tv.ui.screens.player.clean.live

import androidx.compose.ui.input.key.Key
import com.nuvio.tv.ui.screens.player.clean.live.LiveControlsPolicy.KeyAction
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveOkPressGateTest {
    private fun LiveOkPressGate.event(
        action: KeyAction,
        down: Boolean = true,
        repeat: Boolean = false,
        fullscreen: Boolean = true,
        time: Long = 1L,
        key: Key = Key.DirectionCenter,
        device: Int = 0,
    ) = keyAction(key, down, repeat, device, time, fullscreen, action)

    @Test fun `revealing controls owns repeats and release but not the next press`() {
        for (key in listOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)) {
            val gate = LiveOkPressGate()
            assertEquals(KeyAction.SHOW_CONTROLS, gate.event(KeyAction.SHOW_CONTROLS, key = key))
            assertEquals(KeyAction.CONSUME, gate.event(KeyAction.PASS_AND_KEEP_ALIVE, repeat = true, key = key))
            assertEquals(KeyAction.CONSUME, gate.event(KeyAction.PASS_AND_KEEP_ALIVE, down = false, key = key))
            assertEquals(KeyAction.PASS_AND_KEEP_ALIVE, gate.event(KeyAction.PASS_AND_KEEP_ALIVE, time = 2, key = key))
            assertEquals(KeyAction.PASS_AND_KEEP_ALIVE, gate.event(KeyAction.PASS_AND_KEEP_ALIVE, down = false, time = 2, key = key))
        }
    }

    @Test fun `promotion during a guide press cannot activate fullscreen controls`() {
        val gate = LiveOkPressGate()
        assertEquals(KeyAction.PASS, gate.event(KeyAction.PASS, fullscreen = false))
        assertEquals(KeyAction.PASS, gate.event(KeyAction.PASS, repeat = true, fullscreen = false))
        assertEquals(KeyAction.CONSUME, gate.event(KeyAction.PASS_AND_KEEP_ALIVE, repeat = true))
        assertEquals(KeyAction.CONSUME, gate.event(KeyAction.PASS_AND_KEEP_ALIVE, down = false))
        assertEquals(KeyAction.PASS_AND_KEEP_ALIVE, gate.event(KeyAction.PASS_AND_KEEP_ALIVE, time = 2))
    }

    @Test fun `a guide click completed before promotion leaves the next press available`() {
        val gate = LiveOkPressGate()
        assertEquals(KeyAction.PASS, gate.event(KeyAction.PASS, fullscreen = false))
        assertEquals(KeyAction.PASS, gate.event(KeyAction.PASS, down = false, fullscreen = false))
        assertEquals(KeyAction.SHOW_CONTROLS, gate.event(KeyAction.SHOW_CONTROLS, time = 2))
    }

    @Test fun `guide long press remains entirely owned by its channel row`() {
        val gate = LiveOkPressGate()
        assertEquals(KeyAction.PASS, gate.event(KeyAction.PASS, fullscreen = false))
        repeat(4) { assertEquals(KeyAction.PASS, gate.event(KeyAction.PASS, repeat = true, fullscreen = false)) }
        assertEquals(KeyAction.PASS, gate.event(KeyAction.PASS, down = false, fullscreen = false))
    }

    @Test fun `lost release cannot block a fresh deliberate button press`() {
        val gate = LiveOkPressGate()
        gate.event(KeyAction.SHOW_CONTROLS)
        assertEquals(KeyAction.PASS_AND_KEEP_ALIVE, gate.event(KeyAction.PASS_AND_KEEP_ALIVE, time = 2))
        assertEquals(KeyAction.PASS_AND_KEEP_ALIVE, gate.event(KeyAction.PASS_AND_KEEP_ALIVE, down = false, time = 2))
    }

    @Test fun `duplicate down for the same press does not transfer ownership`() {
        val gate = LiveOkPressGate()
        gate.event(KeyAction.SHOW_CONTROLS)
        assertEquals(KeyAction.CONSUME, gate.event(KeyAction.PASS_AND_KEEP_ALIVE))
        assertEquals(KeyAction.CONSUME, gate.event(KeyAction.PASS_AND_KEEP_ALIVE, down = false))
    }

    @Test fun `orphan release or repeat cannot click a fullscreen button`() {
        val gate = LiveOkPressGate()
        assertEquals(KeyAction.CONSUME, gate.event(KeyAction.PASS_AND_KEEP_ALIVE, down = false))
        assertEquals(KeyAction.CONSUME, gate.event(KeyAction.PASS_AND_KEEP_ALIVE, repeat = true))
    }

    @Test fun `another devices release does not release the consumed press`() {
        val gate = LiveOkPressGate()
        gate.event(KeyAction.SHOW_CONTROLS)
        assertEquals(KeyAction.CONSUME, gate.event(KeyAction.PASS_AND_KEEP_ALIVE, down = false, device = 1))
        assertEquals(KeyAction.CONSUME, gate.event(KeyAction.PASS_AND_KEEP_ALIVE, down = false))
    }

    @Test fun `media channel and panel actions keep their existing behavior`() {
        val gate = LiveOkPressGate()
        gate.event(KeyAction.SHOW_CONTROLS)
        assertEquals(KeyAction.TOGGLE_PAUSE, gate.event(KeyAction.TOGGLE_PAUSE, key = Key.MediaPlayPause))
        assertEquals(KeyAction.ZAP_NEXT, gate.event(KeyAction.ZAP_NEXT, key = Key.ChannelUp))
        gate.event(KeyAction.CONSUME, down = false)
        assertEquals(KeyAction.PASS, gate.event(KeyAction.PASS, time = 2))
        assertEquals(KeyAction.PASS, gate.event(KeyAction.PASS, down = false, time = 2))
    }
}
