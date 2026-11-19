package com.example.data.providers

import com.example.data.local.db.TrackDao
import com.example.data.local.prefs.AppSettings
import com.example.domain.model.GlobalSearchResult
import com.example.domain.model.MusicSource
import com.example.domain.model.ProviderHealthState
import com.example.domain.model.ProviderStatus
import com.example.domain.model.RadioStation
import com.example.domain.model.SearchFieldFilter
import com.example.domain.model.Track
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class MultiSourceRepository(
    private val audiusProvider: AudiusProvider,
    private val youtubeMusicProvider: YouTubeMusicProvider,
    private val jamendoProvider: JamendoProvider,
    private val radioBrowserProvider: RadioBrowserProvider,
    private val trackDao: TrackDao
) {
    private val onlineProviders: Map<MusicSource, MusicSourceProvider> = mapOf(
        MusicSource.AUDIUS to audiusProvider,
        MusicSource.YOUTUBE_MUSIC to youtubeMusicProvider,
        MusicSource.JAMENDO to jamendoProvider
    )

    suspend fun checkAllProvidersHealth(settings: AppSettings): List<ProviderStatus> = coroutineScope {
        val priorityMap = settings.sourcePriorityOrder.withIndex().associate { it.value to it.index }
        MusicSource.entries.map { source ->
            async {
                val enabled = source in settings.enabledSources
                val priority = priorityMap[source] ?: 99
                if (!enabled) {
                    ProviderStatus(
                        source = source,
                        enabled = false,
                        priority = priority,
                        health = ProviderHealthState.DISABLED,
                        message = "Disabled by user"
                    )
                } else {
                    when (source) {
                        MusicSource.LOCAL -> ProviderStatus(
                            source = MusicSource.LOCAL,
                            enabled = true,
                            priority = priority,
                            health = ProviderHealthState.ONLINE,
                            message = "Android MediaStore & SAF Ready"
                        )
                        MusicSource.RADIO -> radioBrowserProvider.checkHealth().copy(priority = priority)
                        else -> onlineProviders[source]?.checkHealth()?.copy(priority = priority)
                            ?: ProviderStatus(source, true, priority, ProviderHealthState.OFFLINE, "Unavailable")
                    }
                }
            }
        }.awaitAll().sortedBy { it.priority }
    }

    suspend fun fetchHomeTrending(settings: AppSettings): List<Track> = coroutineScope {
        val ordered = settings.sourcePriorityOrder.filter {
            it in settings.enabledSources && it in onlineProviders
        }
        val lists = ordered.map { source ->
            async {
                runCatching { onlineProviders[source]?.getTrending(16).orEmpty() }.getOrDefault(emptyList())
            }
        }.awaitAll()
        TrackMetadataNormalizer.deduplicateTracks(lists.flatten(), settings.sourcePriorityOrder)
    }

    suspend fun fetchHomeNewReleases(settings: AppSettings): List<Track> = coroutineScope {
        val ordered = settings.sourcePriorityOrder.filter {
            it in settings.enabledSources && it in onlineProviders
        }
        val lists = ordered.map { source ->
            async {
                runCatching { onlineProviders[source]?.getNewReleases(12).orEmpty() }.getOrDefault(emptyList())
            }
        }.awaitAll()
        TrackMetadataNormalizer.deduplicateTracks(lists.flatten(), settings.sourcePriorityOrder)
    }

    suspend fun fetchSourceTrending(source: MusicSource, limit: Int = 20): List<Track> {
        return runCatching { onlineProviders[source]?.getTrending(limit).orEmpty() }.getOrDefault(emptyList())
    }

    suspend fun fetchJamendoGenre(genre: String): List<Track> {
        return runCatching { jamendoProvider.getByGenre(genre, 20) }.getOrDefault(emptyList())
    }

    suspend fun fetchTopRadio(limit: Int = 24): List<RadioStation> {
        return runCatching { radioBrowserProvider.getTopStations(limit) }.getOrDefault(emptyList())
    }

    suspend fun searchRadio(query: String): List<RadioStation> {
        return runCatching { radioBrowserProvider.searchStations(query, 24) }.getOrDefault(emptyList())
    }

    suspend fun globalSearch(
        query: String,
        settings: AppSettings,
        selectedSources: Set<MusicSource> = settings.enabledSources,
        fieldFilter: SearchFieldFilter = SearchFieldFilter.ANY
    ): GlobalSearchResult = coroutineScope {
        if (query.isBlank()) return@coroutineScope GlobalSearchResult()
        val errors = mutableMapOf<MusicSource, String>()

        val localDeferred = async {
            if (MusicSource.LOCAL in selectedSources) {
                trackDao.searchTracks(query).map { it.toDomain() }
                    .filter { it.source == MusicSource.LOCAL }
            } else emptyList()
        }

        val activeOnline = settings.sourcePriorityOrder.filter {
            it in selectedSources && it in onlineProviders
        }

        val trackJobs = activeOnline.map { src ->
            async {
                runCatching { onlineProviders[src]!!.searchTracks(query, 18) }
                    .onFailure { errors[src] = it.message ?: "Search failed" }
                    .getOrDefault(emptyList())
            }
        }
        val artistJobs = activeOnline.map { src ->
            async { runCatching { onlineProviders[src]!!.searchArtists(query, 8) }.getOrDefault(emptyList()) }
        }
        val albumJobs = activeOnline.map { src ->
            async { runCatching { onlineProviders[src]!!.searchAlbums(query, 8) }.getOrDefault(emptyList()) }
        }
        val playlistJobs = activeOnline.map { src ->
            async { runCatching { onlineProviders[src]!!.searchPlaylists(query, 8) }.getOrDefault(emptyList()) }
        }
        val radioDeferred = async {
            if (MusicSource.RADIO in selectedSources) {
                runCatching { radioBrowserProvider.searchStations(query, 12) }.getOrDefault(emptyList())
            } else emptyList()
        }

        val allTracksRaw = localDeferred.await() + trackJobs.awaitAll().flatten()
        val filteredTracks = applyFieldFilter(allTracksRaw, query, fieldFilter)
        val deduplicatedTracks = TrackMetadataNormalizer.deduplicateTracks(
            filteredTracks,
            settings.sourcePriorityOrder
        )

        GlobalSearchResult(
            query = query,
            tracks = deduplicatedTracks,
            artists = artistJobs.awaitAll().flatten().distinctBy { "${it.name.lowercase()}_${it.source}" },
            albums = albumJobs.awaitAll().flatten().distinctBy { "${it.title.lowercase()}_${it.source}" },
            playlists = playlistJobs.awaitAll().flatten().distinctBy { "${it.name.lowercase()}_${it.source}" },
            radioStations = radioDeferred.await(),
            isLoading = false,
            providerErrors = errors
        )
    }

    private fun applyFieldFilter(
        tracks: List<Track>,
        query: String,
        filter: SearchFieldFilter
    ): List<Track> {
        if (filter == SearchFieldFilter.ANY) return tracks
        val q = query.lowercase()
        return tracks.filter { t ->
            when (filter) {
                SearchFieldFilter.TITLE -> t.title.lowercase().contains(q)
                SearchFieldFilter.ARTIST -> t.artist.lowercase().contains(q)
                SearchFieldFilter.ALBUM -> t.album.lowercase().contains(q)
                SearchFieldFilter.GENRE -> t.genre.lowercase().contains(q)
                SearchFieldFilter.ANY -> true
            }
        }
    }

    /**
     * Resolves a playable stream (URL + matching client User-Agent & MIME type) for any track,
     * supporting multi-attempt tier fallback and cross-provider failover.
     */
    suspend fun resolvePlayableStream(
        track: Track,
        settings: AppSettings,
        attempt: Int = 0
    ): ResolvedStream? {
        if (!track.localPath.isNullOrBlank()) {
            return ResolvedStream(url = track.localPath, resolverName = "Offline Local")
        }
        if (track.source == MusicSource.RADIO) {
            return track.streamUrl?.let { ResolvedStream(url = it, resolverName = "Internet Radio") }
        }

        val primary = onlineProviders[track.source]
        val resolved = runCatching { primary?.resolveStream(track, attempt) }.getOrNull()
        if (resolved != null && resolved.url.isNotBlank()) return resolved

        // Automatic cross-provider fallback if primary source failed
        val fallbacks = settings.sourcePriorityOrder.filter {
            it != track.source && it in settings.enabledSources && it in onlineProviders
        }
        val normTitle = TrackMetadataNormalizer.normalizeTitle(track.title)
        for (fallbackSource in fallbacks) {
            val provider = onlineProviders[fallbackSource] ?: continue
            val candidates = runCatching {
                provider.searchTracks("${track.title} ${track.artist}", 5)
            }.getOrDefault(emptyList())
            val match = candidates.firstOrNull {
                TrackMetadataNormalizer.normalizeTitle(it.title) == normTitle
            } ?: candidates.firstOrNull()
            if (match != null) {
                val stream = runCatching { provider.resolveStream(match, 0) }.getOrNull()
                if (stream != null && stream.url.isNotBlank()) return stream
            }
        }
        return track.streamUrl?.let { ResolvedStream(url = it, resolverName = track.source.displayName) }
    }

    suspend fun getRelatedTracks(track: Track): List<Track> {
        val provider = onlineProviders[track.source] ?: youtubeMusicProvider
        return runCatching { provider.getRelatedTracks(track, 12) }.getOrDefault(emptyList())
    }

    /**
     * Builds a fresh, deduplicated list of recommended upcoming tracks for Auto-Queue.
     * Uses YouTube Music's Up Next Radio mix first, tops up from trending if needed,
     * and falls back to local tracks if offline so playback never stops unexpectedly.
     */
    suspend fun buildAutoQueueTracks(
        seedTrack: Track,
        settings: AppSettings,
        excludeIds: Set<String> = emptySet(),
        limit: Int = 15
    ): List<Track> {
        val collected = mutableListOf<Track>()
        val seenIds = excludeIds.toMutableSet().apply { add(seedTrack.id) }
        val seenTitles = mutableSetOf<String>().apply {
            val seedTitle = TrackMetadataNormalizer.normalizeTitle(seedTrack.title)
            if (seedTitle.isNotBlank()) add(seedTitle)
        }

        fun absorb(candidates: List<Track>) {
            for (cand in candidates) {
                if (collected.size >= limit) break
                if (cand.isLiveStream) continue
                if (cand.id in seenIds) continue
                val norm = TrackMetadataNormalizer.normalizeTitle(cand.title)
                if (norm.isNotBlank() && norm in seenTitles) continue
                seenIds.add(cand.id)
                if (norm.isNotBlank()) seenTitles.add(norm)
                collected.add(cand)
            }
        }

        // 1. Primary provider related tracks (for LOCAL tracks, prefer YouTube Music if enabled, else local siblings)
        val primaryProvider = when {
            seedTrack.source in onlineProviders -> onlineProviders[seedTrack.source]
            MusicSource.YOUTUBE_MUSIC in settings.enabledSources -> youtubeMusicProvider
            else -> settings.sourcePriorityOrder.firstNotNullOfOrNull {
                if (it in settings.enabledSources) onlineProviders[it] else null
            }
        }
        if (primaryProvider != null) {
            val related = runCatching { primaryProvider.getRelatedTracks(seedTrack, limit) }
                .getOrDefault(emptyList())
            absorb(related)
        }

        // 2. Top-up from enabled sources trending if related returned fewer than 5 tracks
        if (collected.size < 5) {
            val trending = runCatching { fetchHomeTrending(settings) }.getOrDefault(emptyList())
            absorb(trending)
        }

        // 3. Offline / local fallback from cached & local Room library
        if (collected.size < 3) {
            val localFallback = runCatching {
                trackDao.searchTracks("").map { it.toDomain() }
                    .filter { !it.isLiveStream && (!it.localPath.isNullOrBlank() || it.source != MusicSource.RADIO) }
                    .shuffled()
            }.getOrDefault(emptyList())
            absorb(localFallback)
        }

        return collected.take(limit)
    }
}
