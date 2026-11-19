package com.example.lyrics

import com.example.data.local.db.LyricsDao
import com.example.data.local.db.TranslatedLyricsEntity
import com.example.data.local.prefs.AppSettings
import com.example.domain.model.TranslationProviderType
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Lyrics Translation Engine supporting:
 * 1. ML Kit On-Device Translation (Free, offline-capable after model download)
 * 2. Google Cloud Translation API (optional when API key is configured)
 * 3. LibreTranslate / Custom Translation Endpoint
 *
 * Strictly preserves LRC [mm:ss.xx] timestamps and caches translations in Room by (trackId, targetLang).
 */
class LyricsTranslationEngine(
    private val httpClient: OkHttpClient,
    private val lyricsDao: LyricsDao
) {
    suspend fun translateLyrics(
        trackId: String,
        parsed: ParsedLyrics,
        targetLangCode: String,
        settings: AppSettings
    ): Result<ParsedLyrics> = withContext(Dispatchers.IO) {
        if (parsed.lines.isEmpty()) return@withContext Result.success(parsed)

        // 1. Always check Room cache first so cached translations are never re-translated
        val cached = lyricsDao.getTranslation(trackId, targetLangCode)
        if (cached != null) {
            return@withContext Result.success(
                LrcParser.mergeTranslatedLines(
                    original = parsed,
                    translatedSyncedLrc = cached.translatedSyncedLrc,
                    translatedPlain = cached.translatedPlainLyrics
                )
            )
        }

        // 2. Translate line-by-line while preserving exact LRC timestamps
        val nonEmptyLines = parsed.lines.map { it.text }
        val translatedTextsResult: Result<Pair<List<String>, String>> = when (settings.translationProvider) {
            TranslationProviderType.ML_KIT -> translateWithMlKit(nonEmptyLines, targetLangCode)
            TranslationProviderType.GOOGLE_CLOUD -> {
                val key = settings.googleTranslateApiKey.trim()
                if (key.isBlank()) {
                    translateWithMlKit(nonEmptyLines, targetLangCode)
                } else {
                    translateWithGoogleCloud(nonEmptyLines, targetLangCode, key)
                }
            }
            TranslationProviderType.LIBRE_TRANSLATE -> {
                translateWithLibreTranslate(nonEmptyLines, targetLangCode, settings.libreTranslateUrl)
            }
        }

        val (translatedTexts, providerLabel) = translatedTextsResult.getOrElse { err ->
            return@withContext Result.failure(err)
        }

        val updatedLines = parsed.lines.mapIndexed { idx, line ->
            line.copy(translatedText = translatedTexts.getOrNull(idx) ?: line.text)
        }

        // Build timestamp-preserving translated LRC string for cache
        val translatedSyncedLrc = if (parsed.isSynced) {
            updatedLines.joinToString("\n") { line ->
                "${LrcParser.formatTimestamp(line.timeMs)}${line.translatedText.orEmpty()}"
            }
        } else null

        val translatedPlain = updatedLines.joinToString("\n") { it.translatedText.orEmpty() }

        lyricsDao.saveTranslation(
            TranslatedLyricsEntity(
                trackId = trackId,
                targetLanguage = targetLangCode,
                translatedSyncedLrc = translatedSyncedLrc,
                translatedPlainLyrics = translatedPlain,
                providerName = providerLabel
            )
        )

        Result.success(parsed.copy(lines = updatedLines))
    }

    private suspend fun translateWithMlKit(
        lines: List<String>,
        targetLangCode: String
    ): Result<Pair<List<String>, String>> = runCatching {
        val targetMlKit = mapToMlKitLanguage(targetLangCode)
        val sourceMlKit = detectLikelySourceLanguage(lines, targetMlKit)

        if (sourceMlKit == targetMlKit) {
            return@runCatching lines to "ML Kit (Same Language)"
        }

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceMlKit)
            .setTargetLanguage(targetMlKit)
            .build()
        val translator = Translation.getClient(options)
        try {
            val conditions = DownloadConditions.Builder().build()
            translator.downloadModelIfNeeded(conditions).await()
            val out = lines.map { line ->
                if (line.isBlank()) "" else translator.translate(line).await()
            }
            out to "ML Kit On-Device"
        } finally {
            translator.close()
        }
    }

    private suspend fun translateWithGoogleCloud(
        lines: List<String>,
        targetLangCode: String,
        apiKey: String
    ): Result<Pair<List<String>, String>> = runCatching {
        val out = mutableListOf<String>()
        // Batch in chunks of 25 lines to respect query limits while keeping timestamp alignment
        lines.chunked(25).forEach { chunk ->
            val payload = JSONObject().apply {
                put("q", org.json.JSONArray(chunk))
                put("target", targetLangCode)
                put("format", "text")
            }
            val url = "https://translation.googleapis.com/language/translate/v2?key=${URLEncoder.encode(apiKey, "UTF-8")}"
            val req = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("Google Cloud Translate HTTP ${resp.code}")
                val arr = JSONObject(resp.body?.string().orEmpty())
                    .getJSONObject("data")
                    .getJSONArray("translations")
                for (i in 0 until arr.length()) {
                    out.add(arr.getJSONObject(i).optString("translatedText"))
                }
            }
        }
        out to "Google Cloud Translate"
    }

    private suspend fun translateWithLibreTranslate(
        lines: List<String>,
        targetLangCode: String,
        endpointUrl: String
    ): Result<Pair<List<String>, String>> = runCatching {
        val joined = lines.joinToString("\n")
        val payload = JSONObject().apply {
            put("q", joined)
            put("source", "auto")
            put("target", targetLangCode)
            put("format", "text")
        }
        val req = Request.Builder()
            .url(endpointUrl)
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("LibreTranslate HTTP ${resp.code}")
            val translated = JSONObject(resp.body?.string().orEmpty()).optString("translatedText")
            val split = translated.lines()
            val aligned = lines.indices.map { i -> split.getOrNull(i) ?: lines[i] }
            aligned to "LibreTranslate"
        }
    }

    private fun mapToMlKitLanguage(code: String): String = when (code.lowercase()) {
        "id" -> TranslateLanguage.INDONESIAN
        "en" -> TranslateLanguage.ENGLISH
        "ja" -> TranslateLanguage.JAPANESE
        "ko" -> TranslateLanguage.KOREAN
        "zh" -> TranslateLanguage.CHINESE
        "es" -> TranslateLanguage.SPANISH
        "fr" -> TranslateLanguage.FRENCH
        "de" -> TranslateLanguage.GERMAN
        "ar" -> TranslateLanguage.ARABIC
        "pt" -> TranslateLanguage.PORTUGUESE
        else -> TranslateLanguage.ENGLISH
    }

    private fun detectLikelySourceLanguage(lines: List<String>, targetMlKit: String): String {
        val sample = lines.take(10).joinToString(" ")
        val hasJapanese = sample.any { it in '\u3040'..'\u30FF' }
        val hasKorean = sample.any { it in '\uAC00'..'\uD7AF' }
        val hasChinese = sample.any { it in '\u4E00'..'\u9FFF' }
        val hasArabic = sample.any { it in '\u0600'..'\u06FF' }
        return when {
            hasJapanese -> TranslateLanguage.JAPANESE
            hasKorean -> TranslateLanguage.KOREAN
            hasChinese -> TranslateLanguage.CHINESE
            hasArabic -> TranslateLanguage.ARABIC
            targetMlKit == TranslateLanguage.ENGLISH -> TranslateLanguage.INDONESIAN
            else -> TranslateLanguage.ENGLISH
        }
    }
}
