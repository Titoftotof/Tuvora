package com.nuvio.tv.playback.android.output

/** HDMI audio can disconnect briefly during an app-requested, non-seamless display switch. */
internal class DisplayModeTransitionWindow(
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private var deadlineMs: Long? = null

    fun begin() { deadlineMs = clockMs() + 3_000L }
    fun cancel() { deadlineMs = null }
    fun isActive(): Boolean = deadlineMs?.let { clockMs() < it } == true
}
