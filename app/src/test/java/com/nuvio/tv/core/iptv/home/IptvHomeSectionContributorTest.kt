package com.nuvio.tv.core.iptv.home

import com.nuvio.tv.core.iptv.IptvClientFactory
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.iptv.XtreamItemRegistry
import com.nuvio.tv.core.iptv.content.ContentVod
import com.nuvio.tv.core.iptv.content.ContentSeries
import com.nuvio.tv.core.iptv.content.IptvContentDb
import com.nuvio.tv.core.iptv.match.XtreamMatchIndex
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.IptvHomeCategoryStore
import com.nuvio.tv.data.local.XtreamAccountStore
import com.nuvio.tv.ui.screens.home.toCatalogRow
import com.nuvio.tv.core.contracts.ContributedRows
import com.nuvio.tv.domain.model.legacyKey
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class IptvHomeSectionContributorTest {
    private val active = MutableStateFlow(1)
    private val profile = mockk<ProfileManager> { every { activeProfileId } returns active }
    private val categories = mockk<IptvHomeCategoryStore>()
    private val accounts = mockk<XtreamAccountStore>()
    private val db = mockk<IptvContentDb>()
    private val factory = mockk<IptvClientFactory>(relaxed = true)
    private val index = mockk<XtreamMatchIndex>(relaxed = true)
    private val registry = mockk<XtreamItemRegistry>(relaxed = true)
    private val contributor = IptvHomeSectionContributor(categories, accounts, db, profile, factory, index, registry)
    private val a = XtreamAccount("Aa", "Provider A", "", "", "", sourceType = XtreamAccount.SOURCE_URL)
    private val b = a.copy(id = "BB", name = "Provider B")

    private fun selection(account: XtreamAccount, type: IptvHomeContentType = IptvHomeContentType.MOVIES) =
        IptvHomeCategorySelection(account.id, account.name, type, "category", "Category")

    @Test fun `providers with colliding Java hashes keep separate rows pages and content identities`() = runTest {
        assertEquals(a.id.hashCode(), b.id.hashCode())
        assertNotEquals(selection(a).sourceKey, selection(b).sourceKey)
        coEvery { categories.getSelectionsForProfile(1) } returns listOf(selection(a), selection(b))
        coEvery { accounts.accountsForProfile(1) } returns listOf(a, b)
        coEvery { db.pageVod(a.id, "category", 0, 21) } returns
            (1..21).map { ContentVod(it, "A$it", null, "category", "https://a/$it", "mp4") }
        coEvery { db.pageVod(b.id, "category", 0, 21) } returns
            listOf(ContentVod(1, "B1", null, "category", "https://b/1", "mp4"))
        val rows = contributor.sections(false)
        assertEquals(2, rows.size)
        assertEquals(20, rows[0].items.size)
        assertTrue(rows[0].hasMore)
        assertFalse(rows[1].hasMore)
        assertEquals(XtreamItemRegistry.vodId(a.id, 1), rows[0].items[0].id)
        assertEquals(XtreamItemRegistry.vodId(b.id, 1), rows[1].items[0].id)
        coEvery { db.pageVod(a.id, "category", 20, 51) } returns
            (21..71).map { ContentVod(it, "A$it", null, "category", "https://a/$it", "mp4") }
        val page = contributor.loadSourcePage(rows[0].sourceKey, rows[0].listId, 20)
        assertEquals(50, page.items.size)
        assertEquals(70, page.nextSkip)
        assertEquals(XtreamItemRegistry.vodId(a.id, 21), page.items.first().id)
    }

    @Test fun `series retain their type in ordering keys and end pagination exactly`() = runTest {
        val selection = selection(a, IptvHomeContentType.SERIES)
        coEvery { categories.getSelectionsForProfile(1) } returns listOf(selection)
        coEvery { accounts.accountsForProfile(1) } returns listOf(a)
        coEvery { db.pageSeries(a.id, "category", 0, 21) } returns listOf(ContentSeries(7, "Series", null, "category"))
        val row = contributor.sections(false).single()
        assertEquals("series", row.toCatalogRow().apiType)
        assertEquals(ContributedRows.homeKey(row.key, "series"), row.toCatalogRow().legacyKey())
        assertEquals("series", contributor.declaredRows().single().rawType)
        coEvery { db.pageSeries(a.id, "category", 0, 51) } returns listOf(ContentSeries(7, "Series", null, "category"))
        val page = contributor.loadSourcePage(row.sourceKey, row.listId, null)
        assertEquals(XtreamItemRegistry.seriesId(a.id, 7), page.items.single().id)
        assertNull(page.nextSkip)
    }

    @Test fun `home and see all enforce content type and category restrictions`() = runTest {
        coEvery { categories.getSelectionsForProfile(1) } returns listOf(selection(a))
        for (restricted in listOf(
            a.copy(contentTypes = setOf(XtreamAccount.TYPE_SERIES)),
            a.copy(categorySelections = com.nuvio.tv.core.iptv.CategorySelections(movies = emptyList()))
        )) {
            coEvery { accounts.accountsForProfile(1) } returns listOf(restricted)
            assertTrue(contributor.sections(false).isEmpty())
            assertTrue(contributor.loadSourcePage(selection(a).sourceKey, "movies:category", 0).items.isEmpty())
        }
        io.mockk.coVerify(exactly = 0) { db.pageVod(any(), any(), any(), any()) }
    }

    @Test fun `a failed preview stays retryable without breaking other rows`() = runTest {
        coEvery { categories.getSelectionsForProfile(1) } returns listOf(selection(a), selection(b))
        coEvery { accounts.accountsForProfile(1) } returns listOf(a, b)
        coEvery { db.pageVod(a.id, "category", 0, 21) } throws java.io.IOException()
        coEvery { db.pageVod(b.id, "category", 0, 21) } returns listOf(ContentVod(1, "B", null, "category", "https://b/1", null))
        val rows = contributor.sections(false)
        assertTrue(rows.first().loadFailed)
        assertTrue(rows.first().hasMore)
        assertFalse(rows.last().loadFailed)
        coEvery { db.pageVod(a.id, "category", 0, 21) } returns listOf(ContentVod(1, "A", null, "category", "https://a/1", null))
        assertFalse(contributor.row(selection(a).rowKey)!!.loadFailed)
    }

    @Test fun `disabled accounts and profile switches cannot publish old rows`() = runTest {
        coEvery { categories.getSelectionsForProfile(1) } returns listOf(selection(a))
        coEvery { accounts.accountsForProfile(1) } returns listOf(a.copy(enabled = false))
        assertTrue(contributor.sections(false).isEmpty())
        assertTrue(contributor.loadSourcePage(selection(a).sourceKey, "movies:category", 0).items.isEmpty())
        coEvery { accounts.accountsForProfile(1) } returns listOf(a)
        coEvery { db.pageVod(a.id, "category", 0, 21) } answers {
            active.value = 2
            listOf(ContentVod(1, "Old profile", null, "category", "https://a/1", null))
        }
        assertTrue(contributor.sections(false).isEmpty())
        assertTrue(contributor.declaredRows().isEmpty())
    }
}
