package com.example.data.providers

import com.example.domain.model.AlbumItem
import com.example.domain.model.ArtistItem
import com.example.domain.model.MusicSource
import com.example.domain.model.PlaylistSummary
import com.example.domain.model.ProviderHealthState
import com.example.domain.model.ProviderStatus
import com.example.domain.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Fast, Free YouTube Music Catalog & Direct Audio Stream Provider (zero paid API keys).
 *
 * Engineered for low-latency playback with parallel racing & pre-flight reachability probes:
 * - Catalog: Direct YouTube Music InnerTube WEB_REMIX API first, with Piped/Invidious fallback.
 * - Audio Stream Resolution (4 fast parallel tiers + 15-minute stream cache):
 *   Tier 0: Parallel race of InnerTube ANDROID_VR (direct M4A/Opus) + InnerTube IOS (native HLS + M4A)
 *   Tier 1: Parallel race of InnerTube IOS_MUSIC + ANDROID_MUSIC + TV_EMBEDDED
 *   Tier 2: Parallel race of top live Piped & Invidious instances (4.5s timeout cap — never hangs!)
 *   Tier 3: Parallel race of verified Invidious local audio proxies (/latest_version?local=true)
 *
 * Every candidate URL is pre-flight probed (Range: bytes=0-0) with its exact client User-Agent
 * before being handed to ExoPlayer, eliminating slow multi-tier player errors.
 *
 * Strictly streaming-only (isDownloadable = false).
 */
class YouTubeMusicProvider(
    private val httpClient: OkHttpClient,
    private val customInstanceProvider: () -> String
) : MusicSourceProvider {

    override val source: MusicSource = MusicSource.YOUTUBE_MUSIC

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    // Tight-timeout client dedicated to stream racing & probes so dead mirrors never stall playback
    private val fastClient: OkHttpClient = httpClient.newBuilder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(4, TimeUnit.SECONDS)
        .build()

    private val probeClient: OkHttpClient = httpClient.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .build()

    private val webRemixUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
    private val androidVrUa =
        "com.google.android.apps.youtube.vr.exo/1.61.48 (Linux; U; Android 12L; Quest 3 Build/SQ3A.220605.009.A1) gzip"
    private val iosUa =
        "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)"
    private val iosMusicUa =
        "com.google.ios.youtubemusic/8.05 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)"
    private val androidMusicUa =
        "com.google.android.apps.youtube.music/7.29.52 (Linux; U; Android 14) gzip"

    private val defaultPipedInstances = listOf(
        "https://pipedapi.kavin.rocks",
        "https://pipedapi.adminforge.de",
        "https://api.piped.private.coffee",
        "https://pipedapi.r4fo.com"
    )

    private val defaultInvidiousInstances = listOf(
        "https://inv.nadeko.net",
        "https://yewtu.be",
        "https://invidious.nerdvpn.de",
        "https://iv.datura.network"
    )

    private data class CachedStream(
        val stream: ResolvedStream,
        val expiresAtMs: Long
    )

    private val streamCache = ConcurrentHashMap<String, CachedStream>()

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
    }.distinct().take(6)

    private fun invidiousCandidates(): List<String> = buildList {
        addAll(dynamicInvidiousInstances)
        addAll(defaultInvidiousInstances)
    }.distinct().take(6)

    override suspend fun checkHealth(): ProviderStatus = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val innerTubeOk = searchInnerTubeTracks("music", 2).isNotEmpty()
        val latency = System.currentTimeMillis() - start
        if (innerTubeOk) {
            return@withContext ProviderStatus(
                source = source,
                enabled = true,
                priority = 0,
                health = ProviderHealthState.ONLINE,
                message = "Direct YouTube Music Fast InnerTube Ready",
                latencyMs = latency
            )
        }
        val pipedOk = fetchPipedJsonFast("/trending?region=ID") != null
        val total = System.currentTimeMillis() - start
        if (pipedOk) {
            ProviderStatus(
                source = source,
                enabled = true,
                priority = 0,
                health = ProviderHealthState.DEGRADED,
                message = "Connected via Piped Gateway (${cachedWorkingPiped ?: "auto"})",
                latencyMs = total
            )
        } else {
            ProviderStatus(
                source = source,
                enabled = true,
                priority = 0,
                health = ProviderHealthState.OFFLINE,
                message = "YouTube Music unreachable — periksa koneksi internet",
                latencyMs = total
            )
        }
    }

    override suspend fun getTrending(limit: Int): List<Track> = withContext(Dispatchers.IO) {
        val directCharts = fetchInnerTubeCharts(limit)
        if (directCharts.isNotEmpty()) return@withContext directCharts

        val directSearch = searchInnerTubeTracks("top hits official audio", limit)
        if (directSearch.isNotEmpty()) return@withContext directSearch

        val piped = fetchPipedJsonFast("/trending?region=ID") ?: fetchPipedJsonFast("/trending?region=US")
        if (piped != null && piped.startsWith("[")) {
            val list = parsePipedItems(JSONArray(piped)).take(limit)
            if (list.isNotEmpty()) return@withContext list
        }

        val inv = fetchInvidiousJsonFast("/api/v1/trending?type=Music&region=US")
        if (inv != null && inv.second.startsWith("[")) {
            return@withContext parseInvidiousItems(JSONArray(inv.second)).take(limit)
        }
        emptyList()
    }

    override suspend fun getNewReleases(limit: Int): List<Track> =
        searchTracks("new music release official audio", limit)

    override suspend fun searchTracks(query: String, limit: Int): List<Track> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()

        val direct = searchInnerTubeTracks(query, limit)
        if (direct.isNotEmpty()) return@withContext direct

        val encoded = URLEncoder.encode(query, "UTF-8")
        val piped = fetchPipedJsonFast("/search?q=$encoded&filter=music_songs")
        if (piped != null && piped.startsWith("{")) {
            val tracks = parsePipedItems(JSONObject(piped).optJSONArray("items")).take(limit)
            if (tracks.isNotEmpty()) return@withContext tracks
        }

        val inv = fetchInvidiousJsonFast("/api/v1/search?q=$encoded&type=video")
        if (inv != null && inv.second.startsWith("[")) {
            return@withContext parseInvidiousItems(JSONArray(inv.second)).take(limit)
        }
        emptyList()
    }

    override suspend fun searchArtists(query: String, limit: Int): List<ArtistItem> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
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
        searchInnerTubeTracks(query, limit * 2)
            .filter { it.album.isNotBlank() && it.album != "YouTube Music" }
            .distinctBy { it.album.lowercase() }
            .take(limit)
            .map { t ->
                AlbumItem(
                    id = "ytm_album_${t.album.hashCode()}",
                    title = t.album,
                    artist = t.artist,
                    artworkUrl = t.artworkUrl,
                    source = MusicSource.YOUTUBE_MUSIC,
                    trackCount = 1
                )
            }
    }

    override suspend fun searchPlaylists(query: String, limit: Int): List<PlaylistSummary> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val encoded = URLEncoder.encode(query, "UTF-8")
        val piped = fetchPipedJsonFast("/search?q=$encoded&filter=music_playlists") ?: return@withContext emptyList()
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
     * Fast parallel stream resolver with pre-flight verification & 15-minute cache.
     * Never blocks sequentially on dead mirrors.
     */
    override suspend fun resolveStream(track: Track, attempt: Int): ResolvedStream? = withContext(Dispatchers.IO) {
        val videoId = track.sourceTrackId.takeIf { it.isNotBlank() }
            ?: track.id.removePrefix("ytm_")
        if (videoId.isBlank()) return@withContext null

        val now = System.currentTimeMillis()
        if (attempt == 0) {
            val cached = streamCache[videoId]
            if (cached != null && cached.expiresAtMs > now) {
                return@withContext cached.stream
            }
        } else {
            // Invalidate stale cache if ExoPlayer reported an error and triggered a retry
            streamCache.remove(videoId)
        }

        val tiers: List<suspend () -> ResolvedStream?> = listOf(
            // Tier 0: Race ANDROID_VR (direct M4A/Opus) + IOS (HLS + direct M4A) concurrently (max 5.5s)
            { raceTier0PrimaryInnerTube(videoId) },
            // Tier 1: Race IOS_MUSIC + ANDROID_MUSIC + TV_EMBEDDED concurrently (max 5s)
            { raceTier1SecondaryInnerTube(videoId) },
            // Tier 2: Race top Piped & Invidious mirrors concurrently (max 5s)
            { raceTier2FederationMirrors(videoId) },
            // Tier 3: Race verified Invidious local audio proxies concurrently (max 4.5s)
            { raceTier3InvidiousProxy(videoId) }
        )

        val startIdx = attempt.coerceIn(0, tiers.lastIndex)
        for (tierIdx in startIdx until tiers.size) {
            val resolved = runCatching { tiers[tierIdx]() }.getOrNull()
            if (resolved != null && resolved.url.startsWith("http")) {
                val tagged = resolved.copy(tierIndex = tierIdx)
                streamCache[videoId] = CachedStream(tagged, now + 15 * 60_000L)
                return@withContext tagged
            }
        }
        null
    }

    override suspend fun getRelatedTracks(track: Track, limit: Int): List<Track> = withContext(Dispatchers.IO) {
        val videoId = track.sourceTrackId.takeIf { it.length == 11 }
            ?: track.id.removePrefix("ytm_").takeIf { it.length == 11 }
        val seedNormTitle = TrackMetadataNormalizer.normalizeTitle(track.title)

        val collected = mutableListOf<Track>()
        val seenIds = mutableSetOf(track.id)
        val seenNormTitles = mutableSetOf<String>().apply {
            if (seedNormTitle.isNotBlank()) add(seedNormTitle)
        }

        fun addUnique(candidates: List<Track>) {
            for (cand in candidates) {
                if (collected.size >= limit) break
                val norm = TrackMetadataNormalizer.normalizeTitle(cand.title)
                if (cand.id in seenIds) continue
                if (norm.isNotBlank() && norm in seenNormTitles) continue
                seenIds.add(cand.id)
                if (norm.isNotBlank()) seenNormTitles.add(norm)
                collected.add(cand)
            }
        }

        // 1. Primary: Official YouTube Music "Up Next" Radio mix (RDAMVM<videoId>)
        if (videoId != null) {
            val radioMix = fetchInnerTubeAutoQueue(videoId, limit + 5)
            addUnique(radioMix)
        }

        // 2. Fallback: Related artist/mix tracks via InnerTube search
        if (collected.size < limit.coerceAtMost(8)) {
            val query = if (track.artist.isNotBlank() && !track.artist.equals("YouTube Music", ignoreCase = true)) {
                "${track.artist} songs"
            } else {
                "${track.title} mix"
            }
            addUnique(searchTracks(query, limit + 5))
        }

        // 3. Final fallback: Trending charts so auto-queue is never empty
        if (collected.size < 4) {
            addUnique(getTrending(limit))
        }

        collected.take(limit)
    }

    private fun fetchInnerTubeAutoQueue(videoId: String, limit: Int): List<Track> {
        val payload = JSONObject().apply {
            put("videoId", videoId)
            put("playlistId", "RDAMVM$videoId")
            put("isAudioOnly", true)
            put("enablePersistentPlaylistPanel", true)
            put("tunerSettingValue", "AUTOMIX_SETTING_NORMAL")
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", "1.20250310.01.00")
                    put("hl", "en")
                    put("gl", "ID")
                })
            })
        }
        val body = postInnerTubeWebRemix(
            "https://music.youtube.com/youtubei/v1/next?prettyPrint=false",
            payload
        ) ?: return emptyList()
        return extractPlaylistPanelTracks(body).take(limit)
    }

    internal fun extractPlaylistPanelTracks(root: JSONObject): List<Track> {
        val out = mutableListOf<Track>()
        val seenIds = mutableSetOf<String>()

        fun walk(node: Any?) {
            when (node) {
                is JSONObject -> {
                    val panelRenderer = node.optJSONObject("playlistPanelVideoRenderer")
                    if (panelRenderer != null) {
                        parsePlaylistPanelVideoRenderer(panelRenderer)?.let { t ->
                            if (seenIds.add(t.sourceTrackId)) out.add(t)
                        }
                    }
                    val keys = node.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        if (k != "playlistPanelVideoRenderer") {
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

    private fun parsePlaylistPanelVideoRenderer(renderer: JSONObject): Track? {
        val videoId = renderer.optString("videoId").trim()
        if (videoId.length != 11) return null

        val title = renderer.optJSONObject("title")
            ?.optJSONArray("runs")
            ?.optJSONObject(0)
            ?.optString("text")
            .orEmpty()
            .trim()
        if (title.isBlank()) return null

        val bylineRuns = renderer.optJSONObject("longBylineText")?.optJSONArray("runs")
            ?: renderer.optJSONObject("shortBylineText")?.optJSONArray("runs")

        var artist = "YouTube Music"
        var album = "YouTube Music"
        if (bylineRuns != null) {
            val parts = mutableListOf<String>()
            for (i in 0 until bylineRuns.length()) {
                val txt = bylineRuns.optJSONObject(i)?.optString("text").orEmpty().trim()
                if (txt.isBlank() || txt == "•" || txt == "·" || txt.equals("Song", ignoreCase = true) || txt.equals("Video", ignoreCase = true)) {
                    continue
                }
                if (!Regex("""^\d{4}$""").matches(txt) && !txt.contains("views", ignoreCase = true)) {
                    parts.add(txt)
                }
            }
            if (parts.isNotEmpty()) artist = parts[0]
            if (parts.size >= 2) album = parts[1]
        }

        val lengthText = renderer.optJSONObject("lengthText")
            ?.optJSONArray("runs")
            ?.optJSONObject(0)
            ?.optString("text")
            .orEmpty()
            .trim()
        val durationMs = parseDurationTextToMs(lengthText)

        val thumbs = renderer.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
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

    // =========================================================================
    // PARALLEL TIER RACING ENGINE (SUB-2s TYPICAL RESOLUTION, ZERO HANGS)
    // =========================================================================

    private suspend fun raceFirstValid(
        timeoutMs: Long,
        tasks: List<suspend () -> ResolvedStream?>
    ): ResolvedStream? = withTimeoutOrNull(timeoutMs) {
        coroutineScope {
            val winner = Channel<ResolvedStream>(Channel.CONFLATED)
            val jobs = tasks.map { block ->
                launch(Dispatchers.IO) {
                    val res = runCatching { block() }.getOrNull()
                    if (res != null && res.url.startsWith("http")) {
                        winner.trySend(res)
                    }
                }
            }
            launch {
                jobs.forEach { it.join() }
                winner.close()
            }
            val result = winner.receiveCatching().getOrNull()
            jobs.forEach { it.cancel() }
            result
        }
    }

    private suspend fun raceTier0PrimaryInnerTube(videoId: String): ResolvedStream? {
        val vd = getOrFetchVisitorDataFast()
        return raceFirstValid(
            timeoutMs = 5_500L,
            tasks = listOf(
                { resolveViaInnerTubeIos(videoId, vd, preferHls = true) },
                { resolveViaInnerTubeAndroidVr(videoId, vd) },
                { resolveViaInnerTubeIos(videoId, vd, preferHls = false) }
            )
        )
    }

    private suspend fun raceTier1SecondaryInnerTube(videoId: String): ResolvedStream? {
        val vd = getOrFetchVisitorDataFast()
        return raceFirstValid(
            timeoutMs = 5_000L,
            tasks = listOf(
                { resolveViaInnerTubeIosMusic(videoId, vd) },
                { resolveViaInnerTubeAndroidMusic(videoId, vd) },
                { resolveViaInnerTubeTvEmbedded(videoId, vd) }
            )
        )
    }

    private suspend fun raceTier2FederationMirrors(videoId: String): ResolvedStream? {
        // Refresh directory quickly in background if empty
        if (dynamicPipedInstances.isEmpty() && dynamicInvidiousInstances.isEmpty()) {
            withTimeoutOrNull(2_000L) { refreshPublicInstancesFast() }
        }
        val pipedTasks = pipedCandidates().take(4).map<String, suspend () -> ResolvedStream?> { base ->
            { resolveSinglePipedInstance(base, videoId) }
        }
        val invTasks = invidiousCandidates().take(4).map<String, suspend () -> ResolvedStream?> { base ->
            { resolveSingleInvidiousInstance(base, videoId) }
        }
        return raceFirstValid(timeoutMs = 5_000L, tasks = pipedTasks + invTasks)
    }

    private suspend fun raceTier3InvidiousProxy(videoId: String): ResolvedStream? {
        val tasks = invidiousCandidates().take(4).map<String, suspend () -> ResolvedStream?> { base ->
            {
                val proxyUrl = "$base/latest_version?id=$videoId&itag=140&local=true"
                if (isStreamReachable(proxyUrl, webRemixUa)) {
                    ResolvedStream(
                        url = proxyUrl,
                        userAgent = webRemixUa,
                        mimeType = "audio/mp4",
                        resolverName = "Invidious Proxy"
                    )
                } else null
            }
        }
        return raceFirstValid(timeoutMs = 4_500L, tasks = tasks)
    }

    /**
     * Lightweight pre-flight check (Range: bytes=0-0) confirming googlevideo/mirror does NOT return 403/404.
     * Prevents handing broken URLs to ExoPlayer and wasting seconds cycling errors.
     */
    private fun isStreamReachable(url: String, userAgent: String): Boolean {
        return runCatching {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Range", "bytes=0-0")
                .get()
                .build()
            probeClient.newCall(req).execute().use { resp ->
                resp.code in 200..299
            }
        }.getOrDefault(false)
    }

    // =========================================================================
    // DIRECT YOUTUBE INNERTUBE PLAYER RESOLVERS (ZERO API KEY, DEVICE IP)
    // =========================================================================

    private fun getOrFetchVisitorDataFast(): String? {
        cachedVisitorData?.takeIf { it.isNotBlank() }?.let { return it }
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
            fastClient.newCall(req).execute().use { resp ->
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
        return cachedVisitorData
    }

    private fun resolveViaInnerTubeAndroidVr(videoId: String, visitorData: String?): ResolvedStream? {
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
        return postInnerTubePlayerFast(
            endpoint = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            payload = payload,
            userAgent = androidVrUa,
            clientNameHeader = "28",
            clientVersionHeader = "1.61.48",
            visitorData = visitorData,
            isWebClient = false,
            preferHls = false,
            resolverName = "InnerTube ANDROID_VR"
        )
    }

    private fun resolveViaInnerTubeIos(
        videoId: String,
        visitorData: String?,
        preferHls: Boolean
    ): ResolvedStream? {
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
        return postInnerTubePlayerFast(
            endpoint = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            payload = payload,
            userAgent = iosUa,
            clientNameHeader = "5",
            clientVersionHeader = "20.10.4",
            visitorData = visitorData,
            isWebClient = false,
            preferHls = preferHls,
            resolverName = if (preferHls) "InnerTube IOS HLS" else "InnerTube IOS"
        )
    }

    private fun resolveViaInnerTubeIosMusic(videoId: String, visitorData: String?): ResolvedStream? {
        val payload = JSONObject().apply {
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "IOS_MUSIC")
                    put("clientVersion", "8.05")
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
        return postInnerTubePlayerFast(
            endpoint = "https://music.youtube.com/youtubei/v1/player?prettyPrint=false",
            payload = payload,
            userAgent = iosMusicUa,
            clientNameHeader = "26",
            clientVersionHeader = "8.05",
            visitorData = visitorData,
            isWebClient = false,
            preferHls = true,
            resolverName = "InnerTube IOS_MUSIC"
        )
    }

    private fun resolveViaInnerTubeAndroidMusic(videoId: String, visitorData: String?): ResolvedStream? {
        val payload = JSONObject().apply {
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "ANDROID_MUSIC")
                    put("clientVersion", "7.29.52")
                    put("androidSdkVersion", 34)
                    put("hl", "en")
                    put("gl", "US")
                    if (!visitorData.isNullOrBlank()) put("visitorData", visitorData)
                })
            })
        }
        return postInnerTubePlayerFast(
            endpoint = "https://music.youtube.com/youtubei/v1/player?prettyPrint=false",
            payload = payload,
            userAgent = androidMusicUa,
            clientNameHeader = "21",
            clientVersionHeader = "7.29.52",
            visitorData = visitorData,
            isWebClient = false,
            preferHls = false,
            resolverName = "InnerTube ANDROID_MUSIC"
        )
    }

    private fun resolveViaInnerTubeTvEmbedded(videoId: String, visitorData: String?): ResolvedStream? {
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
        return postInnerTubePlayerFast(
            endpoint = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            payload = payload,
            userAgent = webRemixUa,
            clientNameHeader = "85",
            clientVersionHeader = "2.0",
            visitorData = visitorData,
            isWebClient = true,
            preferHls = false,
            resolverName = "InnerTube TV_EMBEDDED"
        )
    }

    private fun postInnerTubePlayerFast(
        endpoint: String,
        payload: JSONObject,
        userAgent: String,
        clientNameHeader: String,
        clientVersionHeader: String,
        visitorData: String?,
        isWebClient: Boolean,
        preferHls: Boolean,
        resolverName: String
    ): ResolvedStream? {
        return runCatching {
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

            fastClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string().orEmpty()
                if (!body.startsWith("{")) return@use null
                val root = JSONObject(body)
                val status = root.optJSONObject("playabilityStatus")?.optString("status")
                if (status != null && status != "OK" && status != "LIVE_STREAM_OFFLINE") {
                    return@use null
                }
                val streamingData = root.optJSONObject("streamingData") ?: return@use null

                // Build ordered candidate list: HLS first if preferHls, else direct M4A/Opus -> Muxed -> HLS
                val candidates = mutableListOf<ResolvedStream>()
                val hls = streamingData.optString("hlsManifestUrl")
                if (preferHls && hls.startsWith("http")) {
                    candidates.add(
                        ResolvedStream(
                            url = hls,
                            userAgent = userAgent,
                            mimeType = "application/x-mpegURL",
                            resolverName = "$resolverName (HLS)"
                        )
                    )
                }

                pickBestDirectAudioFormat(streamingData.optJSONArray("adaptiveFormats"))?.let { (url, mime) ->
                    candidates.add(
                        ResolvedStream(
                            url = url,
                            userAgent = userAgent,
                            mimeType = mime,
                            resolverName = resolverName
                        )
                    )
                }

                pickBestDirectMuxedFormat(streamingData.optJSONArray("formats"))?.let { (url, mime) ->
                    candidates.add(
                        ResolvedStream(
                            url = url,
                            userAgent = userAgent,
                            mimeType = mime,
                            resolverName = "$resolverName (Muxed)"
                        )
                    )
                }

                if (!preferHls && hls.startsWith("http")) {
                    candidates.add(
                        ResolvedStream(
                            url = hls,
                            userAgent = userAgent,
                            mimeType = "application/x-mpegURL",
                            resolverName = "$resolverName (HLS)"
                        )
                    )
                }

                // Probe candidate URLs with exact UA before returning so ExoPlayer plays on first try!
                for (cand in candidates) {
                    if (isStreamReachable(cand.url, userAgent)) {
                        return@use cand
                    }
                }
                null
            }
        }.getOrNull()
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
            if (obj.has("drmFamilies")) continue
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
            put("params", "EgWKAQIIAWoMEAMQBBAJEAoQBRAV")
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
            fastClient.newCall(req).execute().use { resp ->
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
    // FAST PARALLEL PIPED & INVIDIOUS FEDERATION FALLBACKS
    // =========================================================================

    private suspend fun refreshPublicInstancesFast() = coroutineScope {
        val pipedJob = async(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder().url("https://piped-instances.kavin.rocks/").build()
                probeClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val arr = JSONArray(resp.body?.string().orEmpty())
                        dynamicPipedInstances = buildList {
                            for (i in 0 until minOf(arr.length(), 6)) {
                                val apiUrl = arr.optJSONObject(i)?.optString("api_url")?.trimEnd('/')
                                if (!apiUrl.isNullOrBlank() && apiUrl.startsWith("https://")) add(apiUrl)
                            }
                        }
                    }
                }
            }
        }
        val invJob = async(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder().url("https://api.invidious.io/instances.json?sort_by=health").build()
                probeClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val arr = JSONArray(resp.body?.string().orEmpty())
                        dynamicInvidiousInstances = buildList {
                            for (i in 0 until minOf(arr.length(), 6)) {
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
        awaitAll(pipedJob, invJob)
    }

    private fun resolveSinglePipedInstance(base: String, videoId: String): ResolvedStream? {
        return runCatching {
            val req = Request.Builder()
                .url("$base/streams/$videoId")
                .header("Accept", "application/json")
                .build()
            fastClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string().orEmpty()
                if (!body.startsWith("{")) return@use null
                cachedWorkingPiped = base
                val root = JSONObject(body)
                val bestUrl = selectBestAudioStream(root.optJSONArray("audioStreams"), "url", "bitrate")
                if (bestUrl != null && isStreamReachable(bestUrl, webRemixUa)) {
                    return@use ResolvedStream(url = bestUrl, userAgent = webRemixUa, resolverName = "Piped Gateway")
                }
                val hls = root.optString("hls")
                if (hls.startsWith("http") && isStreamReachable(hls, webRemixUa)) {
                    return@use ResolvedStream(
                        url = hls,
                        userAgent = webRemixUa,
                        mimeType = "application/x-mpegURL",
                        resolverName = "Piped HLS"
                    )
                }
                null
            }
        }.getOrNull()
    }

    private fun resolveSingleInvidiousInstance(base: String, videoId: String): ResolvedStream? {
        return runCatching {
            val req = Request.Builder()
                .url("$base/api/v1/videos/$videoId")
                .header("Accept", "application/json")
                .build()
            fastClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string().orEmpty()
                if (!body.startsWith("{")) return@use null
                val root = JSONObject(body)
                val bestUrl = selectBestInvidiousAudio(root.optJSONArray("adaptiveFormats"), base)
                if (bestUrl != null && isStreamReachable(bestUrl, webRemixUa)) {
                    return@use ResolvedStream(url = bestUrl, userAgent = webRemixUa, resolverName = "Invidious Gateway")
                }
                null
            }
        }.getOrNull()
    }

    private suspend fun fetchPipedJsonFast(path: String): String? = withContext(Dispatchers.IO) {
        for (base in pipedCandidates().take(3)) {
            val body = runCatching {
                val req = Request.Builder().url("$base$path").header("Accept", "application/json").build()
                fastClient.newCall(req).execute().use { resp ->
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

    private suspend fun fetchInvidiousJsonFast(path: String): Pair<String, String>? = withContext(Dispatchers.IO) {
        for (base in invidiousCandidates().take(3)) {
            val body = runCatching {
                val req = Request.Builder().url("$base$path").header("Accept", "application/json").build()
                fastClient.newCall(req).execute().use { resp ->
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
