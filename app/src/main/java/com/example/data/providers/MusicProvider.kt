package com.example.data.providers

import com.example.domain.model.AlbumItem
import com.example.domain.model.ArtistItem
import com.example.domain.model.MusicSource
import com.example.domain.model.PlaylistSummary
import com.example.domain.model.ProviderStatus
import com.example.domain.model.Track
import kotlin.math.abs

interface MusicSourceProvider {
    val source: MusicSource
    suspend fun checkHealth(): ProviderStatus
    suspend fun getTrending(limit: Int = 20): List<Track>
    suspend fun getNewReleases(limit: Int = 15): List<Track> = emptyList()
    suspend fun searchTracks(query: String, limit: Int = 20): List<Track>
    suspend fun searchArtists(query: String, limit: Int = 10): List<ArtistItem> = emptyList()
    suspend fun searchAlbums(query: String, limit: Int = 10): List<AlbumItem> = emptyList()
    suspend fun searchPlaylists(query: String, limit: Int = 10): List<PlaylistSummary> = emptyList()
    suspend fun getRelatedTracks(track: Track, limit: Int = 12): List<Track> = emptyList()
    suspend fun resolveStreamUrl(track: Track): String? = track.streamUrl
}

object TrackMetadataNormalizer {
    private val bracketRegex = Regex("""[\(\[\{][^)\]\}]*(official|video|audio|lyrics|lyric|hd|4k|hq|visualizer|mv|remaster|live|ft\.|feat\.)[^)\]\}]*[\)\]\}]""", RegexOption.IGNORE_CASE)
    private val featRegex = Regex("""\s+(ft\.?|feat\.?|featuring)\s+.*$""", RegexOption.IGNORE_CASE)
    private val nonAlphaNum = Regex("""[^a-z0-9\s]""")
    private val multiSpace = Regex("""\s+""")

    fun normalizeTitle(raw: String): String {
        val noBrackets = bracketRegex.replace(raw, " ")
        val noFeat = featRegex.replace(noBrackets, " ")
        return multiSpace.replace(nonAlphaNum.replace(noFeat.lowercase(), " "), " ").trim()
    }

    fun normalizeArtist(raw: String): String {
        val primary = raw.split(",", "&", " x ", " X ", " feat", " ft.").firstOrNull().orEmpty()
        return multiSpace.replace(nonAlphaNum.replace(primary.lowercase(), " "), " ").trim()
    }

    /**
     * Deduplicates tracks across multiple providers using normalized title, artist, and duration (±5s).
     * Preserves provider priority ordering.
     */
    fun deduplicateTracks(
        tracks: List<Track>,
        sourcePriority: List<MusicSource>
    ): List<Track> {
        val priorityRank = sourcePriority.withIndex().associate { it.value to it.index }
        val sorted = tracks.sortedBy { priorityRank[it.source] ?: Int.MAX_VALUE }
        val unique = mutableListOf<Track>()

        for (candidate in sorted) {
            val candTitle = normalizeTitle(candidate.title)
            val candArtist = normalizeArtist(candidate.artist)
            val isDuplicate = unique.any { existing ->
                val existTitle = normalizeTitle(existing.title)
                val existArtist = normalizeArtist(existing.artist)
                val titleMatches = candTitle.isNotEmpty() && candTitle == existTitle
                val artistMatches = candArtist.isEmpty() || existArtist.isEmpty() ||
                    candArtist == existArtist || candArtist.contains(existArtist) || existArtist.contains(candArtist)
                val durationMatches = existing.durationMs <= 0L || candidate.durationMs <= 0L ||
                    abs(existing.durationMs - candidate.durationMs) <= 5_000L
                titleMatches && artistMatches && durationMatches
            }
            if (!isDuplicate) {
                unique.add(candidate)
            }
        }
        return unique
    }
}
