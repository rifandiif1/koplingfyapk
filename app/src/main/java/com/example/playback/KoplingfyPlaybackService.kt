package com.example.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.example.KoplingfyApplication
import com.example.MainActivity
import com.example.R
import com.example.data.providers.youtube.YTPlayerUtils
import com.example.widget.KoplingfyWidgetProvider
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.IOException

/**
 * Background Media3 MediaLibraryService powering:
 * - Uninterrupted playback when minimized, switching apps, or screen locked (foregroundServiceType="mediaPlayback")
 * - MediaStyle notification with artwork, title, artist, play/pause, prev, next, seek progress
 * - Lock screen, Bluetooth AVRCP, and wired headset controls
 * - Android Auto browsable media library tree
 * - Real-time PCM audio tap via VisualizerAudioProcessor & hardware Equalizer audioSessionId binding
 */
@OptIn(UnstableApi::class)
class KoplingfyPlaybackService : MediaLibraryService() {

    private var mediaLibrarySession: MediaLibrarySession? = null
    private var exoPlayer: ExoPlayer? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    companion object {
        const val CHANNEL_ID = "koplingfy_playback_channel"
        const val ACTION_TOGGLE_PLAY_PAUSE = "com.developer.koplingfy.ACTION_TOGGLE_PLAY_PAUSE"
        const val ACTION_NEXT = "com.developer.koplingfy.ACTION_NEXT"
        const val ACTION_PREV = "com.developer.koplingfy.ACTION_PREV"

        private const val ROOT_ID = "koplingfy_root"
        private const val NODE_FAVORITES = "node_favorites"
        private const val NODE_RECENT = "node_recent"
        private const val NODE_LOCAL = "node_local"
    }

    override fun onCreate() {
        super.onCreate()
        val container = (application as KoplingfyApplication).container

        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(container.visualizerAudioProcessor))
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build()
            }
        }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

        // Metrolist-style DataSource pipeline:
        // CacheDataSource (200MB LRU SimpleCache) -> ResolvingDataSource (on-the-fly YTPlayerUtils + 512KB chunking)
        // -> DefaultDataSource -> OkHttpDataSource
        val okHttpFactory = OkHttpDataSource.Factory(container.mediaHttpClient)
        val baseDataSourceFactory = DefaultDataSource.Factory(this, okHttpFactory)
        val resolvingDataSourceFactory = ResolvingDataSource.Factory(baseDataSourceFactory) { dataSpec ->
            val uri = dataSpec.uri
            if (uri.scheme == YTPlayerUtils.METROLIST_URI_SCHEME && uri.host == YTPlayerUtils.METROLIST_URI_HOST) {
                val videoId = uri.lastPathSegment.orEmpty()
                if (videoId.isBlank()) throw IOException("Empty YouTube videoId in DataSpec")
                val resolved = runBlocking(Dispatchers.IO) {
                    container.youtubeMusicProvider.resolveForExoPlayer(videoId)
                } ?: throw IOException("YTPlayerUtils failed to resolve stream for $videoId")

                container.activeStreamUserAgent.set(
                    resolved.userAgent.takeIf { it.isNotBlank() } ?: "Koplingfy/1.0.0 (Android; Media3)"
                )
                Log.d(
                    "KoplingfyService",
                    "ResolvingDataSource mapped $videoId -> ${resolved.clientName} (pos=${dataSpec.position})"
                )
                val targetUri = Uri.parse(resolved.streamUrl)
                if (!resolved.isHls) {
                    // Metrolist 512 KB chunked range requests avoid googlevideo single-stream throttling
                    dataSpec.withUri(targetUri).subrange(dataSpec.uriPositionOffset, YTPlayerUtils.CHUNK_LENGTH)
                } else {
                    dataSpec.withUri(targetUri)
                }
            } else {
                dataSpec
            }
        }

        val cacheDataSinkFactory = CacheDataSink.Factory()
            .setCache(container.playerCache)
            .setFragmentSize(YTPlayerUtils.CHUNK_LENGTH)

        val dataSourceFactory = CacheDataSource.Factory()
            .setCache(container.playerCache)
            .setUpstreamDataSourceFactory(resolvingDataSourceFactory)
            .setCacheWriteDataSinkFactory(cacheDataSinkFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15_000, // min buffer
                60_000, // max buffer
                1_500,  // buffer for playback
                3_000   // buffer for rebuffer
            )
            .build()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val player = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, true) // Automatic audio focus & interruption handling
            .setHandleAudioBecomingNoisy(true)          // Pause on Bluetooth/headset disconnect
            .setWakeMode(C.WAKE_MODE_NETWORK)           // Keep CPU & Wi-Fi active during locked streaming
            .build()

        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                runCatching { container.equalizerManager.attachToAudioSession(audioSessionId) }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) {
                    container.visualizerAudioProcessor.resetSilence()
                }
                updateWidget(player)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                updateWidget(player)
            }

            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                updateWidget(player)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && player.audioSessionId > 0) {
                    runCatching { container.equalizerManager.attachToAudioSession(player.audioSessionId) }
                }
                if (playbackState == Player.STATE_ENDED) {
                    container.playbackController.notifyTrackEndedFromService(player.currentMediaItem?.mediaId)
                }
                updateWidget(player)
            }
        })

        if (player.audioSessionId > 0) {
            runCatching { container.equalizerManager.attachToAudioSession(player.audioSessionId) }
        }
        exoPlayer = player

        // ForwardingPlayer so notification, lock-screen, and Bluetooth Next/Prev buttons work with our Auto-Queue
        // without breaking Media3's Timeline invariants.
        val sessionPlayer = object : androidx.media3.common.ForwardingPlayer(player) {
            override fun getAvailableCommands(): Player.Commands {
                return super.getAvailableCommands().buildUpon()
                    .add(Player.COMMAND_SEEK_TO_NEXT)
                    .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .build()
            }

            override fun isCommandAvailable(command: Int): Boolean {
                return when (command) {
                    Player.COMMAND_SEEK_TO_NEXT,
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_PREVIOUS,
                    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> true
                    else -> super.isCommandAvailable(command)
                }
            }

            override fun seekToNext() {
                container.playbackController.skipToNext()
            }

            override fun seekToNextMediaItem() {
                container.playbackController.skipToNext()
            }

            override fun seekToPrevious() {
                container.playbackController.skipToPrevious()
            }

            override fun seekToPreviousMediaItem() {
                container.playbackController.skipToPrevious()
            }
        }

        // MediaStyle Notification Provider with Koplingfy channel & branding
        val notificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelId(CHANNEL_ID)
            .setChannelName(R.string.notification_channel_playback)
            .build()
        notificationProvider.setSmallIcon(R.mipmap.ic_launcher_round)
        setMediaNotificationProvider(notificationProvider)

        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_now_playing", true)
        }
        val sessionActivityIntent = PendingIntent.getActivity(
            this,
            1001,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaLibrarySession = MediaLibrarySession.Builder(this, sessionPlayer, LibrarySessionCallback(container))
            .setSessionActivity(sessionActivityIntent)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = (application as KoplingfyApplication).container
        when (intent?.action) {
            ACTION_TOGGLE_PLAY_PAUSE -> container.playbackController.togglePlayPause()
            ACTION_NEXT -> container.playbackController.skipToNext()
            ACTION_PREV -> container.playbackController.skipToPrevious()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaLibrarySession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep playing in background if user swipes task away while actively playing!
        val player = mediaLibrarySession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        val container = (application as KoplingfyApplication).container
        container.equalizerManager.releaseEffects()
        container.visualizerAudioProcessor.resetSilence()
        mediaLibrarySession?.run {
            player.release()
            release()
        }
        mediaLibrarySession = null
        exoPlayer = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun updateWidget(player: Player) {
        val meta = player.mediaMetadata
        val title = meta.title?.toString().orEmpty()
        val artist = meta.artist?.toString().orEmpty()
        val artworkUrl = meta.artworkUri?.toString()
        KoplingfyWidgetProvider.updateAllWidgets(
            context = this,
            title = title,
            artist = artist,
            artworkUrl = artworkUrl,
            isPlaying = player.isPlaying
        )
    }

    private inner class LibrarySessionCallback(
        private val container: com.example.di.AppContainer
    ) : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val root = MediaItem.Builder()
                .setMediaId(ROOT_ID)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("Koplingfy")
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .build()
                )
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(root, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            if (parentId == ROOT_ID) {
                val nodes = ImmutableList.of(
                    browsableNode(NODE_FAVORITES, "Favorites"),
                    browsableNode(NODE_RECENT, "Recently Played"),
                    browsableNode(NODE_LOCAL, "Local Library")
                )
                return Futures.immediateFuture(LibraryResult.ofItemList(nodes, params))
            }
            val future = com.google.common.util.concurrent.SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            serviceScope.launch(Dispatchers.IO) {
                val tracks = when (parentId) {
                    NODE_FAVORITES -> container.database.trackDao().observeFavorites().first()
                    NODE_RECENT -> container.database.historyDao().observeRecentlyPlayed().first()
                    NODE_LOCAL -> container.database.trackDao().observeLocalTracks().first()
                    else -> emptyList()
                }.map { it.toDomain() }
                val items = ImmutableList.copyOf(
                    tracks.take(pageSize.coerceAtLeast(25)).mapNotNull { t ->
                        val uri = t.localPath ?: t.streamUrl ?: return@mapNotNull null
                        MediaItem.Builder()
                            .setMediaId(t.id)
                            .setUri(uri)
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle(t.title)
                                    .setArtist(t.artist)
                                    .setAlbumTitle(t.album)
                                    .setIsBrowsable(false)
                                    .setIsPlayable(true)
                                    .apply {
                                        t.artworkUrl?.let { setArtworkUri(android.net.Uri.parse(it)) }
                                    }
                                    .build()
                            )
                            .build()
                    }
                )
                future.set(LibraryResult.ofItemList(items, params))
            }
            return future
        }

        private fun browsableNode(id: String, title: String): MediaItem =
            MediaItem.Builder()
                .setMediaId(id)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(title)
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .build()
                )
                .build()
    }
}
