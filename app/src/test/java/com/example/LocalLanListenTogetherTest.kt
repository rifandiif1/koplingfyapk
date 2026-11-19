package com.example

import com.example.data.remote.LocalLanListenTogetherEngine
import com.example.domain.model.MusicSource
import com.example.domain.model.Track
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LocalLanListenTogetherTest {

    @Test
    fun encodeAndDecodeRoomCode_roundTripsIpv4AndPort() {
        val code = LocalLanListenTogetherEngine.encodeRoomCode("192.168.43.1", 48721)
        assertTrue(code.startsWith("W"))
        assertTrue(code.length in 7..12)

        val decoded = LocalLanListenTogetherEngine.decodeRoomCode(code)
        assertEquals("192.168.43.1" to 48721, decoded)

        val rawEndpoint = LocalLanListenTogetherEngine.decodeRoomCode("10.0.2.15:12345")
        assertEquals("10.0.2.15" to 12345, rawEndpoint)

        assertNull(LocalLanListenTogetherEngine.decodeRoomCode("INVALID"))
    }

    @Test
    fun localLanHostAndClient_joinAndSyncPlaybackOverLoopback() = runBlocking {
        val host = LocalLanListenTogetherEngine()
        val guest = LocalLanListenTogetherEngine()
        try {
            val initialTrack = Track(
                id = "ytm_dQw4w9WgXcQ",
                title = "Gunslinger",
                artist = "Avenged Sevenfold",
                album = "Avenged Sevenfold",
                durationMs = 251_000L,
                source = MusicSource.YOUTUBE_MUSIC,
                sourceTrackId = "dQw4w9WgXcQ"
            )

            val hostResult = host.startHostRoom(
                username = "Rifandi",
                roomName = "Nongkrong",
                currentTrack = initialTrack,
                isPlaying = true,
                positionMs = 42_000L,
                preferredPort = 0,
                explicitHostIp = "127.0.0.1"
            )
            assertTrue("Host should start cleanly: ${hostResult.exceptionOrNull()}", hostResult.isSuccess)
            val roomCode = hostResult.getOrThrow().roomCode

            val joinResult = guest.joinLanRoom(roomCode, "Budi")
            assertTrue("Guest should join cleanly: ${joinResult.exceptionOrNull()}", joinResult.isSuccess)
            val joinedRoom = joinResult.getOrThrow()
            assertEquals("Gunslinger", joinedRoom.trackTitle)
            assertEquals(2, joinedRoom.memberUids.size)
            assertTrue(joinedRoom.memberNames.values.contains("Rifandi"))
            assertTrue(joinedRoom.memberNames.values.contains("Budi"))

            val nextTrack = initialTrack.copy(id = "ytm_abc12345678", title = "So Far Away")
            val syncRes = guest.syncPlaybackState(
                username = "Budi",
                track = nextTrack,
                playing = true,
                positionMs = 10_000L
            )
            assertTrue(syncRes.isSuccess)

            // Verify host state reflects guest's track update
            val refreshed = guest.joinLanRoom(roomCode, "Budi").getOrNull()
            assertNotNull(refreshed)
            assertEquals("So Far Away", refreshed!!.trackTitle)
            assertEquals("Budi", refreshed.updatedByUsername)
        } finally {
            guest.leaveOrClose()
            host.leaveOrClose()
        }
    }
}
