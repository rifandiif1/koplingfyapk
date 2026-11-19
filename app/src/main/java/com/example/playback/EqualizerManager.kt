package com.example.playback

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class EqualizerState(
    val enabled: Boolean = false,
    val presetIndex: Int = 0, // 0..8 presets, -1 = Custom
    val bandLevelsDb: List<Float> = listOf(0f, 0f, 0f, 0f, 0f),
    val bandCenterFreqsHz: List<Int> = listOf(60, 230, 910, 3600, 14000),
    val bassBoostStrength: Int = 0,
    val virtualizerStrength: Int = 0,
    val loudnessGainMb: Int = 0,
    val hardwareAttached: Boolean = false,
    val audioSessionId: Int = 0
)

/**
 * Real Android Audio Effects Manager integrating Equalizer, BassBoost, Virtualizer,
 * and LoudnessEnhancer directly with Media3 ExoPlayer's active audioSessionId.
 */
class EqualizerManager {

    private val _state = MutableStateFlow(EqualizerState())
    val state: StateFlow<EqualizerState> = _state.asStateFlow()

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null

    fun attachToAudioSession(sessionId: Int) {
        if (sessionId <= 0) return
        if (_state.value.audioSessionId == sessionId && equalizer != null) return
        releaseEffects()

        var attached = false
        var centerFreqs = _state.value.bandCenterFreqsHz

        runCatching {
            val eq = Equalizer(0, sessionId)
            equalizer = eq
            val numBands = eq.numberOfBands.toInt().coerceAtMost(5)
            if (numBands > 0) {
                centerFreqs = (0 until numBands).map { b -> eq.getCenterFreq(b.toShort()) / 1000 }
            }
            attached = true
        }
        runCatching { bassBoost = BassBoost(0, sessionId) }
        runCatching { virtualizer = Virtualizer(0, sessionId) }
        runCatching { loudnessEnhancer = LoudnessEnhancer(sessionId) }

        _state.value = _state.value.copy(
            hardwareAttached = attached,
            audioSessionId = sessionId,
            bandCenterFreqsHz = if (centerFreqs.size == 5) centerFreqs else _state.value.bandCenterFreqsHz
        )
        applyCurrentStateToHardware()
    }

    fun applyConfig(
        enabled: Boolean,
        presetIndex: Int,
        bandLevelsDb: List<Float>,
        bassBoostStrength: Int,
        virtualizerStrength: Int,
        loudnessGainMb: Int
    ) {
        _state.value = _state.value.copy(
            enabled = enabled,
            presetIndex = presetIndex,
            bandLevelsDb = bandLevelsDb.take(5).let { if (it.size == 5) it else listOf(0f, 0f, 0f, 0f, 0f) },
            bassBoostStrength = bassBoostStrength.coerceIn(0, 1000),
            virtualizerStrength = virtualizerStrength.coerceIn(0, 1000),
            loudnessGainMb = loudnessGainMb.coerceIn(0, 1500)
        )
        applyCurrentStateToHardware()
    }

    private fun applyCurrentStateToHardware() {
        val cur = _state.value
        runCatching {
            equalizer?.let { eq ->
                eq.enabled = cur.enabled
                val range = eq.bandLevelRange
                val minMb = range[0].toInt()
                val maxMb = range[1].toInt()
                val numBands = eq.numberOfBands.toInt()
                for (i in 0 until minOf(numBands, cur.bandLevelsDb.size)) {
                    val targetMb = (cur.bandLevelsDb[i] * 100f).toInt().coerceIn(minMb, maxMb)
                    eq.setBandLevel(i.toShort(), targetMb.toShort())
                }
            }
        }
        runCatching {
            bassBoost?.let { bb ->
                bb.enabled = cur.enabled && cur.bassBoostStrength > 0
                if (bb.strengthSupported) {
                    bb.setStrength(cur.bassBoostStrength.toShort())
                }
            }
        }
        runCatching {
            virtualizer?.let { v ->
                v.enabled = cur.enabled && cur.virtualizerStrength > 0
                if (v.strengthSupported) {
                    v.setStrength(cur.virtualizerStrength.toShort())
                }
            }
        }
        runCatching {
            loudnessEnhancer?.let { le ->
                le.enabled = cur.enabled && cur.loudnessGainMb > 0
                le.setTargetGain(cur.loudnessGainMb)
            }
        }
    }

    fun releaseEffects() {
        runCatching { equalizer?.release() }; equalizer = null
        runCatching { bassBoost?.release() }; bassBoost = null
        runCatching { virtualizer?.release() }; virtualizer = null
        runCatching { loudnessEnhancer?.release() }; loudnessEnhancer = null
    }
}
