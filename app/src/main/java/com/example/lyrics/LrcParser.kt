package com.example.lyrics

data class WordTimestamp(
    val timeMs: Long,
    val word: String
)

data class LyricLine(
    val timeMs: Long,
    val text: String,
    val words: List<WordTimestamp> = emptyList(),
    val translatedText: String? = null
)

data class ParsedLyrics(
    val lines: List<LyricLine>,
    val isSynced: Boolean,
    val offsetMs: Long = 0L,
    val rawSyncedLrc: String? = null,
    val rawPlainLyrics: String? = null,
    val providerName: String = ""
)

object LrcParser {
    private val lineTimestampRegex = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?\]""")
    private val wordTimestampRegex = Regex("""<(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?>([^<]*)""")
    private val offsetTagRegex = Regex("""\[offset:\s*([+-]?\d+)\s*\]""", RegexOption.IGNORE_CASE)

    fun parse(
        syncedLrc: String?,
        plainLyrics: String?,
        savedOffsetMs: Long = 0L,
        providerName: String = ""
    ): ParsedLyrics {
        if (!syncedLrc.isNullOrBlank()) {
            val lrcOffset = offsetTagRegex.find(syncedLrc)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
            val parsedLines = mutableListOf<LyricLine>()

            syncedLrc.lineSequence().forEach { rawLine ->
                val matches = lineTimestampRegex.findAll(rawLine).toList()
                if (matches.isNotEmpty()) {
                    val contentAfterTags = rawLine.substring(matches.last().range.last + 1).trim()
                    val wordMatches = wordTimestampRegex.findAll(contentAfterTags).toList()
                    val words = wordMatches.mapNotNull { wm ->
                        val wTime = toMillis(wm.groupValues[1], wm.groupValues[2], wm.groupValues[3])
                        val wordText = wm.groupValues[4].trim()
                        if (wordText.isNotEmpty()) WordTimestamp(wTime, wordText) else null
                    }
                    val cleanText = if (words.isNotEmpty()) {
                        words.joinToString(" ") { it.word }
                    } else {
                        contentAfterTags
                    }
                    matches.forEach { m ->
                        val tMs = toMillis(m.groupValues[1], m.groupValues[2], m.groupValues[3])
                        parsedLines.add(LyricLine(timeMs = tMs, text = cleanText, words = words))
                    }
                }
            }

            if (parsedLines.isNotEmpty()) {
                val sorted = parsedLines.sortedBy { it.timeMs }
                return ParsedLyrics(
                    lines = sorted,
                    isSynced = true,
                    offsetMs = savedOffsetMs + lrcOffset,
                    rawSyncedLrc = syncedLrc,
                    rawPlainLyrics = plainLyrics,
                    providerName = providerName
                )
            }
        }

        if (!plainLyrics.isNullOrBlank()) {
            val plainLines = plainLyrics.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { LyricLine(timeMs = -1L, text = it) }
            return ParsedLyrics(
                lines = plainLines,
                isSynced = false,
                offsetMs = 0L,
                rawSyncedLrc = null,
                rawPlainLyrics = plainLyrics,
                providerName = providerName
            )
        }

        return ParsedLyrics(
            lines = emptyList(),
            isSynced = false,
            providerName = providerName
        )
    }

    fun findActiveLineIndex(lines: List<LyricLine>, positionMs: Long, offsetMs: Long): Int {
        if (lines.isEmpty()) return -1
        val adjusted = positionMs + offsetMs
        var active = -1
        for (i in lines.indices) {
            if (lines[i].timeMs in 0..adjusted) {
                active = i
            } else if (lines[i].timeMs > adjusted) {
                break
            }
        }
        return active
    }

    fun formatTimestamp(timeMs: Long): String {
        val safe = timeMs.coerceAtLeast(0L)
        val minutes = safe / 60_000L
        val seconds = (safe % 60_000L) / 1_000L
        val centis = (safe % 1_000L) / 10L
        return "[%02d:%02d.%02d]".format(minutes, seconds, centis)
    }

    fun serializeToLrc(lines: List<LyricLine>, title: String = "", artist: String = ""): String {
        val sb = StringBuilder()
        if (title.isNotBlank()) sb.append("[ti:").append(title).append("]\n")
        if (artist.isNotBlank()) sb.append("[ar:").append(artist).append("]\n")
        sb.append("[by:Koplingfy]\n")
        lines.forEach { line ->
            if (line.timeMs >= 0L) {
                sb.append(formatTimestamp(line.timeMs)).append(line.text).append('\n')
            } else {
                sb.append(line.text).append('\n')
            }
        }
        return sb.toString()
    }

    fun mergeTranslatedLines(
        original: ParsedLyrics,
        translatedSyncedLrc: String?,
        translatedPlain: String?
    ): ParsedLyrics {
        if (original.lines.isEmpty()) return original
        if (original.isSynced && !translatedSyncedLrc.isNullOrBlank()) {
            val transParsed = parse(translatedSyncedLrc, null)
            val timeMap = transParsed.lines.associateBy { it.timeMs }
            val merged = original.lines.mapIndexed { idx, line ->
                val tr = timeMap[line.timeMs]?.text ?: transParsed.lines.getOrNull(idx)?.text
                line.copy(translatedText = tr)
            }
            return original.copy(lines = merged)
        }
        if (!translatedPlain.isNullOrBlank()) {
            val transLines = translatedPlain.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val merged = original.lines.mapIndexed { idx, line ->
                line.copy(translatedText = transLines.getOrNull(idx))
            }
            return original.copy(lines = merged)
        }
        return original
    }

    private fun toMillis(minStr: String, secStr: String, fracStr: String): Long {
        val min = minStr.toLongOrNull() ?: 0L
        val sec = secStr.toLongOrNull() ?: 0L
        val ms = when (fracStr.length) {
            0 -> 0L
            1 -> (fracStr.toLongOrNull() ?: 0L) * 100L
            2 -> (fracStr.toLongOrNull() ?: 0L) * 10L
            else -> fracStr.take(3).toLongOrNull() ?: 0L
        }
        return min * 60_000L + sec * 1_000L + ms
    }
}
