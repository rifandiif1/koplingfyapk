package com.example.data.providers.youtube

import org.json.JSONObject

/**
 * YouTube InnerTube client profiles modeled directly after `MetrolistGroup/Metrolist`'s
 * `YouTubeClient.kt` configuration for `/youtubei/v1/player` playback resolution.
 */
data class YouTubeClient(
    val name: String,
    val clientName: String,
    val clientVersion: String,
    val clientId: String,
    val userAgent: String,
    val endpoint: String = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
    val origin: String? = null,
    val osName: String? = null,
    val osVersion: String? = null,
    val deviceMake: String? = null,
    val deviceModel: String? = null,
    val androidSdkVersion: Int? = null,
    val embedUrl: String? = null,
    val useSignatureTimestamp: Boolean = false,
    val requiresCipher: Boolean = false,
    val sendVisitorData: Boolean = false,
    val preferHls: Boolean = false
) {
    fun buildPlayerPayload(
        videoId: String,
        cpn: String,
        regionCode: String,
        visitorData: String? = null,
        signatureTimestamp: Int? = null
    ): JSONObject = JSONObject().apply {
        put("videoId", videoId)
        put("cpn", cpn)
        put("contentCheckOk", true)
        put("racyCheckOk", true)
        if (useSignatureTimestamp && signatureTimestamp != null) {
            put("playbackContext", JSONObject().apply {
                put("contentPlaybackContext", JSONObject().apply {
                    put("signatureTimestamp", signatureTimestamp)
                })
            })
        }
        put("context", JSONObject().apply {
            put("client", JSONObject().apply {
                put("clientName", clientName)
                put("clientVersion", clientVersion)
                put("hl", "en")
                put("gl", regionCode)
                osName?.let { put("osName", it) }
                osVersion?.let { put("osVersion", it) }
                deviceMake?.let { put("deviceMake", it) }
                deviceModel?.let { put("deviceModel", it) }
                androidSdkVersion?.let { put("androidSdkVersion", it) }
                if (sendVisitorData && !visitorData.isNullOrBlank()) {
                    put("visitorData", visitorData)
                }
            })
            if (embedUrl != null) {
                put("thirdParty", JSONObject().apply {
                    put("embedUrl", embedUrl)
                })
            }
        })
    }

    companion object {
        private const val CHROME_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"

        val ANDROID_VR_1_65 = YouTubeClient(
            name = "ANDROID_VR 1.65",
            clientName = "ANDROID_VR",
            clientVersion = "1.65.10",
            clientId = "28",
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            osName = "Android",
            osVersion = "12L",
            deviceMake = "Oculus",
            deviceModel = "Quest 3",
            androidSdkVersion = 32
        )

        val ANDROID_VR_1_71 = YouTubeClient(
            name = "ANDROID_VR 1.71",
            clientName = "ANDROID_VR",
            clientVersion = "1.71.26",
            clientId = "28",
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.71.26 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            osName = "Android",
            osVersion = "12L",
            deviceMake = "Oculus",
            deviceModel = "Quest 3",
            androidSdkVersion = 32
        )

        val VISIONOS = YouTubeClient(
            name = "VISIONOS",
            clientName = "VISIONOS",
            clientVersion = "1.02",
            clientId = "101",
            userAgent = "com.google.ios.youtube/1.02 (RealityDevice17,1; U; CPU visionOS 26_5 like Mac OS X;)",
            osName = "visionOS",
            osVersion = "26.5",
            deviceMake = "Apple",
            deviceModel = "RealityDevice17,1"
        )

        /** Metrolist MAIN_CLIENT for YouTube Music web streaming (uses live `sts` + cipher solver). */
        val WEB_REMIX = YouTubeClient(
            name = "WEB_REMIX",
            clientName = "WEB_REMIX",
            clientVersion = "1.20250310.01.00",
            clientId = "67",
            userAgent = CHROME_UA,
            endpoint = "https://music.youtube.com/youtubei/v1/player?prettyPrint=false",
            origin = "https://music.youtube.com",
            useSignatureTimestamp = true,
            requiresCipher = true,
            sendVisitorData = true
        )

        val TVHTML5_SIMPLY_EMBEDDED_PLAYER = YouTubeClient(
            name = "TVHTML5_EMBEDDED",
            clientName = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
            clientVersion = "2.0",
            clientId = "85",
            userAgent = CHROME_UA,
            origin = "https://www.youtube.com",
            embedUrl = "https://www.youtube.com/",
            useSignatureTimestamp = true,
            requiresCipher = true,
            sendVisitorData = true
        )

        val TVHTML5 = YouTubeClient(
            name = "TVHTML5",
            clientName = "TVHTML5",
            clientVersion = "7.20250312.16.00",
            clientId = "7",
            userAgent = CHROME_UA,
            origin = "https://www.youtube.com",
            useSignatureTimestamp = true,
            requiresCipher = true,
            sendVisitorData = true
        )

        val IOS = YouTubeClient(
            name = "IOS",
            clientName = "IOS",
            clientVersion = "20.10.4",
            clientId = "5",
            userAgent = "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
            osName = "iPhone",
            osVersion = "18.3.2.22D82",
            deviceMake = "Apple",
            deviceModel = "iPhone16,2",
            sendVisitorData = true,
            preferHls = true
        )

        val IOS_MUSIC = YouTubeClient(
            name = "IOS_MUSIC",
            clientName = "IOS_MUSIC",
            clientVersion = "8.05",
            clientId = "26",
            userAgent = "com.google.ios.youtubemusic/8.05 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
            endpoint = "https://music.youtube.com/youtubei/v1/player?prettyPrint=false",
            osName = "iPhone",
            osVersion = "18.3.2.22D82",
            deviceMake = "Apple",
            deviceModel = "iPhone16,2",
            sendVisitorData = true,
            preferHls = true
        )

        val ANDROID_CREATOR = YouTubeClient(
            name = "ANDROID_CREATOR",
            clientName = "ANDROID_CREATOR",
            clientVersion = "24.10.100",
            clientId = "14",
            userAgent = "com.google.android.apps.youtube.creator/24.10.100 (Linux; U; Android 14) gzip",
            androidSdkVersion = 34,
            sendVisitorData = true
        )

        val ANDROID_MUSIC = YouTubeClient(
            name = "ANDROID_MUSIC",
            clientName = "ANDROID_MUSIC",
            clientVersion = "7.29.52",
            clientId = "21",
            userAgent = "com.google.android.apps.youtube.music/7.29.52 (Linux; U; Android 14) gzip",
            endpoint = "https://music.youtube.com/youtubei/v1/player?prettyPrint=false",
            androidSdkVersion = 34,
            sendVisitorData = true
        )

        val ANDROID_TESTSUITE = YouTubeClient(
            name = "ANDROID_TESTSUITE",
            clientName = "ANDROID_TESTSUITE",
            clientVersion = "1.9",
            clientId = "30",
            userAgent = "com.google.android.youtube/1.9 (Linux; U; Android 11) gzip",
            androidSdkVersion = 30,
            sendVisitorData = true
        )
    }
}
