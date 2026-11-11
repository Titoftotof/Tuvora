package com.nuvio.tv.data.local

import com.nuvio.tv.TestPreferencesStore
import com.nuvio.tv.core.iptv.home.*
import com.nuvio.tv.core.profile.ProfileManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class IptvHomeCategoryStoreTest {
    private val active = MutableStateFlow(1)
    private val profile = mockk<ProfileManager> { every { activeProfileId } returns active }
    private val stores = mutableMapOf<Int, TestPreferencesStore>()
    private val factory = mockk<ProfileDataStoreFactory> {
        every { get(any(), any()) } answers { stores.getOrPut(firstArg()) { TestPreferencesStore() } }
    }
    private val subject = IptvHomeCategoryStore(factory, profile)
    private fun row(id: String, provider: String = "provider") =
        IptvHomeCategorySelection(provider, provider, IptvHomeContentType.MOVIES, id, id)

    @Test fun `rapid toggles preserve a single entry and the final selection`() = runTest {
        (1..10).map { async { subject.toggleForProfile(1, row("a")) } }.awaitAll()
        val result = subject.getSelectionsForProfile(1)
        assertEquals(1, result.size)
        assertFalse(result.single().enabled)
    }

    @Test fun `ordering moves enabled rows across providers without dropping disabled rows`() = runTest {
        subject.saveSelectionsForProfile(1, listOf(row("a"), row("hidden").copy(enabled = false, order = 1),
            row("b", "other").copy(order = 2)))
        subject.moveForProfile(1, row("b", "other").rowKey, -1)
        assertEquals(listOf("b", "hidden", "a"), subject.getSelectionsForProfile(1).map { it.categoryId })
        assertFalse(subject.getSelectionsForProfile(1)[1].enabled)
    }

    @Test fun `selection remains scoped to its captured profile`() = runTest {
        active.value = 2
        subject.toggleForProfile(1, row("a"))
        assertTrue(subject.getSelectionsForProfile(2).isEmpty())
        assertTrue(subject.getSelectionsForProfile(1).single().enabled)
    }
}
