package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import com.example.domain.model.VisualizerMode
import com.example.playback.AudioAnalysisFrame
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Real-time audio-reactive Compose Canvas Visualizer driven strictly by PCM FFT/Waveform data
 * tapped from ExoPlayer's VisualizerAudioProcessor.
 */
@Composable
fun AudioVisualizerCanvas(
    frame: AudioAnalysisFrame,
    mode: VisualizerMode,
    modifier: Modifier = Modifier,
    primaryColor: Color = MaterialTheme.colorScheme.primary,
    secondaryColor: Color = MaterialTheme.colorScheme.secondary
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        when (mode) {
            VisualizerMode.SPECTRUM -> {
                val bands = frame.spectrumBands
                val count = bands.size
                if (count < 2) return@Canvas
                val stepX = w / (count - 1)
                val fillPath = Path().apply {
                    moveTo(0f, h)
                    for (i in 0 until count) {
                        val x = i * stepX
                        val mag = bands[i].coerceIn(0f, 1f)
                        val y = h - (mag * h * 0.85f) - h * 0.05f
                        if (i == 0) lineTo(x, y)
                        else {
                            val prevX = (i - 1) * stepX
                            val prevMag = bands[i - 1].coerceIn(0f, 1f)
                            val prevY = h - (prevMag * h * 0.85f) - h * 0.05f
                            cubicTo((prevX + x) / 2f, prevY, (prevX + x) / 2f, y, x, y)
                        }
                    }
                    lineTo(w, h)
                    close()
                }
                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            primaryColor.copy(alpha = 0.65f),
                            secondaryColor.copy(alpha = 0.25f),
                            Color.Transparent
                        )
                    )
                )
                val strokePath = Path().apply {
                    for (i in 0 until count) {
                        val x = i * stepX
                        val y = h - (bands[i].coerceIn(0f, 1f) * h * 0.85f) - h * 0.05f
                        if (i == 0) moveTo(x, y) else lineTo(x, y)
                    }
                }
                drawPath(
                    path = strokePath,
                    brush = Brush.horizontalGradient(listOf(primaryColor, secondaryColor)),
                    style = Stroke(width = 4f, cap = StrokeCap.Round)
                )
            }

            VisualizerMode.BARS -> {
                val bands = frame.spectrumBands
                val count = bands.size
                val gap = 6f
                val barW = ((w - gap * (count + 1)) / count).coerceAtLeast(3f)
                val brush = Brush.verticalGradient(
                    colors = listOf(secondaryColor, primaryColor, primaryColor.copy(alpha = 0.35f))
                )
                for (i in 0 until count) {
                    val mag = bands[i].coerceIn(0.03f, 1f)
                    val barH = mag * h * 0.88f
                    val x = gap + i * (barW + gap)
                    drawRoundRect(
                        brush = brush,
                        topLeft = Offset(x, h - barH),
                        size = Size(barW, barH),
                        cornerRadius = CornerRadius(barW / 2f, barW / 2f)
                    )
                }
            }

            VisualizerMode.WAVEFORM -> {
                val wave = frame.waveform
                val count = wave.size
                if (count < 2) return@Canvas
                val midY = h / 2f
                val stepX = w / (count - 1)
                val path = Path().apply {
                    for (i in 0 until count) {
                        val x = i * stepX
                        val y = midY + wave[i].coerceIn(-1f, 1f) * (h * 0.42f)
                        if (i == 0) moveTo(x, y) else lineTo(x, y)
                    }
                }
                // Subtle center baseline
                drawLine(
                    color = primaryColor.copy(alpha = 0.18f),
                    start = Offset(0f, midY),
                    end = Offset(w, midY),
                    strokeWidth = 1.5f
                )
                drawPath(
                    path = path,
                    brush = Brush.horizontalGradient(listOf(primaryColor, secondaryColor, primaryColor)),
                    style = Stroke(width = 5f, cap = StrokeCap.Round)
                )
            }

            VisualizerMode.CIRCULAR -> {
                val bands = frame.spectrumBands
                val count = bands.size
                val center = Offset(w / 2f, h / 2f)
                val baseRadius = min(w, h) * 0.28f + (frame.rmsLevel * 14f)
                val maxExtend = min(w, h) * 0.20f

                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(primaryColor.copy(alpha = 0.24f), Color.Transparent),
                        center = center,
                        radius = baseRadius * 1.6f
                    ),
                    radius = baseRadius * 1.6f,
                    center = center
                )
                drawCircle(
                    color = primaryColor.copy(alpha = 0.45f),
                    radius = baseRadius,
                    center = center,
                    style = Stroke(width = 3f)
                )

                for (i in 0 until count) {
                    val angle = (2.0 * PI * i / count) - PI / 2.0
                    val mag = bands[i].coerceIn(0.04f, 1f)
                    val startX = center.x + cos(angle).toFloat() * baseRadius
                    val startY = center.y + sin(angle).toFloat() * baseRadius
                    val endX = center.x + cos(angle).toFloat() * (baseRadius + mag * maxExtend)
                    val endY = center.y + sin(angle).toFloat() * (baseRadius + mag * maxExtend)
                    val barColor = if (i % 2 == 0) primaryColor else secondaryColor
                    drawLine(
                        color = barColor,
                        start = Offset(startX, startY),
                        end = Offset(endX, endY),
                        strokeWidth = 6f,
                        cap = StrokeCap.Round
                    )
                }
            }

            VisualizerMode.OFF -> Unit
        }
    }
}
