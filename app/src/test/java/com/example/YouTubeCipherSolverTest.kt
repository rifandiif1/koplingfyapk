package com.example

import com.example.data.providers.youtube.SigOp
import com.example.data.providers.youtube.YouTubeCipherSolver
import com.example.lyrics.LyricsEngine
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class YouTubeCipherSolverTest {

    @Test
    fun parseSignatureCipher_decodesUrlSAndSp() {
        val cipher = "s=ABC123%3D%3D&sp=sig&url=https%3A%2F%2Frr1.googlevideo.com%2Fvideoplayback%3Fid%3D1"
        val parsed = YouTubeCipherSolver.parseSignatureCipher(cipher)
        assertNotNull(parsed)
        assertEquals("https://rr1.googlevideo.com/videoplayback?id=1", parsed!!.url)
        assertEquals("ABC123==", parsed.s)
        assertEquals("sig", parsed.sp)
    }

    @Test
    fun extractSignatureTimestamp_findsStsFromBaseJs() {
        val sampleJs = """var config={signatureTimestamp:20145,other:1};"""
        assertEquals(20145, YouTubeCipherSolver.extractSignatureTimestamp(sampleJs))
    }

    @Test
    fun extractFastSigOps_andApply_deciphersStandardYouTubeHelper() {
        val sampleJs = """
            var _yt_player={};(function(g){
            var Xy={
                a1:function(a){a.reverse()},
                b2:function(a,b){a.splice(0,b)},
                c3:function(a,b){var c=a[0];a[0]=a[b%a.length];a[b%a.length]=c}
            };
            var zQ=function(a){a=a.split("");Xy.c3(a,2);Xy.a1(a,0);Xy.b2(a,1);return a.join("")};
            c&&d.set("alr","yes");c&&d.set(e,encodeURIComponent(zQ(decodeURIComponent(c))));
            })(_yt_player);
        """.trimIndent()

        val fnName = YouTubeCipherSolver.extractSigFunctionName(sampleJs)
        assertEquals("zQ", fnName)

        val ops = YouTubeCipherSolver.extractFastSigOps(sampleJs, fnName!!)
        assertEquals(listOf(SigOp.Swap(2), SigOp.Reverse, SigOp.Splice(1)), ops)

        // Input: "abcde" -> swap(2): "cbade" -> reverse: "edabc" -> splice(1): "dabc"
        assertEquals("dabc", YouTubeCipherSolver.applyFastSigOps("abcde", ops))

        val instrumented = YouTubeCipherSolver.instrumentBaseJsForWebView(sampleJs, fnName, "nArr[0]")
        assertTrue(instrumented.contains("window.__koplingfySig=zQ"))
        assertTrue(instrumented.contains("window.__koplingfyN=nArr[0]"))
    }

    @Test
    fun extractNFunctionExpression_findsNTransformHook() {
        val sampleJs = """var k=a.get("n"))&&(b=nTransform[0](b),a.set("n",b))"""
        assertEquals("nTransform[0]", YouTubeCipherSolver.extractNFunctionExpression(sampleJs))
    }

    @Test
    fun generateCpn_returns16ValidUrlSafeChars() {
        val cpn = YouTubeCipherSolver.generateCpn()
        assertEquals(16, cpn.length)
        assertTrue(Regex("""^[A-Za-z0-9_-]{16}$""").matches(cpn))
    }

    @Test
    fun parseYouTubeTranscriptToLrc_convertsSegmentsToSyncedLrc() {
        val encoded = LyricsEngine.encodeTranscriptParams("dQw4w9WgXcQ")
        assertTrue(encoded.isNotBlank())

        val transcriptJson = JSONObject(
            """
            {
              "actions": [
                {
                  "updateEngagementPanelAction": {
                    "content": {
                      "transcriptRenderer": {
                        "body": {
                          "transcriptBodyRenderer": {
                            "cueGroups": [
                              {
                                "transcriptSegmentRenderer": {
                                  "startMs": "12500",
                                  "snippet": { "runs": [ { "text": "We're no strangers to love" } ] }
                                }
                              },
                              {
                                "transcriptSegmentRenderer": {
                                  "startMs": "16250",
                                  "snippet": { "runs": [ { "text": "You know the rules and so do I" } ] }
                                }
                              },
                              {
                                "transcriptSegmentRenderer": {
                                  "startMs": "20100",
                                  "snippet": { "runs": [ { "text": "A full commitment's what I'm thinking of" } ] }
                                }
                              }
                            ]
                          }
                        }
                      }
                    }
                  }
                }
              ]
            }
            """.trimIndent()
        )
        val lrc = LyricsEngine.parseYouTubeTranscriptToLrc(transcriptJson)
        assertNotNull(lrc)
        assertTrue(lrc!!.contains("[00:12.50] We're no strangers to love"))
        assertTrue(lrc.contains("[00:16.25] You know the rules and so do I"))
        assertTrue(lrc.contains("[00:20.10] A full commitment's what I'm thinking of"))
    }

    @Test
    fun metrolistClientPayload_includesSignatureTimestampAndCpnForWebRemix() {
        val payload = com.example.data.providers.youtube.YouTubeClient.WEB_REMIX.buildPlayerPayload(
            videoId = "dQw4w9WgXcQ",
            cpn = "AbCdEfGh12345678",
            regionCode = "ID",
            visitorData = "CgtSampleVisitor",
            signatureTimestamp = 20150
        )
        assertEquals("dQw4w9WgXcQ", payload.getString("videoId"))
        assertEquals("AbCdEfGh12345678", payload.getString("cpn"))
        assertEquals(
            20150,
            payload.getJSONObject("playbackContext")
                .getJSONObject("contentPlaybackContext")
                .getInt("signatureTimestamp")
        )
        assertEquals("WEB_REMIX", payload.getJSONObject("context").getJSONObject("client").getString("clientName"))
    }

    @Test
    fun ytPlayerUtils_rankAudioFormats_prefersOpusAndSupportsCipherWhenEnabled() {
        val utils = com.example.data.providers.youtube.YTPlayerUtils(
            httpClient = okhttp3.OkHttpClient(),
            cipherSolver = YouTubeCipherSolver(null, okhttp3.OkHttpClient())
        )
        val formats = org.json.JSONArray().apply {
            put(JSONObject().apply {
                put("itag", 140)
                put("mimeType", "audio/mp4; codecs=\"mp4a.40.2\"")
                put("bitrate", 128000)
                put("contentLength", "3500000")
                put("url", "https://rr1.googlevideo.com/videoplayback?itag=140")
            })
            put(JSONObject().apply {
                put("itag", 251)
                put("mimeType", "audio/webm; codecs=\"opus\"")
                put("bitrate", 160000)
                put("contentLength", "4200000")
                put("url", "https://rr1.googlevideo.com/videoplayback?itag=251")
            })
            put(JSONObject().apply {
                put("itag", 250)
                put("mimeType", "audio/webm; codecs=\"opus\"")
                put("bitrate", 70000)
                put("signatureCipher", "s=abc&sp=sig&url=https%3A%2F%2Frr1.googlevideo.com%2Fvideoplayback")
            })
        }

        val rankedHigh = utils.rankAudioFormats(
            formats = formats,
            audioQuality = com.example.data.providers.youtube.MetrolistAudioQuality.HIGH,
            allowCipher = true
        )
        assertEquals(3, rankedHigh.size)
        assertEquals(251, rankedHigh.first().itag)
        assertEquals(4_200_000L, rankedHigh.first().contentLength)

        val internalUri = com.example.data.providers.youtube.YTPlayerUtils.buildInternalTrackUri("dQw4w9WgXcQ")
        assertEquals("koplingfy://ytm/dQw4w9WgXcQ", internalUri)
    }
}
