package com.example.data.providers

import com.example.domain.model.AlbumItem
import com.example.domain.model.ArtistItem
import com.example.domain.model.MusicSource
import com.example.domain.model.PlaylistSummary
import com.example.domain.model.ProviderHealthState
import com.example.domain.model.ProviderStatus
import com.example.domain.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Free YouTube Music Catalog & Audio Stream Provider via open Piped & Invidious federation APIs.
 * Requires zero paid API keys, supports configurable custom instance URL, and automatically fails over.
 * Strictly streaming-only (isDownloadable = false) to respect platform policies.
 */
class YouTubeMusicProvider(
    private val httpClient: OkHttpClient,
    private val customInstanceProvider: () -> String
) : MusicSourceProvider {

    override val source: MusicSource = MusicSource.YOUTUBE_MUSIC

    private val defaultPipedInstances = listOf(
        "https://pipedapi.kavin.rocks",
        "https://pipedapi.adminforge.de",
        "https://pipedapi.r4fo.com",
        "https://api.piped.private.coffee"
    )

    private val invidiousFallbacks = listOf(
        "https://inv.nadeko.net",
        "https://yewtu.be",
        "https://invidious.nerdvpn.de"
    )

    @Volatile
    private var cachedWorkingPiped: String? = null

    private fun pipedCandidates(): List<String> = buildList {
        val custom = customInstanceProvider().trim().trimEnd('/')
        if (custom.startsWith("http")) add(custom)
        cachedWorkingPiped?.let { add(it) }
        addAll(defaultPipedInstances)
    }.distinct()

    private suspend fun fetchPipedJson(path: String): String? = withContext(Dispatchers.IO) {
        for (base in pipedCandidates()) {
            val body = runCatching {
                val req = Request.Builder()
                    .url("$base$path")
                    .header("Accept", "application/json")
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val txt = resp.body?.string().orEmpty()
                        if (txt.isNotBlank() && (txt.startsWith("{") || txt.startsWith("["))) {
                            cachedWorkingPiped = base
                            txt
                        } else null
                    } else null
                }
            }.getOrNull()
            if (body != null) return@withContext body
        }
        null
    }

    private suspend fun fetchInvidiousJson(path: String): String? = withContext(Dispatchers.IO) {
        for (base in invidiousFallbacks) {
            val body = runCatching {
                val req = Request.Builder()
                    .url("$base$path")
                    .header("Accept", "application/json")
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val txt = resp.body?.string().orEmpty()
                        if (txt.isNotBlank() && (txt.startsWith("{") || txt.startsWith("["))) txt else null
                    } else null
                }
            }.getOrNull()
            if (body != null) return@withContext body
        }
        null
    }

    override suspend fun checkHealth(): ProviderStatus {
        val start = System.currentTimeMillis()
        val pipedOk = fetchPipedJson("/trending?region=US") != null
        val latency = System.currentTimeMillis() - start
        if (pipedOk) {
            return ProviderStatus(
                source = source,
                enabled = true,
                priority = 1,
                health = ProviderHealthState.ONLINE,
                message = "Connected via Piped (${cachedWorkingPiped ?: "auto"})",
                latencyMs = latency
            )
        }
        val invOk = fetchInvidiousJson("/api/v1/trending?type=Music&region=US") != null
        val totalLatency = System.currentTimeMillis() - start
        return if (invOk) {
            ProviderStatus(
                source = source,
                enabled = true,
                priority = 1,
                health = ProviderHealthState.DEGRADED,
                message = "Connected via Invidious fallback",
                latencyMs = totalLatency
            )
        } else {
            ProviderStatus(
                source = source,
                enabled = true,
                priority = 1,
                health = ProviderHealthState.OFFLINE,
                message = "Public Piped/Invidious nodes busy — set custom instance in Sources",
                latencyMs = totalLatency
            )
        }
    }

    override suspend fun getTrending(limit: Int): List<Track> {
        val piped = fetchPipedJson("/trending?region=US")
        if (piped != null && piped.startsWith("[")) {
            return parsePipedItems(JSONArray(piped)).take(limit)
        }
        val inv = fetchInvidiousJson("/api/v1/trending?type=Music&region=US")
        if (inv != null && inv.startsWith("[")) {
            return parseInvidiousItems(JSONArray(inv)).take(limit)
        }
        return emptyList()
    }

    override suspend fun getNewReleases(limit: Int): List<Track> {
        return searchTracks("new music release official audio", limit)
    }

    override suspend fun searchTracks(query: String, limit: Int): List<Track> {
        if (query.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(query, "UTF-8")
        val piped = fetchPipedJson("/search?q=$encoded&filter=music_songs")
        if (piped != null && piped.startsWith("{")) {
            val items = JSONObject(piped).optJSONArray("items")
            val tracks = parsePipedItems(items).take(limit)
            if (tracks.isNotEmpty()) return tracks
        }
        val inv = fetchInvidiousJson("/api/v1/search?q=$encoded&type=video")
        if (inv != null && inv.startsWith("[")) {
            return parseInvidiousItems(JSONArray(inv)).take(limit)
        }
        return emptyList()
    }

    override suspend fun searchArtists(query: String, limit: Int): List<ArtistItem> {
        if (query.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(query, "UTF-8")
        val piped = fetchPipedJson("/search?q=$encoded&filter=music_artists") ?: return emptyList()
        if (!piped.startsWith("{")) return emptyList()
        val items = JSONObject(piped).optJSONArray("items") ?: return emptyList()
        return buildList {
            for (i in 0 until minOf(items.length(), limit)) {
                val obj = items.optJSONObject(i) ?: continue
                val name = obj.optString("name")
                val url = obj.optString("url")
                if (name.isBlank()) continue
                add(
                    ArtistItem(
                        id = "ytm_artist_${url.substringAfterLast('/')}",
                        name = name,
                        artworkUrl = obj.optString("thumbnail").takeIf { it.isNotBlank() },
                        source = MusicSource.YOUTUBE_MUSIC,
                        followerCount = obj.optInt("subscribers", 0).coerceAtLeast(0)
                    )
                )
            }
        }
    }

    override suspend fun searchAlbums(query: String, limit: Int): List<AlbumItem> {
        if (query.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(query, "UTF-8")
        val piped = fetchPipedJson("/search?q=$encoded&filter=music_albums") ?: return emptyList()
        if (!piped.startsWith("{")) return emptyList()
        val items = JSONObject(piped).optJSONArray("items") ?: return emptyList()
        return buildList {
            for (i in 0 until minOf(items.length(), limit)) {
                val obj = items.optJSONObject(i) ?: continue
                val title = obj.optString("name")
                val url = obj.optString("url")
                if (title.isBlank()) continue
                add(
                    AlbumItem(
                        id = "ytm_album_${url.substringAfterLast('=')}",
                        title = title,
                        artist = obj.optString("uploaderName").ifBlank { "YouTube Music" },
                        artworkUrl = obj.optString("thumbnail").takeIf { it.isNotBlank() },
                        source = MusicSource.YOUTUBE_MUSIC,
                        trackCount = obj.optInt("videos", 0).coerceAtLeast(0)
                    )
                )
            }
        }
    }

    override suspend fun searchPlaylists(query: String, limit: Int): List<PlaylistSummary> {
        if (query.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(query, "UTF-8")
        val piped = fetchPipedJson("/search?q=$encoded&filter=music_playlists") ?: return emptyList()
        if (!piped.startsWith("{")) return emptyList()
        val items = JSONObject(piped).optJSONArray("items") ?: return emptyList()
        return buildList {
            for (i in 0 until minOf(items.length(), limit)) {
                val obj = items.optJSONObject(i) ?: continue
                val name = obj.optString("name")
                val url = obj.optString("url")
                if (name.isBlank()) continue
                add(
                    PlaylistSummary(
                        id = "ytm_pl_${url.substringAfterLast('=')}",
                        name = name,
                        description = obj.optString("uploaderName"),
                        artworkUrl = obj.optString("thumbnail").takeIf { it.isNotBlank() },
                        source = MusicSource.YOUTUBE_MUSIC,
                        trackCount = obj.optInt("videos", 0).coerceAtLeast(0)
                    )
                )
            }
        }
    }

    override suspend fun resolveStreamUrl(track: Track): String? {
        val videoId = track.sourceTrackId
        if (videoId.isBlank()) return null

        // 1. Try Piped /streams/{videoId} for best audio-only stream (M4A/Opus)
        val pipedBody = fetchPipedJson("/streams/$videoId")
        if (pipedBody != null && pipedBody.startsWith("{")) {
            val root = JSONObject(pipedBody)
            val audioStreams = root.optJSONArray("audioStreams")
            val bestUrl = selectBestAudioStream(audioStreams, urlKey = "url", bitrateKey = "bitrate")
            if (bestUrl != null) return bestUrl
            val hls = root.optString("hls")
            if (hls.startsWith("http")) return hls
        }

        // 2. Fallback to Invidious /api/v1/videos/{videoId}
        val invBody = fetchInvidiousJson("/api/v1/videos/$videoId")
        if (invBody != null && invBody.startsWith("{")) {
            val root = JSONObject(invBody)
            val adaptive = root.optJSONArray("adaptiveFormats")
            val bestUrl = selectBestInvidiousAudio(adaptive)
            if (bestUrl != null) return bestUrl
        }
        return null
    }

    override suspend fun getRelatedTracks(track: Track, limit: Int): List<Track> {
        val pipedBody = fetchPipedJson("/streams/${track.sourceTrackId}")
        if (pipedBody != null && pipedBody.startsWith("{")) {
            val related = JSONObject(pipedBody).optJSONArray("relatedStreams")
            val tracks = parsePipedItems(related).take(limit)
            if (tracks.isNotEmpty()) return tracks
        }
        return searchTracks("${track.artist} ${track.title}", limit).filterNot { it.id == track.id }
    }

    private fun selectBestAudioStream(
        streams: JSONArray?,
        urlKey: String,
        bitrateKey: String
    ): String? {
        if (streams == null || streams.length() == 0) return null
        var bestUrl: String? = null
        var bestScore = -1
        for (i in 0 until streams.length()) {
            val obj = streams.optJSONObject(i) ?: continue
            val url = obj.optString(urlKey)
            if (!url.startsWith("http")) continue
            val mime = obj.optString("mimeType").lowercase()
            val bitrate = obj.optInt(bitrateKey, 0)
            // Prefer M4A/MP4 audio for maximum Android hardware decoder compatibility, then Opus/WebM
            val codecBonus = when {
                mime.contains("mp4") || mime.contains("m4a") -> 100_000
                mime.contains("webm") || mime.contains("opus") -> 50_000
                else -> 0
            }
            val score = bitrate + codecBonus
            if (score > bestScore) {
                bestScore = score
                bestUrl = url
            }
        }
        return bestUrl
    }

    private fun selectBestInvidiousAudio(formats: JSONArray?): String? {
        if (formats == null) return null
        var bestUrl: String? = null
        var bestBitrate = -1
        for (i in 0 until formats.length()) {
            val obj = formats.optJSONObject(i) ?: continue
            val type = obj.optString("type").lowercase()
            if (!type.startsWith("audio/")) continue
            val url = obj.optString("url")
            if (!url.startsWith("http")) continue
            val bitrate = obj.optString("bitrate").toIntOrNull() ?: 0
            if (bitrate > bestBitrate) {
                bestBitrate = bitrate
                bestUrl = url
            }
        }
        return bestUrl
    }

    private fun parsePipedItems(arr: JSONArray?): List<Track> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val url = obj.optString("url")
                val videoId = extractVideoId(url)
                val title = obj.optString("title")
                if (videoId.isBlank() || title.isBlank()) continue
                val durationSec = obj.optLong("duration", 0L)
                if (durationSec < 15L) continue
                add(
                    Track(
                        id = "ytm_$videoId",
                        title = title,
                        artist = obj.optString("uploaderName").ifBlank { "YouTube Music" },
                        album = "YouTube Music",
                        durationMs = durationSec * 1000L,
                        artworkUrl = obj.optString("thumbnail").takeIf { it.isNotBlank() }
                            ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
                        streamUrl = null, // Resolved fresh on playback to avoid expiring signed URLs
                        source = MusicSource.YOUTUBE_MUSIC,
                        sourceTrackId = videoId,
                        isDownloadable = false
                    )
                )
            }
        }
    }

    private fun parseInvidiousItems(arr: JSONArray?): List<Track> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val videoId = obj.optString("videoId")
                val title = obj.optString("title")
                if (videoId.isBlank() || title.isBlank()) continue
                val durationSec = obj.optLong("lengthSeconds", 0L)
                if (durationSec < 15L) continue
                val thumbs = obj.optJSONArray("videoThumbnails")
                val thumbUrl = thumbs?.optJSONObject(0)?.optString("url")?.takeIf { it.startsWith("http") }
                    ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
                add(
                    Track(
                        id = "ytm_$videoId",
                        title = title,
                        artist = obj.optString("author").ifBlank { "YouTube Music" },
                        album = "YouTube Music",
                        durationMs = durationSec * 1000L,
                        artworkUrl = thumbUrl,
                        streamUrl = null,
                        source = MusicSource.YOUTUBE_MUSIC,
                        sourceTrackId = videoId,
                        isDownloadable = false
                    )
                )
            }
        }
    }

    private fun extractVideoId(url: String): String {
        return when {
            url.contains("v=") -> url.substringAfter("v=").substringBefore("&")
            url.startsWith("/watch/") -> url.removePrefix("/watch/").substringBefore("?")
            url.length == 11 -> url
            else -> ""
        }
    }
}
