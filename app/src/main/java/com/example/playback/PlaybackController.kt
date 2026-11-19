package com.example.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.data.local.db.HistoryDao
import com.example.data.local.db.PlaybackHistoryEntity
import com.example.data.local.db.TrackDao
import com.example.data.local.db.TrackEntity
import com.example.data.local.prefs.SettingsDataStore
import com.example.data.providers.MultiSourceRepository
import com.example.data.providers.ResolvedStream
import com.example.domain.model.RepeatModeState
import com.example.domain.model.Track
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

data class PlaybackUiState(
    val currentTrack: Track? = null,
    val queue: List<Track> = emptyList(),
    val currentIndex: Int = -1,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatModeState = RepeatModeState.OFF,
    val playbackSpeed: Float = 1.0f,
    val errorMessage: String? = null
)

class PlaybackController(
    private val context: Context,
    private val multiSourceRepository: MultiSourceRepository,
    private val trackDao: TrackDao,
    private val historyDao: HistoryDao,
    private val settingsDataStore: SettingsDataStore,
    private val activeStreamUserAgent: AtomicReference<String>
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null
    private var pendingTrackToPlay: Pair<Track, List<Track>>? = null
    private var resolveJob: Job? = null
    private var currentStreamAttempt = 0
    private val maxStreamAttempts = 6

    init {
        connectToService()
        startPositionTicker()
    }

    private fun connectToService() {
        val token = SessionToken(context, ComponentName(context, KoplingfyPlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                runCatching {
                    val controller = future.get()
                    mediaController = controller
                    attachListener(controller)
                    applySavedPlaybackDefaults(controller)
                    pendingTrackToPlay?.let { (track, queue) ->
                        pendingTrackToPlay = null
                        playTrack(track, queue)
                    }
                }
            },
            ContextCompat.getMainExecutor(context)
        )
    }

    private fun applySavedPlaybackDefaults(controller: MediaController) {
        scope.launch {
            val settings = settingsDataStore.settingsFlow.first()
            controller.playbackParameters = PlaybackParameters(settings.playbackSpeed)
            _state.value = _state.value.copy(
                shuffleEnabled = settings.defaultShuffle,
                repeatMode = settings.defaultRepeat,
                playbackSpeed = settings.playbackSpeed
            )
            if (settings.resumeOnStartup && settings.lastTrackId.isNotBlank() && _state.value.currentTrack == null) {
                val last = trackDao.getTrackById(settings.lastTrackId)?.toDomain()
                if (last != null) {
                    _state.value = _state.value.copy(
                        currentTrack = last,
                        queue = listOf(last),
                        currentIndex = 0,
                        positionMs = settings.lastPositionMs,
                        durationMs = last.durationMs
                    )
                }
            }
        }
    }

    private fun attachListener(controller: MediaController) {
        controller.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.value = _state.value.copy(isPlaying = isPlaying)
                if (isPlaying) {
                    _state.value = _state.value.copy(errorMessage = null)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val buffering = playbackState == Player.STATE_BUFFERING
                val dur = controller.duration.takeIf { it > 0L } ?: _state.value.currentTrack?.durationMs ?: 0L
                _state.value = _state.value.copy(isBuffering = buffering, durationMs = dur)

                if (playbackState == Player.STATE_ENDED) {
                    handleTrackEnded()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val cur = _state.value.currentTrack ?: return
                if (currentStreamAttempt < maxStreamAttempts) {
                    val nextAttempt = currentStreamAttempt
                    currentStreamAttempt = nextAttempt + 1
                    _state.value = _state.value.copy(
                        isBuffering = true,
                        errorMessage = "Switching stream mirror (tier ${nextAttempt + 1})..."
                    )
                    resolveJob?.cancel()
                    resolveJob = scope.launch {
                        val settings = settingsDataStore.settingsFlow.first()
                        val retryStream = multiSourceRepository.resolvePlayableStream(cur, settings, nextAttempt)
                        if (retryStream != null) {
                            currentStreamAttempt = maxOf(currentStreamAttempt, retryStream.tierIndex + 1)
                            prepareAndStartSingle(controller, cur, retryStream, autoPlay = true)
                        } else {
                            _state.value = _state.value.copy(
                                isBuffering = false,
                                isPlaying = false,
                                errorMessage = "Stream error: ${error.localizedMessage ?: "All mirrors exhausted"}"
                            )
                        }
                    }
                } else {
                    _state.value = _state.value.copy(
                        isBuffering = false,
                        isPlaying = false,
                        errorMessage = "Playback error: ${error.localizedMessage ?: "Unable to stream track"}"
                    )
                }
            }
        })
    }

    private fun startPositionTicker() {
        scope.launch {
            var ticks = 0
            while (isActive) {
                delay(250L)
                val ctrl = mediaController
                if (ctrl != null && (ctrl.isPlaying || ctrl.playbackState == Player.STATE_READY)) {
                    val pos = ctrl.currentPosition.coerceAtLeast(0L)
                    val dur = ctrl.duration.takeIf { it > 0L } ?: _state.value.currentTrack?.durationMs ?: 0L
                    _state.value = _state.value.copy(positionMs = pos, durationMs = dur)
                    ticks++
                    if (ticks % 20 == 0) {
                        _state.value.currentTrack?.let { t ->
                            settingsDataStore.saveLastPlayback(t.id, pos)
                        }
                    }
                }
            }
        }
    }

    fun playTrack(track: Track, queue: List<Track> = listOf(track)) {
        val controller = mediaController
        if (controller == null) {
            pendingTrackToPlay = track to queue
            connectToService()
            return
        }
        currentStreamAttempt = 0
        val normalizedQueue = if (queue.any { it.id == track.id }) queue else listOf(track) + queue
        val idx = normalizedQueue.indexOfFirst { it.id == track.id }.coerceAtLeast(0)

        _state.value = _state.value.copy(
            currentTrack = track,
            queue = normalizedQueue,
            currentIndex = idx,
            isBuffering = true,
            positionMs = 0L,
            durationMs = track.durationMs,
            errorMessage = null
        )

        resolveJob?.cancel()
        resolveJob = scope.launch {
            val existing = trackDao.getTrackById(track.id)
            val merged = track.copy(
                isFavorite = existing?.isFavorite ?: track.isFavorite,
                playCount = (existing?.playCount ?: track.playCount) + 1,
                localPath = existing?.localPath ?: track.localPath
            )
            trackDao.upsertTrack(TrackEntity.fromDomain(merged))
            historyDao.insertHistory(PlaybackHistoryEntity(trackId = merged.id, playedAt = System.currentTimeMillis()))
            settingsDataStore.saveLastPlayback(merged.id, 0L)
            _state.value = _state.value.copy(currentTrack = merged)

            val settings = settingsDataStore.settingsFlow.first()
            val stream = multiSourceRepository.resolvePlayableStream(merged, settings, attempt = 0)
            if (stream == null || stream.url.isBlank()) {
                _state.value = _state.value.copy(
                    isBuffering = false,
                    isPlaying = false,
                    errorMessage = "Unable to resolve stream for ${merged.title}"
                )
                return@launch
            }
            currentStreamAttempt = stream.tierIndex + 1
            prepareAndStartSingle(controller, merged, stream, autoPlay = true)
        }
    }

    private fun prepareAndStartSingle(
        controller: MediaController,
        track: Track,
        stream: ResolvedStream,
        autoPlay: Boolean
    ) {
        activeStreamUserAgent.set(
            stream.userAgent?.takeIf { it.isNotBlank() } ?: "Koplingfy/1.0.0 (Android; Media3)"
        )

        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)
            .apply {
                track.artworkUrl?.takeIf { it.isNotBlank() }?.let {
                    setArtworkUri(Uri.parse(it))
                }
            }
            .build()

        val itemBuilder = MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(Uri.parse(stream.url))
            .setMediaMetadata(metadata)

        // Only force explicit MIME type for adaptive manifests (HLS/DASH); let Media3 auto-sniff progressive audio
        val mime = stream.mimeType
        if (mime == "application/x-mpegURL" || mime == "application/dash+xml") {
            itemBuilder.setMimeType(mime)
        }

        controller.setMediaItem(itemBuilder.build())
        controller.prepare()
        if (autoPlay) controller.play()
    }

    fun togglePlayPause() {
        val controller = mediaController ?: return
        if (controller.mediaItemCount == 0) {
            _state.value.currentTrack?.let { playTrack(it, _state.value.queue) }
            return
        }
        if (controller.isPlaying) controller.pause() else controller.play()
    }

    fun seekTo(positionMs: Long) {
        val safe = positionMs.coerceAtLeast(0L)
        mediaController?.seekTo(safe)
        _state.value = _state.value.copy(positionMs = safe)
    }

    fun skipToNext() {
        val cur = _state.value
        if (cur.queue.isEmpty()) return
        val nextIndex = if (cur.shuffleEnabled && cur.queue.size > 1) {
            (cur.queue.indices - cur.currentIndex).random()
        } else {
            val candidate = cur.currentIndex + 1
            if (candidate < cur.queue.size) candidate
            else if (cur.repeatMode == RepeatModeState.ALL) 0
            else return
        }
        playTrack(cur.queue[nextIndex], cur.queue)
    }

    fun skipToPrevious() {
        val cur = _state.value
        if (cur.positionMs > 4_000L) {
            seekTo(0L)
            return
        }
        if (cur.queue.isEmpty()) return
        val prevIndex = if (cur.currentIndex > 0) cur.currentIndex - 1
        else if (cur.repeatMode == RepeatModeState.ALL) cur.queue.lastIndex
        else 0
        playTrack(cur.queue[prevIndex], cur.queue)
    }

    private fun handleTrackEnded() {
        val cur = _state.value
        when (cur.repeatMode) {
            RepeatModeState.ONE -> {
                mediaController?.seekTo(0L)
                mediaController?.play()
            }
            RepeatModeState.ALL -> skipToNext()
            RepeatModeState.OFF -> {
                if (cur.currentIndex + 1 < cur.queue.size || cur.shuffleEnabled) {
                    skipToNext()
                }
            }
        }
    }

    fun toggleShuffle() {
        val next = !_state.value.shuffleEnabled
        _state.value = _state.value.copy(shuffleEnabled = next)
        scope.launch { settingsDataStore.updateDefaultShuffle(next) }
    }

    fun cycleRepeatMode() {
        val next = when (_state.value.repeatMode) {
            RepeatModeState.OFF -> RepeatModeState.ALL
            RepeatModeState.ALL -> RepeatModeState.ONE
            RepeatModeState.ONE -> RepeatModeState.OFF
        }
        _state.value = _state.value.copy(repeatMode = next)
        scope.launch { settingsDataStore.updateDefaultRepeat(next) }
    }

    fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 2.0f)
        mediaController?.playbackParameters = PlaybackParameters(clamped)
        _state.value = _state.value.copy(playbackSpeed = clamped)
        scope.launch { settingsDataStore.updatePlaybackSpeed(clamped) }
    }

    fun addToQueue(track: Track) {
        val cur = _state.value
        if (cur.queue.isEmpty()) {
            playTrack(track, listOf(track))
            return
        }
        _state.value = cur.copy(queue = cur.queue + track)
    }

    fun playNext(track: Track) {
        val cur = _state.value
        if (cur.queue.isEmpty()) {
            playTrack(track, listOf(track))
            return
        }
        val insertIdx = (cur.currentIndex + 1).coerceIn(0, cur.queue.size)
        val updated = cur.queue.toMutableList().apply { add(insertIdx, track) }
        _state.value = cur.copy(queue = updated)
    }

    fun removeFromQueue(index: Int) {
        val cur = _state.value
        if (index !in cur.queue.indices) return
        val updated = cur.queue.toMutableList().apply { removeAt(index) }
        val newCurrentIndex = when {
            updated.isEmpty() -> -1
            index < cur.currentIndex -> cur.currentIndex - 1
            index == cur.currentIndex -> cur.currentIndex.coerceAtMost(updated.lastIndex)
            else -> cur.currentIndex
        }
        _state.value = cur.copy(queue = updated, currentIndex = newCurrentIndex)
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        val cur = _state.value
        if (fromIndex !in cur.queue.indices || toIndex !in cur.queue.indices) return
        val updated = cur.queue.toMutableList()
        val moved = updated.removeAt(fromIndex)
        updated.add(toIndex, moved)
        val newCurrentId = cur.currentTrack?.id
        val newIdx = updated.indexOfFirst { it.id == newCurrentId }
        _state.value = cur.copy(queue = updated, currentIndex = newIdx)
    }

    fun clearQueue() {
        mediaController?.stop()
        mediaController?.clearMediaItems()
        _state.value = _state.value.copy(
            queue = emptyList(),
            currentTrack = null,
            currentIndex = -1,
            isPlaying = false,
            positionMs = 0L,
            durationMs = 0L
        )
    }

    fun clearError() {
        _state.value = _state.value.copy(errorMessage = null)
    }

    fun refreshCurrentTrackFavorite(trackId: String, isFav: Boolean) {
        val cur = _state.value
        if (cur.currentTrack?.id == trackId) {
            _state.value = cur.copy(currentTrack = cur.currentTrack.copy(isFavorite = isFav))
        }
    }
}
