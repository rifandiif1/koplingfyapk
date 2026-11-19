package com.example.data.providers

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
 * Official Audius Open API Provider (free, no API key required).
 * Uses dynamic discovery node resolution with automatic fallback hosts.
 */
class AudiusProvider(
    private val httpClient: OkHttpClient
) : MusicSourceProvider {

    override val source: MusicSource = MusicSource.AUDIUS

    private val appName = "KOPLINGFY"
    private val fallbackHosts = listOf(
        "https://discoveryprovider.audius.co",
        "https://discoveryprovider2.audius.co",
        "https://discoveryprovider3.audius.co",
        "https://audius-discovery-1.cultur3stake.com"
    )

    @Volatile
    private var activeHost: String? = null

    private suspend fun resolveHost(): String = withContext(Dispatchers.IO) {
        activeHost?.let { return@withContext it }
        runCatching {
            val req = Request.Builder().url("https://api.audius.co").build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    val data = JSONObject(body).optJSONArray("data")
                    if (data != null && data.length() > 0) {
                        val host = data.optString(0).trimEnd('/')
                        if (host.startsWith("http")) {
                            activeHost = host
                            return@withContext host
                        }
                    }
                }
            }
        }
        fallbackHosts.first()
    }

    private suspend fun getJson(pathAndQuery: String): JSONObject? = withContext(Dispatchers.IO) {
        val hostsToTry = buildList {
            activeHost?.let { add(it) }
            add(resolveHost())
            addAll(fallbackHosts)
        }.distinct()

        for (host in hostsToTry) {
            val result = runCatching {
                val sep = if (pathAndQuery.contains("?")) "&" else "?"
                val url = "$host$pathAndQuery${sep}app_name=$appName"
                val request = Request.Builder().url(url).build()
                httpClient.newCall(request).execute().use { resp ->
                    if (resp.isSuccessful) {
                        activeHost = host
                        JSONObject(resp.body?.string().orEmpty())
                    } else null
                }
            }.getOrNull()
            if (result != null) return@withContext result
        }
        null
    }

    override suspend fun checkHealth(): ProviderStatus {
        val start = System.currentTimeMillis()
        val json = getJson("/v1/tracks/trending?limit=1")
        val latency = System.currentTimeMillis() - start
        return if (json != null && json.optJSONArray("data") != null) {
            ProviderStatus(
                source = source,
                enabled = true,
                priority = 0,
                health = ProviderHealthState.ONLINE,
                message = "Connected to Audius Discovery Network",
                latencyMs = latency
            )
        } else {
            ProviderStatus(
                source = source,
                enabled = true,
                priority = 0,
                health = ProviderHealthState.OFFLINE,
                message = "Audius discovery hosts unreachable",
                latencyMs = latency
            )
        }
    }

    override suspend fun getTrending(limit: Int): List<Track> {
        val host = resolveHost()
        val json = getJson("/v1/tracks/trending?limit=$limit") ?: return emptyList()
        return parseTrackArray(json.optJSONArray("data"), host)
    }

    override suspend fun getNewReleases(limit: Int): List<Track> {
        val host = resolveHost()
        val json = getJson("/v1/tracks/trending/underground?limit=$limit") ?: return emptyList()
        return parseTrackArray(json.optJSONArray("data"), host)
    }

    override suspend fun searchTracks(query: String, limit: Int): List<Track> {
        if (query.isBlank()) return emptyList()
        val host = resolveHost()
        val encoded = URLEncoder.encode(query, "UTF-8")
        val json = getJson("/v1/tracks/search?query=$encoded&limit=$limit") ?: return emptyList()
        return parseTrackArray(json.optJSONArray("data"), host)
    }

    override suspend fun searchArtists(query: String, limit: Int): List<ArtistItem> {
        if (query.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(query, "UTF-8")
        val json = getJson("/v1/users/search?query=$encoded&limit=$limit") ?: return emptyList()
        val arr = json.optJSONArray("data") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id")
                val name = obj.optString("name").ifBlank { obj.optString("handle") }
                if (id.isBlank() || name.isBlank()) continue
                val pic = obj.optJSONObject("profile_picture")
                val artwork = pic?.optString("480x480")?.takeIf { it.isNotBlank() }
                    ?: pic?.optString("150x150")?.takeIf { it.isNotBlank() }
                add(
                    ArtistItem(
                        id = "audius_artist_$id",
                        name = name,
                        artworkUrl = artwork,
                        source = MusicSource.AUDIUS,
                        trackCount = obj.optInt("track_count", 0),
                        followerCount = obj.optInt("follower_count", 0)
                    )
                )
            }
        }
    }

    override suspend fun searchPlaylists(query: String, limit: Int): List<PlaylistSummary> {
        if (query.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(query, "UTF-8")
        val json = getJson("/v1/playlists/search?query=$encoded&limit=$limit") ?: return emptyList()
        val arr = json.optJSONArray("data") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id")
                val name = obj.optString("playlist_name")
                if (id.isBlank() || name.isBlank()) continue
                val artObj = obj.optJSONObject("artwork")
                val artwork = artObj?.optString("480x480")?.takeIf { it.isNotBlank() }
                    ?: artObj?.optString("150x150")?.takeIf { it.isNotBlank() }
                add(
                    PlaylistSummary(
                        id = "audius_pl_$id",
                        name = name,
                        description = obj.optString("description"),
                        artworkUrl = artwork,
                        source = MusicSource.AUDIUS,
                        trackCount = obj.optInt("track_count", 0)
                    )
                )
            }
        }
    }

    override suspend fun getRelatedTracks(track: Track, limit: Int): List<Track> {
        val query = track.genre.ifBlank { track.artist }
        return searchTracks(query, limit).filterNot { it.id == track.id }
    }

    override suspend fun resolveStreamUrl(track: Track): String {
        val host = resolveHost()
        return "$host/v1/tracks/${track.sourceTrackId}/stream?app_name=$appName"
    }

    private fun parseTrackArray(arr: JSONArray?, host: String): List<Track> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id")
                val title = obj.optString("title")
                if (id.isBlank() || title.isBlank()) continue
                val user = obj.optJSONObject("user")
                val artist = user?.optString("name")?.takeIf { it.isNotBlank() }
                    ?: user?.optString("handle")
                    ?: "Audius Artist"
                val artworkObj = obj.optJSONObject("artwork")
                val artwork = artworkObj?.optString("1000x1000")?.takeIf { it.isNotBlank() }
                    ?: artworkObj?.optString("480x480")?.takeIf { it.isNotBlank() }
                    ?: artworkObj?.optString("150x150")?.takeIf { it.isNotBlank() }
                val durationSec = obj.optLong("duration", 0L)
                val downloadable = obj.optBoolean("is_downloadable", false)
                val streamUrl = "$host/v1/tracks/$id/stream?app_name=$appName"
                add(
                    Track(
                        id = "audius_$id",
                        title = title,
                        artist = artist,
                        album = obj.optString("mood").ifBlank { "Audius Release" },
                        genre = obj.optString("genre").ifBlank { "Independent" },
                        durationMs = durationSec * 1000L,
                        artworkUrl = artwork,
                        streamUrl = streamUrl,
                        source = MusicSource.AUDIUS,
                        sourceTrackId = id,
                        isDownloadable = downloadable,
                        downloadUrl = if (downloadable) streamUrl else null,
                        playCount = obj.optInt("play_count", 0)
                    )
                )
            }
        }
    }
}
