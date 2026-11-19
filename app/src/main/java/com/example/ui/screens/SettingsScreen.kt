package com.example.ui.screens

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.BuildConfig
import com.example.domain.model.AccentPreset
import com.example.domain.model.ArtworkShapeStyle
import com.example.domain.model.ThemeMode
import com.example.domain.model.TranslationProviderType
import com.example.ui.components.formatBytes
import com.example.ui.viewmodel.MainViewModel

@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onOpenEqualizer: () -> Unit,
    onOpenSources: () -> Unit,
    modifier: Modifier = Modifier
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val cacheBytes by viewModel.cacheSizeBytes.collectAsStateWithLifecycle()

    var musixmatchInput by remember(settings.musixmatchApiKey) { mutableStateOf(settings.musixmatchApiKey) }
    var googleTransInput by remember(settings.googleTranslateApiKey) { mutableStateOf(settings.googleTranslateApiKey) }
    var libreUrlInput by remember(settings.libreTranslateUrl) { mutableStateOf(settings.libreTranslateUrl) }

    LaunchedEffect(Unit) { viewModel.refreshCacheSize() }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(top = 12.dp)
            .testTag("settings_screen"),
        contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 150.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("Settings", style = MaterialTheme.typography.headlineMedium)
        }

        // Appearance Section
        item {
            SettingsCard(title = "Appearance & Theme") {
                Text("Theme Mode", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = settings.themeMode == mode,
                            onClick = { viewModel.updateThemeMode(mode) },
                            label = { Text(mode.label, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.testTag("theme_mode_${mode.name}")
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))
                Text("Accent Color", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AccentPreset.entries.forEach { preset ->
                        val selected = settings.accentPreset == preset
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(Color(preset.hex))
                                .clickable { viewModel.updateAccentPreset(preset) }
                                .testTag("accent_${preset.name}"),
                            contentAlignment = Alignment.Center
                        ) {
                            if (selected) {
                                Icon(Icons.Rounded.Check, contentDescription = preset.label, tint = Color.Black)
                            }
                        }
                    }
                }

                SettingsToggleRow(
                    title = "Material You Dynamic Color",
                    subtitle = "Adapt palette from Android 12+ wallpaper",
                    checked = settings.dynamicColor,
                    onCheckedChange = { viewModel.updateDynamicColor(it) }
                )
                SettingsToggleRow(
                    title = "Smooth UI Animations",
                    subtitle = "Enable fluid transitions and visualizer motion",
                    checked = settings.animationsEnabled,
                    onCheckedChange = { viewModel.updateAnimationsEnabled(it) }
                )

                Text("Now Playing Artwork Style", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ArtworkShapeStyle.entries.forEach { style ->
                        FilterChip(
                            selected = settings.artworkShapeStyle == style,
                            onClick = { viewModel.updateArtworkStyle(style) },
                            label = { Text(style.label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }
        }

        // Playback & Audio Section
        item {
            SettingsCard(title = "Playback & Audio Engine") {
                SettingsToggleRow(
                    title = "High-Quality Streaming",
                    subtitle = "Prefer highest available bitrate (M4A/Opus/MP3)",
                    checked = settings.highQualityAudio,
                    onCheckedChange = { viewModel.updateHighQuality(it) }
                )
                SettingsToggleRow(
                    title = "Gapless Playback",
                    subtitle = "Seamless transitions between queued tracks",
                    checked = settings.gaplessPlayback,
                    onCheckedChange = { viewModel.updateGapless(it) }
                )
                SettingsToggleRow(
                    title = "Resume Last Session",
                    subtitle = "Restore last played track and position on startup",
                    checked = settings.resumeOnStartup,
                    onCheckedChange = { viewModel.updateResumeOnStartup(it) }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onOpenEqualizer) {
                        Icon(Icons.Rounded.Equalizer, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Equalizer & DSP")
                    }
                    OutlinedButton(onClick = onOpenSources) {
                        Icon(Icons.Rounded.Hub, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Music Sources")
                    }
                }
            }
        }

        // Lyrics & Translation Section
        item {
            SettingsCard(title = "Lyrics & Translation Engine") {
                SettingsToggleRow(
                    title = "Automatic Synced Lyrics",
                    subtitle = "Auto-match via LRCLIB & cache locally when track starts",
                    checked = settings.autoLyricsEnabled,
                    onCheckedChange = { viewModel.updateAutoLyrics(it) }
                )
                SettingsToggleRow(
                    title = "Auto-Translate Lyrics",
                    subtitle = "Automatically translate lyrics into ${settings.targetTranslationLang.uppercase()}",
                    checked = settings.autoTranslateLyrics,
                    onCheckedChange = { viewModel.updateAutoTranslate(it) }
                )
                Text("Translation Provider", style = MaterialTheme.typography.labelLarge)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(TranslationProviderType.entries) { prov ->
                        FilterChip(
                            selected = settings.translationProvider == prov,
                            onClick = { viewModel.updateTranslationProvider(prov) },
                            label = { Text(prov.label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                OutlinedTextField(
                    value = musixmatchInput,
                    onValueChange = { musixmatchInput = it },
                    label = { Text("Optional Musixmatch API Key") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(onClick = { viewModel.updateMusixmatchKey(musixmatchInput) }) {
                    Text("Save Musixmatch Key")
                }
                OutlinedTextField(
                    value = googleTransInput,
                    onValueChange = { googleTransInput = it },
                    label = { Text("Optional Google Cloud Translate Key") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(onClick = { viewModel.updateGoogleTranslateKey(googleTransInput) }) {
                    Text("Save Google Translate Key")
                }
                OutlinedTextField(
                    value = libreUrlInput,
                    onValueChange = { libreUrlInput = it },
                    label = { Text("LibreTranslate Endpoint URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(onClick = { viewModel.updateLibreTranslateUrl(libreUrlInput) }) {
                    Text("Save LibreTranslate URL")
                }
            }
        }

        // Storage & Cache Section
        item {
            SettingsCard(title = "Storage & Cache Management") {
                Text(
                    text = "Downloads Directory:\n${viewModel.container.downloadManagerRepository.getDownloadDirectoryPath()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Current Cache Size: ${formatBytes(cacheBytes)}",
                    style = MaterialTheme.typography.titleSmall
                )
                OutlinedButton(
                    onClick = { viewModel.clearCachePreservingLibrary() },
                    modifier = Modifier.testTag("clear_cache_button")
                ) {
                    Icon(Icons.Rounded.CleaningServices, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Clear Cache (Keep Library & Playlists)")
                }
            }
        }

        // About Section (KOPLINGFY Branding)
        item {
            SettingsCard(title = "About KOPLINGFY") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("KOPLINGFY v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("Package: ${BuildConfig.APPLICATION_ID}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Developer: RIFANDI", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    text = "Built natively with Kotlin, Jetpack Compose Material 3, AndroidX Media3 ExoPlayer & MediaLibraryService, Room Database, DataStore, Coil, WorkManager, ML Kit Translation, Audius API, Piped/Invidious open gateways, Jamendo API, Radio Browser, and LRCLIB.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(10.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
