package com.nuvio.tv.core.iptv.home

import com.nuvio.tv.core.contracts.ContributedHomeRow
import com.nuvio.tv.core.contracts.ContributedPage
import com.nuvio.tv.core.contracts.ContributedRowDeclaration
import com.nuvio.tv.core.contracts.HomeSectionContributor
import com.nuvio.tv.core.iptv.isXtream
import com.nuvio.tv.core.iptv.isM3UBacked
import com.nuvio.tv.core.iptv.IptvClientFactory
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.iptv.XtreamClient
import com.nuvio.tv.core.iptv.XtreamItemRegistry
import com.nuvio.tv.core.iptv.XtreamKind
import com.nuvio.tv.core.iptv.XtreamResolvedItem
import com.nuvio.tv.core.iptv.content.IptvContentDb
import com.nuvio.tv.core.iptv.match.MatchKind
import com.nuvio.tv.core.iptv.match.XtreamMatchIndex
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.IptvHomeCategoryStore
import com.nuvio.tv.data.local.XtreamAccountStore
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class IptvHomeSectionContributor @Inject constructor(
    private val categoryStore: IptvHomeCategoryStore,
    private val accountStore: XtreamAccountStore,
    private val contentDb: IptvContentDb,
    private val profileManager: ProfileManager,
    private val clientFactory: IptvClientFactory,
    private val matchIndex: XtreamMatchIndex,
    private val registry: XtreamItemRegistry
) : HomeSectionContributor {
    companion object {
        const val CONTRIBUTOR_NAME = "iptv_home"
        const val MAX_PREVIEW_ITEMS = 20
        const val PAGE_SIZE = 50
    }

    override val name = CONTRIBUTOR_NAME
    @Volatile private var declarations: Pair<Int, List<ContributedRowDeclaration>> = 0 to emptyList()

    private fun rememberDeclarations(profileId: Int, selections: List<IptvHomeCategorySelection>) {
        declarations = profileId to selections.filter { it.enabled }.sortedBy { it.order }.map {
            ContributedRowDeclaration(it.rowKey, it.categoryName, it.providerName, it.contentType.rawType)
        }
    }

    override val changes: Flow<Unit> = profileManager.activeProfileId.flatMapLatest { pid ->
        combine(categoryStore.selectionsForProfile(pid), accountStore.accounts,
            matchIndex.buildProgress.map { it.keys.toSet() }.distinctUntilChanged()) { selections, _, _ ->
            rememberDeclarations(pid, selections)
        }
    }

    override fun declaredRows(): List<ContributedRowDeclaration> =
        declarations.takeIf { it.first == profileManager.activeProfileId.value }?.second.orEmpty()

    private val loads = Semaphore(3)
    private val previewCache = object : LinkedHashMap<String, Pair<Long, ContributedHomeRow>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, ContributedHomeRow>>?) = size > 40
    }

    override suspend fun sections(forceRefresh: Boolean): List<ContributedHomeRow> {
        if (forceRefresh) synchronized(previewCache) { previewCache.clear() }
        return loadRows(null)
    }

    override suspend fun row(rowKey: String): ContributedHomeRow? = loadRows(rowKey, force = true).firstOrNull()

    private suspend fun loadRows(rowKey: String?, force: Boolean = false): List<ContributedHomeRow> = withContext(Dispatchers.IO) {
        val pid = profileManager.activeProfileId.value
        val selections = categoryStore.getSelectionsForProfile(pid).filter { it.enabled }.sortedBy { it.order }
        rememberDeclarations(pid, selections)
        val accounts = accountStore.accountsForProfile(pid).filter { it.availableOnDevice }.associateBy { it.id }
        val rows = selections.filter { rowKey == null || it.rowKey == rowKey }.map { selection ->
            async {
                loads.withPermit {
                    val account = accounts[selection.providerId] ?: return@withPermit null
                    val typeKey = if (selection.contentType == IptvHomeContentType.SERIES) XtreamAccount.TYPE_SERIES else XtreamAccount.TYPE_MOVIES
                    if (!account.typeEnabled(typeKey) || !account.allowsCategory(typeKey, selection.categoryId, selection.categoryName)) return@withPermit null
                    val cacheKey = "$pid:${selection.rowKey}:${account.hashCode()}"
                    val previous = synchronized(previewCache) { previewCache[cacheKey] }
                    if (!force && previous != null && System.nanoTime() - previous.first < 60_000_000_000L)
                        return@withPermit previous.second
                    try {
                        val page = withTimeout(15_000) {
                            window(account, selection.contentType, selection.categoryId, 0, MAX_PREVIEW_ITEMS, selection.categoryName)
                        }
                        if (page.items.isEmpty()) return@withPermit null
                        ContributedHomeRow(
                            key = selection.rowKey, sourceKey = selection.sourceKey,
                            listId = "${selection.contentType.key}:${selection.categoryId}",
                            title = selection.categoryName, subtitle = account.name,
                            rawType = selection.contentType.rawType, items = page.items, hasMore = page.nextSkip != null
                        ).also { row -> synchronized(previewCache) { previewCache[cacheKey] = System.nanoTime() to row } }
                    } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                        previous?.second?.copy(loadFailed = true) ?: failureRow(selection, account.name)
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { previous?.second?.copy(loadFailed = true) ?: failureRow(selection, account.name) }
                }
            }
        }.awaitAll().filterNotNull()
        if (pid == profileManager.activeProfileId.value) rows else emptyList()
    }

    private fun failureRow(selection: IptvHomeCategorySelection, provider: String) = ContributedHomeRow(
        key = selection.rowKey, sourceKey = selection.sourceKey,
        listId = "${selection.contentType.key}:${selection.categoryId}",
        title = selection.categoryName, subtitle = provider, rawType = selection.contentType.rawType,
        items = emptyList(), hasMore = true, loadFailed = true
    )

    override fun ownsSource(sourceKey: String) = sourceKey.startsWith("iptv:")

    override suspend fun loadSourcePage(sourceKey: String, listId: String, skip: Int?): ContributedPage =
        withContext(Dispatchers.IO) {
            val empty = ContributedPage(emptyList(), null)
            val pid = profileManager.activeProfileId.value
            val offset = skip ?: 0
            if (offset < 0) return@withContext empty
            val selection = categoryStore.getSelectionsForProfile(pid).firstOrNull {
                it.enabled && it.sourceKey == sourceKey && "${it.contentType.key}:${it.categoryId}" == listId
            } ?: return@withContext empty
            val account = accountStore.accountsForProfile(pid).firstOrNull {
                it.availableOnDevice && it.id == selection.providerId
            } ?: return@withContext empty
            val page = withTimeout(15_000) {
                window(account, selection.contentType, selection.categoryId, offset, PAGE_SIZE, selection.categoryName)
            }
            if (pid == profileManager.activeProfileId.value) page else empty
        }

    private suspend fun window(
        account: XtreamAccount, type: IptvHomeContentType, categoryId: String, offset: Int, limit: Int, categoryName: String? = null
    ): ContributedPage {
        val series = type == IptvHomeContentType.SERIES
        val typeKey = if (series) XtreamAccount.TYPE_SERIES else XtreamAccount.TYPE_MOVIES
        if (!account.enabled || !account.typeEnabled(typeKey) || !account.allowsCategory(typeKey, categoryId, categoryName)) {
            return ContributedPage(emptyList(), null)
        }
        val kind = if (series) MatchKind.SERIES else MatchKind.MOVIE
        val client = clientFactory.clientFor(account)
        fun item(
            sid: Int, name: String, poster: String?, url: String = "",
            plot: String? = null, rating: String? = null, year: String? = null
        ): MetaPreview {
            val id = if (series) XtreamItemRegistry.seriesId(account.id, sid)
                else XtreamItemRegistry.vodId(account.id, sid)
            registry.register(XtreamResolvedItem(
                id = id, type = if (series) ContentType.SERIES else ContentType.MOVIE,
                name = name, poster = poster, streamUrl = url,
                description = plot, imdbRating = rating?.toFloatOrNull(), releaseInfo = year,
                kind = if (series) XtreamKind.SERIES else XtreamKind.VOD,
                accountId = account.id, streamId = sid
            ))
            return MetaPreview(
                id = id, type = if (series) ContentType.SERIES else ContentType.MOVIE,
                rawType = type.rawType, name = name, poster = poster, posterShape = PosterShape.POSTER,
                background = null, logo = null, description = plot, releaseInfo = year,
                imdbRating = rating?.toFloatOrNull(), genres = emptyList()
            )
        }
        val rows = when {
            account.isXtream() && matchIndex.builtAt(account.id, kind) != null ->
                matchIndex.itemsFor(account.id, kind, categoryId, offset, limit + 1).map {
                    item(it.sid, it.name, it.poster,
                        if (series) "" else (client as XtreamClient).buildStreamUrl(account, "movie", it.sid, it.ext ?: "mp4"),
                        rating = it.rating?.toString(), year = it.year?.toString())
                }
            account.isM3UBacked() && series ->
                contentDb.pageSeries(account.id, categoryId, offset, limit + 1).map { item(it.sid, it.name, it.logo) }
            account.isM3UBacked() ->
                contentDb.pageVod(account.id, categoryId, offset, limit + 1).map { item(it.sid, it.name, it.logo, it.url) }
            series -> client.series(account, categoryId).getOrThrow().drop(offset).take(limit + 1)
                .map { item(it.seriesId, it.name, it.poster, plot = it.plot, rating = it.rating) }
            else -> client.vodMovies(account, categoryId).getOrThrow().drop(offset).take(limit + 1)
                .map { item(it.streamId, it.name, it.poster, it.streamUrl, rating = it.rating) }
        }
        return ContributedPage(rows.take(limit), if (rows.size > limit) offset + limit else null)
    }
}
