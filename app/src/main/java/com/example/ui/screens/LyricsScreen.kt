package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
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
import com.example.ui.viewmodel.MainViewModel

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

    val importLrcLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.importLrcFile(uri)
    }

    val exportLrcLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) viewModel.exportLrcFile(uri)
    }

    val parsed = lyricsState.parsedLyrics
    val activeLineIndex = remember(parsed, playbackState.positionMs, lyricsState.offsetMs) {
        if (parsed != null && parsed.isSynced) {
            LrcParser.findActiveLineIndex(parsed.lines, playbackState.positionMs, lyricsState.offsetMs)
        } else -1
    }

    LaunchedEffect(activeLineIndex) {
        if (activeLineIndex >= 0 && parsed != null && parsed.isSynced) {
            listState.animateScrollToItem((activeLineIndex - 2).coerceAtLeast(0))
        }
    }

    val textAlign = when (settings.lyricsAlignment) {
        LyricsAlignment.START -> TextAlign.Start
        LyricsAlignment.CENTER -> TextAlign.Center
        LyricsAlignment.END -> TextAlign.End
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                        MaterialTheme.colorScheme.background
                    )
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding()
            .testTag("lyrics_screen")
    ) {
        // Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track?.title ?: "Lyrics Studio",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = parsed?.providerName?.takeIf { it.isNotBlank() }?.let {
                        "${track?.artist.orEmpty()} • $it${if (parsed.isSynced) " (Synced)" else " (Plain)"}"
                    } ?: track?.artist ?: "No track loaded",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = { showTranslateDialog = true }, modifier = Modifier.testTag("lyrics_translate_btn")) {
                Icon(Icons.Rounded.Translate, contentDescription = "Translate lyrics")
            }
            IconButton(onClick = { showManualSearchDialog = true }, modifier = Modifier.testTag("lyrics_manual_search_btn")) {
                Icon(Icons.Rounded.Search, contentDescription = "Search lyrics manually")
            }
            IconButton(onClick = { showStyleSheet = true }, modifier = Modifier.testTag("lyrics_style_btn")) {
                Icon(Icons.Rounded.Tune, contentDescription = "Lyrics offset and styling")
            }
        }

        // Display Mode Chips + Import/Export Bar
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(LyricsDisplayMode.entries) { mode ->
                FilterChip(
                    selected = settings.lyricsDisplayMode == mode,
                    onClick = { viewModel.updateLyricsDisplayMode(mode) },
                    label = { Text(mode.label) }
                )
            }
            item {
                OutlinedButton(
                    onClick = { importLrcLauncher.launch(arrayOf("*/*")) },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                ) {
                    Icon(Icons.Rounded.FileUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Import LRC", style = MaterialTheme.typography.labelSmall)
                }
            }
            if (parsed != null && parsed.lines.isNotEmpty()) {
                item {
                    OutlinedButton(
                        onClick = { exportLrcLauncher.launch("${track?.title ?: "lyrics"}.lrc") },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                    ) {
                        Icon(Icons.Rounded.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Export LRC", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        // Main Lyrics Canvas
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when {
                lyricsState.isLoading || lyricsState.isTranslating -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text(
                            if (lyricsState.isTranslating) "Translating lyrics (caching timestamps)..."
                            else "Matching lyrics via LRCLIB...",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                parsed == null || parsed.lines.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = lyricsState.errorMessage ?: "Play a song to automatically fetch synchronized lyrics.",
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (track != null) {
                                Button(onClick = { viewModel.loadLyricsForTrack(track, forceRefresh = true) }) {
                                    Icon(Icons.Rounded.Refresh, contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Retry Fetch")
                                }
                            }
                            OutlinedButton(onClick = { showManualSearchDialog = true }) {
                                Text("Manual Search")
                            }
                        }
                    }
                }
                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 48.dp),
                        verticalArrangement = Arrangement.spacedBy(settings.lyricsLineSpacingSp.dp)
                    ) {
                        itemsIndexed(parsed.lines, key = { idx, line -> "${idx}_${line.timeMs}" }) { index, line ->
                            val isActive = parsed.isSynced && index == activeLineIndex
                            val lineColor by animateColorAsState(
                                targetValue = when {
                                    isActive -> MaterialTheme.colorScheme.primary
                                    parsed.isSynced -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                                    else -> MaterialTheme.colorScheme.onSurface
                                },
                                label = "lyric_color"
                            )
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = parsed.isSynced && line.timeMs >= 0L) {
                                        viewModel.container.playbackController.seekTo(
                                            (line.timeMs - lyricsState.offsetMs).coerceAtLeast(0L)
                                        )
                                    }
                                    .padding(vertical = 4.dp)
                            ) {
                                val showOriginal = settings.lyricsDisplayMode != LyricsDisplayMode.TRANSLATED ||
                                    line.translatedText.isNullOrBlank()
                                val showTranslation = settings.lyricsDisplayMode != LyricsDisplayMode.ORIGINAL &&
                                    !line.translatedText.isNullOrBlank()

                                if (showOriginal) {
                                    Text(
                                        text = line.text.ifBlank { "♪" },
                                        fontSize = (if (isActive) settings.lyricsFontSizeSp + 3f else settings.lyricsFontSizeSp).sp,
                                        lineHeight = (settings.lyricsFontSizeSp + 10f).sp,
                                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                                        color = lineColor,
                                        textAlign = textAlign,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                                if (showTranslation) {
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = line.translatedText.orEmpty(),
                                        fontSize = (settings.lyricsFontSizeSp - 3f).coerceAtLeast(13f).sp,
                                        lineHeight = (settings.lyricsFontSizeSp + 4f).sp,
                                        color = if (isActive) MaterialTheme.colorScheme.secondary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = textAlign,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Compact Playback Bar at Bottom
        if (track != null) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Offset: ${if (lyricsState.offsetMs >= 0) "+" else ""}${lyricsState.offsetMs}ms",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { viewModel.adjustLyricsOffset(lyricsState.offsetMs - 250L) }) {
                            Text("-0.25s")
                        }
                        IconButton(onClick = { viewModel.container.playbackController.togglePlayPause() }) {
                            Icon(
                                if (playbackState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = "Play or pause",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        OutlinedButton(onClick = { viewModel.adjustLyricsOffset(lyricsState.offsetMs + 250L) }) {
                            Text("+0.25s")
                        }
                    }
                }
            }
        }
    }

    // Styling & Offset Bottom Sheet
    if (showStyleSheet) {
        ModalBottomSheet(onDismissRequest = { showStyleSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Lyrics Customization", style = MaterialTheme.typography.titleLarge)

                Text("Sync Offset: ${lyricsState.offsetMs} ms", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = lyricsState.offsetMs.toFloat(),
                    onValueChange = { viewModel.adjustLyricsOffset(it.toLong()) },
                    valueRange = -5000f..5000f
                )

                Text("Font Size: ${settings.lyricsFontSizeSp.toInt()} sp", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = settings.lyricsFontSizeSp,
                    onValueChange = { viewModel.updateLyricsFontSize(it) },
                    valueRange = 14f..32f
                )

                Text("Line Spacing: ${settings.lyricsLineSpacingSp.toInt()} dp", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = settings.lyricsLineSpacingSp,
                    onValueChange = { viewModel.updateLyricsLineSpacing(it) },
                    valueRange = 4f..24f
                )

                Text("Alignment", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LyricsAlignment.entries.forEach { align ->
                        FilterChip(
                            selected = settings.lyricsAlignment == align,
                            onClick = { viewModel.updateLyricsAlignment(align) },
                            label = { Text(align.label) }
                        )
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
            title = { Text("Translate Lyrics") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Engine: ${settings.translationProvider.label}. Exact LRC timestamps are preserved and cached locally.",
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
            confirmButton = { TextButton(onClick = { showTranslateDialog = false }) { Text("Close") } }
        )
    }

    // Manual Lyrics Search Dialog
    if (showManualSearchDialog) {
        var searchInput by remember { mutableStateOf("${track?.title.orEmpty()} ${track?.artist.orEmpty()}".trim()) }
        AlertDialog(
            onDismissRequest = { showManualSearchDialog = false },
            title = { Text("Search LRCLIB Manually") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = searchInput,
                        onValueChange = { searchInput = it },
                        label = { Text("Track & Artist") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = { viewModel.searchLyricsManually(searchInput) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Search Candidates") }

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
                                        "${cand.artistName} • ${if (cand.syncedLyrics != null) "Synced LRC" else "Plain"} • Match ${cand.matchScore}%",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showManualSearchDialog = false }) { Text("Close") } }
        )
    }
}
