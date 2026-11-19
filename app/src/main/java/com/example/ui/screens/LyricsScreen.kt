package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.model.LyricsAlignment
import com.example.domain.model.LyricsDisplayMode
import com.example.domain.model.TranslationLanguages
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
fun LyricsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onOpenListenTogether: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    BackHandler { onBack() }

    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val lyricsState by viewModel.lyricsState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val listenState by viewModel.listenTogetherState.collectAsStateWithLifecycle()

    val track = playbackState.currentTrack
    val parsed = lyricsState.parsedLyrics
    val activeRoom = listenState.activeRoom
    val hasSleepTimer = playbackState.sleepTimerRemainingMs > 0L || playbackState.sleepAfterCurrentTrack

    var isFocusExpanded by remember { mutableStateOf(false) }
    var showStyleSheet by remember { mutableStateOf(false) }
    var showTranslateDialog by remember { mutableStateOf(false) }
    var showManualSearchDialog by remember { mutableStateOf(false) }
    var showQueueSheet by remember { mutableStateOf(false) }
    var showSleepSheet by remember { mutableStateOf(false) }
    var showShareSheet by remember { mutableStateOf(false) }

    val importLrcLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) viewModel.importLrcFile(uri) }

    val exportLrcLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri -> if (uri != null) viewModel.exportLrcFile(uri) }

    val (ambientTop, ambientBottom) = rememberArtworkAmbientColor(
        artworkUrl = track?.artworkUrl,
        fallbackSeed = track?.id ?: "koplingfy_lyrics"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(ambientTop, ambientBottom)))
            .statusBarsPadding()
            .navigationBarsPadding()
            .testTag("lyrics_screen")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 580.dp)
                .align(Alignment.Center)
        ) {
            // Upper Stage: Word-by-Word Karaoke Synced Lyrics (Exact Screenshot 2 look)
            KaraokeLyricsCanvas(
                parsed = parsed,
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
                bottomFadeColor = ambientTop.copy(alpha = 0.90f),
                onSeekToMs = { viewModel.container.playbackController.seekTo(it) },
                onRetry = { track?.let { viewModel.loadLyricsForTrack(it, forceRefresh = true) } },
                onManualSearch = { showManualSearchDialog = true },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )

            // Compact Track Header Row (54dp Rounded Artwork + Title/Artist + Fullscreen Focus + More Menu)
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
                        .clickable { onBack() },
                    cornerRadius = 12.dp,
                    isRadio = track?.isLiveStream == true
                )
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track?.title ?: "Lirik",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = track?.artist ?: "Putar lagu untuk melihat lirik",
                        fontSize = 14.sp,
                        color = Color.White.copy(alpha = 0.72f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(
                    onClick = { isFocusExpanded = !isFocusExpanded },
                    modifier = Modifier.testTag("lyrics_focus_btn")
                ) {
                    Icon(
                        imageVector = if (isFocusExpanded) Icons.Rounded.FullscreenExit else Icons.Rounded.CropFree,
                        contentDescription = if (isFocusExpanded) "Tampilkan kontrol" else "Mode fokus lirik",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
                IconButton(
                    onClick = { showStyleSheet = true },
                    modifier = Modifier.testTag("lyrics_style_btn")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MoreHoriz,
                        contentDescription = "Pengaturan lirik",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            // Collapsible Full Player Console (Matches Screenshot 2 seamlessly!)
            AnimatedVisibility(visible = !isFocusExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp)
                        .padding(top = 6.dp, bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
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
                        isLyricsActive = true,
                        isSleepTimerActive = hasSleepTimer,
                        hasActiveRoom = activeRoom != null,
                        onToggleLyrics = onBack,
                        onOpenQueue = { showQueueSheet = true },
                        onOpenShareOrRoom = { showShareSheet = true },
                        onOpenSleepTimer = { showSleepSheet = true }
                    )
                }
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

    // Styling, Translate, Manual Search, Offset & LRC Import/Export Sheet
    if (showStyleSheet) {
        ModalBottomSheet(onDismissRequest = { showStyleSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Pengaturan Lirik", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = {
                            showStyleSheet = false
                            showTranslateDialog = true
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("lyrics_translate_btn")
                    ) {
                        Icon(Icons.Rounded.Translate, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Terjemahkan")
                    }
                    OutlinedButton(
                        onClick = {
                            showStyleSheet = false
                            showManualSearchDialog = true
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("lyrics_manual_search_btn")
                    ) {
                        Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Cari Manual")
                    }
                }

                Text("Mode Tampilan", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LyricsDisplayMode.entries.forEach { mode ->
                        FilterChip(
                            selected = settings.lyricsDisplayMode == mode,
                            onClick = { viewModel.updateLyricsDisplayMode(mode) },
                            label = { Text(mode.label) }
                        )
                    }
                }

                Text("Sync Offset: ${if (lyricsState.offsetMs >= 0) "+" else ""}${lyricsState.offsetMs} ms", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = lyricsState.offsetMs.toFloat(),
                    onValueChange = { viewModel.adjustLyricsOffset(it.toLong()) },
                    valueRange = -5000f..5000f
                )

                Text("Ukuran Teks: ${settings.lyricsFontSizeSp.toInt()} sp", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = settings.lyricsFontSizeSp,
                    onValueChange = { viewModel.updateLyricsFontSize(it) },
                    valueRange = 22f..34f
                )

                Text("Perataan Teks", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LyricsAlignment.entries.forEach { align ->
                        FilterChip(
                            selected = settings.lyricsAlignment == align,
                            onClick = { viewModel.updateLyricsAlignment(align) },
                            label = { Text(align.label) }
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { importLrcLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Rounded.FileUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Import LRC")
                    }
                    if (parsed != null && parsed.lines.isNotEmpty()) {
                        OutlinedButton(
                            onClick = { exportLrcLauncher.launch("${track?.title ?: "lyrics"}.lrc") },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Export LRC")
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    // Translation Language Picker Dialog
    if (showTranslateDialog) {
        AlertDialog(
            onDismissRequest = { showTranslateDialog = false },
            title = { Text("Terjemahkan Lirik") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Engine: ${settings.translationProvider.label}. Timestamp LRC tetap sinkron otomatis.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LazyColumn(modifier = Modifier.height(260.dp)) {
                        items(TranslationLanguages.ALL) { lang ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showTranslateDialog = false
                                        viewModel.translateCurrentLyrics(lang.code)
                                    }
                                    .padding(vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(lang.name, style = MaterialTheme.typography.titleSmall)
                                Text(lang.nativeName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showTranslateDialog = false }) { Text("Tutup") } }
        )
    }

    // Manual Lyrics Search Dialog
    if (showManualSearchDialog) {
        var searchInput by remember { mutableStateOf("${track?.title.orEmpty()} ${track?.artist.orEmpty()}".trim()) }
        AlertDialog(
            onDismissRequest = { showManualSearchDialog = false },
            title = { Text("Cari Lirik Manual") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = searchInput,
                        onValueChange = { searchInput = it },
                        label = { Text("Judul Lagu & Artis") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = { viewModel.searchLyricsManually(searchInput) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Cari Kandidat Lirik") }

                    if (lyricsState.isManualSearching) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        }
                    }
                    LazyColumn(modifier = Modifier.height(220.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(lyricsState.manualSearchResults, key = { it.id }) { cand ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.selectManualLyricsCandidate(cand)
                                        showManualSearchDialog = false
                                    },
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(cand.trackName, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                                    Text(
                                        "${cand.artistName} • ${if (cand.syncedLyrics != null) "Synced LRC" else "Plain"} • Cocok ${cand.matchScore}%",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showManualSearchDialog = false }) { Text("Tutup") } }
        )
    }
}
