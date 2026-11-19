package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.db.KoplingfyDatabase
import com.example.data.local.db.LyricsEntity
import com.example.data.local.db.PlaylistEntity
import com.example.data.local.db.PlaylistTrackEntity
import com.example.data.local.db.TrackEntity
import com.example.data.local.db.TranslatedLyricsEntity
import com.example.domain.model.MusicSource
import com.example.domain.model.Track
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    private lateinit var context: Context
    private lateinit var db: KoplingfyDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, KoplingfyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun appBrandingIsKoplingfyByRifandi() {
        assertEquals("Koplingfy", context.getString(R.string.app_name))
        assertEquals("RIFANDI", context.getString(R.string.developer_name))
    }

    @Test
    fun roomPersistsTracksPlaylistsFavoritesAndLyricsCache() = runTest {
        val track = Track(
            id = "audius_test_1",
            title = "Midnight Echo",
            artist = "Koplingfy Studio",
            album = "Hi-Fi Sessions",
            durationMs = 198_000L,
            source = MusicSource.AUDIUS,
            isFavorite = true
        )
        db.trackDao().upsertTrack(TrackEntity.fromDomain(track))

        val favs = db.trackDao().observeFavorites().first()
        assertEquals(1, favs.size)
        assertEquals("Midnight Echo", favs.first().title)

        // Playlist relation
        db.playlistDao().upsertPlaylist(
            PlaylistEntity("pl_1", "Late Night", "Chill mix", null, MusicSource.LOCAL.name, 1000L)
        )
        db.playlistDao().addTrackToPlaylist(PlaylistTrackEntity("pl_1", track.id, 0, 1000L))
        val plTracks = db.playlistDao().observePlaylistTracks("pl_1").first()
        assertEquals(1, plTracks.size)

        // Lyrics + Translation cache
        db.lyricsDao().saveLyrics(
            LyricsEntity(track.id, track.title, track.artist, "[00:01.00]Echoes in the dark", null, "LRCLIB")
        )
        db.lyricsDao().saveTranslation(
            TranslatedLyricsEntity(track.id, "id", "[00:01.00]Gema dalam gelap", null, "ML Kit")
        )
        assertNotNull(db.lyricsDao().getLyrics(track.id))
        assertEquals("[00:01.00]Gema dalam gelap", db.lyricsDao().getTranslation(track.id, "id")?.translatedSyncedLrc)
    }
}
