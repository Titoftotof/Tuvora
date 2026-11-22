package com.nuvio.tv.ui.screens.iptv

import org.junit.Assert.*
import org.junit.Test

class GuidePlaybackInteractionPolicyTest {
    @Test fun `moving focus clears a channel action notice without clearing playback state`() {
        val state = LiveGuideUiState(focusedChannelId = "old", actionError = "No recording")
        val next = GuidePlaybackInteractionPolicy.focus(state, "new")
        assertEquals("new", next.focusedChannelId)
        assertNull(next.actionError)
    }
    @Test fun `OK on a pending initial tune opens fullscreen without another tune`() {
        assertEquals(GuidePlaybackInteractionPolicy.Click.FULLSCREEN,
            GuidePlaybackInteractionPolicy.click("new", null, "new", false))
    }
    @Test fun `OK on a pending zap does not retry the previous failed channel`() {
        assertEquals(GuidePlaybackInteractionPolicy.Click.FULLSCREEN,
            GuidePlaybackInteractionPolicy.click("new", "old", "new", false))
    }
    @Test fun `another channel tunes and settled failure retries`() {
        assertEquals(GuidePlaybackInteractionPolicy.Click.TUNE,
            GuidePlaybackInteractionPolicy.click("other", "old", "new", true))
        assertEquals(GuidePlaybackInteractionPolicy.Click.RETRY,
            GuidePlaybackInteractionPolicy.click("old", "old", null, false))
        assertEquals(GuidePlaybackInteractionPolicy.Click.FULLSCREEN,
            GuidePlaybackInteractionPolicy.click("old", "old", null, true))
    }
    @Test fun `direct fullscreen mode tunes and promotes on first OK`() {
        assertEquals(GuidePlaybackInteractionPolicy.Click.TUNE_AND_FULLSCREEN,
            GuidePlaybackInteractionPolicy.click("new", null, null, false, directFullscreen = true))
        assertEquals(GuidePlaybackInteractionPolicy.Click.TUNE_AND_FULLSCREEN,
            GuidePlaybackInteractionPolicy.click("other", "old", null, true, directFullscreen = true))
        assertEquals(GuidePlaybackInteractionPolicy.Click.FULLSCREEN,
            GuidePlaybackInteractionPolicy.click("new", "old", "new", false, directFullscreen = true))
        assertEquals(GuidePlaybackInteractionPolicy.Click.FULLSCREEN,
            GuidePlaybackInteractionPolicy.click("old", "old", null, true, directFullscreen = true))
        assertEquals(GuidePlaybackInteractionPolicy.Click.RETRY,
            GuidePlaybackInteractionPolicy.click("old", "old", null, false, directFullscreen = true))
    }
    @Test fun `focus on another channel cannot inherit the playback error`() {
        assertFalse(GuidePlaybackInteractionPolicy.showFocusedError("new", "old"))
        assertFalse(GuidePlaybackInteractionPolicy.showFocusedError(null, "old"))
        assertTrue(GuidePlaybackInteractionPolicy.showFocusedError("old", "old"))
    }
    @Test fun `terminal playback error covers the retained video frame`() {
        assertTrue(GuidePlaybackInteractionPolicy.coverFailedFrame(true))
        assertFalse(GuidePlaybackInteractionPolicy.coverFailedFrame(false))
    }

    @Test fun `long-press Back returns to fullscreen only when in guide and video is playing`() {
        // While browsing guide with active playback: long-press Back jumps to fullscreen
        assertTrue(GuidePlaybackInteractionPolicy.shouldReturnToFullscreenOnBack(
            fullscreen = false, hasPlayback = true, isLongPress = true))

        // Short press on Back does NOT jump to fullscreen (it must unwind guide navigation)
        assertFalse(GuidePlaybackInteractionPolicy.shouldReturnToFullscreenOnBack(
            fullscreen = false, hasPlayback = true, isLongPress = false))

        // If no playback is active, long-press Back does not jump to fullscreen
        assertFalse(GuidePlaybackInteractionPolicy.shouldReturnToFullscreenOnBack(
            fullscreen = false, hasPlayback = false, isLongPress = true))

        // If already in fullscreen, Back is handled by fullscreen exit/collapse policy
        assertFalse(GuidePlaybackInteractionPolicy.shouldReturnToFullscreenOnBack(
            fullscreen = true, hasPlayback = true, isLongPress = true))
    }

    @Test fun `Play button in guide returns to fullscreen when playback is active`() {
        assertTrue(GuidePlaybackInteractionPolicy.shouldReturnToFullscreenOnPlay(
            fullscreen = false, hasPlayback = true))
        assertFalse(GuidePlaybackInteractionPolicy.shouldReturnToFullscreenOnPlay(
            fullscreen = false, hasPlayback = false))
        assertFalse(GuidePlaybackInteractionPolicy.shouldReturnToFullscreenOnPlay(
            fullscreen = true, hasPlayback = true))
    }
}
