package com.nuvio.tv.ui.screens.player.clean.live

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

/**
 * Keeps one physical OK press with its original owner across fullscreen/overlay focus changes.
 * In particular, showing the controls on DOWN must not click their play/pause button on UP.
 * Observe guide events too, but leave its clicks and long clicks to the channel row.
 */
internal class LiveOkPressGate {
    private data class Press(
        val key: Key,
        val deviceId: Int,
        val downTime: Long,
        val startedFullscreen: Boolean,
        val consumed: Boolean,
    ) {
        fun matches(key: Key, deviceId: Int, downTime: Long): Boolean =
            this.key == key && this.deviceId == deviceId && this.downTime == downTime
    }

    private var press: Press? = null

    fun keyAction(event: KeyEvent, fullscreen: Boolean, proposed: LiveControlsPolicy.KeyAction) =
        keyAction(
            key = event.key,
            isKeyDown = event.type == KeyEventType.KeyDown,
            isRepeat = event.nativeKeyEvent.repeatCount > 0,
            deviceId = event.nativeKeyEvent.deviceId,
            downTime = event.nativeKeyEvent.downTime,
            fullscreen = fullscreen,
            proposed = proposed,
        )

    internal fun keyAction(
        key: Key,
        isKeyDown: Boolean,
        isRepeat: Boolean,
        deviceId: Int,
        downTime: Long,
        fullscreen: Boolean,
        proposed: LiveControlsPolicy.KeyAction,
    ): LiveControlsPolicy.KeyAction {
        if (key != Key.DirectionCenter && key != Key.Enter && key != Key.NumPadEnter) return proposed
        val current = press
        val matches = current?.matches(key, deviceId, downTime) == true
        // A fresh press also recovers from a release lost while the app/window was inactive.
        if (isKeyDown && !isRepeat && !matches) {
            press = Press(key, deviceId, downTime, fullscreen,
                consumed = proposed == LiveControlsPolicy.KeyAction.SHOW_CONTROLS)
            return proposed
        }
        if (matches) {
            if (!isKeyDown) press = null
            if (current.consumed || (!current.startedFullscreen && fullscreen)) {
                return LiveControlsPolicy.KeyAction.CONSUME
            }
            return proposed
        }
        // Never let an orphan release/repeat activate a newly focused fullscreen button.
        return if (fullscreen) LiveControlsPolicy.KeyAction.CONSUME else proposed
    }
}
