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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Free YouTube Music Catalog & Direct Audio Stream Provider (zero paid API keys).
 *
 * Uses a resilient multi-tier architecture:
 * - Catalog (Search / Charts / Discovery): Direct YouTube Music InnerTube WEB_REMIX API first,
 *   with Piped & Invidious federation fallback.
 * - Audio Stream Resolution (6-tier waterfall):
 *   1. Direct InnerTube ANDROID_VR (direct unencrypted M4A/Opus audio from device IP)
 *   2. Direct InnerTube IOS (direct M4A/Opus + native HLS manifest)
 *   3. Direct InnerTube ANDROID_TESTSUITE
 *   4. Direct InnerTube TVHTML5_SIMPLY_EMBEDDED_PLAYER
 *   5. Live Piped API instances (auto-discovered + user custom gateway)
 *   6. Live Invidious API instances (supports both direct & relative /latest_version proxy URLs)
 *
 * Strictly streaming-only (isDownloadable = false).
 */
class YouTubeMusicProvider(
    private val httpClient: OkHttpClient,
    private val customInstanceProvider: () -> String
) : MusicSourceProvider {

    override val source: MusicSource = MusicSource.YOUTUBE_MUSIC

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val webRemixUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"
    private val androidVrUa =
        "com.google.android.apps.youtube.vr.exo/1.60.19 (Linux; U; Android 12L; Quest 3 Build/SQ3A.220605.009.A1) gzip"
    private val iosUa =
        "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)"
    private val androidTestsuiteUa =
        "com.google.android.youtube/1.9 (Linux; U; Android 11) gzip"

    private val defaultPipedInstances = listOf(
        "https://pipedapi.kavin.rocks",
        "https://pipedapi.adminforge.de",
        "https://pipedapi.r4fo.com",
        "https://api.piped.private.coffee",
        "https://pipedapi.darkness.services"
    )

    private val defaultInvidiousInstances = listOf(
        "https://inv.nadeko.net",
        "https://yewtu.be",
        "https://invidious.nerdvpn.de",
        "https://iv.datura.network"
    )

    @Volatile
    private var cachedWorkingPiped: String? = null

    @Volatile
    private var dynamicPipedInstances: List<String> = emptyList()

    @Volatile
    private var dynamicInvidiousInstances: List<String> = emptyList()

    @Volatile
    private var cachedVisitorData: String? = null

    private fun pipedCandidates(): List<String> = buildList {
        val custom = customInstanceProvider().trim().trimEnd('/')
        if (custom.startsWith("http")) add(custom)
        cachedWorkingPiped?.let { add(it) }
        addAll(dynamicPipedInstances)
        addAll(defaultPipedInstances)
    }.distinct()

    private fun invidiousCandidates(): List<String> = buildList {
        addAll(dynamicInvidiousInstances)
        addAll(defaultInvidiousInstances)
    }.distinct()

    override suspend fun checkHealth(): ProviderStatus = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val innerTubeOk = searchInnerTubeTracks("music", 2).isNotEmpty()
        val latency = System.currentTimeMillis() - start
        if (innerTubeOk) {
            return@withContext ProviderStatus(
                source = source,
                enabled = true,
                priority = 1,
                health = ProviderHealthState.ONLINE,
                message = "Direct YouTube Music InnerTube Ready (ANDROID_VR / IOS)",
                latencyMs = latency
            )
        }
        val pipedOk = fetchPipedJson("/trending?region=US") != null
        val total = System.currentTimeMillis() - start
        if (pipedOk) {
            ProviderStatus(
                source = source,
                enabled = true,
                priority = 1,
                health = ProviderHealthState.DEGRADED,
                message = "Connected via Piped Gateway (${cachedWorkingPiped ?: "auto"})",
                latencyMs = total
            )
        } else {
            ProviderStatus(
                source = source,
                enabled = true,
                priority = 1,
                health = ProviderHealthState.OFFLINE,
                message = "YouTube Music unreachable — check internet or Piped URL",
                latencyMs = total
            )
        }
    }

    override suspend fun getTrending(limit: Int): List<Track> = withContext(Dispatchers.IO) {
        // 1. Try direct YouTube Music InnerTube Charts / Top Hits
        val directCharts = fetchInnerTubeCharts(limit)
        if (directCharts.isNotEmpty()) return@withContext directCharts

        val directSearch = searchInnerTubeTracks("top hits official audio", limit)
        if (directSearch.isNotEmpty()) return@withContext directSearch

        // 2. Piped fallback
        val piped = fetchPipedJson("/trending?region=ID") ?: fetchPipedJson("/trending?region=US")
        if (piped != null && piped.startsWith("[")) {
            val list = parsePipedItems(JSONArray(piped)).take(limit)
            if (list.isNotEmpty()) return@withContext list
        }

        // 3. Invidious fallback
        val inv = fetchInvidiousJson("/api/v1/trending?type=Music&region=US")
        if (inv != null && inv.second.startsWith("[")) {
            return@withContext parseInvidiousItems(JSONArray(inv.second)).take(limit)
        }
        emptyList()
    }

    override suspend fun getNewReleases(limit: Int): List<Track> =
        searchTracks("new music release 2025 official audio", limit)

    override suspend fun searchTracks(query: String, limit: Int): List<Track> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()

        // 1. Direct YouTube Music InnerTube Search (fast, zero third-party server needed)
        val direct = searchInnerTubeTracks(query, limit)
        if (direct.isNotEmpty()) return@withContext direct

        // 2. Piped API fallback
        val encoded = URLEncoder.encode(query, "UTF-8")
        val piped = fetchPipedJson("/search?q=$encoded&filter=music_songs")
        if (piped != null && piped.startsWith("{")) {
            val tracks = parsePipedItems(JSONObject(piped).optJSONArray("items")).take(limit)
            if (tracks.isNotEmpty()) return@withContext tracks
        }

        // 3. Invidious API fallback
        val inv = fetchInvidiousJson("/api/v1/search?q=$encoded&type=video")
        if (inv != null && inv.second.startsWith("[")) {
            return@withContext parseInvidiousItems(JSONArray(inv.second)).take(limit)
        }
        emptyList()
    }

    override suspend fun searchArtists(query: String, limit: Int): List<ArtistItem> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val encoded = URLEncoder.encode(query, "UTF-8")
        val piped = fetchPipedJson("/search?q=$encoded&filter=music_artists")
        if (piped != null && piped.startsWith("{")) {
            val items = JSONObject(piped).optJSONArray("items")
            if (items != null) {
                return@withContext buildList {
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
        }
        // Fallback: derive unique artists from direct InnerTube track search
        searchInnerTubeTracks(query, limit * 2)
            .distinctBy { it.artist.lowercase() }
            .take(limit)
            .map { t ->
                ArtistItem(
                    id = "ytm_artist_${t.artist.hashCode()}",
                    name = t.artist,
                    artworkUrl = t.artworkUrl,
                    source = MusicSource.YOUTUBE_MUSIC
                )
            }
    }

    override suspend fun searchAlbums(query: String, limit: Int): List<AlbumItem> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val encoded = URLEncoder.encode(query, "UTF-8")
        val piped = fetchPipedJson("/search?q=$encoded&filter=music_albums") ?: return@withContext emptyList()
        if (!piped.startsWith("{")) return@withContext emptyList()
        val items = JSONObject(piped).optJSONArray("items") ?: return@withContext emptyList()
        buildList {
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

    override suspend fun searchPlaylists(query: String, limit: Int): List<PlaylistSummary> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val encoded = URLEncoder.encode(query, "UTF-8")
        val piped = fetchPipedJson("/search?q=$encoded&filter=music_playlists") ?: return@withContext emptyList()
        if (!piped.startsWith("{")) return@withContext emptyList()
        val items = JSONObject(piped).optJSONArray("items") ?: return@withContext emptyList()
        buildList {
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

    override suspend fun resolveStreamUrl(track: Track): String? =
        resolveStream(track, attempt = 0)?.url

    /**
     * Multi-tier YouTube audio stream resolver.
     * Each retry attempt starts further down the waterfall so HTTP 403 / expired streams recover seamlessly.
     */
    override suspend fun resolveStream(track: Track, attempt: Int): ResolvedStream? = withContext(Dispatchers.IO) {
        val videoId = track.sourceTrackId.takeIf { it.isNotBlank() }
            ?: track.id.removePrefix("ytm_")
        if (videoId.isBlank()) return@withContext null

        val resolvers: List<suspend () -> ResolvedStream?> = listOf(
            // Tier 1: Direct InnerTube ANDROID_VR (direct unencrypted M4A/Opus audio from user's own IP)
            { resolveViaInnerTubeAndroidVr(videoId) },
            // Tier 2: Direct InnerTube IOS (direct M4A/Opus + native HLS manifest)
            { resolveViaInnerTubeIos(videoId) },
            // Tier 3: Direct InnerTube ANDROID_TESTSUITE
            { resolveViaInnerTubeAndroidTestsuite(videoId) },
            // Tier 4: Direct InnerTube TVHTML5_SIMPLY_EMBEDDED_PLAYER
            { resolveViaInnerTubeTvEmbedded(videoId) },
            // Tier 5: Piped Federation (refreshes public instance directory automatically if needed)
            { resolveViaPiped(videoId) },
            // Tier 6: Invidious Federation (supports direct URL + local proxy /latest_version)
            { resolveViaInvidious(videoId) }
        )

        val startIdx = attempt.coerceIn(0, resolvers.lastIndex)
        for (i in startIdx until resolvers.size) {
            val resolved = runCatching { resolvers[i]() }.getOrNull()
            if (resolved != null && resolved.url.startsWith("http")) {
                return@withContext resolved.copy(tierIndex = i)
            }
        }
        null
    }

    override suspend fun getRelatedTracks(track: Track, limit: Int): List<Track> = withContext(Dispatchers.IO) {
        val pipedBody = fetchPipedJson("/streams/${track.sourceTrackId}")
        if (pipedBody != null && pipedBody.startsWith("{")) {
            val related = JSONObject(pipedBody).optJSONArray("relatedStreams")
            val tracks = parsePipedItems(related).take(limit)
            if (tracks.isNotEmpty()) return@withContext tracks
        }
        searchTracks("${track.artist} ${track.title}", limit).filterNot { it.id == track.id }
    }

    // =========================================================================
    // DIRECT YOUTUBE INNERTUBE PLAYER RESOLVERS (ZERO API KEY, DEVICE IP)
    // =========================================================================

    private fun getOrFetchVisitorData(): String? {
        cachedVisitorData?.takeIf { it.isNotBlank() }?.let { return it }

        // 1. Primary: YouTube Music visitor_id endpoint
        runCatching {
            val payload = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB_REMIX")
                        put("clientVersion", "1.20250310.01.00")
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
            }
            val req = Request.Builder()
                .url("https://music.youtube.com/youtubei/v1/visitor_id?prettyPrint=false")
                .header("User-Agent", webRemixUa)
                .header("Origin", "https://music.youtube.com")
                .header("X-YouTube-Client-Name", "67")
                .header("X-YouTube-Client-Version", "1.20250310.01.00")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    if (body.startsWith("{")) {
                        val vd = JSONObject(body)
                            .optJSONObject("responseContext")
                            ?.optString("visitorData")
                            .orEmpty()
                        if (vd.isNotBlank()) {
                            cachedVisitorData = vd
                            return vd
                        }
                    }
                }
            }
        }

        // 2. Fallback: YouTube sw.js_data visitor cookie token
        runCatching {
            val req = Request.Builder()
                .url("https://www.youtube.com/sw.js_data")
                .header("User-Agent", webRemixUa)
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val txt = resp.body?.string().orEmpty()
                    val match = Regex(""""(Cgt[A-Za-z0-9_%-]{10,})"""").find(txt)
                    val vd = match?.groupValues?.getOrNull(1).orEmpty()
                    if (vd.isNotBlank()) {
                        cachedVisitorData = vd
                        return vd
                    }
                }
            }
        }
        return null
    }

    private fun resolveViaInnerTubeAndroidVr(videoId: String): ResolvedStream? {
        val visitorData = getOrFetchVisitorData()
        val payload = JSONObject().apply {
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "ANDROID_VR")
                    put("clientVersion", "1.61.48")
                    put("deviceMake", "Oculus")
                    put("deviceModel", "Quest 3")
                    put("osName", "Android")
                    put("osVersion", "12L")
                    put("androidSdkVersion", 32)
                    put("hl", "en")
                    put("gl", "US")
                    if (!visitorData.isNullOrBlank()) put("visitorData", visitorData)
                })
            })
        }
        return postInnerTubePlayer(
            payload = payload,
            userAgent = androidVrUa,
            clientNameHeader = "28",
            clientVersionHeader = "1.61.48",
            visitorData = visitorData,
            isWebClient = false,
            resolverName = "InnerTube ANDROID_VR"
        )
    }

    private fun resolveViaInnerTubeIos(videoId: String): ResolvedStream? {
        val visitorData = getOrFetchVisitorData()
        val payload = JSONObject().apply {
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "IOS")
                    put("clientVersion", "20.10.4")
                    put("deviceMake", "Apple")
                    put("deviceModel", "iPhone16,2")
                    put("osName", "iPhone")
                    put("osVersion", "18.3.2.22D82")
                    put("hl", "en")
                    put("gl", "US")
                    if (!visitorData.isNullOrBlank()) put("visitorData", visitorData)
                })
            })
        }
        return postInnerTubePlayer(
            payload = payload,
            userAgent = iosUa,
            clientNameHeader = "5",
            clientVersionHeader = "20.10.4",
            visitorData = visitorData,
            isWebClient = false,
            resolverName = "InnerTube IOS"
        )
    }

    private fun resolveViaInnerTubeAndroidTestsuite(videoId: String): ResolvedStream? {
        val visitorData = getOrFetchVisitorData()
        val payload = JSONObject().apply {
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "ANDROID_TESTSUITE")
                    put("clientVersion", "1.9")
                    put("androidSdkVersion", 30)
                    put("hl", "en")
                    put("gl", "US")
                    if (!visitorData.isNullOrBlank()) put("visitorData", visitorData)
                })
            })
        }
        return postInnerTubePlayer(
            payload = payload,
            userAgent = androidTestsuiteUa,
            clientNameHeader = "30",
            clientVersionHeader = "1.9",
            visitorData = visitorData,
            isWebClient = false,
            resolverName = "InnerTube ANDROID_TESTSUITE"
        )
    }

    private fun resolveViaInnerTubeTvEmbedded(videoId: String): ResolvedStream? {
        val visitorData = getOrFetchVisitorData()
        val payload = JSONObject().apply {
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "TVHTML5_SIMPLY_EMBEDDED_PLAYER")
                    put("clientVersion", "2.0")
                    put("hl", "en")
                    put("gl", "US")
                    if (!visitorData.isNullOrBlank()) put("visitorData", visitorData)
                })
                put("thirdParty", JSONObject().apply {
                    put("embedUrl", "https://www.youtube.com/")
                })
            })
        }
        return postInnerTubePlayer(
            payload = payload,
            userAgent = webRemixUa,
            clientNameHeader = "85",
            clientVersionHeader = "2.0",
            visitorData = visitorData,
            isWebClient = true,
            resolverName = "InnerTube TV_EMBEDDED"
        )
    }

    private fun postInnerTubePlayer(
        payload: JSONObject,
        userAgent: String,
        clientNameHeader: String,
        clientVersionHeader: String,
        visitorData: String?,
        isWebClient: Boolean,
        resolverName: String
    ): ResolvedStream? {
        val endpoints = listOf(
            "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            "https://music.youtube.com/youtubei/v1/player?prettyPrint=false"
        )
        for (endpoint in endpoints) {
            val resolved = runCatching {
                val reqBuilder = Request.Builder()
                    .url(endpoint)
                    .header("User-Agent", userAgent)
                    .header("X-YouTube-Client-Name", clientNameHeader)
                    .header("X-YouTube-Client-Version", clientVersionHeader)
                    .header("X-Goog-Api-Format-Version", "2")
                    .header("Accept", "application/json")
                    .post(payload.toString().toRequestBody(jsonMediaType))

                if (!visitorData.isNullOrBlank()) {
                    reqBuilder.header("X-Goog-Visitor-Id", visitorData)
                }
                if (isWebClient) {
                    reqBuilder.header("Origin", "https://www.youtube.com")
                    reqBuilder.header("Referer", "https://www.youtube.com/")
                }

                httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val body = resp.body?.string().orEmpty()
                    if (!body.startsWith("{")) return@use null
                    val root = JSONObject(body)
                    val status = root.optJSONObject("playabilityStatus")?.optString("status")
                    if (status != null && status != "OK" && status != "LIVE_STREAM_OFFLINE") {
                        return@use null
                    }
                    val streamingData = root.optJSONObject("streamingData") ?: return@use null

                    // 1. Prefer best direct non-DRM audio-only adaptive format (M4A itag 140/141 or Opus itag 251/250)
                    val adaptive = streamingData.optJSONArray("adaptiveFormats")
                    val bestAdaptive = pickBestDirectAudioFormat(adaptive)
                    if (bestAdaptive != null) {
                        return@use ResolvedStream(
                            url = bestAdaptive.first,
                            userAgent = userAgent,
                            mimeType = bestAdaptive.second,
                            resolverName = resolverName
                        )
                    }

                    // 2. Fallback to muxed formats (itag 18 MP4 — always playable by ExoPlayer)
                    val muxed = streamingData.optJSONArray("formats")
                    val bestMuxed = pickBestDirectMuxedFormat(muxed)
                    if (bestMuxed != null) {
                        return@use ResolvedStream(
                            url = bestMuxed.first,
                            userAgent = userAgent,
                            mimeType = bestMuxed.second,
                            resolverName = "$resolverName (Muxed)"
                        )
                    }

                    // 3. Fallback to HLS Manifest URL (native Media3 ExoPlayer HLS playback)
                    val hls = streamingData.optString("hlsManifestUrl")
                    if (hls.startsWith("http")) {
                        return@use ResolvedStream(
                            url = hls,
                            userAgent = userAgent,
                            mimeType = "application/x-mpegURL",
                            resolverName = "$resolverName (HLS)"
                        )
                    }
                    null
                }
            }.getOrNull()
            if (resolved != null) return resolved
        }
        return null
    }

    internal fun pickBestDirectAudioFormat(formats: JSONArray?): Pair<String, String>? {
        if (formats == null || formats.length() == 0) return null
        var bestUrl: String? = null
        var bestMime = "audio/mp4"
        var bestScore = -1

        for (i in 0 until formats.length()) {
            val obj = formats.optJSONObject(i) ?: continue
            if (obj.has("drmFamilies")) continue
            val mime = obj.optString("mimeType").lowercase()
            if (!mime.startsWith("audio/")) continue
            val url = obj.optString("url")
            if (!url.startsWith("http")) continue
            val bitrate = obj.optInt("bitrate", obj.optInt("averageBitrate", 0))
            // Prefer AAC/M4A (universal Android MediaCodec) then Opus/WebM
            val codecBonus = when {
                mime.contains("mp4") || mime.contains("mp4a") -> 200_000
                mime.contains("opus") || mime.contains("webm") -> 100_000
                else -> 0
            }
            val score = bitrate + codecBonus
            if (score > bestScore) {
                bestScore = score
                bestUrl = url
                bestMime = mime.substringBefore(";")
            }
        }
        return bestUrl?.let { it to bestMime }
    }

    private fun pickBestDirectMuxedFormat(formats: JSONArray?): Pair<String, String>? {
        if (formats == null) return null
        for (i in 0 until formats.length()) {
            val obj = formats.optJSONObject(i) ?: continue
            val url = obj.optString("url")
            if (url.startsWith("http")) {
                val mime = obj.optString("mimeType").substringBefore(";").ifBlank { "video/mp4" }
                return url to mime
            }
        }
        return null
    }

    // =========================================================================
    // DIRECT YOUTUBE MUSIC INNERTUBE CATALOG (SEARCH & CHARTS)
    // =========================================================================

    private fun searchInnerTubeTracks(query: String, limit: Int): List<Track> {
        val payload = JSONObject().apply {
            put("query", query)
            put("params", "EgWKAQIIAWoMEAMQBBAJEAoQBRAV") // Songs filter on YouTube Music
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", "1.20250310.01.00")
                    put("hl", "en")
                    put("gl", "US")
                })
            })
        }
        val body = postInnerTubeWebRemix("https://music.youtube.com/youtubei/v1/search?prettyPrint=false", payload)
            ?: return emptyList()
        return extractTracksFromInnerTubeJson(body).take(limit)
    }

    private fun fetchInnerTubeCharts(limit: Int): List<Track> {
        val payload = JSONObject().apply {
            put("browseId", "FEmusic_charts")
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", "1.20250310.01.00")
                    put("hl", "en")
                    put("gl", "US")
                })
            })
        }
        val body = postInnerTubeWebRemix("https://music.youtube.com/youtubei/v1/browse?prettyPrint=false", payload)
            ?: return emptyList()
        return extractTracksFromInnerTubeJson(body).take(limit)
    }

    private fun postInnerTubeWebRemix(url: String, payload: JSONObject): JSONObject? {
        return runCatching {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", webRemixUa)
                .header("Origin", "https://music.youtube.com")
                .header("Referer", "https://music.youtube.com/")
                .header("X-YouTube-Client-Name", "67")
                .header("X-YouTube-Client-Version", "1.20250310.01.00")
                .header("Accept", "application/json")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val txt = resp.body?.string().orEmpty()
                    if (txt.startsWith("{")) JSONObject(txt) else null
                } else null
            }
        }.getOrNull()
    }

    internal fun extractTracksFromInnerTubeJson(root: JSONObject): List<Track> {
        val out = mutableListOf<Track>()
        val seenIds = mutableSetOf<String>()

        fun walk(node: Any?) {
            when (node) {
                is JSONObject -> {
                    val itemRenderer = node.optJSONObject("musicResponsiveListItemRenderer")
                    if (itemRenderer != null) {
                        parseMusicListItemRenderer(itemRenderer)?.let { track ->
                            if (seenIds.add(track.sourceTrackId)) out.add(track)
                        }
                    }
                    val keys = node.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        if (k != "musicResponsiveListItemRenderer") {
                            walk(node.opt(k))
                        }
                    }
                }
                is JSONArray -> {
                    for (i in 0 until node.length()) {
                        walk(node.opt(i))
                    }
                }
            }
        }

        walk(root)
        return out
    }

    private fun parseMusicListItemRenderer(renderer: JSONObject): Track? {
        var videoId = renderer.optJSONObject("playlistItemData")?.optString("videoId").orEmpty()
        if (videoId.isBlank()) {
            videoId = renderer.optJSONObject("overlay")
                ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("musicPlayButtonRenderer")
                ?.optJSONObject("playNavigationEndpoint")
                ?.optJSONObject("watchEndpoint")
                ?.optString("videoId").orEmpty()
        }
        if (videoId.length != 11) return null

        val flexCols = renderer.optJSONArray("flexColumns") ?: return null
        val title = flexCols.optJSONObject(0)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
            ?.optJSONArray("runs")
            ?.optJSONObject(0)
            ?.optString("text")
            .orEmpty()
            .trim()
        if (title.isBlank()) return null

        val metaRuns = flexCols.optJSONObject(1)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
            ?.optJSONArray("runs")

        var artist = "YouTube Music"
        var album = "YouTube Music"
        var durationMs = 0L

        if (metaRuns != null) {
            val parts = mutableListOf<String>()
            for (i in 0 until metaRuns.length()) {
                val txt = metaRuns.optJSONObject(i)?.optString("text").orEmpty().trim()
                if (txt.isBlank() || txt == "•" || txt == "·" || txt.equals("Song", ignoreCase = true) || txt.equals("Video", ignoreCase = true)) {
                    continue
                }
                val parsedDur = parseDurationTextToMs(txt)
                if (parsedDur > 0L) {
                    durationMs = parsedDur
                } else if (!txt.contains("plays", ignoreCase = true) && !txt.contains("views", ignoreCase = true)) {
                    parts.add(txt)
                }
            }
            if (parts.isNotEmpty()) artist = parts[0]
            if (parts.size >= 2) album = parts[1]
        }

        val thumbs = renderer.optJSONObject("thumbnail")
            ?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails")
        val thumbUrl = if (thumbs != null && thumbs.length() > 0) {
            thumbs.optJSONObject(thumbs.length() - 1)?.optString("url")
        } else null

        return Track(
            id = "ytm_$videoId",
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            artworkUrl = thumbUrl?.takeIf { it.startsWith("http") }
                ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
            streamUrl = null,
            source = MusicSource.YOUTUBE_MUSIC,
            sourceTrackId = videoId,
            isDownloadable = false
        )
    }

    internal fun parseDurationTextToMs(text: String): Long {
        if (!Regex("""^\d{1,2}:\d{2}(:\d{2})?$""").matches(text)) return 0L
        val parts = text.split(":").mapNotNull { it.toLongOrNull() }
        return when (parts.size) {
            2 -> (parts[0] * 60L + parts[1]) * 1000L
            3 -> (parts[0] * 3600L + parts[1] * 60L + parts[2]) * 1000L
            else -> 0L
        }
    }

    // =========================================================================
    // PIPED & INVIDIOUS FEDERATION FALLBACKS
    // =========================================================================

    private fun refreshPublicInstancesIfNeeded() {
        if (dynamicPipedInstances.isEmpty()) {
            runCatching {
                val req = Request.Builder().url("https://piped-instances.kavin.rocks/").build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val arr = JSONArray(resp.body?.string().orEmpty())
                        dynamicPipedInstances = buildList {
                            for (i in 0 until minOf(arr.length(), 10)) {
                                val apiUrl = arr.optJSONObject(i)?.optString("api_url")?.trimEnd('/')
                                if (!apiUrl.isNullOrBlank() && apiUrl.startsWith("https://")) add(apiUrl)
                            }
                        }
                    }
                }
            }
        }
        if (dynamicInvidiousInstances.isEmpty()) {
            runCatching {
                val req = Request.Builder().url("https://api.invidious.io/instances.json?sort_by=health").build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val arr = JSONArray(resp.body?.string().orEmpty())
                        dynamicInvidiousInstances = buildList {
                            for (i in 0 until minOf(arr.length(), 10)) {
                                val pair = arr.optJSONArray(i) ?: continue
                                val meta = pair.optJSONObject(1) ?: continue
                                if (meta.optBoolean("api", false)) {
                                    val uri = meta.optString("uri").trimEnd('/')
                                    if (uri.startsWith("https://")) add(uri)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun resolveViaPiped(videoId: String): ResolvedStream? {
        val body = fetchPipedJson("/streams/$videoId") ?: run {
            refreshPublicInstancesIfNeeded()
            fetchPipedJson("/streams/$videoId")
        } ?: return null

        if (!body.startsWith("{")) return null
        val root = JSONObject(body)
        val audioStreams = root.optJSONArray("audioStreams")
        val bestUrl = selectBestAudioStream(audioStreams, urlKey = "url", bitrateKey = "bitrate")
        if (bestUrl != null) {
            return ResolvedStream(url = bestUrl, resolverName = "Piped Gateway")
        }
        val hls = root.optString("hls")
        if (hls.startsWith("http")) {
            return ResolvedStream(url = hls, mimeType = "application/x-mpegURL", resolverName = "Piped HLS")
        }
        return null
    }

    private suspend fun resolveViaInvidious(videoId: String): ResolvedStream? {
        val inv = fetchInvidiousJson("/api/v1/videos/$videoId") ?: run {
            refreshPublicInstancesIfNeeded()
            fetchInvidiousJson("/api/v1/videos/$videoId")
        } ?: return null

        val (baseUrl, body) = inv
        if (!body.startsWith("{")) return null
        val root = JSONObject(body)
        val adaptive = root.optJSONArray("adaptiveFormats")
        val bestUrl = selectBestInvidiousAudio(adaptive, baseUrl)
        if (bestUrl != null) {
            return ResolvedStream(url = bestUrl, resolverName = "Invidious Gateway")
        }
        // Fallback to Invidious local audio proxy endpoint (itag 140 = M4A audio)
        return ResolvedStream(
            url = "$baseUrl/latest_version?id=$videoId&itag=140&local=true",
            mimeType = "audio/mp4",
            resolverName = "Invidious Proxy"
        )
    }

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

    private suspend fun fetchInvidiousJson(path: String): Pair<String, String>? = withContext(Dispatchers.IO) {
        for (base in invidiousCandidates()) {
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
            if (body != null) return@withContext base to body
        }
        null
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

    private fun selectBestInvidiousAudio(formats: JSONArray?, baseUrl: String): String? {
        if (formats == null) return null
        var bestUrl: String? = null
        var bestBitrate = -1
        for (i in 0 until formats.length()) {
            val obj = formats.optJSONObject(i) ?: continue
            val type = obj.optString("type").lowercase()
            if (!type.startsWith("audio/")) continue
            val rawUrl = obj.optString("url")
            val resolvedUrl = when {
                rawUrl.startsWith("http") -> rawUrl
                rawUrl.startsWith("/") -> "$baseUrl$rawUrl"
                else -> continue
            }
            val bitrate = obj.optString("bitrate").toIntOrNull() ?: 0
            if (bitrate > bestBitrate) {
                bestBitrate = bitrate
                bestUrl = resolvedUrl
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
                        streamUrl = null,
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
