package com.example.lyrics

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.example.data.local.db.LyricsDao
import com.example.data.local.db.LyricsEntity
import com.example.data.providers.TrackMetadataNormalizer
import com.example.domain.model.MusicSource
import com.example.domain.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.abs

data class LyricsCandidate(
    val id: String,
    val trackName: String,
    val artistName: String,
    val albumName: String,
    val durationSec: Long,
    val syncedLyrics: String?,
    val plainLyrics: String?,
    val providerName: String,
    val matchScore: Int
)

/**
 * Independent Lyrics Engine decoupled from Playback Engine.
 * Priority:
 * 1. Local Room Cache
 * 2. LRCLIB API (/api/get + /api/search with strict track/artist/album/duration matching)
 * 3. BetterLyrics / Lyrics.ovh fallback
 * 4. Optional Licensed Musixmatch API (when configured)
 * 5. Manual LRC import / export
 */
class LyricsEngine(
    private val context: Context,
    private val httpClient: OkHttpClient,
    private val lyricsDao: LyricsDao,
    private val musixmatchKeyProvider: () -> String
) {
    private val userAgent = "Koplingfy/1.0.0 (https://github.com/rifandi/koplingfy)"
    private val rateLimitMutex = Mutex()
    private var lastLrcLibCallMs = 0L

    suspend fun getLyricsForTrack(
        track: Track,
        forceRefresh: Boolean = false
    ): ParsedLyrics? = withContext(Dispatchers.IO) {
        if (track.isLiveStream) return@withContext null

        // 1. Check Room local cache first
        if (!forceRefresh) {
            val cached = lyricsDao.getLyrics(track.id)
            if (cached != null && (!cached.syncedLrc.isNullOrBlank() || !cached.plainLyrics.isNullOrBlank())) {
                return@withContext LrcParser.parse(
                    syncedLrc = cached.syncedLrc,
                    plainLyrics = cached.plainLyrics,
                    savedOffsetMs = cached.offsetMs,
                    providerName = "${cached.providerName} (Cached)"
                )
            }
        }

        val cleanTitle = TrackMetadataNormalizer.normalizeTitle(track.title).ifBlank { track.title }
        val cleanArtist = TrackMetadataNormalizer.normalizeArtist(track.artist).ifBlank { track.artist }
        val durationSec = (track.durationMs / 1000L).coerceAtLeast(0L)

        // 2. Try LRCLIB exact GET /api/get
        val exactLrcLib = fetchLrcLibExact(
            title = track.title,
            artist = track.artist,
            album = track.album,
            durationSec = durationSec
        ) ?: if (cleanTitle != track.title.lowercase()) {
            fetchLrcLibExact(cleanTitle, cleanArtist, "", durationSec)
        } else null

        if (exactLrcLib != null && (!exactLrcLib.syncedLyrics.isNullOrBlank() || !exactLrcLib.plainLyrics.isNullOrBlank())) {
            return@withContext persistAndParse(track, exactLrcLib)
        }

        // 3. Try LRCLIB /api/search with strict title/artist/duration scoring
        val searchCandidates = searchLrcLib(
            query = "$cleanTitle $cleanArtist".trim(),
            expectedTitle = cleanTitle,
            expectedArtist = cleanArtist,
            expectedAlbum = track.album,
            expectedDurationSec = durationSec
        )
        val bestCandidate = searchCandidates.firstOrNull { it.matchScore >= 60 }
        if (bestCandidate != null) {
            return@withContext persistAndParse(track, bestCandidate)
        }

        // 4. YouTube InnerTube /youtubei/v1/get_transcript synced lyrics (Metrolist transcript pipeline)
        if (track.source == MusicSource.YOUTUBE_MUSIC) {
            val videoId = track.sourceTrackId.takeIf { it.length == 11 }
                ?: track.id.removePrefix("ytm_").takeIf { it.length == 11 }
            if (videoId != null) {
                val ytTranscript = fetchYouTubeTranscriptLyrics(videoId, track)
                if (ytTranscript != null) {
                    return@withContext persistAndParse(track, ytTranscript)
                }
            }
        }

        // 5. Fallback: Lyrics.ovh (BetterLyrics fallback for unsynced lyrics)
        val ovh = fetchLyricsOvh(cleanArtist, cleanTitle)
        if (ovh != null) {
            return@withContext persistAndParse(track, ovh)
        }

        // 6. Optional Licensed Musixmatch API if key is configured
        val mmKey = musixmatchKeyProvider().trim()
        if (mmKey.isNotBlank()) {
            val mm = fetchMusixmatch(cleanTitle, cleanArtist, mmKey)
            if (mm != null) {
                return@withContext persistAndParse(track, mm)
            }
        }

        null
    }

    suspend fun searchManualCandidates(query: String, track: Track? = null): List<LyricsCandidate> =
        withContext(Dispatchers.IO) {
            searchLrcLib(
                query = query,
                expectedTitle = track?.title ?: query,
                expectedArtist = track?.artist.orEmpty(),
                expectedAlbum = track?.album.orEmpty(),
                expectedDurationSec = (track?.durationMs ?: 0L) / 1000L
            )
        }

    suspend fun selectCandidateForTrack(track: Track, candidate: LyricsCandidate): ParsedLyrics =
        withContext(Dispatchers.IO) {
            persistAndParse(track, candidate)
        }

    suspend fun updateLyricsOffset(trackId: String, offsetMs: Long) = withContext(Dispatchers.IO) {
        lyricsDao.updateOffset(trackId, offsetMs)
    }

    suspend fun importLocalLrc(track: Track, uri: Uri): ParsedLyrics? = withContext(Dispatchers.IO) {
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull() ?: return@withContext null

        val isSynced = text.contains(Regex("""\[\d{1,3}:\d{2}"""))
        val entity = LyricsEntity(
            trackId = track.id,
            title = track.title,
            artist = track.artist,
            syncedLrc = if (isSynced) text else null,
            plainLyrics = if (!isSynced) text else null,
            providerName = "Local LRC Import"
        )
        lyricsDao.saveLyrics(entity)
        LrcParser.parse(entity.syncedLrc, entity.plainLyrics, 0L, entity.providerName)
    }

    suspend fun exportLrcToUri(parsed: ParsedLyrics, track: Track, uri: Uri): Boolean =
        withContext(Dispatchers.IO) {
            val content = parsed.rawSyncedLrc
                ?: LrcParser.serializeToLrc(parsed.lines, track.title, track.artist)
            runCatching {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                    it.write(content)
                }
                true
            }.getOrDefault(false)
        }

    private suspend fun persistAndParse(track: Track, candidate: LyricsCandidate): ParsedLyrics {
        val entity = LyricsEntity(
            trackId = track.id,
            title = track.title,
            artist = track.artist,
            syncedLrc = candidate.syncedLyrics,
            plainLyrics = candidate.plainLyrics,
            providerName = candidate.providerName
        )
        lyricsDao.saveLyrics(entity)
        return LrcParser.parse(
            syncedLrc = candidate.syncedLyrics,
            plainLyrics = candidate.plainLyrics,
            savedOffsetMs = 0L,
            providerName = candidate.providerName
        )
    }

    private suspend fun throttleLrcLib() {
        rateLimitMutex.withLock {
            val now = System.currentTimeMillis()
            val delta = now - lastLrcLibCallMs
            if (delta < 300L) {
                kotlinx.coroutines.delay(300L - delta)
            }
            lastLrcLibCallMs = System.currentTimeMillis()
        }
    }

    private suspend fun fetchLrcLibExact(
        title: String,
        artist: String,
        album: String,
        durationSec: Long
    ): LyricsCandidate? {
        if (title.isBlank() || artist.isBlank()) return null
        throttleLrcLib()
        return runCatching {
            val sb = StringBuilder("https://lrclib.net/api/get?")
            sb.append("track_name=").append(URLEncoder.encode(title, "UTF-8"))
            sb.append("&artist_name=").append(URLEncoder.encode(artist, "UTF-8"))
            if (album.isNotBlank() && !album.equals("Unknown Album", ignoreCase = true) && !album.equals("YouTube Music", ignoreCase = true)) {
                sb.append("&album_name=").append(URLEncoder.encode(album, "UTF-8"))
            }
            if (durationSec > 10L) {
                sb.append("&duration=").append(durationSec)
            }
            val req = Request.Builder()
                .url(sb.toString())
                .header("User-Agent", userAgent)
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val obj = JSONObject(resp.body?.string().orEmpty())
                val synced = obj.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }
                val plain = obj.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" }
                if (synced == null && plain == null) return@runCatching null
                LyricsCandidate(
                    id = obj.optString("id"),
                    trackName = obj.optString("trackName", title),
                    artistName = obj.optString("artistName", artist),
                    albumName = obj.optString("albumName", album),
                    durationSec = obj.optLong("duration", durationSec),
                    syncedLyrics = synced,
                    plainLyrics = plain,
                    providerName = "LRCLIB",
                    matchScore = 100
                )
            }
        }.getOrNull()
    }

    private suspend fun searchLrcLib(
        query: String,
        expectedTitle: String,
        expectedArtist: String,
        expectedAlbum: String,
        expectedDurationSec: Long
    ): List<LyricsCandidate> {
        if (query.isBlank()) return emptyList()
        throttleLrcLib()
        return runCatching {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val req = Request.Builder()
                .url("https://lrclib.net/api/search?q=$encoded")
                .header("User-Agent", userAgent)
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching emptyList()
                val arr = JSONArray(resp.body?.string().orEmpty())
                val normTitle = TrackMetadataNormalizer.normalizeTitle(expectedTitle)
                val normArtist = TrackMetadataNormalizer.normalizeArtist(expectedArtist)
                val normAlbum = expectedAlbum.lowercase().trim()

                buildList {
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val synced = obj.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }
                        val plain = obj.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" }
                        if (synced == null && plain == null) continue

                        val candTitle = obj.optString("trackName")
                        val candArtist = obj.optString("artistName")
                        val candAlbum = obj.optString("albumName")
                        val candDur = obj.optLong("duration", 0L)

                        val score = scoreCandidate(
                            normExpectedTitle = normTitle,
                            normExpectedArtist = normArtist,
                            normExpectedAlbum = normAlbum,
                            expectedDurationSec = expectedDurationSec,
                            candTitle = candTitle,
                            candArtist = candArtist,
                            candAlbum = candAlbum,
                            candDurationSec = candDur,
                            hasSynced = synced != null
                        )
                        add(
                            LyricsCandidate(
                                id = obj.optString("id"),
                                trackName = candTitle,
                                artistName = candArtist,
                                albumName = candAlbum,
                                durationSec = candDur,
                                syncedLyrics = synced,
                                plainLyrics = plain,
                                providerName = "LRCLIB",
                                matchScore = score
                            )
                        )
                    }
                }.sortedByDescending { it.matchScore }
            }
        }.getOrDefault(emptyList())
    }

    private suspend fun fetchLyricsOvh(artist: String, title: String): LyricsCandidate? {
        if (artist.isBlank() || title.isBlank()) return null
        return runCatching {
            val a = URLEncoder.encode(artist, "UTF-8").replace("+", "%20")
            val t = URLEncoder.encode(title, "UTF-8").replace("+", "%20")
            val req = Request.Builder()
                .url("https://api.lyrics.ovh/v1/$a/$t")
                .header("User-Agent", userAgent)
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val lyrics = JSONObject(resp.body?.string().orEmpty())
                    .optString("lyrics")
                    .trim()
                    .takeIf { it.isNotBlank() } ?: return@runCatching null
                LyricsCandidate(
                    id = "ovh_${artist}_$title",
                    trackName = title,
                    artistName = artist,
                    albumName = "",
                    durationSec = 0L,
                    syncedLyrics = null,
                    plainLyrics = lyrics,
                    providerName = "Lyrics.ovh",
                    matchScore = 65
                )
            }
        }.getOrNull()
    }

    private suspend fun fetchMusixmatch(title: String, artist: String, apiKey: String): LyricsCandidate? {
        return runCatching {
            val qTrack = URLEncoder.encode(title, "UTF-8")
            val qArtist = URLEncoder.encode(artist, "UTF-8")
            val url = "https://api.musixmatch.com/ws/1.1/matcher.lyrics.get?q_track=$qTrack&q_artist=$qArtist&apikey=$apiKey"
            val req = Request.Builder().url(url).build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val body = JSONObject(resp.body?.string().orEmpty())
                    .optJSONObject("message")
                    ?.optJSONObject("body")
                    ?.optJSONObject("lyrics")
                val text = body?.optString("lyrics_body")?.takeIf { it.isNotBlank() } ?: return@runCatching null
                LyricsCandidate(
                    id = "mm_${title}_$artist",
                    trackName = title,
                    artistName = artist,
                    albumName = "",
                    durationSec = 0L,
                    syncedLyrics = null,
                    plainLyrics = text,
                    providerName = "Musixmatch",
                    matchScore = 70
                )
            }
        }.getOrNull()
    }

    private fun fetchYouTubeTranscriptLyrics(videoId: String, track: Track): LyricsCandidate? {
        return runCatching {
            val params = encodeTranscriptParams(videoId)
            val payload = JSONObject().apply {
                put("params", params)
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB")
                        put("clientVersion", "2.20250312.04.00")
                        put("hl", "en")
                        put("gl", Locale.getDefault().country.takeIf { it.length == 2 } ?: "ID")
                    })
                })
            }
            val jsonType = "application/json; charset=utf-8".toMediaType()
            val req = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/get_transcript?prettyPrint=false")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36")
                .header("Origin", "https://www.youtube.com")
                .header("Referer", "https://www.youtube.com/watch?v=$videoId")
                .header("X-YouTube-Client-Name", "1")
                .header("X-YouTube-Client-Version", "2.20250312.04.00")
                .post(payload.toString().toRequestBody(jsonType))
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string().orEmpty()
                if (!body.startsWith("{")) return@use null
                val syncedLrc = parseYouTubeTranscriptToLrc(JSONObject(body)) ?: return@use null
                LyricsCandidate(
                    id = "yt_transcript_$videoId",
                    trackName = track.title,
                    artistName = track.artist,
                    albumName = track.album,
                    durationSec = track.durationMs / 1000L,
                    syncedLyrics = syncedLrc,
                    plainLyrics = null,
                    providerName = "YouTube Transcript",
                    matchScore = 88
                )
            }
        }.getOrNull()
    }

    companion object {
        internal fun encodeTranscriptParams(videoId: String): String {
            val idBytes = videoId.toByteArray(Charsets.UTF_8)
            val framed = byteArrayOf(0x0a, idBytes.size.toByte()) + idBytes
            return Base64.encodeToString(framed, Base64.NO_WRAP)
        }

        internal fun parseYouTubeTranscriptToLrc(root: JSONObject): String? {
            val lines = mutableListOf<Pair<Long, String>>()

            fun extractTextFromRuns(obj: JSONObject?): String {
                if (obj == null) return ""
                val simple = obj.optString("simpleText").trim()
                if (simple.isNotBlank()) return simple
                val runs = obj.optJSONArray("runs") ?: return ""
                return buildString {
                    for (i in 0 until runs.length()) {
                        append(runs.optJSONObject(i)?.optString("text").orEmpty())
                    }
                }.trim()
            }

            fun walk(node: Any?) {
                when (node) {
                    is JSONObject -> {
                        val seg = node.optJSONObject("transcriptSegmentRenderer")
                        if (seg != null) {
                            val startMs = seg.optString("startMs").toLongOrNull() ?: -1L
                            val txt = extractTextFromRuns(seg.optJSONObject("snippet"))
                                .replace("\n", " ")
                                .trim()
                            if (startMs >= 0L && txt.isNotBlank()) {
                                lines.add(startMs to txt)
                            }
                        }
                        val cue = node.optJSONObject("transcriptCueRenderer")
                        if (cue != null) {
                            val startMs = cue.optString("startOffsetMs").toLongOrNull() ?: -1L
                            val txt = extractTextFromRuns(cue.optJSONObject("cue"))
                                .replace("\n", " ")
                                .trim()
                            if (startMs >= 0L && txt.isNotBlank()) {
                                lines.add(startMs to txt)
                            }
                        }
                        val keys = node.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            if (k != "transcriptSegmentRenderer" && k != "transcriptCueRenderer") {
                                walk(node.opt(k))
                            }
                        }
                    }
                    is JSONArray -> {
                        for (i in 0 until node.length()) walk(node.opt(i))
                    }
                }
            }

            walk(root)
            if (lines.size < 3) return null
            return lines.sortedBy { it.first }.joinToString("\n") { (ms, text) ->
                val totalSec = ms / 1000L
                val min = totalSec / 60L
                val sec = totalSec % 60L
                val centis = (ms % 1000L) / 10L
                String.format(Locale.US, "[%02d:%02d.%02d] %s", min, sec, centis, text)
            }
        }

        fun scoreCandidate(
            normExpectedTitle: String,
            normExpectedArtist: String,
            normExpectedAlbum: String,
            expectedDurationSec: Long,
            candTitle: String,
            candArtist: String,
            candAlbum: String,
            candDurationSec: Long,
            hasSynced: Boolean
        ): Int {
            var score = 0
            val ct = TrackMetadataNormalizer.normalizeTitle(candTitle)
            val ca = TrackMetadataNormalizer.normalizeArtist(candArtist)
            val cal = candAlbum.lowercase().trim()

            if (ct == normExpectedTitle && ct.isNotEmpty()) score += 45
            else if (ct.contains(normExpectedTitle) || normExpectedTitle.contains(ct)) score += 25

            if (ca == normExpectedArtist && ca.isNotEmpty()) score += 30
            else if (normExpectedArtist.isNotEmpty() && (ca.contains(normExpectedArtist) || normExpectedArtist.contains(ca))) score += 18

            if (normExpectedAlbum.isNotEmpty() && cal.isNotEmpty() && (cal == normExpectedAlbum || cal.contains(normExpectedAlbum))) {
                score += 10
            }

            if (expectedDurationSec > 10L && candDurationSec > 10L) {
                val diff = abs(expectedDurationSec - candDurationSec)
                when {
                    diff <= 2L -> score += 20
                    diff <= 5L -> score += 12
                    diff <= 10L -> score += 4
                    else -> score -= 25 // Reject mismatched durations (live/remix vs studio)
                }
            }

            if (hasSynced) score += 8
            return score
        }
    }
}
