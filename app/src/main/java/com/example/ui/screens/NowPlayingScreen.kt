package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.model.VisualizerMode
import com.example.ui.components.AudioVisualizerCanvas
import com.example.ui.components.FloatingGlassPlayerDock
import com.example.ui.components.KaraokeLyricsCanvas
import com.example.ui.components.PlayerQueueBottomSheet
import com.example.ui.components.PlayerShareAndRoomSheet
import com.example.ui.components.PlayerSleepTimerSheet
import com.example.ui.components.PlayerTransportRow
import com.example.ui.components.SlimPlayerProgressBar
import com.example.ui.components.SystemVolumeRow
import com.example.ui.components.TrackArtwork
import com.example.ui.components.rememberArtworkAmbientColor
import com.example.ui.viewmodel.MainViewModel

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
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val lyricsState by viewModel.lyricsState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val audioFrame by viewModel.audioAnalysis.collectAsStateWithLifecycle()
    val listenState by viewModel.listenTogetherState.collectAsStateWithLifecycle()

    val track = playbackState.currentTrack
    val activeRoom = listenState.activeRoom

    var isLyricsMode by remember { mutableStateOf(false) }
    var showQueueSheet by remember { mutableStateOf(false) }
    var showSleepSheet by remember { mutableStateOf(false) }
    var showShareSheet by remember { mutableStateOf(false) }
    var showMoreSheet by remember { mutableStateOf(false) }
    var showSpeedSheet by remember { mutableStateOf(false) }

    BackHandler {
        if (isLyricsMode) isLyricsMode = false else onClose()
    }

    val (ambientTop, ambientBottom) = rememberArtworkAmbientColor(
        artworkUrl = track?.artworkUrl,
        fallbackSeed = track?.id ?: "koplingfy"
    )

    val isVisualMode = settings.visualizerMode != VisualizerMode.OFF
    val hasSleepTimer = playbackState.sleepTimerRemainingMs > 0L || playbackState.sleepAfterCurrentTrack

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(ambientTop, ambientBottom)
                )
            )
            .testTag("now_playing_screen")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 580.dp)
                .align(Alignment.Center)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Upper Stage: Seamless Crossfade between Full-Bleed Melted Artwork (Screenshot 1)
            // and Live Word-by-Word Karaoke Lyrics (Screenshot 2)
            AnimatedContent(
                targetState = isLyricsMode,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                label = "now_playing_stage"
            ) { lyricsVisible ->
                if (!lyricsVisible) {
                    // --- SCREENSHOT 1: FULL-BLEED ARTWORK STAGE ---
                    Box(modifier = Modifier.fillMaxSize()) {
                        // Full-bleed edge-to-edge artwork spanning behind status bar
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(0.84f)
                                .align(Alignment.TopCenter)
                        ) {
                            TrackArtwork(
                                artworkUrl = track?.artworkUrl,
                                contentDescription = track?.title ?: "Sampul album",
                                modifier = Modifier.fillMaxSize(),
                                cornerRadius = 0.dp,
                                isRadio = track?.isLiveStream == true
                            )

                            // Optional live audio-reactive visualizer overlay when "Visual" tab is active
                            if (isVisualMode) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Black.copy(alpha = 0.48f))
                                        .statusBarsPadding()
                                        .padding(top = 62.dp, bottom = 56.dp, start = 20.dp, end = 20.dp)
                                ) {
                                    AudioVisualizerCanvas(
                                        frame = audioFrame,
                                        mode = settings.visualizerMode,
                                        primaryColor = Color.White,
                                        secondaryColor = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }

                            // Subtle top protective scrim so "Sedang diputar" is always readable on white covers
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(110.dp)
                                    .align(Alignment.TopCenter)
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(
                                                Color.Black.copy(alpha = 0.28f),
                                                Color.Transparent
                                            )
                                        )
                                    )
                            )

                            // Seamless melt gradient from bottom of artwork into matte atmospheric backdrop
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight(0.48f)
                                    .align(Alignment.BottomCenter)
                                    .background(
                                        Brush.verticalGradient(
                                            colorStops = arrayOf(
                                                0.0f to Color.Transparent,
                                                0.45f to ambientTop.copy(alpha = 0.72f),
                                                0.82f to ambientTop.copy(alpha = 0.96f),
                                                1.0f to ambientTop
                                            )
                                        )
                                    )
                            )
                        }

                        // Top-left "Sedang diputar / Mix ..." header + subtle top-right collapse button
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .statusBarsPadding()
                                .padding(horizontal = 20.dp, vertical = 12.dp)
                                .align(Alignment.TopCenter),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                val textShadow = Shadow(
                                    color = Color.Black.copy(alpha = 0.35f),
                                    offset = Offset(0f, 1.5f),
                                    blurRadius = 6f
                                )
                                Text(
                                    text = if (activeRoom != null) "Dengarkan Bersama • ${activeRoom.roomCode}" else "Sedang diputar",
                                    style = MaterialTheme.typography.labelMedium.copy(shadow = textShadow),
                                    color = Color.White.copy(alpha = 0.92f),
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = track?.let { "Mix ${it.title}" } ?: "Koplingfy Mix",
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontStyle = FontStyle.Italic,
                                        fontWeight = FontWeight.ExtraBold,
                                        shadow = textShadow
                                    ),
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Surface(
                                shape = CircleShape,
                                color = Color.Black.copy(alpha = 0.24f),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f))
                            ) {
                                IconButton(
                                    onClick = onClose,
                                    modifier = Modifier
                                        .size(38.dp)
                                        .testTag("np_close_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.KeyboardArrowDown,
                                        contentDescription = "Tutup pemutar",
                                        tint = Color.White,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }

                        // Bottom section of upper stage: Frosted "Audio | Visual" segmented pill + Title/Artist/Heart/More
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomCenter)
                                .padding(horizontal = 22.dp, vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // Frosted Glass Segmented Pill ("Audio | Visual") matching Screenshot 1
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = Color.White.copy(alpha = 0.12f),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.28f)),
                                modifier = Modifier.height(38.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(3.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .clip(RoundedCornerShape(50))
                                            .background(if (!isVisualMode) Color.White.copy(alpha = 0.24f) else Color.Transparent)
                                            .clickable { viewModel.updateVisualizerMode(VisualizerMode.OFF) }
                                            .padding(horizontal = 22.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "Audio",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = if (!isVisualMode) FontWeight.SemiBold else FontWeight.Normal,
                                            color = if (!isVisualMode) Color.White else Color.White.copy(alpha = 0.72f)
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .clip(RoundedCornerShape(50))
                                            .background(if (isVisualMode) Color.White.copy(alpha = 0.24f) else Color.Transparent)
                                            .clickable {
                                                val modes = VisualizerMode.entries.filter { it != VisualizerMode.OFF }
                                                val nextMode = if (isVisualMode && modes.isNotEmpty()) {
                                                    val idx = (modes.indexOf(settings.visualizerMode) + 1) % modes.size
                                                    modes[idx]
                                                } else {
                                                    modes.firstOrNull() ?: VisualizerMode.OFF
                                                }
                                                viewModel.updateVisualizerMode(nextMode)
                                            }
                                            .padding(horizontal = 20.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Rounded.GraphicEq,
                                                contentDescription = null,
                                                tint = if (isVisualMode) Color.White else Color.White.copy(alpha = 0.78f),
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Spacer(Modifier.width(5.dp))
                                            Text(
                                                text = "Visual",
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = if (isVisualMode) FontWeight.SemiBold else FontWeight.Normal,
                                                color = if (isVisualMode) Color.White else Color.White.copy(alpha = 0.78f)
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(Modifier.height(18.dp))

                            // Title, Artist & Circular Frosted Glass Favorite + More Buttons
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = track?.title ?: "Belum Ada Lagu",
                                        fontSize = 22.sp,
                                        lineHeight = 26.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = track?.artist ?: "Pilih lagu untuk mulai memutar",
                                        fontSize = 15.sp,
                                        color = Color.White.copy(alpha = 0.72f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Spacer(Modifier.width(10.dp))

                                if (track != null) {
                                    Surface(
                                        shape = CircleShape,
                                        color = Color.White.copy(alpha = 0.14f),
                                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.24f)),
                                        modifier = Modifier.size(42.dp)
                                    ) {
                                        IconButton(
                                            onClick = { viewModel.toggleFavorite(track) },
                                            modifier = Modifier.testTag("np_favorite_button")
                                        ) {
                                            Icon(
                                                imageVector = if (track.isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                                contentDescription = "Favoritkan lagu",
                                                tint = if (track.isFavorite) Color(0xFFFF5C8A) else Color.White,
                                                modifier = Modifier.size(21.dp)
                                            )
                                        }
                                    }
                                    Spacer(Modifier.width(10.dp))
                                }

                                Surface(
                                    shape = CircleShape,
                                    color = Color.White.copy(alpha = 0.14f),
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.24f)),
                                    modifier = Modifier.size(42.dp)
                                ) {
                                    IconButton(
                                        onClick = { showMoreSheet = true },
                                        modifier = Modifier.testTag("np_more_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.MoreVert,
                                            contentDescription = "Opsi lainnya",
                                            tint = Color.White,
                                            modifier = Modifier.size(21.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // --- SCREENSHOT 2: LIVE KARAOKE LYRICS + COMPACT TRACK HEADER ---
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                    ) {
                        KaraokeLyricsCanvas(
                            parsed = lyricsState.parsedLyrics,
                            positionMs = playbackState.positionMs,
                            offsetMs = lyricsState.offsetMs,
                            isLoading = lyricsState.isLoading,
                            isTranslating = lyricsState.isTranslating,
                            errorMessage = lyricsState.errorMessage,
                            fontSizeSp = settings.lyricsFontSizeSp,
                            lineSpacingSp = settings.lyricsLineSpacingSp,
                            alignment = settings.lyricsAlignment,
                            displayMode = settings.lyricsDisplayMode,
                            topFadeColor = ambientTop,
                            bottomFadeColor = ambientTop.copy(alpha = 0.92f),
                            onSeekToMs = { viewModel.container.playbackController.seekTo(it) },
                            onRetry = { track?.let { viewModel.loadLyricsForTrack(it, forceRefresh = true) } },
                            onManualSearch = onOpenLyrics,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        )

                        // Compact Rounded Artwork + Title/Artist + Fullscreen Focus & More Row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 22.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TrackArtwork(
                                artworkUrl = track?.artworkUrl,
                                contentDescription = track?.title ?: "Sampul album",
                                modifier = Modifier
                                    .size(54.dp)
                                    .clickable { isLyricsMode = false },
                                cornerRadius = 12.dp,
                                isRadio = track?.isLiveStream == true
                            )
                            Spacer(Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = track?.title ?: "Belum Ada Lagu",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = track?.artist ?: "Koplingfy",
                                    fontSize = 14.sp,
                                    color = Color.White.copy(alpha = 0.72f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(
                                onClick = onOpenLyrics,
                                modifier = Modifier.testTag("np_lyrics_fullscreen_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.CropFree,
                                    contentDescription = "Buka layar penuh lirik",
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            IconButton(onClick = onOpenLyrics) {
                                Icon(
                                    imageVector = Icons.Rounded.MoreHoriz,
                                    contentDescription = "Pengaturan lirik",
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Shared Lower Playback Console (Identical across Screenshot 1 & Screenshot 2!)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp)
                    .padding(top = 8.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                playbackState.errorMessage?.let { err ->
                    Text(
                        text = err,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFFFB4AB),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                SlimPlayerProgressBar(
                    positionMs = playbackState.positionMs,
                    durationMs = playbackState.durationMs,
                    isLiveStream = track?.isLiveStream == true,
                    enabled = track != null,
                    onSeekTo = { viewModel.container.playbackController.seekTo(it) }
                )

                PlayerTransportRow(
                    isPlaying = playbackState.isPlaying,
                    isBuffering = playbackState.isBuffering,
                    shuffleEnabled = playbackState.shuffleEnabled,
                    repeatMode = playbackState.repeatMode,
                    onToggleShuffle = { viewModel.container.playbackController.toggleShuffle() },
                    onPrevious = { viewModel.container.playbackController.skipToPrevious() },
                    onTogglePlayPause = { viewModel.container.playbackController.togglePlayPause() },
                    onNext = { viewModel.container.playbackController.skipToNext() },
                    onCycleRepeat = { viewModel.container.playbackController.cycleRepeatMode() }
                )

                SystemVolumeRow()

                Spacer(Modifier.height(2.dp))

                FloatingGlassPlayerDock(
                    isLyricsActive = isLyricsMode,
                    isSleepTimerActive = hasSleepTimer,
                    hasActiveRoom = activeRoom != null,
                    onToggleLyrics = {
                        isLyricsMode = !isLyricsMode
                        if (isLyricsMode && track != null && lyricsState.trackId != track.id) {
                            viewModel.loadLyricsForTrack(track, forceRefresh = false)
                        }
                    },
                    onOpenQueue = { showQueueSheet = true },
                    onOpenShareOrRoom = { showShareSheet = true },
                    onOpenSleepTimer = { showSleepSheet = true }
                )
            }
        }
    }

    if (showQueueSheet) {
        PlayerQueueBottomSheet(
            viewModel = viewModel,
            playbackState = playbackState,
            onDismiss = { showQueueSheet = false }
        )
    }

    if (showSleepSheet) {
        PlayerSleepTimerSheet(
            viewModel = viewModel,
            playbackState = playbackState,
            onDismiss = { showSleepSheet = false }
        )
    }

    if (showShareSheet) {
        PlayerShareAndRoomSheet(
            track = track,
            activeRoomCode = activeRoom?.roomCode,
            onOpenListenTogether = onOpenListenTogether,
            onDismiss = { showShareSheet = false }
        )
    }

    if (showMoreSheet) {
        ModalBottomSheet(onDismissRequest = { showMoreSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Opsi Pemutar", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                MoreOptionTile(
                    icon = Icons.Rounded.Equalizer,
                    title = "Equalizer & Efek Audio",
                    subtitle = "Bass Boost, Virtualizer & 5-Band EQ",
                    onClick = {
                        showMoreSheet = false
                        onOpenEqualizer()
                    }
                )
                MoreOptionTile(
                    icon = Icons.Rounded.Speed,
                    title = "Kecepatan Pemutaran (${playbackState.playbackSpeed}x)",
                    subtitle = "Atur tempo 0.5x hingga 2.0x",
                    onClick = {
                        showMoreSheet = false
                        showSpeedSheet = true
                    }
                )
                MoreOptionTile(
                    icon = Icons.Rounded.Headphones,
                    title = if (activeRoom != null) "Room Aktif • ${activeRoom.roomCode}" else "Dengarkan Bersama",
                    subtitle = "Sinkronkan pemutaran lagu dengan teman",
                    onClick = {
                        showMoreSheet = false
                        onOpenListenTogether()
                    }
                )
                MoreOptionTile(
                    icon = Icons.Rounded.Tune,
                    title = "Pengaturan & Terjemahan Lirik",
                    subtitle = "Cari lirik manual, terjemahkan, sync offset & LRC",
                    onClick = {
                        showMoreSheet = false
                        onOpenLyrics()
                    }
                )
                Spacer(Modifier.height(18.dp))
            }
        }
    }

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
private fun MoreOptionTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
