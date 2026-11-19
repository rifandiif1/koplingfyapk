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
 * Official Jamendo API v3.0 Provider (Creative Commons streaming & legal downloads).
 * Uses user-configured Jamendo client_id (free registration at devportal.jamendo.com).
 */
class JamendoProvider(
    private val httpClient: OkHttpClient,
    private val clientIdProvider: () -> String
) : MusicSourceProvider {

    override val source: MusicSource = MusicSource.JAMENDO
    private val baseUrl = "https://api.jamendo.com/v3.0"

    private fun activeClientId(): String = clientIdProvider().trim()

    private suspend fun getJson(endpoint: String): JSONObject? = withContext(Dispatchers.IO) {
        val clientId = activeClientId()
        if (clientId.isBlank()) return@withContext null
        runCatching {
            val sep = if (endpoint.contains("?")) "&" else "?"
            val url = "$baseUrl$endpoint${sep}client_id=$clientId&format=json"
            val req = Request.Builder().url(url).build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) JSONObject(resp.body?.string().orEmpty()) else null
            }
        }.getOrNull()
    }

    override suspend fun checkHealth(): ProviderStatus {
        val clientId = activeClientId()
        if (clientId.isBlank()) {
            return ProviderStatus(
                source = source,
                enabled = true,
                priority = 3,
                health = ProviderHealthState.REQUIRES_KEY,
                message = "Configure free Jamendo client_id in Sources / Settings (devportal.jamendo.com)"
            )
        }
        val start = System.currentTimeMillis()
        val json = getJson("/tracks/?limit=1&order=popularity_week")
        val latency = System.currentTimeMillis() - start
        val status = json?.optJSONObject("headers")?.optString("status")
        return if (status == "success") {
            ProviderStatus(
                source = source,
                enabled = true,
                priority = 3,
                health = ProviderHealthState.ONLINE,
                message = "Connected to Jamendo Creative Commons API",
                latencyMs = latency
            )
        } else {
            ProviderStatus(
                source = source,
                enabled = true,
                priority = 3,
                health = ProviderHealthState.OFFLINE,
                message = json?.optJSONObject("headers")?.optString("error_message")
                    ?.takeIf { it.isNotBlank() } ?: "Invalid client_id or network error",
                latencyMs = latency
            )
        }
    }

    override suspend fun getTrending(limit: Int): List<Track> {
        val json = getJson("/tracks/?limit=$limit&order=popularity_week&audioformat=mp32") ?: return emptyList()
        return parseTracks(json.optJSONArray("results"))
    }

    override suspend fun getNewReleases(limit: Int): List<Track> {
        val json = getJson("/tracks/?limit=$limit&order=releasedate_desc&audioformat=mp32") ?: return emptyList()
        return parseTracks(json.optJSONArray("results"))
    }

    suspend fun getByGenre(genre: String, limit: Int = 20): List<Track> {
        val encoded = URLEncoder.encode(genre.lowercase(), "UTF-8")
        val json = getJson("/tracks/?tags=$encoded&limit=$limit&order=popularity_total&audioformat=mp32")
            ?: return emptyList()
        return parseTracks(json.optJSONArray("results"))
    }

    override suspend fun searchTracks(query: String, limit: Int): List<Track> {
        if (query.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(query, "UTF-8")
        val json = getJson("/tracks/?search=$encoded&limit=$limit&audioformat=mp32") ?: return emptyList()
        return parseTracks(json.optJSONArray("results"))
    }

    override suspend fun searchArtists(query: String, limit: Int): List<ArtistItem> {
        if (query.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(query, "UTF-8")
        val json = getJson("/artists/?namesearch=$encoded&limit=$limit") ?: return emptyList()
        val arr = json.optJSONArray("results") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                add(
                    ArtistItem(
                        id = "jamendo_artist_${obj.optString("id")}",
                        name = obj.optString("name"),
                        artworkUrl = obj.optString("image").takeIf { it.isNotBlank() },
                        source = MusicSource.JAMENDO
                    )
                )
            }
        }
    }

    override suspend fun searchAlbums(query: String, limit: Int): List<AlbumItem> {
        if (query.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(query, "UTF-8")
        val json = getJson("/albums/?namesearch=$encoded&limit=$limit") ?: return emptyList()
        val arr = json.optJSONArray("results") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                add(
                    AlbumItem(
                        id = "jamendo_album_${obj.optString("id")}",
                        title = obj.optString("name"),
                        artist = obj.optString("artist_name"),
                        artworkUrl = obj.optString("image").takeIf { it.isNotBlank() },
                        year = obj.optString("releasedate").take(4).toIntOrNull() ?: 0,
                        source = MusicSource.JAMENDO
                    )
                )
            }
        }
    }

    override suspend fun searchPlaylists(query: String, limit: Int): List<PlaylistSummary> {
        if (query.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(query, "UTF-8")
        val json = getJson("/playlists/?namesearch=$encoded&limit=$limit") ?: return emptyList()
        val arr = json.optJSONArray("results") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                add(
                    PlaylistSummary(
                        id = "jamendo_pl_${obj.optString("id")}",
                        name = obj.optString("name"),
                        description = "By ${obj.optString("user_name")}",
                        source = MusicSource.JAMENDO
                    )
                )
            }
        }
    }

    private fun parseTracks(arr: JSONArray?): List<Track> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id")
                val title = obj.optString("name")
                val audio = obj.optString("audio")
                if (id.isBlank() || title.isBlank() || audio.isBlank()) continue
                val downloadAllowed = obj.optBoolean("audiodownload_allowed", false)
                val downloadUrl = obj.optString("audiodownload").takeIf { downloadAllowed && it.startsWith("http") }
                add(
                    Track(
                        id = "jamendo_$id",
                        title = title,
                        artist = obj.optString("artist_name").ifBlank { "Jamendo Artist" },
                        album = obj.optString("album_name").ifBlank { "Jamendo CC" },
                        year = obj.optString("releasedate").take(4).toIntOrNull() ?: 0,
                        durationMs = obj.optLong("duration", 0L) * 1000L,
                        artworkUrl = obj.optString("album_image").ifBlank { obj.optString("image") }
                            .takeIf { it.startsWith("http") },
                        streamUrl = audio,
                        source = MusicSource.JAMENDO,
                        sourceTrackId = id,
                        isDownloadable = downloadAllowed && downloadUrl != null,
                        downloadUrl = downloadUrl
                    )
                )
            }
        }
    }
}
