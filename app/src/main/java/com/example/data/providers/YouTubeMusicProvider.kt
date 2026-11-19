package com.example.data.providers

import android.util.Log
import com.example.data.providers.youtube.MetrolistAudioQuality
import com.example.data.providers.youtube.MetrolistPlaybackData
import com.example.data.providers.youtube.YTPlayerUtils
import com.example.data.providers.youtube.YouTubeCipherSolver
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
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Fast, Free YouTube Music Catalog & Direct Audio Stream Provider (zero paid API keys).
 *
 * Engineered for low-latency playback with parallel racing & pre-flight reachability probes:
 * - Catalog: Direct YouTube Music InnerTube WEB_REMIX API first, with Piped/Invidious fallback.
 * - Audio Stream Resolution (Metrolist / Zemer Cipher 6-tier pipeline + 15-minute stream cache):
 *   Tier 0: Direct InnerTube ANDROID_VR (1.65.10 & 1.71.26) + VISIONOS (zero-PO-token, instant)
 *   Tier 1: Metrolist MAIN_CLIENT (WEB_REMIX) + TVHTML5_SIMPLY_EMBEDDED_PLAYER + TVHTML5
 *           with live `signatureTimestamp` (`sts`), `cpn`, `signatureCipher`, and `n`-param solver
 *   Tier 2: Native Apple HLS via InnerTube IOS & IOS_MUSIC
 *   Tier 3: Secondary Android InnerTube clients (ANDROID_CREATOR, ANDROID_MUSIC, ANDROID_TESTSUITE)
 *   Tier 4: Expanded live Piped API gateways
 *   Tier 5: Expanded live Invidious API gateways (?local=true) & verified direct audio proxy
 *
 * Strictly streaming-only (isDownloadable = false).
 */
class YouTubeMusicProvider(
    private val httpClient: OkHttpClient,
    private val customInstanceProvider: () -> String,
    private val cipherSolver: YouTubeCipherSolver = YouTubeCipherSolver(null, httpClient),
    private val ytPlayerUtils: YTPlayerUtils = YTPlayerUtils(httpClient, cipherSolver)
) : MusicSourceProvider {

    override val source: MusicSource = MusicSource.YOUTUBE_MUSIC

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    // Balanced mobile-friendly timeout client for InnerTube & mirror racing
    private val fastClient: OkHttpClient = httpClient.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val probeClient: OkHttpClient = httpClient.newBuilder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(6, TimeUnit.SECONDS)
        .build()

    private val deviceRegion: String
        get() = Locale.getDefault().country.takeIf { it.length == 2 }?.uppercase(Locale.US) ?: "ID"

    private val webRemixUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
    private val visionOsUa =
        "com.google.ios.youtube/1.02 (RealityDevice17,1; U; CPU visionOS 26_5 like Mac OS X;)"
    private val androidVrUa =
        "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"
    private val androidVrAltUa =
        "com.google.android.apps.youtube.vr.oculus/1.71.26 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"
    private val iosUa =
        "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)"
    private val iosMusicUa =
        "com.google.ios.youtubemusic/8.05 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)"
    private val androidCreatorUa =
        "com.google.android.apps.youtube.creator/24.10.100 (Linux; U; Android 14) gzip"
    private val androidMusicUa =
        "com.google.android.apps.youtube.music/7.29.52 (Linux; U; Android 14) gzip"
    private val androidTestSuiteUa =
        "com.google.android.youtube/1.9 (Linux; U; Android 11) gzip"

    private val defaultPipedInstances = listOf(
        "https://pipedapi.kavin.rocks",
        "https://pipedapi.adminforge.de",
        "https://api.piped.private.coffee",
        "https://pipedapi.r4fo.com",
        "https://pipedapi.darkness.services",
        "https://pipedapi.moomoo.me",
        "https://pipedapi.ducks.party",
        "https://pipedapi.leptons.xyz"
    )

    private val defaultInvidiousInstances = listOf(
        "https://inv.nadeko.net",
        "https://yewtu.be",
        "https://invidious.nerdvpn.de",
        "https://iv.datura.network",
        "https://invidious.perennialte.ch",
        "https://invidious.materialio.us",
        "https://iv.nboeck.de",
        "https://invidious.protokolla.fi"
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
    }.distinct().take(10)

    private fun invidiousCandidates(): List<String> = buildList {
        addAll(dynamicInvidiousInstances)
        addAll(defaultInvidiousInstances)
    }.distinct().take(10)

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
     * Resolves a verified stream URL using `MetrolistGroup/Metrolist`'s `YTPlayerUtils` pipeline
     * (Tiers 0–3: ANDROID_VR / VISIONOS / WEB_REMIX + Cipher / TVHTML5 + Cipher / IOS HLS / Android clients)
     * with automatic Piped & Invidious federation failover (Tiers 4–5).
     */
    override suspend fun resolveStream(track: Track, attempt: Int): ResolvedStream? = withContext(Dispatchers.IO) {
        val videoId = track.sourceTrackId.takeIf { it.isNotBlank() }
            ?: track.id.removePrefix("ytm_")
        if (videoId.isBlank()) return@withContext null

        val now = System.currentTimeMillis()
        if (attempt == 0) {
            ytPlayerUtils.getCachedPlayback(videoId)?.let { cached ->
                return@withContext cached.toResolvedStream()
            }
            val cached = streamCache[videoId]
            if (cached != null && cached.expiresAtMs > now) {
                return@withContext cached.stream
            }
        } else {
            // Invalidate stale cache when ExoPlayer reports a playback error and advances tier
            ytPlayerUtils.invalidateCache(videoId)
            streamCache.remove(videoId)
        }

        // Tiers 0..3: Metrolist YTPlayerUtils (11 InnerTube clients + HEAD validateStatus + Cipher solver)
        if (attempt <= 3) {
            val metrolistData = runCatching {
                ytPlayerUtils.playerResponseForPlayback(
                    videoId = videoId,
                    audioQuality = MetrolistAudioQuality.HIGH,
                    startTier = attempt
                )
            }.getOrNull()
            if (metrolistData != null && metrolistData.streamUrl.startsWith("http")) {
                val resolved = metrolistData.toResolvedStream()
                streamCache[videoId] = CachedStream(resolved, metrolistData.expiresAtMs)
                return@withContext resolved
            }
        }

        // Tiers 4..5: Federation failover (Piped & Invidious ?local=true + verified proxy)
        val mirrorTiers: List<Pair<Int, suspend () -> ResolvedStream?>> = listOf(
            4 to { raceTier4PipedMirrors(videoId) },
            5 to { raceTier5InvidiousMirrorsAndProxy(videoId) }
        )
        for ((tierIdx, runner) in mirrorTiers) {
            if (tierIdx < attempt) continue
            val resolved = runCatching { runner() }.getOrNull()
            if (resolved != null && resolved.url.startsWith("http")) {
                val tagged = resolved.copy(tierIndex = tierIdx)
                Log.d("KoplingfyYouTube", "Resolved $videoId via mirror tier=$tierIdx (${tagged.resolverName})")
                streamCache[videoId] = CachedStream(tagged, now + 15 * 60_000L)
                return@withContext tagged
            }
        }
        Log.w("KoplingfyYouTube", "All Metrolist + mirror stream tiers exhausted for videoId=$videoId (attempt=$attempt)")
        null
    }

    /**
     * Used directly by `KoplingfyPlaybackService`'s Metrolist `ResolvingDataSource` on ExoPlayer's IO thread.
     * Hits the cached `MetrolistPlaybackData` / `streamCache` in 0ms on first play, and transparently
     * refreshes expired YouTube URLs on long pauses or seeks.
     */
    suspend fun resolveForExoPlayer(videoId: String): MetrolistPlaybackData? = withContext(Dispatchers.IO) {
        if (videoId.isBlank()) return@withContext null
        val now = System.currentTimeMillis()

        ytPlayerUtils.getCachedPlayback(videoId)?.let { return@withContext it }

        val cached = streamCache[videoId]
        if (cached != null && cached.expiresAtMs > now + 30_000L) {
            return@withContext cached.stream.toPlaybackData(videoId, cached.expiresAtMs)
        }

        // Re-resolve fresh URL transparently (e.g. after a multi-hour pause when googlevideo URL expired)
        val fresh = ytPlayerUtils.playerResponseForPlayback(
            videoId = videoId,
            audioQuality = MetrolistAudioQuality.HIGH,
            startTier = 0
        )
        if (fresh != null) {
            streamCache[videoId] = CachedStream(fresh.toResolvedStream(), fresh.expiresAtMs)
            return@withContext fresh
        }

        val mirror = raceTier4PipedMirrors(videoId) ?: raceTier5InvidiousMirrorsAndProxy(videoId)
        if (mirror != null) {
            val exp = now + 15 * 60_000L
            streamCache[videoId] = CachedStream(mirror, exp)
            return@withContext mirror.toPlaybackData(videoId, exp)
        }
        null
    }

    private fun MetrolistPlaybackData.toResolvedStream(): ResolvedStream = ResolvedStream(
        url = streamUrl,
        userAgent = userAgent,
        mimeType = mimeType,
        resolverName = "Metrolist ${clientName}",
        tierIndex = tierIndex
    )

    private fun ResolvedStream.toPlaybackData(videoId: String, expiresAtMs: Long): MetrolistPlaybackData =
        MetrolistPlaybackData(
            videoId = videoId,
            streamUrl = url,
            mimeType = mimeType ?: "audio/mp4",
            userAgent = userAgent ?: webRemixUa,
            contentLength = -1L,
            bitrate = 128_000,
            itag = 0,
            loudnessDb = null,
            expiresAtMs = expiresAtMs,
            clientName = resolverName,
            isHls = mimeType == "application/x-mpegURL" || url.contains("m3u8", ignoreCase = true),
            tierIndex = tierIndex
        )

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
        // VISIONOS & ANDROID_VR do not require visitorData or cipher — launch immediately!
        return raceFirstValid(
            timeoutMs = 10_000L,
            tasks = listOf(
                { resolveViaInnerTubeVisionOs(videoId) },
                { resolveViaInnerTubeAndroidVr(videoId, "1.65.10", androidVrUa) },
                { resolveViaInnerTubeAndroidVr(videoId, "1.71.26", androidVrAltUa) }
            )
        )
    }

    /**
     * Metrolist `YTPlayerUtils` core pipeline:
     * Fetches live `signatureTimestamp` (`sts`) from YouTube's active `player_ias` script,
     * queries `WEB_REMIX` (`music.youtube.com/youtubei/v1/player`), `TVHTML5_SIMPLY_EMBEDDED_PLAYER`,
     * and `TVHTML5` in parallel, and solves `signatureCipher` + `n`-param via `YouTubeCipherSolver`.
     */
    private suspend fun raceTier1MetrolistCipherClients(videoId: String): ResolvedStream? {
        val vd = getVisitorDataQuick()
        val sts = withTimeoutOrNull(4_500L) { cipherSolver.getSignatureTimestamp() }
        return raceFirstValid(
            timeoutMs = 14_000L,
            tasks = listOf(
                { resolveViaInnerTubeWebRemix(videoId, vd, sts) },
                { resolveViaInnerTubeTvEmbedded(videoId, vd, sts) },
                { resolveViaInnerTubeTvHtml5(videoId, vd, sts) }
            )
        )
    }

    private suspend fun raceTier2IosHlsInnerTube(videoId: String): ResolvedStream? {
        val vd = getVisitorDataQuick()
        return raceFirstValid(
            timeoutMs = 10_000L,
            tasks = listOf(
                { resolveViaInnerTubeIos(videoId, vd, preferHls = true) },
                { resolveViaInnerTubeIosMusic(videoId, vd) },
                { resolveViaInnerTubeIos(videoId, vd, preferHls = false) }
            )
        )
    }

    private suspend fun raceTier3SecondaryAndroidClients(videoId: String): ResolvedStream? {
        val vd = getVisitorDataQuick()
        return raceFirstValid(
            timeoutMs = 10_000L,
            tasks = listOf(
                { resolveViaInnerTubeAndroidCreator(videoId, vd) },
                { resolveViaInnerTubeAndroidMusic(videoId, vd) },
                { resolveViaInnerTubeAndroidTestSuite(videoId, vd) }
            )
        )
    }

    private suspend fun raceTier4PipedMirrors(videoId: String): ResolvedStream? {
        if (dynamicPipedInstances.isEmpty()) {
            withTimeoutOrNull(2_500L) { refreshPublicInstancesFast() }
        }
        val tasks = pipedCandidates().take(8).map<String, suspend () -> ResolvedStream?> { base ->
            { resolveSinglePipedInstance(base, videoId) }
        }
        return raceFirstValid(timeoutMs = 10_000L, tasks = tasks)
    }

    private suspend fun raceTier5InvidiousMirrorsAndProxy(videoId: String): ResolvedStream? {
        if (dynamicInvidiousInstances.isEmpty()) {
            withTimeoutOrNull(2_500L) { refreshPublicInstancesFast() }
        }
        val apiTasks = invidiousCandidates().take(8).map<String, suspend () -> ResolvedStream?> { base ->
            { resolveSingleInvidiousInstance(base, videoId) }
        }
        val apiWinner = raceFirstValid(timeoutMs = 9_000L, tasks = apiTasks)
        if (apiWinner != null) return apiWinner

        val hosts = invidiousCandidates().take(6)
        val proxyTasks = hosts.flatMap { base ->
            listOf<suspend () -> ResolvedStream?>(
                {
                    val proxyUrl = "$base/latest_version?id=$videoId&itag=140&local=true"
                    if (isAudioProxyLive(proxyUrl, webRemixUa)) {
                        ResolvedStream(
                            url = proxyUrl,
                            userAgent = webRemixUa,
                            mimeType = "audio/mp4",
                            resolverName = "Invidious Proxy M4A"
                        )
                    } else null
                },
                {
                    val proxyUrl = "$base/latest_version?id=$videoId&itag=251&local=true"
                    if (isAudioProxyLive(proxyUrl, webRemixUa)) {
                        ResolvedStream(
                            url = proxyUrl,
                            userAgent = webRemixUa,
                            mimeType = "audio/webm",
                            resolverName = "Invidious Proxy Opus"
                        )
                    } else null
                }
            )
        }
        return raceFirstValid(timeoutMs = 8_500L, tasks = proxyTasks)
    }

    /**
     * Safe verification strictly for unconfirmed direct Invidious proxy endpoints.
     * Avoids `Range: bytes=0-0` (which breaks many proxies/CDNs); verifies HTTP 2xx + binary/audio
     * Content-Type and immediately closes the connection without downloading the stream body.
     */
    private fun isAudioProxyLive(url: String, userAgent: String): Boolean {
        return runCatching {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .get()
                .build()
            probeClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use false
                val contentType = resp.header("Content-Type").orEmpty().lowercase(Locale.US)
                contentType.startsWith("audio/") ||
                    contentType.startsWith("video/") ||
                    contentType.contains("octet-stream") ||
                    contentType.isBlank()
            }
        }.getOrDefault(false)
    }

    // =========================================================================
    // DIRECT YOUTUBE INNERTUBE PLAYER RESOLVERS (ZERO API KEY, DEVICE IP)
    // =========================================================================

    private suspend fun getVisitorDataQuick(): String? {
        cachedVisitorData?.takeIf { it.isNotBlank() }?.let { return it }
        return withTimeoutOrNull(1_800L) {
            withContext(Dispatchers.IO) { getOrFetchVisitorDataFast() }
        }
    }

    private fun getOrFetchVisitorDataFast(): String? {
        cachedVisitorData?.takeIf { it.isNotBlank() }?.let { return it }
        runCatching {
            val payload = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB_REMIX")
                        put("clientVersion", "1.20250310.01.00")
                        put("hl", "en")
                        put("gl", deviceRegion)
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
            probeClient.newCall(req).execute().use { resp ->
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

    private fun resolveViaInnerTubeVisionOs(videoId: String): ResolvedStream? {
        val payload = JSONObject().apply {
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "VISIONOS")
                    put("clientVersion", "1.02")
                    put("deviceMake", "Apple")
                    put("deviceModel", "RealityDevice17,1")
                    put("osName", "visionOS")
                    put("osVersion", "26.5")
                    put("hl", "en")
                    put("gl", deviceRegion)
                })
            })
        }
        return postInnerTubePlayerDirect(
            endpoint = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            payload = payload,
            userAgent = visionOsUa,
            clientNameHeader = "101",
            clientVersionHeader = "1.02",
            visitorData = null,
            isWebClient = false,
            preferHls = false,
            resolverName = "InnerTube VISIONOS"
        )
    }

    private fun resolveViaInnerTubeAndroidVr(
        videoId: String,
        clientVersion: String,
        userAgent: String
    ): ResolvedStream? {
        val payload = JSONObject().apply {
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "ANDROID_VR")
                    put("clientVersion", clientVersion)
                    put("deviceMake", "Oculus")
                    put("deviceModel", "Quest 3")
                    put("osName", "Android")
                    put("osVersion", "12L")
                    put("androidSdkVersion", 32)
                    put("hl", "en")
                    put("gl", deviceRegion)
                })
            })
        }
        return postInnerTubePlayerDirect(
            endpoint = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            payload = payload,
            userAgent = userAgent,
            clientNameHeader = "28",
            clientVersionHeader = clientVersion,
            visitorData = null,
            isWebClient = false,
            preferHls = false,
            resolverName = "InnerTube ANDROID_VR $clientVersion"
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
                    put("gl", deviceRegion)
                    if (!visitorData.isNullOrBlank()) put("visitorData", visitorData)
                })
            })
        }
        return postInnerTubePlayerDirect(
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
                    put("gl", deviceRegion)
                    if (!visitorData.isNullOrBlank()) put("visitorData", visitorData)
                })
            })
        }
        return postInnerTubePlayerDirect(
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

    private fun resolveViaInnerTubeAndroidCreator(videoId: String, visitorData: String?): ResolvedStream? {
        val payload = JSONObject().apply {
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "ANDROID_CREATOR")
                    put("clientVersion", "24.10.100")
                    put("androidSdkVersion", 34)
                    put("hl", "en")
                    put("gl", deviceRegion)
                    if (!visitorData.isNullOrBlank()) put("visitorData", visitorData)
                })
            })
        }
        return postInnerTubePlayerDirect(
            endpoint = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            payload = payload,
            userAgent = androidCreatorUa,
            clientNameHeader = "14",
            clientVersionHeader = "24.10.100",
            visitorData = visitorData,
            isWebClient = false,
            preferHls = false,
            resolverName = "InnerTube ANDROID_CREATOR"
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
                    put("gl", deviceRegion)
                    if (!visitorData.isNullOrBlank()) put("visitorData", visitorData)
                })
            })
        }
        return postInnerTubePlayerDirect(
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

    private fun resolveViaInnerTubeAndroidTestSuite(videoId: String, visitorData: String?): ResolvedStream? {
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
                    put("gl", deviceRegion)
                    if (!visitorData.isNullOrBlank()) put("visitorData", visitorData)
                })
            })
        }
        return postInnerTubePlayerDirect(
            endpoint = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            payload = payload,
            userAgent = androidTestSuiteUa,
            clientNameHeader = "30",
            clientVersionHeader = "1.9",
            visitorData = visitorData,
            isWebClient = false,
            preferHls = false,
            resolverName = "InnerTube ANDROID_TESTSUITE"
        )
    }

    private suspend fun resolveViaInnerTubeWebRemix(
        videoId: String,
        visitorData: String?,
        signatureTimestamp: Int?
    ): ResolvedStream? {
        val cpn = YouTubeCipherSolver.generateCpn()
        val payload = JSONObject().apply {
            put("videoId", videoId)
            put("cpn", cpn)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
            if (signatureTimestamp != null) {
                put("playbackContext", JSONObject().apply {
                    put("contentPlaybackContext", JSONObject().apply {
                        put("signatureTimestamp", signatureTimestamp)
                    })
                })
            }
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", "1.20250310.01.00")
                    put("hl", "en")
                    put("gl", deviceRegion)
                    if (!visitorData.isNullOrBlank()) put("visitorData", visitorData)
                })
            })
        }
        return postInnerTubePlayerCipherAware(
            endpoint = "https://music.youtube.com/youtubei/v1/player?prettyPrint=false",
            payload = payload,
            userAgent = webRemixUa,
            clientNameHeader = "67",
            clientVersionHeader = "1.20250310.01.00",
            visitorData = visitorData,
            originUrl = "https://music.youtube.com",
            cpn = cpn,
            preferHls = false,
            resolverName = "InnerTube WEB_REMIX (Cipher)"
        )
    }

    private suspend fun resolveViaInnerTubeTvEmbedded(
        videoId: String,
        visitorData: String?,
        signatureTimestamp: Int?
    ): ResolvedStream? {
        val cpn = YouTubeCipherSolver.generateCpn()
        val payload = JSONObject().apply {
            put("videoId", videoId)
            put("cpn", cpn)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
            if (signatureTimestamp != null) {
                put("playbackContext", JSONObject().apply {
                    put("contentPlaybackContext", JSONObject().apply {
                        put("signatureTimestamp", signatureTimestamp)
                    })
                })
            }
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "TVHTML5_SIMPLY_EMBEDDED_PLAYER")
                    put("clientVersion", "2.0")
                    put("hl", "en")
                    put("gl", deviceRegion)
                    if (!visitorData.isNullOrBlank()) put("visitorData", visitorData)
                })
                put("thirdParty", JSONObject().apply {
                    put("embedUrl", "https://www.youtube.com/")
                })
            })
        }
        return postInnerTubePlayerCipherAware(
            endpoint = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            payload = payload,
            userAgent = webRemixUa,
            clientNameHeader = "85",
            clientVersionHeader = "2.0",
            visitorData = visitorData,
            originUrl = "https://www.youtube.com",
            cpn = cpn,
            preferHls = false,
            resolverName = "InnerTube TV_EMBEDDED (Cipher)"
        )
    }

    private suspend fun resolveViaInnerTubeTvHtml5(
        videoId: String,
        visitorData: String?,
        signatureTimestamp: Int?
    ): ResolvedStream? {
        val cpn = YouTubeCipherSolver.generateCpn()
        val payload = JSONObject().apply {
            put("videoId", videoId)
            put("cpn", cpn)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
            if (signatureTimestamp != null) {
                put("playbackContext", JSONObject().apply {
                    put("contentPlaybackContext", JSONObject().apply {
                        put("signatureTimestamp", signatureTimestamp)
                    })
                })
            }
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "TVHTML5")
                    put("clientVersion", "7.20250312.16.00")
                    put("hl", "en")
                    put("gl", deviceRegion)
                    if (!visitorData.isNullOrBlank()) put("visitorData", visitorData)
                })
            })
        }
        return postInnerTubePlayerCipherAware(
            endpoint = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            payload = payload,
            userAgent = webRemixUa,
            clientNameHeader = "7",
            clientVersionHeader = "7.20250312.16.00",
            visitorData = visitorData,
            originUrl = "https://www.youtube.com",
            cpn = cpn,
            preferHls = false,
            resolverName = "InnerTube TVHTML5 (Cipher)"
        )
    }

    /**
     * Metrolist / Zemer-Cipher aware InnerTube `/player` request:
     * Handles both direct `url` and `signatureCipher` (`s`/`sp`/`url`) audio formats,
     * solves `sig` and `n`-param via `YouTubeCipherSolver`, and appends `cpn`.
     */
    private suspend fun postInnerTubePlayerCipherAware(
        endpoint: String,
        payload: JSONObject,
        userAgent: String,
        clientNameHeader: String,
        clientVersionHeader: String,
        visitorData: String?,
        originUrl: String,
        cpn: String,
        preferHls: Boolean,
        resolverName: String
    ): ResolvedStream? {
        val root = runCatching {
            val reqBuilder = Request.Builder()
                .url(endpoint)
                .header("User-Agent", userAgent)
                .header("X-YouTube-Client-Name", clientNameHeader)
                .header("X-YouTube-Client-Version", clientVersionHeader)
                .header("X-Goog-Api-Format-Version", "2")
                .header("Origin", originUrl)
                .header("Referer", "$originUrl/")
                .header("Accept", "application/json")
                .post(payload.toString().toRequestBody(jsonMediaType))

            if (!visitorData.isNullOrBlank()) {
                reqBuilder.header("X-Goog-Visitor-Id", visitorData)
            }

            fastClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string().orEmpty()
                if (body.startsWith("{")) JSONObject(body) else null
            }
        }.getOrNull() ?: return null

        val status = root.optJSONObject("playabilityStatus")?.optString("status")
        if (status != null && status != "OK" && status != "LIVE_STREAM_OFFLINE") {
            return null
        }
        val streamingData = root.optJSONObject("streamingData") ?: return null
        val hls = streamingData.optString("hlsManifestUrl")

        if (preferHls && hls.startsWith("http")) {
            return ResolvedStream(
                url = hls,
                userAgent = userAgent,
                mimeType = "application/x-mpegURL",
                resolverName = "$resolverName (HLS)"
            )
        }

        // Rank adaptive audio formats (both direct `url` AND `signatureCipher` / `cipher`)
        val rankedAudio = rankAudioFormatsIncludingCipher(streamingData.optJSONArray("adaptiveFormats"))
        for (entry in rankedAudio) {
            val finalUrl = cipherSolver.resolveStreamUrl(
                directUrl = entry.directUrl,
                signatureCipher = entry.signatureCipher,
                cpn = cpn,
                solveNParam = true
            )
            if (finalUrl != null && finalUrl.startsWith("http")) {
                return ResolvedStream(
                    url = finalUrl,
                    userAgent = userAgent,
                    mimeType = entry.mimeType,
                    resolverName = resolverName
                )
            }
        }

        // Fallback to muxed formats (also cipher-aware)
        val rankedMuxed = rankMuxedFormatsIncludingCipher(streamingData.optJSONArray("formats"))
        for (entry in rankedMuxed) {
            val finalUrl = cipherSolver.resolveStreamUrl(
                directUrl = entry.directUrl,
                signatureCipher = entry.signatureCipher,
                cpn = cpn,
                solveNParam = true
            )
            if (finalUrl != null && finalUrl.startsWith("http")) {
                return ResolvedStream(
                    url = finalUrl,
                    userAgent = userAgent,
                    mimeType = entry.mimeType,
                    resolverName = "$resolverName (Muxed)"
                )
            }
        }

        if (hls.startsWith("http")) {
            return ResolvedStream(
                url = hls,
                userAgent = userAgent,
                mimeType = "application/x-mpegURL",
                resolverName = "$resolverName (HLS)"
            )
        }
        return null
    }

    private data class FormatCandidate(
        val directUrl: String?,
        val signatureCipher: String?,
        val mimeType: String,
        val score: Int
    )

    private fun rankAudioFormatsIncludingCipher(formats: JSONArray?): List<FormatCandidate> {
        if (formats == null || formats.length() == 0) return emptyList()
        val list = mutableListOf<FormatCandidate>()
        for (i in 0 until formats.length()) {
            val obj = formats.optJSONObject(i) ?: continue
            if (obj.has("drmFamilies")) continue
            val mime = obj.optString("mimeType").lowercase()
            if (!mime.startsWith("audio/")) continue
            val direct = obj.optString("url").takeIf { it.startsWith("http") }
            val cipher = obj.optString("signatureCipher").ifBlank { obj.optString("cipher") }
                .takeIf { it.isNotBlank() }
            if (direct == null && cipher == null) continue

            val bitrate = obj.optInt("bitrate", obj.optInt("averageBitrate", 0))
            val codecBonus = when {
                mime.contains("mp4") || mime.contains("mp4a") -> 200_000
                mime.contains("opus") || mime.contains("webm") -> 150_000
                else -> 0
            }
            val directBonus = if (direct != null) 50_000 else 0
            list.add(
                FormatCandidate(
                    directUrl = direct,
                    signatureCipher = cipher,
                    mimeType = mime.substringBefore(";"),
                    score = bitrate + codecBonus + directBonus
                )
            )
        }
        return list.sortedByDescending { it.score }
    }

    private fun rankMuxedFormatsIncludingCipher(formats: JSONArray?): List<FormatCandidate> {
        if (formats == null || formats.length() == 0) return emptyList()
        val list = mutableListOf<FormatCandidate>()
        for (i in 0 until formats.length()) {
            val obj = formats.optJSONObject(i) ?: continue
            if (obj.has("drmFamilies")) continue
            val direct = obj.optString("url").takeIf { it.startsWith("http") }
            val cipher = obj.optString("signatureCipher").ifBlank { obj.optString("cipher") }
                .takeIf { it.isNotBlank() }
            if (direct == null && cipher == null) continue
            val mime = obj.optString("mimeType").substringBefore(";").ifBlank { "video/mp4" }
            val bitrate = obj.optInt("bitrate", 0)
            list.add(FormatCandidate(direct, cipher, mime, bitrate))
        }
        return list.sortedByDescending { it.score }
    }

    /**
     * Posts to YouTube InnerTube `/player` and immediately returns the best playable stream URL
     * (appending `cpn`) without destructive `Range: bytes=0-0` pre-flight probes. If ExoPlayer
     * encounters a 403 on playback, PlaybackController automatically advances to the next tier.
     */
    private fun postInnerTubePlayerDirect(
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
        val cpn = YouTubeCipherSolver.generateCpn()
        if (!payload.has("cpn")) payload.put("cpn", cpn)
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
                val cand = extractCandidateFromPlayerJson(
                    root = root,
                    userAgent = userAgent,
                    preferHls = preferHls,
                    resolverName = resolverName
                ) ?: return@use null
                val finalUrl = if (cand.mimeType != "application/x-mpegURL" && !cand.url.contains("cpn=")) {
                    val sep = if (cand.url.contains("?")) "&" else "?"
                    "${cand.url}${sep}cpn=$cpn"
                } else cand.url
                cand.copy(url = finalUrl)
            }
        }.getOrNull()
    }

    internal fun extractCandidateFromPlayerJson(
        root: JSONObject,
        userAgent: String,
        preferHls: Boolean,
        resolverName: String
    ): ResolvedStream? {
        val status = root.optJSONObject("playabilityStatus")?.optString("status")
        if (status != null && status != "OK" && status != "LIVE_STREAM_OFFLINE") {
            return null
        }
        val streamingData = root.optJSONObject("streamingData") ?: return null
        val hls = streamingData.optString("hlsManifestUrl")

        if (preferHls && hls.startsWith("http")) {
            return ResolvedStream(
                url = hls,
                userAgent = userAgent,
                mimeType = "application/x-mpegURL",
                resolverName = "$resolverName (HLS)"
            )
        }

        pickBestDirectAudioFormat(streamingData.optJSONArray("adaptiveFormats"))?.let { (url, mime) ->
            return ResolvedStream(
                url = url,
                userAgent = userAgent,
                mimeType = mime,
                resolverName = resolverName
            )
        }

        pickBestDirectMuxedFormat(streamingData.optJSONArray("formats"))?.let { (url, mime) ->
            return ResolvedStream(
                url = url,
                userAgent = userAgent,
                mimeType = mime,
                resolverName = "$resolverName (Muxed)"
            )
        }

        if (hls.startsWith("http")) {
            return ResolvedStream(
                url = hls,
                userAgent = userAgent,
                mimeType = "application/x-mpegURL",
                resolverName = "$resolverName (HLS)"
            )
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
                if (bestUrl != null) {
                    return@use ResolvedStream(url = bestUrl, userAgent = webRemixUa, resolverName = "Piped Gateway")
                }
                val hls = root.optString("hls")
                if (hls.startsWith("http")) {
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
                .url("$base/api/v1/videos/$videoId?local=true")
                .header("Accept", "application/json")
                .build()
            fastClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string().orEmpty()
                if (!body.startsWith("{")) return@use null
                val root = JSONObject(body)
                val bestAudio = selectBestInvidiousAudio(root.optJSONArray("adaptiveFormats"), base)
                if (bestAudio != null) {
                    return@use ResolvedStream(url = bestAudio, userAgent = webRemixUa, resolverName = "Invidious Gateway")
                }
                val muxed = root.optJSONArray("formatStreams")?.let { arr ->
                    var picked: String? = null
                    for (i in 0 until arr.length()) {
                        val rawUrl = arr.optJSONObject(i)?.optString("url").orEmpty()
                        val resolved = when {
                            rawUrl.startsWith("http") -> rawUrl
                            rawUrl.startsWith("/") -> "$base$rawUrl"
                            else -> null
                        }
                        if (resolved != null) {
                            picked = resolved
                            break
                        }
                    }
                    picked
                }
                if (muxed != null) {
                    return@use ResolvedStream(url = muxed, userAgent = webRemixUa, resolverName = "Invidious Muxed")
                }
                val hls = root.optString("hlsUrl")
                if (hls.startsWith("http")) {
                    return@use ResolvedStream(
                        url = hls,
                        userAgent = webRemixUa,
                        mimeType = "application/x-mpegURL",
                        resolverName = "Invidious HLS"
                    )
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
