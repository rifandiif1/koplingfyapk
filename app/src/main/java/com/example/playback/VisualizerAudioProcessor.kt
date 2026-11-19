package com.example.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class AudioAnalysisFrame(
    val spectrumBands: FloatArray = FloatArray(32),
    val waveform: FloatArray = FloatArray(64),
    val rmsLevel: Float = 0f,
    val peakLevel: Float = 0f,
    val timestampMs: Long = 0L
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioAnalysisFrame) return false
        return timestampMs == other.timestampMs && rmsLevel == other.rmsLevel
    }

    override fun hashCode(): Int = (timestampMs * 31 + rmsLevel.hashCode()).toInt()
}

/**
 * Custom Media3 BaseAudioProcessor that taps real decoded 16-bit PCM buffers directly from
 * ExoPlayer's audio rendering pipeline without requiring RECORD_AUDIO permission.
 * Performs real-time radix-2 FFT (256-point) across 32 logarithmic frequency bands + 64-point waveform.
 */
@UnstableApi
class VisualizerAudioProcessor : BaseAudioProcessor() {

    private val _analysisFlow = MutableStateFlow(AudioAnalysisFrame())
    val analysisFlow: StateFlow<AudioAnalysisFrame> = _analysisFlow.asStateFlow()

    private val fftSize = 256
    private val bandCount = 32
    private val waveCount = 64

    private val sampleRing = FloatArray(fftSize)
    private val fftReal = FloatArray(fftSize)
    private val fftImag = FloatArray(fftSize)
    private val hannWindow = FloatArray(fftSize) { i ->
        (0.5f * (1f - cos(2.0 * PI * i / (fftSize - 1)))).toFloat()
    }
    private val smoothedBands = FloatArray(bandCount)
    private var ringIndex = 0
    private var lastEmitMs = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) {
            inputAudioFormat
        } else {
            AudioProcessor.AudioFormat.NOT_SET
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val pos = inputBuffer.position()
        val limit = inputBuffer.limit()
        val remaining = limit - pos
        if (remaining <= 0) return

        // Copy audio untouched to output buffer so playback is 100% lossless
        val output = replaceOutputBuffer(remaining)
        output.put(inputBuffer)
        output.flip()

        // Tap 16-bit PCM samples for real-time audio analysis
        val channelCount = inputAudioFormat.channelCount.coerceAtLeast(1)
        val strideBytes = channelCount * 2
        var idx = pos
        while (idx + 1 < limit) {
            val lo = inputBuffer.get(idx).toInt() and 0xFF
            val hi = inputBuffer.get(idx + 1).toInt()
            val sample16 = (hi shl 8) or lo
            sampleRing[ringIndex] = sample16 / 32768f
            ringIndex = (ringIndex + 1) % fftSize
            idx += strideBytes
        }

        val now = System.currentTimeMillis()
        if (now - lastEmitMs >= 33L) { // ~30 fps smooth updates
            lastEmitMs = now
            computeFrame(now)
        }
    }

    fun resetSilence() {
        smoothedBands.fill(0f)
        _analysisFlow.value = AudioAnalysisFrame(timestampMs = System.currentTimeMillis())
    }

    private fun computeFrame(nowMs: Long) {
        var sumSq = 0f
        var peak = 0f
        val waveform = FloatArray(waveCount)
        val step = fftSize / waveCount

        for (i in 0 until fftSize) {
            val s = sampleRing[(ringIndex + i) % fftSize]
            sumSq += s * s
            val absS = kotlin.math.abs(s)
            if (absS > peak) peak = absS
            if (i % step == 0 && i / step < waveCount) {
                waveform[i / step] = s.coerceIn(-1f, 1f)
            }
            fftReal[i] = s * hannWindow[i]
            fftImag[i] = 0f
        }
        val rms = sqrt(sumSq / fftSize).coerceIn(0f, 1f)

        // In-place iterative radix-2 Cooley-Tukey FFT
        fftInPlace(fftReal, fftImag)

        // Group 128 FFT bins into 32 perceptual logarithmic frequency bands
        val half = fftSize / 2
        val bands = FloatArray(bandCount)
        for (b in 0 until bandCount) {
            val startBin = (1 + (b * b * (half - 2)) / (bandCount * bandCount)).coerceIn(1, half - 1)
            val endBin = (1 + ((b + 1) * (b + 1) * (half - 2)) / (bandCount * bandCount))
                .coerceIn(startBin, half - 1)
            var magSum = 0f
            var count = 0
            for (k in startBin..endBin) {
                val mag = sqrt(fftReal[k] * fftReal[k] + fftImag[k] * fftImag[k])
                magSum += mag
                count++
            }
            val avgMag = if (count > 0) magSum / count else 0f
            // Perceptual dB-like normalization
            val normalized = (ln(1f + avgMag * 2.4f) / 3.4f).coerceIn(0f, 1f)
            // Fast attack, smooth decay
            smoothedBands[b] = if (normalized > smoothedBands[b]) {
                smoothedBands[b] * 0.35f + normalized * 0.65f
            } else {
                smoothedBands[b] * 0.78f + normalized * 0.22f
            }
            bands[b] = smoothedBands[b]
        }

        _analysisFlow.value = AudioAnalysisFrame(
            spectrumBands = bands,
            waveform = waveform,
            rmsLevel = rms,
            peakLevel = peak,
            timestampMs = nowMs
        )
    }

    private fun fftInPlace(real: FloatArray, imag: FloatArray) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val angle = -2.0 * PI / len
            val wLenR = cos(angle).toFloat()
            val wLenI = sin(angle).toFloat()
            var i = 0
            while (i < n) {
                var wR = 1f
                var wI = 0f
                val halfLen = len shr 1
                for (k in 0 until halfLen) {
                    val uR = real[i + k]
                    val uI = imag[i + k]
                    val vR = real[i + k + halfLen] * wR - imag[i + k + halfLen] * wI
                    val vI = real[i + k + halfLen] * wI + imag[i + k + halfLen] * wR
                    real[i + k] = uR + vR
                    imag[i + k] = uI + vI
                    real[i + k + halfLen] = uR - vR
                    imag[i + k + halfLen] = uI - vI
                    val nextWR = wR * wLenR - wI * wLenI
                    wI = wR * wLenI + wI * wLenR
                    wR = nextWR
                }
                i += len
            }
            len = len shl 1
        }
    }
}
