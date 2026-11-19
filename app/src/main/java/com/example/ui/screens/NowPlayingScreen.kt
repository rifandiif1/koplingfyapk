package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.rounded.Headphones
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.model.RepeatModeState
import com.example.domain.model.VisualizerMode
import com.example.ui.components.AudioVisualizerCanvas
import com.example.ui.components.SourceBadge
import com.example.ui.components.TrackArtwork
import com.example.ui.components.formatTimeMs
import com.example.ui.viewmodel.MainViewModel
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    viewModel: MainViewModel,
    onClose: () -> Unit,
    onOpenLyrics: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenListenTogether: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler { onClose() }

    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val audioFrame by viewModel.audioAnalysis.collectAsStateWithLifecycle()
    val listenState by viewModel.listenTogetherState.collectAsStateWithLifecycle()
    val track = playbackState.currentTrack
    val activeRoom = listenState.activeRoom

    var showQueueSheet by remember { mutableStateOf(false) }
    var showSpeedSheet by remember { mutableStateOf(false) }
    var isSeeking by remember { mutableStateOf(false) }
    var seekSliderPos by remember { mutableFloatStateOf(0f) }

    val effectivePos = if (isSeeking) seekSliderPos.toLong() else playbackState.positionMs
    val maxDur = playbackState.durationMs.coerceAtLeast(1L)

    // Dynamic ambient top tint per track for rich Spotify/Apple Music-grade atmosphere
    val ambientPalette = remember(track?.id) {
        val swatches = listOf(
            Color(0xFF1D4D4F) to Color(0xFF0E1D24),
            Color(0xFF4A2545) to Color(0xFF1A0F1D),
            Color(0xFF1E3A5F) to Color(0xFF0D1726),
            Color(0xFF4F2E1E) to Color(0xFF1D120D),
            Color(0xFF1F4B3B) to Color(0xFF0D1E19),
            Color(0xFF392B58) to Color(0xFF151024)
        )
        val idx = abs((track?.id ?: "default").hashCode()) % swatches.size
        swatches[idx]
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        ambientPalette.first,
                        ambientPalette.second,
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
                Surface(
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.10f)
                ) {
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier
                            .size(44.dp)
                            .testTag("np_close_button")
                    ) {
                        Icon(
                            Icons.Rounded.KeyboardArrowDown,
                            contentDescription = "Tutup pemutar",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (activeRoom != null) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                            border = BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
                            ),
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .clickable(onClick = onOpenListenTogether)
                                .testTag("np_active_room_pill")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Headphones,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "SYNC • ${activeRoom.roomCode}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.8.sp
                                )
                            }
                        }
                    } else {
                        Text(
                            text = "SEDANG DIPUTAR",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.72f),
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp
                        )
                        if (track != null) {
                            Spacer(Modifier.height(3.dp))
                            SourceBadge(track.source)
                        }
                    }
                }

                Surface(
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.10f)
                ) {
                    IconButton(
                        onClick = { showSpeedSheet = true },
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Speed,
                            contentDescription = "Kecepatan pemutaran",
                            tint = Color.White
                        )
                    }
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

            Spacer(Modifier.height(10.dp))

            // Main Stage: Framed Elevated Artwork + Optional Audio-Reactive Visualizer
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(settings.artworkShapeStyle.cornerDp.dp),
                    shadowElevation = 22.dp,
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
                    color = Color.Black.copy(alpha = 0.3f),
                    modifier = Modifier
                        .fillMaxWidth(0.88f)
                        .aspectRatio(1f)
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        TrackArtwork(
                            artworkUrl = track?.artworkUrl,
                            contentDescription = track?.title ?: "Sampul album",
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
                                                Color.Black.copy(alpha = 0.22f),
                                                Color.Black.copy(alpha = 0.66f)
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
            }

            Spacer(Modifier.height(16.dp))

            // Title, Artist, Album & Favorite
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track?.title ?: "Belum Ada Lagu",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = track?.let {
                            if (it.album.isNotBlank()) "${it.artist} • ${it.album}" else it.artist
                        } ?: "Pilih lagu untuk mulai memutar",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.72f),
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
                            contentDescription = "Favoritkan lagu",
                            tint = if (track.isFavorite) MaterialTheme.colorScheme.secondary else Color.White,
                            modifier = Modifier.size(28.dp)
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                )
            }

            Spacer(Modifier.height(8.dp))

            // Interactive Seekbar (Spotify crisp white active track)
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
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = 0.25f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("np_seekbar")
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (track?.isLiveStream == true) "SIARAN LANGSUNG" else formatTimeMs(effectivePos),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.72f)
                )
                Text(
                    text = if (track?.isLiveStream == true) "LIVE" else formatTimeMs(playbackState.durationMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.72f)
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
                        contentDescription = "Acak",
                        tint = if (playbackState.shuffleEnabled) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.7f)
                    )
                }

                IconButton(
                    onClick = { viewModel.container.playbackController.skipToPrevious() },
                    modifier = Modifier
                        .size(54.dp)
                        .testTag("np_prev_button")
                ) {
                    Icon(
                        Icons.Rounded.SkipPrevious,
                        contentDescription = "Sebelumnya",
                        tint = Color.White,
                        modifier = Modifier.size(38.dp)
                    )
                }

                Surface(
                    shape = CircleShape,
                    color = Color.White,
                    shadowElevation = 10.dp,
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .clickable { viewModel.container.playbackController.togglePlayPause() }
                        .testTag("np_play_pause_button")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (playbackState.isBuffering) {
                            CircularProgressIndicator(
                                color = Color(0xFF0A0E17),
                                modifier = Modifier.size(28.dp),
                                strokeWidth = 3.dp
                            )
                        } else {
                            Icon(
                                imageVector = if (playbackState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = if (playbackState.isPlaying) "Jeda" else "Putar",
                                tint = Color(0xFF0A0E17),
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
                    Icon(
                        Icons.Rounded.SkipNext,
                        contentDescription = "Berikutnya",
                        tint = Color.White,
                        modifier = Modifier.size(38.dp)
                    )
                }

                IconButton(
                    onClick = { viewModel.container.playbackController.cycleRepeatMode() },
                    modifier = Modifier.testTag("np_repeat_button")
                ) {
                    Icon(
                        imageVector = if (playbackState.repeatMode == RepeatModeState.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                        contentDescription = playbackState.repeatMode.label,
                        tint = if (playbackState.repeatMode != RepeatModeState.OFF) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.7f)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Bottom Studio Glass Action Dock: Lirik | Bersama | EQ | Antrean
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = Color.White.copy(alpha = 0.08f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    StudioDockAction(
                        icon = Icons.Rounded.Lyrics,
                        label = "Lirik",
                        highlighted = false,
                        onClick = onOpenLyrics,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("np_lyrics_button")
                    )
                    StudioDockAction(
                        icon = Icons.Rounded.Headphones,
                        label = if (activeRoom != null) activeRoom.roomCode else "Bersama",
                        highlighted = activeRoom != null,
                        onClick = onOpenListenTogether,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("np_listen_together_button")
                    )
                    StudioDockAction(
                        icon = Icons.Rounded.Equalizer,
                        label = "Equalizer",
                        highlighted = false,
                        onClick = onOpenEqualizer,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("np_equalizer_button")
                    )
                    StudioDockAction(
                        icon = Icons.AutoMirrored.Rounded.QueueMusic,
                        label = "Antrean (${playbackState.queue.size})",
                        highlighted = false,
                        onClick = { showQueueSheet = true },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("np_queue_button")
                    )
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
                    Text("Antrean Berikutnya (${playbackState.queue.size})", style = MaterialTheme.typography.titleLarge)
                    Row {
                        IconButton(onClick = {
                            viewModel.saveCurrentQueueAsPlaylist("Queue Mix")
                            showQueueSheet = false
                        }) {
                            Icon(Icons.Rounded.Save, contentDescription = "Simpan antrean ke playlist")
                        }
                        IconButton(onClick = {
                            viewModel.container.playbackController.clearQueue()
                            showQueueSheet = false
                        }) {
                            Icon(Icons.Rounded.DeleteSweep, contentDescription = "Bersihkan antrean")
                        }
                    }
                }
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Antrean Otomatis (Autoplay)",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = if (playbackState.isAutoQueueLoading) {
                                        "Membuat daftar lagu terkait..."
                                    } else if (playbackState.autoQueueEnabled) {
                                        "Otomatis lanjut ke lagu serupa saat selesai"
                                    } else {
                                        "Nonaktif — berhenti di akhir antrean"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Switch(
                                checked = playbackState.autoQueueEnabled,
                                onCheckedChange = { viewModel.container.playbackController.toggleAutoQueue() },
                                modifier = Modifier.testTag("queue_auto_queue_switch")
                            )
                        }
                        if (playbackState.isAutoQueueLoading) {
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(3.dp)
                                    .clip(RoundedCornerShape(50))
                            )
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
                                Icon(Icons.Rounded.ArrowUpward, contentDescription = "Naikkan")
                            }
                            IconButton(
                                onClick = { viewModel.container.playbackController.moveQueueItem(index, index + 1) },
                                enabled = index < playbackState.queue.lastIndex
                            ) {
                                Icon(Icons.Rounded.ArrowDownward, contentDescription = "Turunkan")
                            }
                            IconButton(onClick = { viewModel.container.playbackController.removeFromQueue(index) }) {
                                Icon(Icons.Rounded.Close, contentDescription = "Hapus dari antrean")
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
                Text("Kecepatan Pemutaran (${playbackState.playbackSpeed}x)", style = MaterialTheme.typography.titleLarge)
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

@Composable
private fun StudioDockAction(
    icon: ImageVector,
    label: String,
    highlighted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (highlighted) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.9f),
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (highlighted) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.82f),
            fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
