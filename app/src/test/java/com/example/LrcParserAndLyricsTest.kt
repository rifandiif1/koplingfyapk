package com.example

import com.example.lyrics.LrcParser
import com.example.lyrics.LyricsEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserAndLyricsTest {

    @Test
    fun parseStandardSyncedLrcAndFindActiveLine() {
        val raw = """
            [offset:0]
            [00:05.00]First line of audio
            [00:10.50]Second line plays now
            [00:20.00]Chorus begins
        """.trimIndent()

        val parsed = LrcParser.parse(raw, null, providerName = "LRCLIB")
        assertTrue(parsed.isSynced)
        assertEquals(3, parsed.lines.size)
        assertEquals(5_000L, parsed.lines[0].timeMs)
        assertEquals(10_500L, parsed.lines[1].timeMs)
        assertEquals(0, LrcParser.findActiveLineIndex(parsed.lines, 6_000L, 0L))
        assertEquals(1, LrcParser.findActiveLineIndex(parsed.lines, 15_000L, 0L))
        assertEquals(2, LrcParser.findActiveLineIndex(parsed.lines, 25_000L, 0L))
    }

    @Test
    fun parseEnhancedWordLevelLrc() {
        val raw = "[00:12.00]<00:12.00>Hello <00:12.50>Koplingfy <00:13.00>world"
        val parsed = LrcParser.parse(raw, null)
        assertTrue(parsed.isSynced)
        assertEquals(1, parsed.lines.size)
        assertEquals("Hello Koplingfy world", parsed.lines[0].text)
        assertEquals(3, parsed.lines[0].words.size)
        assertEquals(12_500L, parsed.lines[0].words[1].timeMs)
    }

    @Test
    fun mergeTranslatedLinesPreservesExactTimestamps() {
        val original = LrcParser.parse("[00:02.00]Hello world\n[00:05.50]Good night", null)
        val translatedLrc = "[00:02.00]Halo dunia\n[00:05.50]Selamat malam"
        val merged = LrcParser.mergeTranslatedLines(original, translatedLrc, null)

        assertEquals(2_000L, merged.lines[0].timeMs)
        assertEquals("Halo dunia", merged.lines[0].translatedText)
        assertEquals(5_500L, merged.lines[1].timeMs)
        assertEquals("Selamat malam", merged.lines[1].translatedText)
    }

    @Test
    fun lyricsCandidateScoringRejectsWrongDuration() {
        val exactScore = LyricsEngine.scoreCandidate(
            normExpectedTitle = "midnight city",
            normExpectedArtist = "m83",
            normExpectedAlbum = "hurry up",
            expectedDurationSec = 243,
            candTitle = "Midnight City",
            candArtist = "M83",
            candAlbum = "Hurry Up, We're Dreaming",
            candDurationSec = 244,
            hasSynced = true
        )
        val wrongDurationScore = LyricsEngine.scoreCandidate(
            normExpectedTitle = "midnight city",
            normExpectedArtist = "m83",
            normExpectedAlbum = "hurry up",
            expectedDurationSec = 243,
            candTitle = "Midnight City (Extended Remix)",
            candArtist = "Unknown DJ",
            candAlbum = "Club Mix",
            candDurationSec = 420,
            hasSynced = false
        )
        assertTrue(exactScore >= 90)
        assertFalse(wrongDurationScore >= 60)
    }
}
