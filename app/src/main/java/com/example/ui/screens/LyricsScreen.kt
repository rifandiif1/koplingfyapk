package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Tune
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
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.model.LyricsAlignment
import com.example.domain.model.LyricsDisplayMode
import com.example.domain.model.TranslationLanguages
import com.example.lyrics.LrcParser
import com.example.ui.components.TrackArtwork
import com.example.ui.components.formatTimeMs
import com.example.ui.viewmodel.MainViewModel
import kotlin.math.abs

/**
 * Curated Spotify-style rich full-screen lyric mood palettes (vibrant upper wash -> deep grounded lower wash).
 */
private val spotifyLyricPalettes: List<Pair<Color, Color>> = listOf(
    Color(0xFF9C254D) to Color(0xFF3E0B1E), // Crimson Berry
    Color(0xFF1D4E89) to Color(0xFF0A1D37), // Deep Cobalt
    Color(0xFF1B6B4A) to Color(0xFF092A1C), // Emerald Forest
    Color(0xFFB44C23) to Color(0xFF421808), // Sunset Terracotta
    Color(0xFF5B3192) to Color(0xFF1F0E38), // Royal Velvet
    Color(0xFF8C5A1E) to Color(0xFF341E07)  // Warm Amber Studio
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler { onBack() }

    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val lyricsState by viewModel.lyricsState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val track = playbackState.currentTrack

    val listState = rememberLazyListState()
    var showStyleSheet by remember { mutableStateOf(false) }
    var showTranslateDialog by remember { mutableStateOf(false) }
    var showManualSearchDialog by remember { mutableStateOf(false) }
    var isSeeking by remember { mutableStateOf(false) }
    var seekSliderPos by remember { mutableFloatStateOf(0f) }

    val importLrcLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) viewModel.importLrcFile(uri) }

    val exportLrcLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri -> if (uri != null) viewModel.exportLrcFile(uri) }

    // Pick a vibrant Spotify-inspired gradient anchored to the current track
    val paletteIndex = remember(track?.id) {
        val hash = abs((track?.title.orEmpty() + track?.artist.orEmpty()).hashCode())
        hash % spotifyLyricPalettes.size
    }
    val topBackdrop by animateColorAsState(
        targetValue = spotifyLyricPalettes[paletteIndex].first,
        animationSpec = tween(600),
        label = "spotify_bg_top"
    )
    val bottomBackdrop by animateColorAsState(
        targetValue = spotifyLyricPalettes[paletteIndex].second,
        animationSpec = tween(600),
        label = "spotify_bg_bottom"
    )

    val parsed = lyricsState.parsedLyrics
    val activeLineIndex = remember(parsed, playbackState.positionMs, lyricsState.offsetMs) {
        if (parsed != null && parsed.isSynced) {
            LrcParser.findActiveLineIndex(parsed.lines, playbackState.positionMs, lyricsState.offsetMs)
        } else -1
    }

    // Smooth Spotify auto-scroll keeping active line centered in the upper-middle third
    LaunchedEffect(activeLineIndex) {
        if (activeLineIndex >= 0 && parsed != null && parsed.isSynced) {
            listState.animateScrollToItem(
                index = (activeLineIndex - 1).coerceAtLeast(0),
                scrollOffset = -120
            )
        }
    }

    val textAlign = when (settings.lyricsAlignment) {
        LyricsAlignment.START -> TextAlign.Start
        LyricsAlignment.CENTER -> TextAlign.Center
        LyricsAlignment.END -> TextAlign.End
    }

    val effectivePos = if (isSeeking) seekSliderPos.toLong() else playbackState.positionMs
    val maxDur = playbackState.durationMs.coerceAtLeast(1L)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        topBackdrop,
                        topBackdrop.copy(alpha = 0.88f),
                        bottomBackdrop
                    )
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding()
            .testTag("lyrics_screen")
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Minimal Spotify-Style Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.22f),
                    modifier = Modifier.size(40.dp)
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Rounded.KeyboardArrowDown,
                            contentDescription = "Tutup lirik",
                            tint = Color.White,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }

                Spacer(Modifier.width(10.dp))

                if (track != null) {
                    TrackArtwork(
                        artworkUrl = track.artworkUrl,
                        contentDescription = track.title,
                        modifier = Modifier.size(38.dp),
                        cornerRadius = 8.dp
                    )
                    Spacer(Modifier.width(10.dp))
                }

                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.Start
                ) {
                    Text(
                        text = track?.title ?: "Lirik",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = track?.artist ?: "Putar lagu untuk melihat lirik",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.78f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(
                    onClick = { showTranslateDialog = true },
                    modifier = Modifier.testTag("lyrics_translate_btn")
                ) {
                    Icon(Icons.Rounded.Translate, contentDescription = "Terjemahkan lirik", tint = Color.White)
                }
                IconButton(
                    onClick = { showManualSearchDialog = true },
                    modifier = Modifier.testTag("lyrics_manual_search_btn")
                ) {
                    Icon(Icons.Rounded.Search, contentDescription = "Cari lirik manual", tint = Color.White)
                }
                IconButton(
                    onClick = { showStyleSheet = true },
                    modifier = Modifier.testTag("lyrics_style_btn")
                ) {
                    Icon(Icons.Rounded.Tune, contentDescription = "Pengaturan gaya lirik", tint = Color.White)
                }
            }

            // Full-Screen Spotify Lyrics Canvas
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when {
                    lyricsState.isLoading || lyricsState.isTranslating -> {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator(color = Color.White)
                            Text(
                                text = if (lyricsState.isTranslating) "Menerjemahkan lirik..." else "Memuat lirik sinkron...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.85f)
                            )
                        }
                    }
                    parsed == null || parsed.lines.isEmpty() -> {
                        Column(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Text(
                                text = lyricsState.errorMessage ?: "Lirik belum tersedia untuk lagu ini.",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center,
                                color = Color.White.copy(alpha = 0.88f)
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                if (track != null) {
                                    Surface(
                                        shape = RoundedCornerShape(50),
                                        color = Color.White,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(50))
                                            .clickable { viewModel.loadLyricsForTrack(track, forceRefresh = true) }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Rounded.Refresh, contentDescription = null, tint = bottomBackdrop, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("Coba Lagi", color = bottomBackdrop, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = Color.White.copy(alpha = 0.18f),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .clickable { showManualSearchDialog = true }
                                ) {
                                    Text(
                                        text = "Cari Manual",
                                        color = Color.White,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
                                    )
                                }
                            }
                        }
                    }
                    else -> {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = 24.dp,
                                end = 24.dp,
                                top = 36.dp,
                                bottom = 96.dp
                            ),
                            verticalArrangement = Arrangement.spacedBy(settings.lyricsLineSpacingSp.coerceAtLeast(14f).dp)
                        ) {
                            itemsIndexed(parsed.lines, key = { idx, line -> "${idx}_${line.timeMs}" }) { index, line ->
                                val isActive = parsed.isSynced && index == activeLineIndex
                                val isPast = parsed.isSynced && activeLineIndex >= 0 && index < activeLineIndex

                                val targetColor = when {
                                    isActive -> Color.White
                                    isPast -> Color.White.copy(alpha = 0.62f)
                                    parsed.isSynced -> Color.Black.copy(alpha = 0.45f)
                                    else -> Color.White.copy(alpha = 0.92f)
                                }
                                val animatedColor by animateColorAsState(
                                    targetValue = targetColor,
                                    animationSpec = tween(220),
                                    label = "spotify_line_color"
                                )
                                val scale by animateFloatAsState(
                                    targetValue = if (isActive) 1.03f else 1.0f,
                                    animationSpec = spring(stiffness = 450f, dampingRatio = 0.8f),
                                    label = "spotify_line_scale"
                                )

                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable(enabled = parsed.isSynced && line.timeMs >= 0L) {
                                            viewModel.container.playbackController.seekTo(
                                                (line.timeMs - lyricsState.offsetMs).coerceAtLeast(0L)
                                            )
                                        }
                                        .graphicsLayer {
                                            scaleX = scale
                                            scaleY = scale
                                        }
                                        .padding(vertical = 4.dp)
                                ) {
                                    val showOriginal = settings.lyricsDisplayMode != LyricsDisplayMode.TRANSLATED ||
                                        line.translatedText.isNullOrBlank()
                                    val showTranslation = settings.lyricsDisplayMode != LyricsDisplayMode.ORIGINAL &&
                                        !line.translatedText.isNullOrBlank()

                                    if (showOriginal) {
                                        Text(
                                            text = line.text.ifBlank { "•••" },
                                            fontSize = settings.lyricsFontSizeSp.coerceIn(20f, 34f).sp,
                                            lineHeight = (settings.lyricsFontSizeSp.coerceIn(20f, 34f) + 9f).sp,
                                            fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Bold,
                                            color = animatedColor,
                                            textAlign = textAlign,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                    if (showTranslation) {
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = line.translatedText.orEmpty(),
                                            fontSize = (settings.lyricsFontSizeSp - 7f).coerceAtLeast(15f).sp,
                                            lineHeight = (settings.lyricsFontSizeSp - 1f).coerceAtLeast(21f).sp,
                                            fontWeight = FontWeight.Medium,
                                            color = if (isActive) Color.White.copy(alpha = 0.88f)
                                            else Color.White.copy(alpha = 0.45f),
                                            textAlign = textAlign,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }

                            // Subtle provider attribution footer just like Spotify ("Lyrics provided by...")
                            item {
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    text = "Lirik disediakan oleh ${parsed.providerName.ifBlank { "LRCLIB" }}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White.copy(alpha = 0.50f),
                                    textAlign = textAlign,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }

                        // Soft top & bottom fade scrims for Spotify depth
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(26.dp)
                                .align(Alignment.TopCenter)
                                .background(
                                    Brush.verticalGradient(listOf(topBackdrop, Color.Transparent))
                                )
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(42.dp)
                                .align(Alignment.BottomCenter)
                                .background(
                                    Brush.verticalGradient(listOf(Color.Transparent, bottomBackdrop))
                                )
                        )
                    }
                }
            }

            // Spotify-Style Bottom Playback Control Dock
            if (track != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Slider(
                        value = if (track.isLiveStream) 1f else effectivePos.toFloat().coerceIn(0f, maxDur.toFloat()),
                        onValueChange = {
                            if (!track.isLiveStream) {
                                isSeeking = true
                                seekSliderPos = it
                            }
                        },
                        onValueChangeFinished = {
                            if (!track.isLiveStream) {
                                viewModel.container.playbackController.seekTo(seekSliderPos.toLong())
                                isSeeking = false
                            }
                        },
                        valueRange = 0f..(if (track.isLiveStream) 1f else maxDur.toFloat()),
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color.White,
                            inactiveTrackColor = Color.White.copy(alpha = 0.28f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = formatTimeMs(effectivePos),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.72f)
                        )
                        Text(
                            text = formatTimeMs(playbackState.durationMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.72f)
                        )
                    }

                    Spacer(Modifier.height(4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Quick Sync Offset -0.25s
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = Color.White.copy(alpha = 0.14f),
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .clickable { viewModel.adjustLyricsOffset(lyricsState.offsetMs - 250L) }
                        ) {
                            Text(
                                text = "-0.25s",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }

                        IconButton(onClick = { viewModel.container.playbackController.skipToPrevious() }) {
                            Icon(Icons.Rounded.SkipPrevious, contentDescription = "Sebelumnya", tint = Color.White, modifier = Modifier.size(30.dp))
                        }

                        // Signature Spotify Pure White Circular Play/Pause Button
                        Surface(
                            shape = CircleShape,
                            color = Color.White,
                            modifier = Modifier
                                .size(60.dp)
                                .clip(CircleShape)
                                .clickable { viewModel.container.playbackController.togglePlayPause() }
                                .testTag("lyrics_play_pause_btn")
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (playbackState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    contentDescription = if (playbackState.isPlaying) "Jeda" else "Putar",
                                    tint = bottomBackdrop,
                                    modifier = Modifier.size(34.dp)
                                )
                            }
                        }

                        IconButton(onClick = { viewModel.container.playbackController.skipToNext() }) {
                            Icon(Icons.Rounded.SkipNext, contentDescription = "Berikutnya", tint = Color.White, modifier = Modifier.size(30.dp))
                        }

                        // Quick Sync Offset +0.25s
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = Color.White.copy(alpha = 0.14f),
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .clickable { viewModel.adjustLyricsOffset(lyricsState.offsetMs + 250L) }
                        ) {
                            Text(
                                text = "+0.25s",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    // Styling, Display Mode, Offset & LRC Import/Export Bottom Sheet
    if (showStyleSheet) {
        ModalBottomSheet(onDismissRequest = { showStyleSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Pengaturan Lirik", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

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
                    valueRange = 20f..34f
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
