package com.nuvio.tv.core.iptv

import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.iptv.match.MatchKind
import com.nuvio.tv.core.iptv.match.TitleNormalizer
import com.nuvio.tv.core.iptv.match.XtreamMatchIndex
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tmdb.TmdbEnrichment
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.local.XtreamAccountStore
import com.nuvio.tv.data.remote.api.IptvMovieSearchResult
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.dto.XtreamVodInfoDto
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaCastMember
import com.nuvio.tv.domain.model.TmdbSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Shared by focus previews and details. Playback identity always stays with the IPTV source. */
@Singleton
class IptvMovieMetadata @Inject constructor(
    private val accounts: XtreamAccountStore,
    private val client: XtreamClient,
    private val index: XtreamMatchIndex,
    private val api: TmdbApi,
    private val tmdb: TmdbMetadataService,
    private val settingsStore: TmdbSettingsDataStore,
    private val profiles: ProfileManager
) {
    private data class Cached(val meta: Meta, val expires: Long)
    private val cache = object : LinkedHashMap<String, Cached>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?) = size > 128
    }
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<Meta>>()
    private val requests = Semaphore(2)

    suspend fun resolve(item: XtreamResolvedItem): Meta {
        val base = item.toMeta()
        if (item.kind != XtreamKind.VOD) return base
        val profile = profiles.activeProfileId.value
        val settings = settingsStore.settings.first()
        val account = accounts.accountsForProfile(profile).firstOrNull { it.enabled && it.id == item.accountId }
            ?: return base
        if (!account.isXtream()) return base
        // Settings/language and profile are part of the key; another profile never inherits a result.
        val key = "$profile:${item.id}:$settings"
        synchronized(cache) { cache[key]?.takeIf { it.expires > System.currentTimeMillis() } }?.let { return it.meta }
        val deferred = CompletableDeferred<Meta>()
        inFlight.putIfAbsent(key, deferred)?.let { return it.await() }
        try {
            var cacheable = true
            val result = requests.withPermit {
                val provider = withTimeoutOrNull(8_000) {
                    client.vodDetails(account, item.streamId).also { if (it.isFailure) cacheable = false }.getOrNull()
                }
                var supplied = providerMovieMeta(base, provider)
                // The TMDB switch stays authoritative: provider details are read either way, but no
                // TMDB request is made while the user has TMDB enrichment turned off.
                val enriched = if (!settings.enabled) null else withTimeoutOrNull(12_000) {
                    try {
                        // A panel-supplied TMDB id does not depend on the optional local index.
                        val indexed = if (provider?.tmdbId?.takeIf { it > 0 } == null) {
                            index.item(account.id, MatchKind.MOVIE, item.streamId)
                        } else null
                        val year = provider?.releaseDate.orEmpty().take(4).toIntOrNull()
                            ?: provider?.releaseDateAlt.orEmpty().take(4).toIntOrNull()
                            ?: indexed?.year ?: TitleNormalizer.yearOf(item.name)
                        var tmdbId = provider?.tmdbId?.takeIf { it > 0 } ?: indexed?.tmdb?.takeIf { it > 0 }
                        if (tmdbId == null && year != null) {
                            // Panels can return an empty or differently localized name in vod_info.
                            // Retry the catalog title only after a genuine miss, never after ambiguity.
                            val names = listOfNotNull(provider?.name.present(), item.name.present())
                                .distinctBy { movieLookupTitle(it, year) }
                            for (name in names) {
                                val query = movieLookupTitle(name, year)
                                if (query.isBlank()) continue
                                val response = api.searchMovies(BuildConfig.TMDB_API_KEY, query, year, settings.language)
                                val body = response.body()
                                if (!response.isSuccessful || body == null) {
                                    cacheable = false
                                    break
                                }
                                // Don't accept a first-page match if unseen candidates might also match.
                                if (body.totalPages > 1) break
                                tmdbId = uniqueMovieMatch(name, year, body.results)
                                if (tmdbId != null || body.results.isNotEmpty()) break
                            }
                        }
                        // Keep the resolved external id for ratings without changing playback identity.
                        supplied = supplied.copy(tmdbId = tmdbId)
                        tmdbId?.let { tmdb.fetchEnrichment(it.toString(), item.type, settings.language) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        cacheable = false
                        null
                    }
                }
                // A TMDB miss is only worth retrying when TMDB was actually asked.
                if (provider == null || (settings.enabled && enriched == null)) cacheable = false
                mergeMovieEnrichment(supplied, enriched, settings)
            }
            if (profiles.activeProfileId.value != profile) {
                deferred.complete(base)
                return base
            }
            if (cacheable) synchronized(cache) { cache[key] = Cached(result, System.currentTimeMillis() + 15 * 60_000L) }
            deferred.complete(result)
            return result
        } catch (e: CancellationException) {
            deferred.cancel(e)
            throw e
        } catch (e: Exception) {
            deferred.complete(base)
            return base
        } finally {
            inFlight.remove(key, deferred)
        }
    }
}

internal fun uniqueMovieMatch(name: String, year: Int, candidates: List<IptvMovieSearchResult>): Int? {
    val wanted = movieLookupTitle(name, year)
    if (wanted.isBlank()) return null
    return candidates.filter {
        it.id > 0 && it.releaseDate?.take(4)?.toIntOrNull() == year &&
            listOfNotNull(it.title, it.originalTitle).any { title -> movieLookupTitle(title, year) == wanted }
    }.distinctBy { it.id }.singleOrNull()?.id
}

/** Strip explicit provider tags, retaining meaningful title words and numbers. */
internal fun movieLookupTitle(name: String, year: Int): String {
    // ']' carries no special meaning outside a character class, so it stays unescaped.
    val prefix = Regex("^(?:\\[?(?:FR|EN|MULTI|VOSTFR|4K|UHD|FHD|HD)]?\\s*[|:–-]\\s*)+", RegexOption.IGNORE_CASE)
    return TitleNormalizer.normKey(name.replace(prefix, "")).removeSuffix(" $year").trim()
}

private fun String?.present() = this?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
private fun String?.people() = present()?.split(',', ';')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

internal fun providerMovieMeta(base: Meta, info: XtreamVodInfoDto?): Meta {
    if (info == null) return base
    val cast = info.cast.people()
    val minutes = info.durationSeconds?.takeIf { it > 0 }?.let { (it / 60).coerceAtLeast(1).toString() }
        ?: info.duration.present()?.let { duration ->
            if (':' in duration) {
                val parts = duration.split(':').map { it.toIntOrNull() }
                if (parts.size == 3 && parts.all { it != null }) (parts[0]!! * 60 + parts[1]!!).toString() else null
            } else duration.toIntOrNull()?.takeIf { it > 0 }?.toString()
        }
    return base.copy(
        name = info.name.present() ?: base.name,
        description = info.plot.present() ?: base.description,
        poster = info.movieImage.present() ?: info.coverBig.present() ?: base.poster,
        background = info.backdrop.present()?.takeUnless { it == info.movieImage || it == info.coverBig || it == base.poster }
            ?: base.background,
        releaseInfo = (info.releaseDate.present() ?: info.releaseDateAlt.present())?.take(4) ?: base.releaseInfo,
        imdbRating = info.rating?.toFloatOrNull()?.takeIf { it > 0 && it <= 10 } ?: base.imdbRating,
        genres = info.genre.people().ifEmpty { base.genres },
        runtime = minutes ?: base.runtime,
        tmdbId = info.tmdbId?.takeIf { it > 0 } ?: base.tmdbId,
        director = info.director.people().ifEmpty { base.director },
        cast = cast.ifEmpty { base.cast },
        castMembers = cast.map { MetaCastMember(name = it) }.ifEmpty { base.castMembers }
    )
}

internal fun mergeMovieEnrichment(base: Meta, enrichment: TmdbEnrichment?, settings: TmdbSettings): Meta {
    val e = enrichment ?: return base
    val people = (e.directorMembers + e.writerMembers + e.castMembers.ifEmpty { base.castMembers })
        .filter { it.name.isNotBlank() }
        .distinctBy { Triple(it.tmdbId, it.name.lowercase(java.util.Locale.ROOT), it.character) }
    return base.copy(
        name = if (settings.useBasicInfo) e.localizedTitle.present() ?: e.originalTitle.present() ?: base.name else base.name,
        description = if (settings.useBasicInfo) e.description.present() ?: base.description else base.description,
        imdbRating = if (settings.useBasicInfo) e.rating?.toFloat()?.takeIf { it > 0 } ?: base.imdbRating else base.imdbRating,
        genres = if (settings.useBasicInfo) e.genres.ifEmpty { base.genres } else base.genres,
        poster = if (settings.useArtwork) e.poster.present() ?: base.poster else base.poster,
        background = if (settings.useArtwork) e.backdrop.present() ?: base.background else base.background,
        logo = if (settings.useArtwork) e.logo.present() ?: base.logo else base.logo,
        releaseInfo = if (settings.useDetails) e.releaseInfo.present() ?: base.releaseInfo else base.releaseInfo,
        runtime = if (settings.useDetails) e.runtimeMinutes?.toString() ?: base.runtime else base.runtime,
        status = if (settings.useDetails) e.status.present() ?: base.status else base.status,
        ageRating = if (settings.useDetails) e.ageRating.present() ?: base.ageRating else base.ageRating,
        country = if (settings.useDetails) e.countries?.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: base.country else base.country,
        language = if (settings.useDetails) e.language.present() ?: base.language else base.language,
        director = if (settings.useCredits) e.director.ifEmpty { base.director } else base.director,
        writer = if (settings.useCredits) e.writer.ifEmpty { base.writer } else base.writer,
        cast = if (settings.useCredits) e.castMembers.map { it.name }.ifEmpty { base.cast } else base.cast,
        castMembers = if (settings.useCredits) people.ifEmpty { base.castMembers } else base.castMembers,
        productionCompanies = if (settings.useProductions) e.productionCompanies.ifEmpty { base.productionCompanies } else base.productionCompanies,
        networks = if (settings.useNetworks) e.networks.ifEmpty { base.networks } else base.networks,
        trailers = if (settings.useTrailers) e.trailers.ifEmpty { base.trailers } else base.trailers
    )
}
