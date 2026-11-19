package com.example.di

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.example.R
import com.example.data.local.db.KoplingfyDatabase
import com.example.data.local.media.LocalMusicScanner
import com.example.data.local.prefs.AppSettings
import com.example.data.local.prefs.SettingsDataStore
import com.example.data.providers.AudiusProvider
import com.example.data.providers.JamendoProvider
import com.example.data.providers.MultiSourceRepository
import com.example.data.providers.RadioBrowserProvider
import com.example.data.providers.YouTubeMusicProvider
import com.example.data.remote.ListenTogetherController
import com.example.data.remote.ListenTogetherRepository
import com.example.download.DownloadManagerRepository
import com.example.lyrics.LyricsEngine
import com.example.lyrics.LyricsTranslationEngine
import com.example.playback.EqualizerManager
import com.example.playback.PlaybackController
import com.example.playback.VisualizerAudioProcessor
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@OptIn(UnstableApi::class)
class AppContainer(val context: Context) {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val settingsDataStore = SettingsDataStore(context)
    private val latestSettings = MutableStateFlow(AppSettings())

    /**
     * Thread-safe User-Agent reference used by ExoPlayer's OkHttpDataSource so direct
     * YouTube InnerTube googlevideo.com streams send the exact matching client User-Agent.
     */
    val activeStreamUserAgent = AtomicReference("Koplingfy/1.0.0 (Android; Media3)")

    init {
        appScope.launch {
            settingsDataStore.settingsFlow.collect { latestSettings.value = it }
        }
    }

    val httpCacheDir = File(context.cacheDir, "koplingfy_http_cache")

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .cache(Cache(httpCacheDir, 30L * 1024L * 1024L))
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .writeTimeout(18, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor { chain ->
            val original = chain.request()
            // Preserve custom User-Agent set by InnerTube/LRCLIB calls; apply default otherwise
            val req = if (original.header("User-Agent") == null) {
                original.newBuilder()
                    .header("User-Agent", "Koplingfy/1.0.0 (Android; MusicPlayer)")
                    .build()
            } else original
            chain.proceed(req)
        }
        .build()

    val mediaHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor { chain ->
            val original = chain.request()
            val ua = activeStreamUserAgent.get().orEmpty().ifBlank { "Koplingfy/1.0.0 (Android; Media3)" }
            val builder = original.newBuilder()
                .header("User-Agent", ua)
            // Only send YouTube web Origin/Referer when using a browser UA (e.g. TV_EMBEDDED / Piped / Invidious)
            if (ua.startsWith("Mozilla/") && original.url.host.contains("googlevideo.com", ignoreCase = true)) {
                builder.header("Origin", "https://www.youtube.com")
                builder.header("Referer", "https://www.youtube.com/")
            }
            chain.proceed(builder.build())
        }
        .build()

    val database: KoplingfyDatabase = KoplingfyDatabase.getInstance(context)

    val localMusicScanner = LocalMusicScanner(
        context = context,
        trackDao = database.trackDao(),
        tagOverrideDao = database.tagOverrideDao()
    )

    val audiusProvider = AudiusProvider(httpClient)
    val youtubeMusicProvider = YouTubeMusicProvider(
        httpClient = httpClient,
        customInstanceProvider = { latestSettings.value.customPipedInstanceUrl }
    )
    val jamendoProvider = JamendoProvider(
        httpClient = httpClient,
        clientIdProvider = { latestSettings.value.jamendoClientId }
    )
    val radioBrowserProvider = RadioBrowserProvider(httpClient)

    val multiSourceRepository = MultiSourceRepository(
        audiusProvider = audiusProvider,
        youtubeMusicProvider = youtubeMusicProvider,
        jamendoProvider = jamendoProvider,
        radioBrowserProvider = radioBrowserProvider,
        trackDao = database.trackDao()
    )

    val lyricsEngine = LyricsEngine(
        context = context,
        httpClient = httpClient,
        lyricsDao = database.lyricsDao(),
        musixmatchKeyProvider = { latestSettings.value.musixmatchApiKey }
    )

    val lyricsTranslationEngine = LyricsTranslationEngine(
        httpClient = httpClient,
        lyricsDao = database.lyricsDao()
    )

    val visualizerAudioProcessor = VisualizerAudioProcessor()
    val equalizerManager = EqualizerManager()

    val playbackController = PlaybackController(
        context = context,
        multiSourceRepository = multiSourceRepository,
        trackDao = database.trackDao(),
        historyDao = database.historyDao(),
        settingsDataStore = settingsDataStore,
        activeStreamUserAgent = activeStreamUserAgent
    )

    val downloadManagerRepository = DownloadManagerRepository(
        context = context,
        downloadDao = database.downloadDao(),
        trackDao = database.trackDao()
    )

    // Lazily resolved using the provisioned named database ID (never parameterless FirebaseFirestore.getInstance())
    val listenTogetherRepository: ListenTogetherRepository by lazy {
        val dbId = context.applicationContext.getString(R.string.firestore_database_id)
        ListenTogetherRepository(FirebaseFirestore.getInstance(dbId))
    }

    val listenTogetherController = ListenTogetherController(
        repositoryProvider = { listenTogetherRepository },
        playbackController = playbackController,
        settingsDataStore = settingsDataStore
    )

    fun calculateCacheSizeBytes(): Long {
        fun dirSize(dir: File?): Long {
            if (dir == null || !dir.exists()) return 0L
            return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }
        return dirSize(context.cacheDir)
    }

    suspend fun clearCachesPreservingLibrary() {
        runCatching { httpClient.cache?.evictAll() }
        runCatching {
            context.cacheDir.listFiles()?.forEach { child ->
                child.deleteRecursively()
            }
        }
        database.lyricsDao().clearLyricsCache()
        database.lyricsDao().clearTranslationsCache()
        database.trackDao().clearCachedOnlineTracks()
    }
}
