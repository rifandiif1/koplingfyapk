package com.example

import com.example.data.providers.TrackMetadataNormalizer
import com.example.domain.model.MusicSource
import com.example.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackDeduplicationTest {

    @Test
    fun normalizeStripsOfficialVideoAndFeaturingNoise() {
        assertEquals(
            "blinding lights",
            TrackMetadataNormalizer.normalizeTitle("Blinding Lights (Official Audio) [HQ]")
        )
        assertEquals(
            "starboy",
            TrackMetadataNormalizer.normalizeTitle("Starboy feat. Daft Punk")
        )
    }

    @Test
    fun deduplicateCrossProviderTracksRespectingSourcePriority() {
        val yt = Track(
            id = "ytm_1",
            title = "Neon Drift (Official Music Video)",
            artist = "Synthwave Lab",
            durationMs = 210_000L,
            source = MusicSource.YOUTUBE_MUSIC
        )
        val audius = Track(
            id = "audius_1",
            title = "Neon Drift",
            artist = "Synthwave Lab",
            durationMs = 212_000L,
            source = MusicSource.AUDIUS
        )
        val distinct = Track(
            id = "local_1",
            title = "Acoustic Sunrise",
            artist = "Rifandi",
            durationMs = 185_000L,
            source = MusicSource.LOCAL
        )

        val result = TrackMetadataNormalizer.deduplicateTracks(
            tracks = listOf(yt, audius, distinct),
            sourcePriority = listOf(MusicSource.AUDIUS, MusicSource.YOUTUBE_MUSIC, MusicSource.LOCAL)
        )

        assertEquals(2, result.size)
        assertEquals(MusicSource.AUDIUS, result[0].source)
        assertEquals("local_1", result[1].id)
    }
}
