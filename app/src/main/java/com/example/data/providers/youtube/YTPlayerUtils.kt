package com.example.data.providers.youtube

import android.util.Log
import kotlinx.coroutines.Dispatchers
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
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

enum class MetrolistAudioQuality {
    AUTO,
    HIGH,
    LOW
}

/**
 * Resolved playback stream metadata modeled directly after `MetrolistGroup/Metrolist`'s
 * `YTPlayerUtils.PlaybackData`.
 */
data class MetrolistPlaybackData(
    val videoId: String,
    val streamUrl: String,
    val mimeType: String,
    val userAgent: String,
    val contentLength: Long,
    val bitrate: Int,
    val itag: Int,
    val loudnessDb: Float?,
    val expiresAtMs: Long,
    val clientName: String,
    val isHls: Boolean,
    val tierIndex: Int
)

/**
 * Playback stream resolver modeled directly after `MetrolistGroup/Metrolist`'s `YTPlayerUtils.kt`:
 * - `MAIN_CLIENT` + `STREAM_FALLBACK_CLIENTS` client rotation
 * - Live `signatureTimestamp` (`sts`) & `YouTubeCipherSolver` (`sig` + `n` deobfuscation)
 * - Quality-aware `findFormat()` audio scoring (Opus `itag=251` & M4A `itag=140`)
 * - Non-destructive HTTP `HEAD` status validation (`validateStatus`) that catches 403/404 immediately
 *   without breaking HLS manifests or rejecting valid streams with `Range: bytes=0-0`
 * - `expiresInSeconds`-aware `playbackCache` supporting Metrolist's `ResolvingDataSource` + `SimpleCache`
 */
class YTPlayerUtils(
    httpClient: OkHttpClient,
    private val cipherSolver: YouTubeCipherSolver
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val apiClient: OkHttpClient = httpClient.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(11, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val headClient: OkHttpClient = httpClient.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /** Metrolist-style URL cache keyed by `videoId`, respecting YouTube's `expiresInSeconds`. */
    private val playbackCache = ConcurrentHashMap<String, MetrolistPlaybackData>()

    @Volatile
    private var cachedVisitorData: String? = null

    private val deviceRegion: String
        get() = Locale.getDefault().country.takeIf { it.length == 2 }?.uppercase(Locale.US) ?: "ID"

    /**
     * Exact Metrolist-style client tiers:
     * - Tier 0: Direct unencrypted clients (`ANDROID_VR` 1.65 / 1.71 + `VISIONOS`) — fastest, zero cipher overhead
     * - Tier 1: Metrolist `MAIN_CLIENT` (`WEB_REMIX`) + `TVHTML5_EMBEDDED` + `TVHTML5` with live `sts` & cipher solver
     * - Tier 2: Native Apple HLS (`IOS` + `IOS_MUSIC`)
     * - Tier 3: Secondary Android clients (`ANDROID_CREATOR`, `ANDROID_MUSIC`, `ANDROID_TESTSUITE`)
     */
    private val clientTiers: List<List<YouTubeClient>> = listOf(
        listOf(YouTubeClient.ANDROID_VR_1_65, YouTubeClient.VISIONOS, YouTubeClient.ANDROID_VR_1_71),
        listOf(YouTubeClient.WEB_REMIX, YouTubeClient.TVHTML5_SIMPLY_EMBEDDED_PLAYER, YouTubeClient.TVHTML5),
        listOf(YouTubeClient.IOS, YouTubeClient.IOS_MUSIC),
        listOf(YouTubeClient.ANDROID_CREATOR, YouTubeClient.ANDROID_MUSIC, YouTubeClient.ANDROID_TESTSUITE)
    )

    fun getCachedPlayback(videoId: String): MetrolistPlaybackData? {
        val now = System.currentTimeMillis()
        val cached = playbackCache[videoId] ?: return null
        return if (cached.expiresAtMs > now + 60_000L) cached else {
            playbackCache.remove(videoId)
            null
        }
    }

    fun invalidateCache(videoId: String) {
        playbackCache.remove(videoId)
    }

    /**
     * Resolves a verified, playable `MetrolistPlaybackData` for [videoId] starting at [startTier].
     */
    suspend fun playerResponseForPlayback(
        videoId: String,
        audioQuality: MetrolistAudioQuality = MetrolistAudioQuality.HIGH,
        startTier: Int = 0
    ): MetrolistPlaybackData? = withContext(Dispatchers.IO) {
        if (videoId.isBlank()) return@withContext null

        if (startTier == 0) {
            getCachedPlayback(videoId)?.let { return@withContext it }
        } else {
            playbackCache.remove(videoId)
        }

        val clampedStart = startTier.coerceIn(0, clientTiers.lastIndex)
        for (tierIdx in clampedStart until clientTiers.size) {
            val clients = clientTiers[tierIdx]
            val needsVd = clients.any { it.sendVisitorData }
            val needsSts = clients.any { it.useSignatureTimestamp }
            val vd = if (needsVd) getVisitorDataQuick() else null
            val sts = if (needsSts) withTimeoutOrNull(4_500L) { cipherSolver.getSignatureTimestamp() } else null

            val timeoutMs = if (needsSts) 14_000L else 10_000L
            val winner = raceClients(
                timeoutMs = timeoutMs,
                clients = clients,
                resolver = { client ->
                    resolveWithClient(
                        client = client,
                        videoId = videoId,
                        audioQuality = audioQuality,
                        visitorData = vd,
                        signatureTimestamp = sts,
                        tierIndex = tierIdx
                    )
                }
            )
            if (winner != null) {
                playbackCache[videoId] = winner
                Log.d(
                    TAG,
                    "Resolved $videoId via ${winner.clientName} (tier=$tierIdx, itag=${winner.itag}, hls=${winner.isHls}, len=${winner.contentLength})"
                )
                return@withContext winner
            }
        }
        null
    }

    private suspend fun raceClients(
        timeoutMs: Long,
        clients: List<YouTubeClient>,
        resolver: suspend (YouTubeClient) -> MetrolistPlaybackData?
    ): MetrolistPlaybackData? = withTimeoutOrNull(timeoutMs) {
        coroutineScope {
            val winner = Channel<MetrolistPlaybackData>(Channel.CONFLATED)
            val jobs = clients.map { client ->
                launch(Dispatchers.IO) {
                    val data = runCatching { resolver(client) }.getOrNull()
                    if (data != null && data.streamUrl.startsWith("http")) {
                        winner.trySend(data)
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

    private suspend fun resolveWithClient(
        client: YouTubeClient,
        videoId: String,
        audioQuality: MetrolistAudioQuality,
        visitorData: String?,
        signatureTimestamp: Int?,
        tierIndex: Int
    ): MetrolistPlaybackData? {
        val cpn = YouTubeCipherSolver.generateCpn()
        val payload = client.buildPlayerPayload(
            videoId = videoId,
            cpn = cpn,
            regionCode = deviceRegion,
            visitorData = visitorData,
            signatureTimestamp = signatureTimestamp
        )

        val root = postPlayerRequest(client, payload, visitorData) ?: return null
        val playabilityStatus = root.optJSONObject("playabilityStatus")?.optString("status")
        if (playabilityStatus != null && playabilityStatus != "OK" && playabilityStatus != "LIVE_STREAM_OFFLINE") {
            return null
        }

        val streamingData = root.optJSONObject("streamingData") ?: return null
        val expiresSec = streamingData.optString("expiresInSeconds").toLongOrNull()
            ?.coerceIn(300L, 21_600L) ?: 18_000L
        val expiresAtMs = System.currentTimeMillis() + (expiresSec - 180L).coerceAtLeast(120L) * 1000L
        val loudnessDb = root.optJSONObject("playerConfig")
            ?.optJSONObject("audioConfig")
            ?.optDouble("loudnessDb", Double.NaN)
            ?.takeIf { !it.isNaN() }
            ?.toFloat()

        val hlsUrl = streamingData.optString("hlsManifestUrl")
        if (client.preferHls && hlsUrl.startsWith("http")) {
            return MetrolistPlaybackData(
                videoId = videoId,
                streamUrl = hlsUrl,
                mimeType = "application/x-mpegURL",
                userAgent = client.userAgent,
                contentLength = -1L,
                bitrate = 128_000,
                itag = 0,
                loudnessDb = loudnessDb,
                expiresAtMs = expiresAtMs,
                clientName = "${client.name} (HLS)",
                isHls = true,
                tierIndex = tierIndex
            )
        }

        // Select best ranked adaptive audio formats (Metrolist `findFormat` logic)
        val rankedFormats = rankAudioFormats(
            formats = streamingData.optJSONArray("adaptiveFormats"),
            audioQuality = audioQuality,
            allowCipher = client.requiresCipher
        ) + rankMuxedFormats(
            formats = streamingData.optJSONArray("formats"),
            allowCipher = client.requiresCipher
        )

        for (fmt in rankedFormats.take(3)) {
            val resolvedUrl = cipherSolver.resolveStreamUrl(
                directUrl = fmt.directUrl,
                signatureCipher = fmt.signatureCipher,
                cpn = cpn,
                solveNParam = client.requiresCipher
            ) ?: continue

            // Metrolist's `validateStatus`: quick HTTP HEAD to reject 403/404 immediately before ExoPlayer
            if (validateStatus(resolvedUrl, client)) {
                return MetrolistPlaybackData(
                    videoId = videoId,
                    streamUrl = resolvedUrl,
                    mimeType = fmt.mimeType,
                    userAgent = client.userAgent,
                    contentLength = fmt.contentLength,
                    bitrate = fmt.bitrate,
                    itag = fmt.itag,
                    loudnessDb = loudnessDb,
                    expiresAtMs = expiresAtMs,
                    clientName = client.name,
                    isHls = false,
                    tierIndex = tierIndex
                )
            }
        }

        // Final HLS fallback on the same client if progressive formats returned 403
        if (hlsUrl.startsWith("http")) {
            return MetrolistPlaybackData(
                videoId = videoId,
                streamUrl = hlsUrl,
                mimeType = "application/x-mpegURL",
                userAgent = client.userAgent,
                contentLength = -1L,
                bitrate = 128_000,
                itag = 0,
                loudnessDb = loudnessDb,
                expiresAtMs = expiresAtMs,
                clientName = "${client.name} (HLS)",
                isHls = true,
                tierIndex = tierIndex
            )
        }
        return null
    }

    private fun postPlayerRequest(
        client: YouTubeClient,
        payload: JSONObject,
        visitorData: String?
    ): JSONObject? {
        return runCatching {
            val reqBuilder = Request.Builder()
                .url(client.endpoint)
                .header("User-Agent", client.userAgent)
                .header("X-YouTube-Client-Name", client.clientId)
                .header("X-YouTube-Client-Version", client.clientVersion)
                .header("X-Goog-Api-Format-Version", "2")
                .header("Accept", "application/json")
                .post(payload.toString().toRequestBody(jsonMediaType))

            if (client.sendVisitorData && !visitorData.isNullOrBlank()) {
                reqBuilder.header("X-Goog-Visitor-Id", visitorData)
            }
            client.origin?.let { origin ->
                reqBuilder.header("Origin", origin)
                reqBuilder.header("Referer", "$origin/")
            }

            apiClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string().orEmpty()
                if (body.startsWith("{")) JSONObject(body) else null
            }
        }.getOrNull()
    }

    /**
     * Metrolist `YTPlayerUtils.validateStatus(url)`:
     * Uses HTTP `HEAD` (never `Range: bytes=0-0`!) to immediately reject `403`/`404`/`410` URLs
     * so we fall back to the next InnerTube client in ~200ms rather than stalling ExoPlayer.
     * On transient network timeout, returns true so ExoPlayer's own retry/buffering still gets a shot.
     */
    private fun validateStatus(url: String, client: YouTubeClient): Boolean {
        return runCatching {
            val reqBuilder = Request.Builder()
                .url(url)
                .header("User-Agent", client.userAgent)
                .head()
            client.origin?.let { origin ->
                reqBuilder.header("Origin", origin)
                reqBuilder.header("Referer", "$origin/")
            }
            headClient.newCall(reqBuilder.build()).execute().use { resp ->
                when (resp.code) {
                    in 200..299, 405 -> true
                    403, 404, 410 -> {
                        Log.w(TAG, "HEAD ${resp.code} rejected stream from ${client.name}")
                        false
                    }
                    else -> false
                }
            }
        }.getOrDefault(true)
    }

    private suspend fun getVisitorDataQuick(): String? {
        cachedVisitorData?.takeIf { it.isNotBlank() }?.let { return it }
        return withTimeoutOrNull(1_800L) {
            withContext(Dispatchers.IO) {
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
                        .header("User-Agent", YouTubeClient.WEB_REMIX.userAgent)
                        .header("Origin", "https://music.youtube.com")
                        .header("X-YouTube-Client-Name", "67")
                        .header("X-YouTube-Client-Version", "1.20250310.01.00")
                        .post(payload.toString().toRequestBody(jsonMediaType))
                        .build()
                    headClient.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val txt = resp.body?.string().orEmpty()
                            if (txt.startsWith("{")) {
                                val vd = JSONObject(txt)
                                    .optJSONObject("responseContext")
                                    ?.optString("visitorData")
                                    .orEmpty()
                                if (vd.isNotBlank()) {
                                    cachedVisitorData = vd
                                    return@use vd
                                }
                            }
                        }
                        null
                    }
                }.getOrNull()
            }
        } ?: cachedVisitorData
    }

    internal data class RankedAudioFormat(
        val directUrl: String?,
        val signatureCipher: String?,
        val mimeType: String,
        val itag: Int,
        val bitrate: Int,
        val contentLength: Long,
        val score: Int
    )

    internal fun rankAudioFormats(
        formats: JSONArray?,
        audioQuality: MetrolistAudioQuality,
        allowCipher: Boolean
    ): List<RankedAudioFormat> {
        if (formats == null || formats.length() == 0) return emptyList()
        val out = mutableListOf<RankedAudioFormat>()
        for (i in 0 until formats.length()) {
            val obj = formats.optJSONObject(i) ?: continue
            if (obj.has("drmFamilies")) continue
            val rawMime = obj.optString("mimeType").lowercase(Locale.US)
            if (!rawMime.startsWith("audio/")) continue

            val direct = obj.optString("url").takeIf { it.startsWith("http") }
            val cipher = if (allowCipher) {
                obj.optString("signatureCipher").ifBlank { obj.optString("cipher") }
                    .takeIf { it.isNotBlank() }
            } else null
            if (direct == null && cipher == null) continue

            val itag = obj.optInt("itag", 0)
            val bitrate = obj.optInt("bitrate", obj.optInt("averageBitrate", 0))
            val contentLength = obj.optString("contentLength").toLongOrNull()
                ?: obj.optLong("contentLength", -1L)
            val mimeClean = rawMime.substringBefore(";")

            // Metrolist format scoring: prefer Opus (webm / itag 251) & M4A (mp4 / itag 140)
            val codecBonus = when {
                mimeClean.contains("webm") || rawMime.contains("opus") -> 250_000
                mimeClean.contains("mp4") || rawMime.contains("mp4a") -> 220_000
                else -> 0
            }
            val qualityScore = when (audioQuality) {
                MetrolistAudioQuality.LOW -> -bitrate
                MetrolistAudioQuality.HIGH, MetrolistAudioQuality.AUTO -> bitrate
            }
            val directBonus = if (direct != null) 40_000 else 0
            out.add(
                RankedAudioFormat(
                    directUrl = direct,
                    signatureCipher = cipher,
                    mimeType = mimeClean,
                    itag = itag,
                    bitrate = bitrate,
                    contentLength = contentLength,
                    score = qualityScore + codecBonus + directBonus
                )
            )
        }
        return out.sortedByDescending { it.score }
    }

    private fun rankMuxedFormats(
        formats: JSONArray?,
        allowCipher: Boolean
    ): List<RankedAudioFormat> {
        if (formats == null || formats.length() == 0) return emptyList()
        val out = mutableListOf<RankedAudioFormat>()
        for (i in 0 until formats.length()) {
            val obj = formats.optJSONObject(i) ?: continue
            if (obj.has("drmFamilies")) continue
            val direct = obj.optString("url").takeIf { it.startsWith("http") }
            val cipher = if (allowCipher) {
                obj.optString("signatureCipher").ifBlank { obj.optString("cipher") }
                    .takeIf { it.isNotBlank() }
            } else null
            if (direct == null && cipher == null) continue
            val mimeClean = obj.optString("mimeType").substringBefore(";").ifBlank { "video/mp4" }
            val itag = obj.optInt("itag", 0)
            val bitrate = obj.optInt("bitrate", 0)
            val contentLength = obj.optString("contentLength").toLongOrNull() ?: -1L
            out.add(RankedAudioFormat(direct, cipher, mimeClean, itag, bitrate, contentLength, bitrate))
        }
        return out.sortedByDescending { it.score }
    }

    companion object {
        private const val TAG = "YTPlayerUtils"
        const val CHUNK_LENGTH: Long = 512L * 1024L // Exact Metrolist 512 KB chunk size
        const val METROLIST_URI_SCHEME = "koplingfy"
        const val METROLIST_URI_HOST = "ytm"

        fun buildInternalTrackUri(videoId: String): String =
            "$METROLIST_URI_SCHEME://$METROLIST_URI_HOST/$videoId"

        /**
         * Appends `range=0-<contentLength>` for direct progressive `googlevideo.com` URLs
         * if not already present (Metrolist anti-throttling convention).
         */
        fun appendContentRangeIfNeeded(url: String, contentLength: Long): String {
            if (contentLength <= 0L) return url
            if (!url.contains("googlevideo.com", ignoreCase = true)) return url
            if (url.contains("range=", ignoreCase = true)) return url
            val sep = if (url.contains("?")) "&" else "?"
            return "${url}${sep}range=0-$contentLength"
        }
    }
}
