package com.example

import com.example.data.providers.YouTubeMusicProvider
import com.example.domain.model.MusicSource
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class YouTubeMusicProviderTest {

    private val provider = YouTubeMusicProvider(
        httpClient = OkHttpClient(),
        customInstanceProvider = { "" }
    )

    @Test
    fun parseDurationTextToMs_parsesMinutesAndHoursCorrectly() {
        assertEquals(225_000L, provider.parseDurationTextToMs("3:45"))
        assertEquals(3_665_000L, provider.parseDurationTextToMs("1:01:05"))
        assertEquals(0L, provider.parseDurationTextToMs("Song"))
    }

    @Test
    fun pickBestDirectAudioFormat_prefersNonDrmM4aAndSkipsDrmOrVideo() {
        val formats = JSONArray().apply {
            // Video-only format (must be ignored)
            put(JSONObject().apply {
                put("mimeType", "video/mp4; codecs=\"avc1.640028\"")
                put("url", "https://rr1.googlevideo.com/videoonly")
                put("bitrate", 2_000_000)
            })
            // DRM-protected high-bitrate audio (must be skipped)
            put(JSONObject().apply {
                put("mimeType", "audio/mp4; codecs=\"mp4a.40.2\"")
                put("url", "https://rr1.googlevideo.com/drm_audio")
                put("bitrate", 256_000)
                put("drmFamilies", JSONArray().put("WIDEVINE"))
            })
            // Opus audio
            put(JSONObject().apply {
                put("mimeType", "audio/webm; codecs=\"opus\"")
                put("url", "https://rr1.googlevideo.com/opus_160")
                put("bitrate", 160_000)
            })
            // Direct M4A audio (should win due to universal MediaCodec preference)
            put(JSONObject().apply {
                put("mimeType", "audio/mp4; codecs=\"mp4a.40.2\"")
                put("url", "https://rr1.googlevideo.com/m4a_128")
                put("bitrate", 128_000)
            })
        }

        val best = provider.pickBestDirectAudioFormat(formats)
        assertNotNull(best)
        assertEquals("https://rr1.googlevideo.com/m4a_128", best!!.first)
        assertEquals("audio/mp4", best.second)
    }

    @Test
    fun pickBestDirectAudioFormat_returnsNullWhenOnlyCipheredStreamsPresent() {
        val formats = JSONArray().apply {
            put(JSONObject().apply {
                put("mimeType", "audio/mp4; codecs=\"mp4a.40.2\"")
                put("signatureCipher", "s=encrypted&sp=sig&url=https%3A%2F%2Fexample.com")
                put("bitrate", 128_000)
            })
        }
        assertNull(provider.pickBestDirectAudioFormat(formats))
    }

    @Test
    fun extractTracksFromInnerTubeJson_extractsTracksWithMetadata() {
        val sampleJson = JSONObject(
            """
            {
              "contents": {
                "sectionListRenderer": {
                  "contents": [
                    {
                      "musicResponsiveListItemRenderer": {
                        "playlistItemData": { "videoId": "dQw4w9WgXcQ" },
                        "thumbnail": {
                          "musicThumbnailRenderer": {
                            "thumbnail": {
                              "thumbnails": [
                                { "url": "https://lh3.googleusercontent.com/sample-thumb" }
                              ]
                            }
                          }
                        },
                        "flexColumns": [
                          {
                            "musicResponsiveListItemFlexColumnRenderer": {
                              "text": { "runs": [ { "text": "Never Gonna Give You Up" } ] }
                            }
                          },
                          {
                            "musicResponsiveListItemFlexColumnRenderer": {
                              "text": {
                                "runs": [
                                  { "text": "Song" },
                                  { "text": " • " },
                                  { "text": "Rick Astley" },
                                  { "text": " • " },
                                  { "text": "Whenever You Need Somebody" },
                                  { "text": " • " },
                                  { "text": "3:33" }
                                ]
                              }
                            }
                          }
                        ]
                      }
                    }
                  ]
                }
              }
            }
            """.trimIndent()
        )

        val tracks = provider.extractTracksFromInnerTubeJson(sampleJson)
        assertEquals(1, tracks.size)
        val track = tracks.first()
        assertEquals("ytm_dQw4w9WgXcQ", track.id)
        assertEquals("dQw4w9WgXcQ", track.sourceTrackId)
        assertEquals("Never Gonna Give You Up", track.title)
        assertEquals("Rick Astley", track.artist)
        assertEquals("Whenever You Need Somebody", track.album)
        assertEquals(213_000L, track.durationMs)
        assertEquals(MusicSource.YOUTUBE_MUSIC, track.source)
        assertEquals(false, track.isDownloadable)
    }
}
