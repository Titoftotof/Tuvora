package com.nuvio.tv.playback.mediasession

import com.nuvio.tv.playback.core.ContentType
import com.nuvio.tv.playback.core.PlaybackSnapshot
import com.nuvio.tv.playback.core.SessionProfile

/**
 * Fire OS sends a platform transport pause when HDMI audio briefly drops during mode matching.
 * Preserve live and catch-up play intent only for that bounded, app-requested transition.
 * UI commands, voice/other controllers, VOD and pauses outside the switch remain authoritative.
 */
internal object FireTvHdmiPausePolicy {
    fun shouldIgnore(
        manufacturer: String,
        callerPackage: String?,
        callerUid: Int?,
        displayModeTransition: Boolean,
        snapshot: PlaybackSnapshot,
    ): Boolean =
        manufacturer.equals("Amazon", ignoreCase = true) &&
            callerPackage == "android" && callerUid == 1000 &&
            displayModeTransition &&
            snapshot.requestSummary?.contentType in setOf(ContentType.LIVE, ContentType.CATCH_UP) &&
            snapshot.profile == SessionProfile.FULLSCREEN &&
            snapshot.playWhenReady
}
