package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.model.EqualizerPresets
import com.example.ui.viewmodel.MainViewModel

@Composable
fun EqualizerScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler { onBack() }

    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val eqState by viewModel.equalizerState.collectAsStateWithLifecycle()
    val freqLabels = listOf("60 Hz", "230 Hz", "910 Hz", "3.6 kHz", "14 kHz")

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .testTag("equalizer_screen")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("Studio Equalizer & DSP", style = MaterialTheme.typography.titleLarge)
                Text(
                    text = if (eqState.hardwareAttached) "Audio Session #${eqState.audioSessionId} Active"
                    else "Start playback to attach hardware audio session",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(onClick = { viewModel.resetEqualizer() }) {
                Icon(Icons.Rounded.RestartAlt, contentDescription = "Reset equalizer")
            }
            Switch(
                checked = settings.eqEnabled,
                onCheckedChange = { viewModel.setEqualizerEnabled(it) },
                modifier = Modifier.testTag("eq_enable_switch")
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 40.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text("Sound Presets", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(EqualizerPresets.ALL) { idx, preset ->
                        FilterChip(
                            selected = settings.eqPresetIndex == idx,
                            onClick = { viewModel.selectEqualizerPreset(idx) },
                            label = { Text(preset.name) },
                            modifier = Modifier.testTag("eq_preset_${preset.name}")
                        )
                    }
                    item {
                        FilterChip(
                            selected = settings.eqPresetIndex == -1,
                            onClick = {},
                            label = { Text("Custom") }
                        )
                    }
                }
            }

            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("5-Band Graphic Equalizer (-12 dB to +12 dB)", style = MaterialTheme.typography.titleSmall)
                        settings.eqBandLevelsDb.forEachIndexed { idx, dbValue ->
                            val hz = eqState.bandCenterFreqsHz.getOrNull(idx)
                            val label = if (hz != null && hz > 0) {
                                if (hz >= 1000) "%.1f kHz".format(hz / 1000f) else "$hz Hz"
                            } else freqLabels.getOrElse(idx) { "Band ${idx + 1}" }

                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(label, style = MaterialTheme.typography.labelMedium)
                                    Text(
                                        "%+.1f dB".format(dbValue),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Slider(
                                    value = dbValue,
                                    onValueChange = { viewModel.setEqualizerBand(idx, it) },
                                    valueRange = -12f..12f,
                                    enabled = settings.eqEnabled
                                )
                            }
                        }
                    }
                }
            }

            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("Spatial & Dynamics Audio Effects", style = MaterialTheme.typography.titleSmall)

                        EffectSliderRow(
                            title = "Bass Boost",
                            valueLabel = "${settings.bassBoostStrength / 10}%",
                            value = settings.bassBoostStrength.toFloat(),
                            range = 0f..1000f,
                            enabled = settings.eqEnabled,
                            onValueChange = {
                                viewModel.setAudioEffects(it.toInt(), settings.virtualizerStrength, settings.loudnessGainMb)
                            }
                        )

                        EffectSliderRow(
                            title = "3D Virtualizer / Surround",
                            valueLabel = "${settings.virtualizerStrength / 10}%",
                            value = settings.virtualizerStrength.toFloat(),
                            range = 0f..1000f,
                            enabled = settings.eqEnabled,
                            onValueChange = {
                                viewModel.setAudioEffects(settings.bassBoostStrength, it.toInt(), settings.loudnessGainMb)
                            }
                        )

                        EffectSliderRow(
                            title = "Loudness Enhancer",
                            valueLabel = "+%.1f dB".format(settings.loudnessGainMb / 100f),
                            value = settings.loudnessGainMb.toFloat(),
                            range = 0f..1500f,
                            enabled = settings.eqEnabled,
                            onValueChange = {
                                viewModel.setAudioEffects(settings.bassBoostStrength, settings.virtualizerStrength, it.toInt())
                            }
                        )
                    }
                }
            }

            item {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Hardware DSP effects attach directly to ExoPlayer's audio session and remain active during background and lock-screen playback.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun EffectSliderRow(
    title: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(valueLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            enabled = enabled
        )
    }
}
