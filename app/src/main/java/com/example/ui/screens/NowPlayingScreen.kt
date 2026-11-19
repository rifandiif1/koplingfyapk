package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.model.RepeatModeState
import com.example.domain.model.VisualizerMode
import com.example.ui.components.AudioVisualizerCanvas
import com.example.ui.components.SourceBadge
import com.example.ui.components.TrackArtwork
import com.example.ui.components.formatTimeMs
import com.example.ui.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    viewModel: MainViewModel,
    onClose: () -> Unit,
    onOpenLyrics: () -> Unit,
    onOpenEqualizer: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler { onClose() }

    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val audioFrame by viewModel.audioAnalysis.collectAsStateWithLifecycle()
    val track = playbackState.currentTrack

    var showQueueSheet by remember { mutableStateOf(false) }
    var showSpeedSheet by remember { mutableStateOf(false) }
    var isSeeking by remember { mutableStateOf(false) }
    var seekSliderPos by remember { mutableFloatStateOf(0f) }

    val effectivePos = if (isSeeking) seekSliderPos.toLong() else playbackState.positionMs
    val maxDur = playbackState.durationMs.coerceAtLeast(1L)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.background
                    )
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding()
            .testTag("now_playing_screen")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 560.dp)
                .align(Alignment.Center)
                .padding(horizontal = 22.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose, modifier = Modifier.testTag("np_close_button")) {
                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Collapse player", modifier = Modifier.size(30.dp))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "NOW PLAYING",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    if (track != null) {
                        Spacer(Modifier.height(2.dp))
                        SourceBadge(track.source)
                    }
                }
                IconButton(onClick = { showSpeedSheet = true }) {
                    Icon(Icons.Rounded.Speed, contentDescription = "Playback speed")
                }
            }

            Spacer(Modifier.height(10.dp))

            // Visualizer Mode Selector Chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
            ) {
                VisualizerMode.entries.forEach { mode ->
                    FilterChip(
                        selected = settings.visualizerMode == mode,
                        onClick = { viewModel.updateVisualizerMode(mode) },
                        label = { Text(mode.label, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // Main Stage: Large Artwork + Optional Live Audio-Reactive Visualizer Overlay
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.88f)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(settings.artworkShapeStyle.cornerDp.dp))
                ) {
                    TrackArtwork(
                        artworkUrl = track?.artworkUrl,
                        contentDescription = track?.title ?: "Album artwork",
                        modifier = Modifier.fillMaxSize(),
                        cornerRadius = settings.artworkShapeStyle.cornerDp.dp,
                        isRadio = track?.isLiveStream == true
                    )
                    if (settings.visualizerMode != VisualizerMode.OFF) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        listOf(
                                            Color.Black.copy(alpha = 0.25f),
                                            Color.Black.copy(alpha = 0.65f)
                                        )
                                    )
                                )
                                .padding(14.dp)
                        ) {
                            AudioVisualizerCanvas(
                                frame = audioFrame,
                                mode = settings.visualizerMode,
                                primaryColor = MaterialTheme.colorScheme.primary,
                                secondaryColor = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // Title, Artist, Album & Favorite
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track?.title ?: "Nothing Playing",
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = track?.let {
                            if (it.album.isNotBlank()) "${it.artist} • ${it.album}" else it.artist
                        } ?: "Select a song to start playback",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (track != null) {
                    IconButton(
                        onClick = { viewModel.toggleFavorite(track) },
                        modifier = Modifier.testTag("np_favorite_button")
                    ) {
                        Icon(
                            imageVector = if (track.isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            contentDescription = "Favorite track",
                            tint = if (track.isFavorite) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            playbackState.errorMessage?.let { err ->
                Text(
                    text = err,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Spacer(Modifier.height(10.dp))

            // Interactive Seekbar
            Slider(
                value = if (track?.isLiveStream == true) 1f else effectivePos.toFloat().coerceIn(0f, maxDur.toFloat()),
                onValueChange = {
                    if (track?.isLiveStream != true) {
                        isSeeking = true
                        seekSliderPos = it
                    }
                },
                onValueChangeFinished = {
                    if (track?.isLiveStream != true) {
                        viewModel.container.playbackController.seekTo(seekSliderPos.toLong())
                        isSeeking = false
                    }
                },
                valueRange = 0f..(if (track?.isLiveStream == true) 1f else maxDur.toFloat()),
                enabled = track != null && !track.isLiveStream,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("np_seekbar")
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (track?.isLiveStream == true) "LIVE BROADCAST" else formatTimeMs(effectivePos),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (track?.isLiveStream == true) "STREAM" else formatTimeMs(playbackState.durationMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(10.dp))

            // Main Transport Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { viewModel.container.playbackController.toggleShuffle() },
                    modifier = Modifier.testTag("np_shuffle_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Shuffle,
                        contentDescription = "Shuffle",
                        tint = if (playbackState.shuffleEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(
                    onClick = { viewModel.container.playbackController.skipToPrevious() },
                    modifier = Modifier
                        .size(54.dp)
                        .testTag("np_prev_button")
                ) {
                    Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(36.dp))
                }

                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .clickable { viewModel.container.playbackController.togglePlayPause() }
                        .testTag("np_play_pause_button")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (playbackState.isBuffering) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(28.dp),
                                strokeWidth = 3.dp
                            )
                        } else {
                            Icon(
                                imageVector = if (playbackState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = if (playbackState.isPlaying) "Pause" else "Play",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(38.dp)
                            )
                        }
                    }
                }

                IconButton(
                    onClick = { viewModel.container.playbackController.skipToNext() },
                    modifier = Modifier
                        .size(54.dp)
                        .testTag("np_next_button")
                ) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = "Next", modifier = Modifier.size(36.dp))
                }

                IconButton(
                    onClick = { viewModel.container.playbackController.cycleRepeatMode() },
                    modifier = Modifier.testTag("np_repeat_button")
                ) {
                    Icon(
                        imageVector = if (playbackState.repeatMode == RepeatModeState.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                        contentDescription = playbackState.repeatMode.label,
                        tint = if (playbackState.repeatMode != RepeatModeState.OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // Bottom Studio Action Bar: Lyrics | Equalizer | Queue
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                TextButton(
                    onClick = onOpenLyrics,
                    modifier = Modifier.testTag("np_lyrics_button")
                ) {
                    Icon(Icons.Rounded.Lyrics, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Lyrics")
                }
                TextButton(
                    onClick = onOpenEqualizer,
                    modifier = Modifier.testTag("np_equalizer_button")
                ) {
                    Icon(Icons.Rounded.Equalizer, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Equalizer")
                }
                TextButton(
                    onClick = { showQueueSheet = true },
                    modifier = Modifier.testTag("np_queue_button")
                ) {
                    Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Queue (${playbackState.queue.size})")
                }
            }
        }
    }

    // Queue Management Modal Sheet
    if (showQueueSheet) {
        ModalBottomSheet(onDismissRequest = { showQueueSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Up Next Queue (${playbackState.queue.size})", style = MaterialTheme.typography.titleLarge)
                    Row {
                        IconButton(onClick = {
                            viewModel.saveCurrentQueueAsPlaylist("Queue Mix")
                            showQueueSheet = false
                        }) {
                            Icon(Icons.Rounded.Save, contentDescription = "Save queue as playlist")
                        }
                        IconButton(onClick = {
                            viewModel.container.playbackController.clearQueue()
                            showQueueSheet = false
                        }) {
                            Icon(Icons.Rounded.DeleteSweep, contentDescription = "Clear queue")
                        }
                    }
                }
                LazyColumn(
                    modifier = Modifier.height(360.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    itemsIndexed(playbackState.queue, key = { idx, t -> "${idx}_${t.id}" }) { index, qTrack ->
                        val isCurrent = index == playbackState.currentIndex
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.playTrack(qTrack, playbackState.queue) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isCurrent) {
                                Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            } else {
                                Text("${index + 1}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(24.dp))
                            }
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    qTrack.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    qTrack.artist,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                            IconButton(
                                onClick = { viewModel.container.playbackController.moveQueueItem(index, index - 1) },
                                enabled = index > 0
                            ) {
                                Icon(Icons.Rounded.ArrowUpward, contentDescription = "Move up")
                            }
                            IconButton(
                                onClick = { viewModel.container.playbackController.moveQueueItem(index, index + 1) },
                                enabled = index < playbackState.queue.lastIndex
                            ) {
                                Icon(Icons.Rounded.ArrowDownward, contentDescription = "Move down")
                            }
                            IconButton(onClick = { viewModel.container.playbackController.removeFromQueue(index) }) {
                                Icon(Icons.Rounded.Close, contentDescription = "Remove from queue")
                            }
                        }
                    }
                }
            }
        }
    }

    // Playback Speed Sheet
    if (showSpeedSheet) {
        val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
        ModalBottomSheet(onDismissRequest = { showSpeedSheet = false }) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("Playback Speed (${playbackState.playbackSpeed}x)", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    speeds.forEach { spd ->
                        FilterChip(
                            selected = playbackState.playbackSpeed == spd,
                            onClick = {
                                viewModel.container.playbackController.setPlaybackSpeed(spd)
                                showSpeedSheet = false
                            },
                            label = { Text("${spd}x") }
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
