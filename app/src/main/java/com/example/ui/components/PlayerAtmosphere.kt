package com.example.ui.components

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.media.AudioManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.example.domain.model.LyricsAlignment
import com.example.domain.model.LyricsDisplayMode
import com.example.domain.model.RepeatModeState
import com.example.domain.model.Track
import com.example.lyrics.LrcParser
import com.example.lyrics.ParsedLyrics
import com.example.playback.PlaybackUiState
import com.example.ui.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Samples the real album artwork bitmap via Coil and transforms its dominant hue into
 * a silky Apple Music / Auralys matte atmospheric tone (soft slate-gray on monochrome covers,
 * deep velvety muted color washes on vibrant covers).
 */
@Composable
fun rememberArtworkAmbientColor(
    artworkUrl: String?,
    fallbackSeed: String
): Pair<Color, Color> {
    val context = LocalContext.current
    val fallbackTone = remember(fallbackSeed) {
        val tones = listOf(
            Color(0xFF676765) to Color(0xFF4F4F4D),
            Color(0xFF3E5263) to Color(0xFF283744),
            Color(0xFF5A4554) to Color(0xFF3C2D38),
            Color(0xFF3E5950) to Color(0xFF273B35),
            Color(0xFF5E4E3E) to Color(0xFF3F3328)
        )
        tones[abs(fallbackSeed.hashCode()) % tones.size]
    }

    var sampledPair by remember(artworkUrl) { mutableStateOf(fallbackTone) }

    LaunchedEffect(artworkUrl) {
        if (artworkUrl.isNullOrBlank()) {
            sampledPair = fallbackTone
            return@LaunchedEffect
        }
        val extracted = withContext(Dispatchers.IO) {
            runCatching {
                val request = ImageRequest.Builder(context)
                    .data(artworkUrl)
                    .size(32, 32)
                    .allowHardware(false)
                    .build()
                val result = context.imageLoader.execute(request) as? SuccessResult
                val bmp = result?.drawable?.toBitmap(32, 32, Bitmap.Config.ARGB_8888)
                    ?: return@runCatching null
                extractMatteAtmospherePair(bmp)
            }.getOrNull()
        }
        if (extracted != null) {
            sampledPair = extracted
        }
    }

    val topAnimated by animateColorAsState(
        targetValue = sampledPair.first,
        animationSpec = tween(650),
        label = "ambient_top"
    )
    val bottomAnimated by animateColorAsState(
        targetValue = sampledPair.second,
        animationSpec = tween(650),
        label = "ambient_bottom"
    )
    return topAnimated to bottomAnimated
}

private fun extractMatteAtmospherePair(bitmap: Bitmap): Pair<Color, Color> {
    var rSum = 0L
    var gSum = 0L
    var bSum = 0L
    var weightedCount = 0L
    val hsv = FloatArray(3)

    // Prefer vibrant pixels if present, otherwise average overall brightness for clean monochrome matte
    var vibrantR = 0L
    var vibrantG = 0L
    var vibrantB = 0L
    var vibrantCount = 0L

    for (y in 0 until bitmap.height) {
        for (x in 0 until bitmap.width) {
            val px = bitmap.getPixel(x, y)
            val r = AndroidColor.red(px)
            val g = AndroidColor.green(px)
            val b = AndroidColor.blue(px)
            AndroidColor.RGBToHSV(r, g, b, hsv)
            rSum += r
            gSum += g
            bSum += b
            weightedCount++
            if (hsv[1] > 0.22f && hsv[2] in 0.18f..0.92f) {
                vibrantR += r
                vibrantG += g
                vibrantB += b
                vibrantCount++
            }
        }
    }

    val useVibrant = vibrantCount > (weightedCount / 10).coerceAtLeast(8)
    val avgR = if (useVibrant) (vibrantR / vibrantCount).toInt() else (rSum / weightedCount.coerceAtLeast(1)).toInt()
    val avgG = if (useVibrant) (vibrantG / vibrantCount).toInt() else (gSum / weightedCount.coerceAtLeast(1)).toInt()
    val avgB = if (useVibrant) (vibrantB / vibrantCount).toInt() else (bSum / weightedCount.coerceAtLeast(1)).toInt()

    AndroidColor.RGBToHSV(avgR, avgG, avgB, hsv)
    val isMonochrome = hsv[1] < 0.14f

    return if (isMonochrome) {
        // Silky warm-neutral matte gray matching monochrome/light artwork screenshots
        Color(0xFF70706E) to Color(0xFF5C5C5A)
    } else {
        val topHsv = floatArrayOf(
            hsv[0],
            hsv[1].coerceIn(0.24f, 0.56f),
            hsv[2].coerceIn(0.32f, 0.46f)
        )
        val bottomHsv = floatArrayOf(
            hsv[0],
            (topHsv[1] * 1.05f).coerceAtMost(0.62f),
            (topHsv[2] * 0.72f).coerceIn(0.18f, 0.34f)
        )
        Color(AndroidColor.HSVToColor(topHsv)) to Color(AndroidColor.HSVToColor(bottomHsv))
    }
}

/**
 * Formats remaining time with a leading minus sign (e.g. "-0:25") just like Apple Music / Auralys.
 */
fun formatRemainingTimeMs(positionMs: Long, durationMs: Long): String {
    val rem = (durationMs - positionMs).coerceAtLeast(0L)
    return "-${formatTimeMs(rem)}"
}

/**
 * Ultra-slim interactive rounded white progress bar matching the screenshots.
 */
@Composable
fun SlimPlayerProgressBar(
    positionMs: Long,
    durationMs: Long,
    isLiveStream: Boolean,
    enabled: Boolean,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var barWidthPx by remember { mutableIntStateOf(1) }

    val safeDuration = durationMs.coerceAtLeast(1L)
    val liveFraction = if (isLiveStream) 1f else (positionMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)
    val activeFraction = if (isDragging) dragFraction else liveFraction
    val displayPosMs = if (isDragging) (dragFraction * safeDuration).toLong() else positionMs

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .onSizeChanged { barWidthPx = it.width.coerceAtLeast(1) }
                .pointerInput(enabled, isLiveStream, safeDuration) {
                    if (!enabled || isLiveStream) return@pointerInput
                    detectTapGestures { offset ->
                        val frac = (offset.x / barWidthPx.toFloat()).coerceIn(0f, 1f)
                        onSeekTo((frac * safeDuration).toLong())
                    }
                }
                .pointerInput(enabled, isLiveStream, safeDuration) {
                    if (!enabled || isLiveStream) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            isDragging = true
                            dragFraction = (offset.x / barWidthPx.toFloat()).coerceIn(0f, 1f)
                        },
                        onDragEnd = {
                            onSeekTo((dragFraction * safeDuration).toLong())
                            isDragging = false
                        },
                        onDragCancel = { isDragging = false },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            dragFraction = (change.position.x / barWidthPx.toFloat()).coerceIn(0f, 1f)
                        }
                    )
                }
                .testTag("np_seekbar"),
            contentAlignment = Alignment.CenterStart
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (isDragging) 7.dp else 5.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.24f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(activeFraction.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(50))
                        .background(Color.White)
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = if (isLiveStream) "LIVE" else formatTimeMs(displayPosMs),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.70f),
                fontWeight = FontWeight.Medium
            )
            Text(
                text = if (isLiveStream) "SIARAN" else formatRemainingTimeMs(displayPosMs, durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.70f),
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * Clean icon-only transport row matching the screenshots (Shuffle • Prev • Large White Play/Pause • Next • Repeat).
 */
@Composable
fun PlayerTransportRow(
    isPlaying: Boolean,
    isBuffering: Boolean,
    shuffleEnabled: Boolean,
    repeatMode: RepeatModeState,
    onToggleShuffle: () -> Unit,
    onPrevious: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onCycleRepeat: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onToggleShuffle,
            modifier = Modifier
                .size(48.dp)
                .testTag("np_shuffle_button")
        ) {
            Icon(
                imageVector = Icons.Rounded.Shuffle,
                contentDescription = "Acak",
                tint = if (shuffleEnabled) Color.White else Color.White.copy(alpha = 0.58f),
                modifier = Modifier.size(24.dp)
            )
        }

        IconButton(
            onClick = onPrevious,
            modifier = Modifier
                .size(54.dp)
                .testTag("np_prev_button")
        ) {
            Icon(
                imageVector = Icons.Rounded.SkipPrevious,
                contentDescription = "Sebelumnya",
                tint = Color.White,
                modifier = Modifier.size(38.dp)
            )
        }

        Box(
            modifier = Modifier
                .size(68.dp)
                .clip(CircleShape)
                .clickable(onClick = onTogglePlayPause)
                .testTag("np_play_pause_button"),
            contentAlignment = Alignment.Center
        ) {
            if (isBuffering) {
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.size(34.dp),
                    strokeWidth = 3.dp
                )
            } else {
                Icon(
                    imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (isPlaying) "Jeda" else "Putar",
                    tint = Color.White,
                    modifier = Modifier.size(56.dp)
                )
            }
        }

        IconButton(
            onClick = onNext,
            modifier = Modifier
                .size(54.dp)
                .testTag("np_next_button")
        ) {
            Icon(
                imageVector = Icons.Rounded.SkipNext,
                contentDescription = "Berikutnya",
                tint = Color.White,
                modifier = Modifier.size(38.dp)
            )
        }

        IconButton(
            onClick = onCycleRepeat,
            modifier = Modifier
                .size(48.dp)
                .testTag("np_repeat_button")
        ) {
            Icon(
                imageVector = if (repeatMode == RepeatModeState.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                contentDescription = repeatMode.label,
                tint = if (repeatMode != RepeatModeState.OFF) Color.White else Color.White.copy(alpha = 0.58f),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

/**
 * Real Android AudioManager STREAM_MUSIC volume bar with mute toggle & hardware volume button sync.
 */
@Composable
fun SystemVolumeRow(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    val maxVolume = remember(audioManager) {
        audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    }

    var volumeFraction by remember {
        mutableFloatStateOf(
            audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume.toFloat()
        )
    }
    var lastNonZeroVolume by remember { mutableFloatStateOf(volumeFraction.coerceAtLeast(0.4f)) }
    var barWidthPx by remember { mutableIntStateOf(1) }

    // Stay in sync when user presses physical device volume buttons
    DisposableEffect(context, audioManager, maxVolume) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                val frac = (cur.toFloat() / maxVolume.toFloat()).coerceIn(0f, 1f)
                volumeFraction = frac
                if (frac > 0.01f) lastNonZeroVolume = frac
            }
        }
        val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        runCatching { context.registerReceiver(receiver, filter) }
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    fun applyVolume(fraction: Float) {
        val clamped = fraction.coerceIn(0f, 1f)
        volumeFraction = clamped
        if (clamped > 0.01f) lastNonZeroVolume = clamped
        val targetIndex = (clamped * maxVolume).roundToInt().coerceIn(0, maxVolume)
        runCatching {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetIndex, 0)
        }
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        IconButton(
            onClick = {
                if (volumeFraction > 0.01f) applyVolume(0f) else applyVolume(lastNonZeroVolume)
            },
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.VolumeOff,
                contentDescription = "Bisukan volume",
                tint = Color.White.copy(alpha = if (volumeFraction <= 0.01f) 1f else 0.72f),
                modifier = Modifier.size(20.dp)
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .height(28.dp)
                .onSizeChanged { barWidthPx = it.width.coerceAtLeast(1) }
                .pointerInput(maxVolume) {
                    detectTapGestures { offset ->
                        applyVolume(offset.x / barWidthPx.toFloat())
                    }
                }
                .pointerInput(maxVolume) {
                    detectHorizontalDragGestures { change, _ ->
                        change.consume()
                        applyVolume(change.position.x / barWidthPx.toFloat())
                    }
                },
            contentAlignment = Alignment.CenterStart
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.22f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(volumeFraction.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.88f))
                )
            }
        }

        IconButton(
            onClick = { applyVolume((volumeFraction + 0.1f).coerceAtMost(1f)) },
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.VolumeUp,
                contentDescription = "Naikkan volume",
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * Floating frosted-glass bottom capsule dock with 4 iconic actions matching both screenshots:
 * [Lyrics] • [Queue] • [Share / Listen Together] • [Sleep Timer]
 */
@Composable
fun FloatingGlassPlayerDock(
    isLyricsActive: Boolean,
    isSleepTimerActive: Boolean,
    hasActiveRoom: Boolean,
    onToggleLyrics: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenShareOrRoom: () -> Unit,
    onOpenSleepTimer: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(50),
        color = Color.White.copy(alpha = 0.11f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.28f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            DockCapsuleButton(
                icon = Icons.Rounded.ChatBubbleOutline,
                contentDescription = "Lirik",
                active = isLyricsActive,
                onClick = onToggleLyrics,
                modifier = Modifier
                    .weight(1f)
                    .testTag("np_lyrics_button")
            )
            DockCapsuleButton(
                icon = Icons.AutoMirrored.Rounded.FormatListBulleted,
                contentDescription = "Antrean",
                active = false,
                onClick = onOpenQueue,
                modifier = Modifier
                    .weight(1f)
                    .testTag("np_queue_button")
            )
            DockCapsuleButton(
                icon = Icons.Rounded.Share,
                contentDescription = "Bagikan & Dengarkan Bersama",
                active = hasActiveRoom,
                onClick = onOpenShareOrRoom,
                modifier = Modifier
                    .weight(1f)
                    .testTag("np_share_room_button")
            )
            DockCapsuleButton(
                icon = Icons.Rounded.Bedtime,
                contentDescription = "Pengatur waktu tidur",
                active = isSleepTimerActive,
                onClick = onOpenSleepTimer,
                modifier = Modifier
                    .weight(1f)
                    .testTag("np_sleep_timer_button")
            )
        }
    }
}

@Composable
private fun DockCapsuleButton(
    icon: ImageVector,
    contentDescription: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(50))
            .background(if (active) Color.White.copy(alpha = 0.20f) else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (active) Color.White else Color.White.copy(alpha = 0.72f),
            modifier = Modifier.size(22.dp)
        )
    }
}

/**
 * Apple Music / Auralys style synced lyrics canvas with progressive word-by-word illumination
 * on the active line, soft depth fade scrims at top/bottom, and tap-to-seek.
 */
@Composable
fun KaraokeLyricsCanvas(
    parsed: ParsedLyrics?,
    positionMs: Long,
    offsetMs: Long,
    isLoading: Boolean,
    isTranslating: Boolean,
    errorMessage: String?,
    fontSizeSp: Float,
    lineSpacingSp: Float,
    alignment: LyricsAlignment,
    displayMode: LyricsDisplayMode,
    topFadeColor: Color,
    bottomFadeColor: Color,
    onSeekToMs: (Long) -> Unit,
    onRetry: () -> Unit,
    onManualSearch: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val activeLineIndex = remember(parsed, positionMs, offsetMs) {
        if (parsed != null && parsed.isSynced) {
            LrcParser.findActiveLineIndex(parsed.lines, positionMs, offsetMs)
        } else -1
    }

    LaunchedEffect(activeLineIndex) {
        if (activeLineIndex >= 0 && parsed != null && parsed.isSynced) {
            listState.animateScrollToItem(
                index = (activeLineIndex - 1).coerceAtLeast(0),
                scrollOffset = -40
            )
        }
    }

    val textAlign = when (alignment) {
        LyricsAlignment.START -> TextAlign.Start
        LyricsAlignment.CENTER -> TextAlign.Center
        LyricsAlignment.END -> TextAlign.End
    }

    Box(modifier = modifier.fillMaxSize()) {
        when {
            isLoading || isTranslating -> {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.5.dp)
                    Text(
                        text = if (isTranslating) "Menerjemahkan lirik..." else "Memuat lirik sinkron...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.80f)
                    )
                }
            }
            parsed == null || parsed.lines.isEmpty() -> {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = errorMessage ?: "Lirik belum tersedia untuk lagu ini.",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        color = Color.White.copy(alpha = 0.82f)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = Color.White.copy(alpha = 0.22f),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)),
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .clickable(onClick = onRetry)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Rounded.Refresh, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Coba Lagi", color = Color.White, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = Color.White.copy(alpha = 0.14f),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.24f)),
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .clickable(onClick = onManualSearch)
                        ) {
                            Text(
                                text = "Cari Manual",
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)
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
                        top = 28.dp,
                        bottom = 52.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(lineSpacingSp.coerceIn(18f, 30f).dp)
                ) {
                    itemsIndexed(parsed.lines, key = { idx, line -> "${idx}_${line.timeMs}" }) { index, line ->
                        val isActive = parsed.isSynced && index == activeLineIndex
                        val isPast = parsed.isSynced && activeLineIndex >= 0 && index < activeLineIndex
                        val distance = if (activeLineIndex >= 0) abs(index - activeLineIndex) else 0

                        val baseAlpha = when {
                            !parsed.isSynced -> 0.88f
                            isActive -> 1f
                            isPast && distance == 1 -> 0.44f
                            isPast -> 0.30f
                            distance == 1 -> 0.42f
                            else -> 0.28f
                        }
                        val animatedScale by animateFloatAsState(
                            targetValue = if (isActive) 1.02f else 1.0f,
                            animationSpec = spring(stiffness = 420f, dampingRatio = 0.85f),
                            label = "karaoke_scale"
                        )

                        // Compute progressive word-by-word glow fraction inside the active line
                        val lineProgress = if (isActive) {
                            val lineStart = line.timeMs
                            val nextStart = parsed.lines.getOrNull(index + 1)?.timeMs
                                ?.takeIf { it > lineStart }
                                ?: (lineStart + 4_500L)
                            val lineDur = (nextStart - lineStart).coerceAtLeast(800L)
                            val adjustedPos = positionMs + offsetMs
                            ((adjustedPos - lineStart).toFloat() / lineDur.toFloat()).coerceIn(0.08f, 1f)
                        } else 0f

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(enabled = parsed.isSynced && line.timeMs >= 0L) {
                                    onSeekToMs((line.timeMs - offsetMs).coerceAtLeast(0L))
                                }
                                .graphicsLayer {
                                    scaleX = animatedScale
                                    scaleY = animatedScale
                                }
                                .padding(vertical = 2.dp)
                        ) {
                            val showOriginal = displayMode != LyricsDisplayMode.TRANSLATED ||
                                line.translatedText.isNullOrBlank()
                            val showTranslation = displayMode != LyricsDisplayMode.ORIGINAL &&
                                !line.translatedText.isNullOrBlank()

                            if (showOriginal) {
                                val textContent = line.text.ifBlank { "•••" }
                                val sz = fontSizeSp.coerceIn(22f, 34f)
                                if (isActive) {
                                    val words = textContent.split(" ")
                                    val totalChars = textContent.length.coerceAtLeast(1)
                                    val litCharCutoff = (totalChars * lineProgress).toInt()
                                    val annotated = buildAnnotatedString {
                                        var cursor = 0
                                        words.forEachIndexed { wIdx, word ->
                                            val wordMid = cursor + (word.length / 2)
                                            val isWordLit = wordMid <= litCharCutoff || wIdx == 0
                                            withStyle(
                                                SpanStyle(
                                                    color = if (isWordLit) Color.White else Color.White.copy(alpha = 0.42f)
                                                )
                                            ) {
                                                append(word)
                                            }
                                            cursor += word.length
                                            if (wIdx < words.lastIndex) {
                                                append(" ")
                                                cursor += 1
                                            }
                                        }
                                    }
                                    Text(
                                        text = annotated,
                                        fontSize = sz.sp,
                                        lineHeight = (sz + 8f).sp,
                                        fontWeight = FontWeight.SemiBold,
                                        textAlign = textAlign,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                } else {
                                    Text(
                                        text = textContent,
                                        fontSize = sz.sp,
                                        lineHeight = (sz + 8f).sp,
                                        fontWeight = FontWeight.Medium,
                                        color = Color.White.copy(alpha = baseAlpha),
                                        textAlign = textAlign,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }

                            if (showTranslation) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = line.translatedText.orEmpty(),
                                    fontSize = (fontSizeSp - 8f).coerceAtLeast(15f).sp,
                                    lineHeight = (fontSizeSp - 2f).coerceAtLeast(21f).sp,
                                    fontWeight = FontWeight.Normal,
                                    color = Color.White.copy(alpha = if (isActive) 0.85f else 0.35f),
                                    textAlign = textAlign,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }

                    item {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "Lirik oleh ${parsed.providerName.ifBlank { "LRCLIB" }}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.45f),
                            textAlign = textAlign,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // Soft atmospheric top & bottom melt scrims
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                        .align(Alignment.TopCenter)
                        .background(Brush.verticalGradient(listOf(topFadeColor, Color.Transparent)))
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .align(Alignment.BottomCenter)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, bottomFadeColor)))
                )
            }
        }
    }
}

/**
 * Shared Queue Bottom Sheet with Auto-Queue (Autoplay) toggle, reorder & save-as-playlist.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerQueueBottomSheet(
    viewModel: MainViewModel,
    playbackState: PlaybackUiState,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
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
                        onDismiss()
                    }) {
                        Icon(Icons.Rounded.Save, contentDescription = "Simpan antrean ke playlist")
                    }
                    IconButton(onClick = {
                        viewModel.container.playbackController.clearQueue()
                        onDismiss()
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

/**
 * Shared Sleep Timer Modal Sheet matching the moon icon in the floating glass dock.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerSleepTimerSheet(
    viewModel: MainViewModel,
    playbackState: PlaybackUiState,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Pengatur Waktu Tidur", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            val statusText = when {
                playbackState.sleepAfterCurrentTrack -> "Aktif: Berhenti setelah lagu ini selesai"
                playbackState.sleepTimerRemainingMs > 0L -> "Aktif: Sisa ${formatTimeMs(playbackState.sleepTimerRemainingMs)}"
                else -> "Pilih durasi sebelum musik dijeda otomatis"
            }
            Text(statusText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))

            val options = listOf(10, 15, 30, 45, 60)
            options.forEach { mins ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable {
                            viewModel.container.playbackController.setSleepTimerMinutes(mins)
                            viewModel.showMessage("Pengatur waktu tidur disetel $mins menit")
                            onDismiss()
                        }
                ) {
                    Text(
                        text = "$mins menit",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable {
                        viewModel.container.playbackController.setSleepAfterCurrentTrack()
                        viewModel.showMessage("Musik akan berhenti setelah lagu ini selesai")
                        onDismiss()
                    }
            ) {
                Text(
                    text = "Akhir lagu saat ini",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
                )
            }

            if (playbackState.sleepTimerRemainingMs > 0L || playbackState.sleepAfterCurrentTrack) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable {
                            viewModel.container.playbackController.cancelSleepTimer()
                            viewModel.showMessage("Pengatur waktu tidur dimatikan")
                            onDismiss()
                        }
                ) {
                    Text(
                        text = "Matikan Pengatur Waktu",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

/**
 * Shared Share & Dengarkan Bersama quick sheet invoked from the Share dock button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerShareAndRoomSheet(
    track: Track?,
    activeRoomCode: String?,
    onOpenListenTogether: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Bagikan & Dengarkan Bersama", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable {
                        onDismiss()
                        onOpenListenTogether()
                    }
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Headphones, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (activeRoomCode != null) "Room Aktif • $activeRoomCode" else "Dengarkan Bersama Real-Time",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = "Sinkronkan lagu ini dengan teman pakai kode room",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        )
                    }
                }
            }

            if (track != null) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable {
                            val link = if (track.sourceTrackId.length == 11) {
                                "https://music.youtube.com/watch?v=${track.sourceTrackId}"
                            } else ""
                            val text = buildString {
                                append("Lagi dengerin \"${track.title}\" - ${track.artist} di KOPLINGFY")
                                if (link.isNotBlank()) append("\n$link")
                            }
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, text)
                            }
                            runCatching {
                                context.startActivity(Intent.createChooser(shareIntent, "Bagikan lagu"))
                            }
                            onDismiss()
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Share, contentDescription = null)
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text("Bagikan Lagu Ini", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${track.title} • ${track.artist}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}
