package com.example.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.util.Log
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
import com.example.data.providers.youtube.YTPlayerUtils
import com.example.domain.model.MusicSource
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
    val autoQueueEnabled: Boolean = true,
    val isAutoQueueLoading: Boolean = false,
    val sleepTimerRemainingMs: Long = 0L,
    val sleepAfterCurrentTrack: Boolean = false,
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
    private var autoQueueJob: Job? = null
    private var prewarmJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var currentStreamAttempt = 0
    private val maxStreamAttempts = 6
    private var suppressLocalBroadcast = false

    // Guards against double-advancing when both Service & MediaController observe STATE_ENDED
    private var lastHandledEndTrackId: String? = null
    private var lastHandledEndEpochMs: Long = 0L
    private var consecutiveAutoSkipFailures: Int = 0

    /**
     * Callback invoked ONLY on local user-initiated play/pause/seek/track changes so
     * an active Listen Together room can broadcast the change to other listeners.
     */
    var onLocalPlaybackAction: ((track: Track?, isPlaying: Boolean, positionMs: Long) -> Unit)? = null

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
                }.onFailure { err ->
                    Log.e("KoplingfyPlayback", "Failed to connect MediaController", err)
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
                    if (!last.isLiveStream && _state.value.autoQueueEnabled) {
                        ensureAutoQueueFilled(last, playNextWhenReady = false)
                    }
                }
            }
        }
    }

    private fun attachListener(controller: MediaController) {
        controller.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.value = _state.value.copy(isPlaying = isPlaying)
                if (isPlaying) {
                    consecutiveAutoSkipFailures = 0
                    _state.value = _state.value.copy(errorMessage = null)
                    prewarmNextTrackStream()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val buffering = playbackState == Player.STATE_BUFFERING
                val dur = controller.duration.takeIf { it > 0L } ?: _state.value.currentTrack?.durationMs ?: 0L
                _state.value = _state.value.copy(isBuffering = buffering, durationMs = dur)

                if (playbackState == Player.STATE_ENDED) {
                    handleTrackEnded(controller.currentMediaItem?.mediaId)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val cur = _state.value.currentTrack ?: return
                Log.w(
                    "KoplingfyPlayback",
                    "PlayerError (${error.errorCodeName}) on '${cur.title}' attempt=$currentStreamAttempt/${maxStreamAttempts}: ${error.message}"
                )
                if (currentStreamAttempt < maxStreamAttempts) {
                    val nextAttempt = currentStreamAttempt
                    currentStreamAttempt = nextAttempt + 1
                    _state.value = _state.value.copy(
                        isBuffering = true,
                        errorMessage = "Mencoba jalur audio cadangan (${nextAttempt + 1}/$maxStreamAttempts)..."
                    )
                    resolveJob?.cancel()
                    resolveJob = scope.launch {
                        val settings = settingsDataStore.settingsFlow.first()
                        val retryStream = multiSourceRepository.resolvePlayableStream(cur, settings, nextAttempt)
                        if (retryStream != null && retryStream.url.isNotBlank()) {
                            currentStreamAttempt = maxOf(currentStreamAttempt, retryStream.tierIndex + 1)
                            prepareAndStartSingle(controller, cur, retryStream, autoPlay = true)
                        } else {
                            handleUnplayableTrack(cur, error.localizedMessage)
                        }
                    }
                } else {
                    handleUnplayableTrack(cur, error.localizedMessage)
                }
            }
        })
    }

    private fun handleUnplayableTrack(failedTrack: Track, reason: String?) {
        val cur = _state.value
        val hasNextInQueue = cur.queue.size > 1 && (cur.currentIndex + 1 < cur.queue.size || cur.shuffleEnabled)
        if (hasNextInQueue && consecutiveAutoSkipFailures < 3 && !failedTrack.isLiveStream) {
            consecutiveAutoSkipFailures++
            _state.value = cur.copy(
                isBuffering = true,
                isPlaying = false,
                errorMessage = "Gagal memuat ${failedTrack.title}, lanjut ke lagu berikutnya..."
            )
            resolveJob?.cancel()
            resolveJob = scope.launch {
                delay(700L)
                skipToNext(isAutoSkip = true)
            }
        } else {
            _state.value = cur.copy(
                isBuffering = false,
                isPlaying = false,
                errorMessage = "Gagal memutar audio: ${reason ?: "Periksa koneksi internet"}"
            )
        }
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

    fun playTrack(
        track: Track,
        queue: List<Track> = listOf(track),
        fromRemoteSync: Boolean = false,
        startPositionMs: Long = 0L,
        autoPlay: Boolean = true,
        isAutoSkip: Boolean = false
    ) {
        val controller = mediaController
        if (controller == null) {
            pendingTrackToPlay = track to queue
            connectToService()
            return
        }
        if (!isAutoSkip) {
            consecutiveAutoSkipFailures = 0
        }
        currentStreamAttempt = 0
        val dedupedInput = queue.distinctBy { it.id }
        val normalizedQueue = if (dedupedInput.any { it.id == track.id }) dedupedInput else listOf(track) + dedupedInput
        val idx = normalizedQueue.indexOfFirst { it.id == track.id }.coerceAtLeast(0)

        _state.value = _state.value.copy(
            currentTrack = track,
            queue = normalizedQueue,
            currentIndex = idx,
            isBuffering = true,
            positionMs = startPositionMs,
            durationMs = track.durationMs,
            errorMessage = null
        )

        if (!fromRemoteSync && !suppressLocalBroadcast) {
            onLocalPlaybackAction?.invoke(track, autoPlay, startPositionMs)
        }

        // Immediately build or top up the automatic queue if fewer than 5 upcoming tracks remain
        if (!track.isLiveStream && !fromRemoteSync && _state.value.autoQueueEnabled) {
            val remainingUpcoming = normalizedQueue.size - 1 - idx
            if (remainingUpcoming < 5) {
                val anchor = normalizedQueue.lastOrNull() ?: track
                ensureAutoQueueFilled(anchorTrack = anchor, playNextWhenReady = false)
            }
        }

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
            settingsDataStore.saveLastPlayback(merged.id, startPositionMs)
            _state.value = _state.value.copy(currentTrack = merged)

            val settings = settingsDataStore.settingsFlow.first()
            val stream = multiSourceRepository.resolvePlayableStream(merged, settings, attempt = 0)
            if (stream == null || stream.url.isBlank()) {
                handleUnplayableTrack(merged, "Stream tidak tersedia")
                return@launch
            }
            currentStreamAttempt = stream.tierIndex + 1
            prepareAndStartSingle(controller, merged, stream, autoPlay = autoPlay, startPositionMs = startPositionMs)
        }
    }

    /**
     * Automatically builds and appends related tracks to the queue so playback continues seamlessly
     * when the current song ends.
     */
    private fun ensureAutoQueueFilled(anchorTrack: Track, playNextWhenReady: Boolean) {
        if (anchorTrack.isLiveStream) return
        // Avoid cancelling an in-flight auto-queue job unless we now urgently need to play next
        if (autoQueueJob?.isActive == true && !playNextWhenReady) return

        autoQueueJob?.cancel()
        autoQueueJob = scope.launch {
            _state.value = _state.value.copy(isAutoQueueLoading = true)
            val settings = settingsDataStore.settingsFlow.first()
            val existingIds = _state.value.queue.map { it.id }.toSet()
            val related = runCatching {
                multiSourceRepository.buildAutoQueueTracks(
                    seedTrack = anchorTrack,
                    settings = settings,
                    excludeIds = existingIds,
                    limit = 15
                )
            }.getOrDefault(emptyList())

            val latest = _state.value
            val currentIds = latest.queue.map { it.id }.toSet()
            val freshTracks = related.filter { it.id !in currentIds }

            if (freshTracks.isNotEmpty()) {
                val updatedQueue = latest.queue + freshTracks
                _state.value = latest.copy(
                    queue = updatedQueue,
                    isAutoQueueLoading = false
                )
                if (playNextWhenReady) {
                    val nextIdx = (latest.currentIndex + 1).coerceIn(0, updatedQueue.lastIndex)
                    playTrack(updatedQueue[nextIdx], updatedQueue)
                } else {
                    prewarmNextTrackStream()
                }
            } else {
                _state.value = latest.copy(isAutoQueueLoading = false)
                // If no new tracks could be fetched online/offline, loop queue smoothly when urgent
                if (playNextWhenReady && latest.queue.size > 1) {
                    val nextIdx = (latest.currentIndex + 1) % latest.queue.size
                    playTrack(latest.queue[nextIdx], latest.queue)
                }
            }
        }
    }

    /**
     * Pre-resolves the immediate next track's audio stream in the background so transitioning
     * at the end of a song is near-instant (cached inside YouTubeMusicProvider for 15 minutes).
     */
    private fun prewarmNextTrackStream() {
        val cur = _state.value
        val nextIdx = cur.currentIndex + 1
        val nextTrack = cur.queue.getOrNull(nextIdx) ?: return
        if (nextTrack.isLiveStream || !nextTrack.localPath.isNullOrBlank()) return

        prewarmJob?.cancel()
        prewarmJob = scope.launch(Dispatchers.IO) {
            delay(4_000L) // Let current track play and buffer smoothly first
            if (!_state.value.isPlaying) return@launch
            val settings = settingsDataStore.settingsFlow.first()
            runCatching {
                multiSourceRepository.resolvePlayableStream(nextTrack, settings, attempt = 0)
            }
        }
    }

    /**
     * Applies remote Listen Together room state without broadcasting back to Firestore.
     */
    fun applyRemoteSync(track: Track, shouldPlay: Boolean, targetPositionMs: Long) {
        suppressLocalBroadcast = true
        try {
            val controller = mediaController
            val current = _state.value.currentTrack
            if (current == null || current.id != track.id || controller == null || controller.mediaItemCount == 0) {
                playTrack(
                    track = track,
                    queue = listOf(track),
                    fromRemoteSync = true,
                    startPositionMs = targetPositionMs,
                    autoPlay = shouldPlay
                )
            } else {
                val drift = kotlin.math.abs(controller.currentPosition - targetPositionMs)
                if (drift > 2_500L) {
                    controller.seekTo(targetPositionMs.coerceAtLeast(0L))
                    _state.value = _state.value.copy(positionMs = targetPositionMs.coerceAtLeast(0L))
                }
                if (shouldPlay && !controller.isPlaying) {
                    controller.play()
                } else if (!shouldPlay && controller.isPlaying) {
                    controller.pause()
                }
            }
        } finally {
            suppressLocalBroadcast = false
        }
    }

    private fun prepareAndStartSingle(
        controller: MediaController,
        track: Track,
        stream: ResolvedStream,
        autoPlay: Boolean,
        startPositionMs: Long = 0L
    ) {
        Log.d(
            "KoplingfyPlayback",
            "Starting '${track.title}' via ${stream.resolverName} (tier=${stream.tierIndex}, mime=${stream.mimeType})"
        )
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

        val mime = stream.mimeType
        val isHlsUrl = mime == "application/x-mpegURL" ||
            stream.url.contains("m3u8", ignoreCase = true) ||
            stream.url.contains("hls_playlist", ignoreCase = true)
        val isDashUrl = mime == "application/dash+xml"

        val ytVideoId = if (track.source == MusicSource.YOUTUBE_MUSIC && track.localPath.isNullOrBlank()) {
            track.sourceTrackId.takeIf { it.isNotBlank() } ?: track.id.removePrefix("ytm_")
        } else null

        val itemBuilder = MediaItem.Builder()
            .setMediaId(track.id)
            .setMediaMetadata(metadata)

        val isMetrolistDirect = ytVideoId != null && !isHlsUrl && !isDashUrl && stream.tierIndex <= 3
        if (isMetrolistDirect) {
            // Metrolist pipeline: route direct googlevideo streams through CacheDataSource + ResolvingDataSource (512KB chunked)
            val containerTag = if (mime?.contains("webm", ignoreCase = true) == true) "webm" else "mp4"
            itemBuilder
                .setUri(Uri.parse(YTPlayerUtils.buildInternalTrackUri(ytVideoId!!)))
                .setCustomCacheKey("${ytVideoId}_${containerTag}")
        } else {
            itemBuilder.setUri(Uri.parse(stream.url))
            when {
                isHlsUrl -> itemBuilder.setMimeType("application/x-mpegURL")
                isDashUrl -> itemBuilder.setMimeType("application/dash+xml")
            }
        }

        controller.setMediaItem(itemBuilder.build())
        controller.prepare()
        if (startPositionMs > 1_000L) {
            controller.seekTo(startPositionMs)
        }
        if (autoPlay) controller.play() else controller.pause()
    }

    fun togglePlayPause() {
        val controller = mediaController ?: return
        if (controller.mediaItemCount == 0) {
            _state.value.currentTrack?.let { playTrack(it, _state.value.queue) }
            return
        }
        val willPlay = !controller.isPlaying
        if (willPlay) controller.play() else controller.pause()
        if (!suppressLocalBroadcast) {
            onLocalPlaybackAction?.invoke(_state.value.currentTrack, willPlay, controller.currentPosition.coerceAtLeast(0L))
        }
    }

    fun seekTo(positionMs: Long) {
        val safe = positionMs.coerceAtLeast(0L)
        mediaController?.seekTo(safe)
        _state.value = _state.value.copy(positionMs = safe)
        if (!suppressLocalBroadcast) {
            onLocalPlaybackAction?.invoke(_state.value.currentTrack, _state.value.isPlaying, safe)
        }
    }

    fun skipToNext(isAutoSkip: Boolean = false) {
        val cur = _state.value
        if (cur.queue.isEmpty()) return
        val nextIndex = if (cur.shuffleEnabled && cur.queue.size > 1) {
            (cur.queue.indices - cur.currentIndex).random()
        } else {
            val candidate = cur.currentIndex + 1
            if (candidate < cur.queue.size) {
                candidate
            } else if (cur.repeatMode == RepeatModeState.ALL) {
                0
            } else {
                // Reached end of queue: automatically fetch & play related tracks if Auto-Queue is ON
                val anchor = cur.currentTrack ?: cur.queue.lastOrNull()
                if (cur.autoQueueEnabled && anchor != null && !anchor.isLiveStream) {
                    ensureAutoQueueFilled(anchorTrack = anchor, playNextWhenReady = true)
                }
                return
            }
        }
        playTrack(cur.queue[nextIndex], cur.queue, isAutoSkip = isAutoSkip)
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

    /**
     * Called directly by KoplingfyPlaybackService when ExoPlayer finishes a track in the background,
     * guaranteeing continuous playback even if the UI activity is minimized or destroyed.
     */
    fun notifyTrackEndedFromService(endedMediaId: String?) {
        scope.launch {
            handleTrackEnded(endedMediaId)
        }
    }

    private fun handleTrackEnded(endedMediaId: String?) {
        val cur = _state.value
        val activeId = cur.currentTrack?.id ?: return
        if (endedMediaId != null && endedMediaId != activeId) return

        val now = System.currentTimeMillis()
        if (lastHandledEndTrackId == activeId && (now - lastHandledEndEpochMs) < 2_500L) {
            return
        }
        lastHandledEndTrackId = activeId
        lastHandledEndEpochMs = now

        if (cur.sleepAfterCurrentTrack) {
            mediaController?.pause()
            _state.value = cur.copy(
                isPlaying = false,
                sleepAfterCurrentTrack = false,
                sleepTimerRemainingMs = 0L
            )
            return
        }

        when (cur.repeatMode) {
            RepeatModeState.ONE -> {
                mediaController?.seekTo(0L)
                mediaController?.play()
            }
            RepeatModeState.ALL -> skipToNext()
            RepeatModeState.OFF -> {
                if (cur.currentIndex + 1 < cur.queue.size || (cur.shuffleEnabled && cur.queue.size > 1)) {
                    skipToNext()
                } else if (cur.autoQueueEnabled && !cur.currentTrack.isLiveStream) {
                    ensureAutoQueueFilled(anchorTrack = cur.currentTrack, playNextWhenReady = true)
                }
            }
        }
    }

    fun setSleepTimerMinutes(minutes: Int) {
        sleepTimerJob?.cancel()
        if (minutes <= 0) {
            _state.value = _state.value.copy(sleepTimerRemainingMs = 0L, sleepAfterCurrentTrack = false)
            return
        }
        val totalMs = minutes * 60_000L
        val endEpoch = System.currentTimeMillis() + totalMs
        _state.value = _state.value.copy(
            sleepTimerRemainingMs = totalMs,
            sleepAfterCurrentTrack = false
        )
        sleepTimerJob = scope.launch {
            while (isActive) {
                val left = (endEpoch - System.currentTimeMillis()).coerceAtLeast(0L)
                _state.value = _state.value.copy(sleepTimerRemainingMs = left)
                if (left <= 0L) {
                    mediaController?.pause()
                    _state.value = _state.value.copy(isPlaying = false, sleepTimerRemainingMs = 0L)
                    break
                }
                delay(1_000L)
            }
        }
    }

    fun setSleepAfterCurrentTrack() {
        sleepTimerJob?.cancel()
        _state.value = _state.value.copy(
            sleepTimerRemainingMs = 0L,
            sleepAfterCurrentTrack = true
        )
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        _state.value = _state.value.copy(
            sleepTimerRemainingMs = 0L,
            sleepAfterCurrentTrack = false
        )
    }

    fun toggleAutoQueue() {
        val next = !_state.value.autoQueueEnabled
        _state.value = _state.value.copy(autoQueueEnabled = next)
        val cur = _state.value
        if (next && cur.currentTrack != null && !cur.currentTrack.isLiveStream) {
            val remaining = cur.queue.size - 1 - cur.currentIndex
            if (remaining < 5) {
                ensureAutoQueueFilled(cur.queue.lastOrNull() ?: cur.currentTrack, playNextWhenReady = false)
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
        autoQueueJob?.cancel()
        prewarmJob?.cancel()
        mediaController?.stop()
        mediaController?.clearMediaItems()
        _state.value = _state.value.copy(
            queue = emptyList(),
            currentTrack = null,
            currentIndex = -1,
            isPlaying = false,
            isAutoQueueLoading = false,
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
