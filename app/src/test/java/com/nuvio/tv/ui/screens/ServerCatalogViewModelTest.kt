package com.nuvio.tv.ui.screens

import androidx.lifecycle.SavedStateHandle
import com.nuvio.tv.core.contracts.*
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ServerCatalogViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher); HomeSectionContributorRegistry.resetForTest() }
    @After fun cleanup() { HomeSectionContributorRegistry.resetForTest(); Dispatchers.resetMain() }
    @Test fun `pagination failure keeps loaded items and retries the same offset`() = runTest(dispatcher) {
        val active = MutableStateFlow(1)
        val profile = mockk<ProfileManager> { every { activeProfileId } returns active }
        val item = MetaPreview(id = "item", type = ContentType.MOVIE, name = "Movie", poster = null, posterShape = com.nuvio.tv.domain.model.PosterShape.POSTER,
            background = null, logo = null, description = null, releaseInfo = null, imdbRating = null, genres = emptyList())
        var calls = 0
        val offsets = mutableListOf<Int?>()
        HomeSectionContributorRegistry.register(object : HomeSectionContributor {
            override val name = "test"
            override fun ownsSource(sourceKey: String) = true
            override suspend fun sections(forceRefresh: Boolean) = listOf(
                ContributedHomeRow("row", "source", "list", "Category", "Provider", "movie", listOf(item), true))
            override suspend fun loadSourcePage(sourceKey: String, listId: String, skip: Int?): ContributedPage {
                offsets += skip
                if (calls++ == 0) throw java.io.IOException()
                return ContributedPage(listOf(item.copy(id = "second")), null)
            }
        })
        val vm = ServerCatalogViewModel(SavedStateHandle(mapOf("addonId" to ContributedRows.ADDON_ID, "catalogId" to "row")),
            mockk(relaxed = true), profile)
        runCurrent()
        vm.loadMore()
        runCurrent()
        assertTrue(vm.failed.value)
        assertEquals(listOf("item"), vm.row.value!!.items.map { it.id })
        assertTrue(vm.row.value!!.hasMore)
        vm.retry()
        runCurrent()
        assertFalse(vm.failed.value)
        assertEquals(listOf(1, 1), offsets)
        assertEquals(listOf("item", "second"), vm.row.value!!.items.map { it.id })
        assertFalse(vm.row.value!!.hasMore)
        active.value = 2
        vm.loadMore()
        assertNull(vm.row.value)
    }
}
