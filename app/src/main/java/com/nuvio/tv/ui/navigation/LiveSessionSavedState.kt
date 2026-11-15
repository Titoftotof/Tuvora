package com.nuvio.tv.ui.navigation

import androidx.lifecycle.SavedStateHandle
import com.nuvio.tv.playback.live.LiveChannelTarget

/**
 * Identity of an accepted tune, owned by one navigation entry. Android saves it with the task;
 * it is not a "last channel on every launch" preference and never contains transport or tokens.
 */
internal class LiveSessionSavedState(private val handle: SavedStateHandle) {
    class Identity(val contentId: String, val accountId: String, val origin: CleanLiveLaunchOrigin)

    fun save(target: LiveChannelTarget, profileId: Int, origin: CleanLiveLaunchOrigin = CleanLiveLaunchOrigin.LIBRARY) {
        handle[KEY] = arrayListOf(profileId.toString(), target.contentId.value, target.selection.accountId.value, origin.name)
    }

    fun read(profileId: Int, accountId: String? = null): Identity? {
        val values = handle.get<ArrayList<String>>(KEY) ?: return null
        if (values.size != 4 || values[0] != profileId.toString() ||
            values[1].isBlank() || values[2].isBlank() ||
            (accountId != null && accountId != values[2])) {
            clear()
            return null
        }
        val origin = CleanLiveLaunchOrigin.entries.firstOrNull { it.name == values[3] } ?: run {
            clear()
            return null
        }
        return Identity(values[1], values[2], origin)
    }

    fun clear() { handle.remove<ArrayList<String>>(KEY) }

    private companion object {
        const val KEY = "live.acceptedSession.v1"
    }
}
