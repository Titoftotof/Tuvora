package com.nuvio.tv.ui.screens.iptv

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import com.nuvio.tv.core.iptv.BoundedLoad
import com.nuvio.tv.core.iptv.IptvClient
import com.nuvio.tv.core.iptv.IptvClientFactory
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.iptv.XtreamCategory
import com.nuvio.tv.core.iptv.XtreamItemRegistry
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.RememberedHubSelection
import com.nuvio.tv.data.local.XtreamAccountStore
import com.nuvio.tv.data.local.XtreamHubSelectionStore
import com.nuvio.tv.data.local.XtreamLiveStore
import com.nuvio.tv.domain.repository.LibraryRepository
import com.nuvio.tv.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class XtreamHubFixedSectionTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val realSink = BoundedLoad.sink

    private val accountA = XtreamAccount(
        id = "acc-a", name = "Provider A", baseUrl = "http://a.test", username = "u", password = "p",
        sourceType = XtreamAccount.SOURCE_XTREAM
    )
    private val accountB = XtreamAccount(
        id = "acc-b", name = "Provider B", baseUrl = "http://b.test", username = "u", password = "p",
        sourceType = XtreamAccount.SOURCE_XTREAM
    )

    @Before
    fun setUp() {
        BoundedLoad.sink = { _, _ -> }
    }

    @After
    fun tearDown() {
        BoundedLoad.sink = realSink
    }

    private fun buildVm(
        fixedSection: XtreamSection?,
        sharedAccountFlow: MutableStateFlow<String?>,
        selection: XtreamHubSelectionStore,
        startOnLiveTv: Boolean = false,
    ): XtreamHubViewModel {
        val store = mockk<XtreamAccountStore> {
            every { accounts } returns MutableStateFlow(listOf(accountA, accountB))
        }
        val provider = mockk<IptvClient>(relaxed = true) {
            coEvery { liveCategories(any()) } returns Result.success(listOf(XtreamCategory("0", "Live")))
            coEvery { vodCategories(any()) } returns Result.success(listOf(XtreamCategory("1", "Action")))
            coEvery { seriesCategories(any()) } returns Result.success(listOf(XtreamCategory("2", "Drama")))
        }
        val factory = mockk<IptvClientFactory> { every { clientFor(any()) } returns provider }
        val liveStore = mockk<XtreamLiveStore>(relaxed = true) { every { recents } returns flowOf(emptyList()) }
        val library = mockk<LibraryRepository>(relaxed = true) { every { libraryItems } returns flowOf(emptyList()) }
        val matchIndex = mockk<com.nuvio.tv.core.iptv.match.XtreamMatchIndex>(relaxed = true) {
            coEvery { categoriesFor(any(), any()) } returns emptyList()
            coEvery { builtAt(any(), any()) } returns null
        }
        val enricher = mockk<com.nuvio.tv.core.iptv.match.PosterEnricher>(relaxed = true) {
            every { updates } returns MutableSharedFlow()
        }
        val failover = mockk<com.nuvio.tv.core.iptv.PlaylistServerFailover>(relaxed = true) {
            every { lastFailedServerUrl(any()) } returns null
        }
        val layout = mockk<LayoutPreferenceDataStore> {
            every { posterCardWidthDp } returns flowOf(126)
            every { posterCardHeightDp } returns flowOf(189)
            every { posterCardCornerRadiusDp } returns flowOf(12)
            every { posterLabelsEnabled } returns flowOf(true)
            every { homeImdbRatingsVisibility } returns flowOf(com.nuvio.tv.domain.model.HomeImdbRatingsVisibility.SHOW_ALL)
            every { iptvStartOnLiveTv } returns flowOf(startOnLiveTv)
        }
        val context = mockk<Context>(relaxed = true)
        every { selection.selectedAccountId } returns sharedAccountFlow
        val savedState = if (fixedSection != null) {
            SavedStateHandle(mapOf(XtreamHubViewModel.ARG_FIXED_SECTION to fixedSection.name))
        } else {
            SavedStateHandle()
        }
        return XtreamHubViewModel(
            store = store,
            clientFactory = factory,
            registry = XtreamItemRegistry(),
            liveStore = liveStore,
            selectionStore = selection,
            libraryRepository = library,
            fileStore = mockk(relaxed = true),
            contentDb = mockk(relaxed = true),
            resolver = mockk(relaxed = true),
            matchIndex = matchIndex,
            posterEnricher = enricher,
            failover = failover,
            layoutPreferenceDataStore = layout,
            context = context,
            savedStateHandle = savedState,
        )
    }

    @Test
    fun `fixed section ignores remembered hub tab and blocks selectSection`() = runTest(main.dispatcher) {
        val sharedAccount = MutableStateFlow<String?>(accountA.id)
        val selection = mockk<XtreamHubSelectionStore>(relaxed = true) {
            coEvery { read() } returns RememberedHubSelection(accountA.id, XtreamSection.LIVE.name)
        }
        val vm = buildVm(XtreamSection.MOVIES, sharedAccount, selection)
        runCurrent()

        assertEquals(XtreamSection.MOVIES, vm.uiState.value.section)
        vm.selectSection(XtreamSection.SERIES)
        runCurrent()
        assertEquals(XtreamSection.MOVIES, vm.uiState.value.section)
    }

    @Test
    fun `startOnLiveTv in hub mode opens LIVE even when Movies was remembered`() = runTest(main.dispatcher) {
        val sharedAccount = MutableStateFlow<String?>(accountA.id)
        val selection = mockk<XtreamHubSelectionStore>(relaxed = true) {
            coEvery { read() } returns RememberedHubSelection(accountA.id, XtreamSection.MOVIES.name)
        }
        val vm = buildVm(fixedSection = null, sharedAccountFlow = sharedAccount, selection = selection, startOnLiveTv = true)
        runCurrent()

        assertEquals(XtreamSection.LIVE, vm.uiState.value.section)
    }

    @Test
    fun `switching provider persists account only and syncs across split screens`() = runTest(main.dispatcher) {
        val sharedAccount = MutableStateFlow<String?>(accountA.id)
        val selection = mockk<XtreamHubSelectionStore>(relaxed = true) {
            coEvery { read() } returns RememberedHubSelection(accountA.id, XtreamSection.LIVE.name)
        }
        val vm = buildVm(XtreamSection.SERIES, sharedAccount, selection)
        runCurrent()
        assertEquals(XtreamSection.SERIES, vm.uiState.value.section)
        assertEquals(accountA.id, vm.uiState.value.selectedAccountId)

        // Switching from another split screen via the shared store updates this screen too.
        sharedAccount.value = accountB.id
        runCurrent()
        assertEquals(accountB.id, vm.uiState.value.selectedAccountId)

        // Explicit user pick saves account without clobbering remembered hub tab.
        vm.selectAccount(accountA.id)
        runCurrent()
        assertEquals(accountA.id, vm.uiState.value.selectedAccountId)
        coVerify(exactly = 1) { selection.saveAccount(accountA.id) }
        coVerify(exactly = 0) { selection.save(any(), any()) }
    }
}
