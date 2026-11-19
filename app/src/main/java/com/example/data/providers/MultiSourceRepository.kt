package com.example.data.providers

import com.example.data.local.db.TrackDao
import com.example.data.local.prefs.AppSettings
import com.example.domain.model.AlbumItem
import com.example.domain.model.ArtistItem
import com.example.domain.model.GlobalSearchResult
import com.example.domain.model.MusicSource
import com.example.domain.model.PlaylistSummary
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
     * Resolves a playable URL for any track, with automatic cross-provider fallback if the primary fails.
     */
    suspend fun resolvePlayableUrl(track: Track, settings: AppSettings): String? {
        // Offline local or downloaded copy always wins!
        if (!track.localPath.isNullOrBlank()) return track.localPath
        if (track.source == MusicSource.RADIO) return track.streamUrl

        val primary = onlineProviders[track.source]
        val resolved = runCatching { primary?.resolveStreamUrl(track) }.getOrNull()
        if (!resolved.isNullOrBlank()) return resolved

        // Automatic fallback: search enabled alternate online providers for matching track
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
                val url = runCatching { provider.resolveStreamUrl(match) }.getOrNull()
                if (!url.isNullOrBlank()) return url
            }
        }
        return track.streamUrl
    }

    suspend fun getRelatedTracks(track: Track): List<Track> {
        val provider = onlineProviders[track.source] ?: audiusProvider
        return runCatching { provider.getRelatedTracks(track, 12) }.getOrDefault(emptyList())
    }
}
