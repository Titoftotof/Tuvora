package com.nuvio.tv.data.local

import com.nuvio.tv.TestPreferencesStore
import com.nuvio.tv.core.picture.AspectMode
import com.nuvio.tv.core.picture.PictureMemory
import com.nuvio.tv.core.picture.VideoZoom
import com.nuvio.tv.core.profile.ProfileManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class VodAspectModePreferencesTest {
    private val profileId = MutableStateFlow(1)
    private val stores = mutableMapOf<Pair<Int, String>, TestPreferencesStore>()
    private val factory = mockk<ProfileDataStoreFactory> {
        every { get(any(), any()) } answers {
            stores.getOrPut(firstArg<Int>() to secondArg<String>()) { TestPreferencesStore() }
        }
    }
    private val manager = mockk<ProfileManager> {
        every { activeProfileId } returns profileId
    }
    private val preferences = TrackPreferenceDataStore(factory, manager)

    @Test fun `selecting a mode affects only that film or series`() = runTest {
        preferences.saveVodPicture("film", PictureMemory(AspectMode.STRETCH.name))
        assertEquals(AspectMode.STRETCH.name, preferences.loadVodPicture("film")?.aspectMode)
        assertNull(preferences.loadVodPicture("other"))
        assertNull(preferences.defaultVodAspectMode())
    }

    @Test fun `apply all replaces legacy and new overrides while preserving zoom and live memory`() = runTest {
        val zoom = VideoZoom(1.2f, 1.1f)
        preferences.savePicture("legacy-series", PictureMemory(AspectMode.CINEMA_ZOOM.name, zoom))
        preferences.saveVodPicture("film", PictureMemory(AspectMode.STRETCH.name))
        val channel = PictureMemory(AspectMode.FULL_SCREEN.name, zoom)
        preferences.savePicture("xtream:account:live:1", channel)

        preferences.applyAspectModeToAllVod(AspectMode.ORIGINAL)

        assertEquals(AspectMode.ORIGINAL, preferences.defaultVodAspectMode())
        assertEquals(PictureMemory(AspectMode.ORIGINAL.name, zoom), preferences.loadVodPicture("legacy-series"))
        assertEquals(AspectMode.ORIGINAL.name, preferences.loadVodPicture("film")?.aspectMode)
        assertEquals(AspectMode.ORIGINAL.name, preferences.loadVodPicture("new-film")?.aspectMode)
        assertEquals(channel, preferences.loadPicture("xtream:account:live:1"))
    }

    @Test fun `a later individual selection overrides the default and stays profile scoped`() = runTest {
        preferences.applyAspectModeToAllVod(AspectMode.ORIGINAL)
        preferences.saveVodPicture("series", PictureMemory(AspectMode.SLIGHT_ZOOM.name))
        assertEquals(AspectMode.SLIGHT_ZOOM.name, preferences.loadVodPicture("series")?.aspectMode)
        assertEquals(AspectMode.ORIGINAL.name, preferences.loadVodPicture("other")?.aspectMode)
        profileId.value = 2
        assertNull(preferences.defaultVodAspectMode())
        assertNull(preferences.loadVodPicture("series"))
    }
}
