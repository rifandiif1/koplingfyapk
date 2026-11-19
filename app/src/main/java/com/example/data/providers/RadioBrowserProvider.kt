package com.example.data.providers

import com.example.domain.model.MusicSource
import com.example.domain.model.ProviderHealthState
import com.example.domain.model.ProviderStatus
import com.example.domain.model.RadioStation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder

/**
 * Open Radio Browser Directory API (free, community-maintained global internet radio catalog).
 */
class RadioBrowserProvider(
    private val httpClient: OkHttpClient
) {
    private val servers = listOf(
        "https://de1.api.radio-browser.info",
        "https://at1.api.radio-browser.info",
        "https://nl1.api.radio-browser.info"
    )

    private suspend fun fetchArray(path: String): JSONArray? = withContext(Dispatchers.IO) {
        for (server in servers) {
            val arr = runCatching {
                val req = Request.Builder()
                    .url("$server$path")
                    .header("User-Agent", "Koplingfy/1.0.0 (Android)")
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) JSONArray(resp.body?.string().orEmpty()) else null
                }
            }.getOrNull()
            if (arr != null) return@withContext arr
        }
        null
    }

    suspend fun checkHealth(): ProviderStatus {
        val start = System.currentTimeMillis()
        val arr = fetchArray("/json/stations/topvote/1")
        val latency = System.currentTimeMillis() - start
        return if (arr != null && arr.length() > 0) {
            ProviderStatus(
                source = MusicSource.RADIO,
                enabled = true,
                priority = 4,
                health = ProviderHealthState.ONLINE,
                message = "Connected to Radio Browser Global Directory",
                latencyMs = latency
            )
        } else {
            ProviderStatus(
                source = MusicSource.RADIO,
                enabled = true,
                priority = 4,
                health = ProviderHealthState.OFFLINE,
                message = "Radio Browser directory unreachable",
                latencyMs = latency
            )
        }
    }

    suspend fun getTopStations(limit: Int = 25): List<RadioStation> {
        val arr = fetchArray("/json/stations/topvote/$limit?hidebroken=true") ?: return emptyList()
        return parseStations(arr)
    }

    suspend fun searchStations(query: String, limit: Int = 25): List<RadioStation> {
        if (query.isBlank()) return getTopStations(limit)
        val encoded = URLEncoder.encode(query, "UTF-8")
        val arr = fetchArray("/json/stations/search?name=$encoded&limit=$limit&hidebroken=true&order=votes&reverse=true")
            ?: return emptyList()
        return parseStations(arr)
    }

    suspend fun getStationsByTag(tag: String, limit: Int = 20): List<RadioStation> {
        val encoded = URLEncoder.encode(tag.lowercase(), "UTF-8")
        val arr = fetchArray("/json/stations/bytag/$encoded?limit=$limit&hidebroken=true&order=votes&reverse=true")
            ?: return emptyList()
        return parseStations(arr)
    }

    private fun parseStations(arr: JSONArray): List<RadioStation> = buildList {
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val uuid = obj.optString("stationuuid")
            val name = obj.optString("name").trim()
            val url = obj.optString("url_resolved").ifBlank { obj.optString("url") }.trim()
            if (uuid.isBlank() || name.isBlank() || !url.startsWith("http")) continue
            add(
                RadioStation(
                    id = uuid,
                    name = name,
                    streamUrl = url,
                    artworkUrl = obj.optString("favicon").takeIf { it.startsWith("http") },
                    country = obj.optString("country"),
                    genre = obj.optString("tags").split(",").take(2).joinToString(", ") { it.trim() },
                    bitrateKbps = obj.optInt("bitrate", 128)
                )
            )
        }
    }
}
