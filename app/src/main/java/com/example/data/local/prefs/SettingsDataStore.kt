package com.example.data.local.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.domain.model.AccentPreset
import com.example.domain.model.ArtworkShapeStyle
import com.example.domain.model.LibrarySortOption
import com.example.domain.model.LyricsAlignment
import com.example.domain.model.LyricsDisplayMode
import com.example.domain.model.MusicSource
import com.example.domain.model.RepeatModeState
import com.example.domain.model.ThemeMode
import com.example.domain.model.TranslationProviderType
import com.example.domain.model.VisualizerMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.koplingfyDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "koplingfy_settings"
)

data class AppSettings(
    // Appearance
    val themeMode: ThemeMode = ThemeMode.DARK,
    val accentPreset: AccentPreset = AccentPreset.AMBER_GOLD,
    val dynamicColor: Boolean = false,
    val animationsEnabled: Boolean = true,
    val artworkShapeStyle: ArtworkShapeStyle = ArtworkShapeStyle.ROUNDED,
    val visualizerMode: VisualizerMode = VisualizerMode.SPECTRUM,
    // Playback
    val highQualityAudio: Boolean = true,
    val gaplessPlayback: Boolean = true,
    val resumeOnStartup: Boolean = true,
    val defaultRepeat: RepeatModeState = RepeatModeState.OFF,
    val defaultShuffle: Boolean = false,
    val playbackSpeed: Float = 1.0f,
    // Lyrics & Translation
    val autoLyricsEnabled: Boolean = true,
    val lyricsFontSizeSp: Float = 26f,
    val lyricsLineSpacingSp: Float = 16f,
    val lyricsAlignment: LyricsAlignment = LyricsAlignment.START,
    val lyricsDisplayMode: LyricsDisplayMode = LyricsDisplayMode.ORIGINAL,
    val autoTranslateLyrics: Boolean = false,
    val targetTranslationLang: String = "id",
    val translationProvider: TranslationProviderType = TranslationProviderType.ML_KIT,
    // Library
    val librarySortOption: LibrarySortOption = LibrarySortOption.TITLE_ASC,
    val customScanFolders: Set<String> = emptySet(),
    val excludedFolders: Set<String> = emptySet(),
    // Music Sources & Provider Configuration (Default: YouTube Music ONLY)
    val enabledSources: Set<MusicSource> = setOf(MusicSource.YOUTUBE_MUSIC),
    val sourcePriorityOrder: List<MusicSource> = listOf(
        MusicSource.YOUTUBE_MUSIC,
        MusicSource.AUDIUS,
        MusicSource.LOCAL,
        MusicSource.JAMENDO,
        MusicSource.RADIO
    ),
    val customPipedInstanceUrl: String = "https://pipedapi.kavin.rocks",
    val jamendoClientId: String = "",
    val musixmatchApiKey: String = "",
    val googleTranslateApiKey: String = "",
    val libreTranslateUrl: String = "https://libretranslate.com/translate",
    // Equalizer
    val eqEnabled: Boolean = false,
    val eqPresetIndex: Int = 0,
    val eqBandLevelsDb: List<Float> = listOf(0f, 0f, 0f, 0f, 0f),
    val bassBoostStrength: Int = 0, // 0..1000
    val virtualizerStrength: Int = 0, // 0..1000
    val loudnessGainMb: Int = 0, // 0..1500
    // Resume state & Listen Together
    val lastTrackId: String = "",
    val lastPositionMs: Long = 0L,
    val listenUsername: String = ""
)

class SettingsDataStore(private val context: Context) {

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val ACCENT_PRESET = stringPreferencesKey("accent_preset")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val ANIMATIONS_ENABLED = booleanPreferencesKey("animations_enabled")
        val ARTWORK_STYLE = stringPreferencesKey("artwork_style")
        val VISUALIZER_MODE = stringPreferencesKey("visualizer_mode")

        val HIGH_QUALITY = booleanPreferencesKey("high_quality_audio")
        val GAPLESS = booleanPreferencesKey("gapless_playback")
        val RESUME_STARTUP = booleanPreferencesKey("resume_on_startup")
        val DEFAULT_REPEAT = stringPreferencesKey("default_repeat")
        val DEFAULT_SHUFFLE = booleanPreferencesKey("default_shuffle")
        val PLAYBACK_SPEED = floatPreferencesKey("playback_speed")

        val AUTO_LYRICS = booleanPreferencesKey("auto_lyrics")
        val LYRICS_FONT_SIZE = floatPreferencesKey("lyrics_font_size")
        val LYRICS_LINE_SPACING = floatPreferencesKey("lyrics_line_spacing")
        val LYRICS_ALIGNMENT = stringPreferencesKey("lyrics_alignment")
        val LYRICS_DISPLAY_MODE = stringPreferencesKey("lyrics_display_mode")
        val AUTO_TRANSLATE = booleanPreferencesKey("auto_translate")
        val TARGET_LANG = stringPreferencesKey("target_translation_lang")
        val TRANSLATION_PROVIDER = stringPreferencesKey("translation_provider")

        val LIBRARY_SORT = stringPreferencesKey("library_sort")
        val CUSTOM_FOLDERS = stringSetPreferencesKey("custom_scan_folders")
        val EXCLUDED_FOLDERS = stringSetPreferencesKey("excluded_folders")

        val ENABLED_SOURCES = stringSetPreferencesKey("enabled_sources")
        val SOURCE_PRIORITY = stringPreferencesKey("source_priority")
        val PIPED_URL = stringPreferencesKey("piped_url")
        val JAMENDO_CLIENT_ID = stringPreferencesKey("jamendo_client_id")
        val MUSIXMATCH_KEY = stringPreferencesKey("musixmatch_api_key")
        val GOOGLE_TRANSLATE_KEY = stringPreferencesKey("google_translate_api_key")
        val LIBRE_TRANSLATE_URL = stringPreferencesKey("libre_translate_url")

        val EQ_ENABLED = booleanPreferencesKey("eq_enabled")
        val EQ_PRESET_INDEX = intPreferencesKey("eq_preset_index")
        val EQ_BANDS = stringPreferencesKey("eq_bands")
        val BASS_BOOST = intPreferencesKey("bass_boost")
        val VIRTUALIZER = intPreferencesKey("virtualizer")
        val LOUDNESS = intPreferencesKey("loudness")

        val LAST_TRACK_ID = stringPreferencesKey("last_track_id")
        val LAST_POSITION_MS = longPreferencesKey("last_position_ms")
        val LISTEN_USERNAME = stringPreferencesKey("listen_username")
        val ENABLED_SOURCES_V2 = stringSetPreferencesKey("enabled_sources_v2")
    }

    val settingsFlow: Flow<AppSettings> = context.koplingfyDataStore.data.map { prefs ->
        val defaultSources = setOf(MusicSource.YOUTUBE_MUSIC.name)
        val enabledNames = prefs[Keys.ENABLED_SOURCES_V2] ?: defaultSources
        val enabledSources = enabledNames.mapNotNull { name ->
            runCatching { MusicSource.valueOf(name) }.getOrNull()
        }.toSet().ifEmpty { setOf(MusicSource.YOUTUBE_MUSIC) }

        val priorityStr = prefs[Keys.SOURCE_PRIORITY]
        val priorityOrder = if (priorityStr.isNullOrBlank()) {
            AppSettings().sourcePriorityOrder
        } else {
            val parsed = priorityStr.split(",").mapNotNull { name ->
                runCatching { MusicSource.valueOf(name.trim()) }.getOrNull()
            }
            val missing = MusicSource.entries.filterNot { it in parsed }
            parsed + missing
        }

        val bandStr = prefs[Keys.EQ_BANDS]
        val bands = if (bandStr.isNullOrBlank()) {
            listOf(0f, 0f, 0f, 0f, 0f)
        } else {
            bandStr.split(",").mapNotNull { it.toFloatOrNull() }.take(5).let {
                if (it.size == 5) it else listOf(0f, 0f, 0f, 0f, 0f)
            }
        }

        AppSettings(
            themeMode = prefs[Keys.THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.DARK,
            accentPreset = prefs[Keys.ACCENT_PRESET]?.let { runCatching { AccentPreset.valueOf(it) }.getOrNull() }
                ?: AccentPreset.AMBER_GOLD,
            dynamicColor = prefs[Keys.DYNAMIC_COLOR] ?: false,
            animationsEnabled = prefs[Keys.ANIMATIONS_ENABLED] ?: true,
            artworkShapeStyle = prefs[Keys.ARTWORK_STYLE]?.let { runCatching { ArtworkShapeStyle.valueOf(it) }.getOrNull() }
                ?: ArtworkShapeStyle.ROUNDED,
            visualizerMode = prefs[Keys.VISUALIZER_MODE]?.let { runCatching { VisualizerMode.valueOf(it) }.getOrNull() }
                ?: VisualizerMode.SPECTRUM,
            highQualityAudio = prefs[Keys.HIGH_QUALITY] ?: true,
            gaplessPlayback = prefs[Keys.GAPLESS] ?: true,
            resumeOnStartup = prefs[Keys.RESUME_STARTUP] ?: true,
            defaultRepeat = prefs[Keys.DEFAULT_REPEAT]?.let { runCatching { RepeatModeState.valueOf(it) }.getOrNull() }
                ?: RepeatModeState.OFF,
            defaultShuffle = prefs[Keys.DEFAULT_SHUFFLE] ?: false,
            playbackSpeed = prefs[Keys.PLAYBACK_SPEED] ?: 1.0f,
            autoLyricsEnabled = prefs[Keys.AUTO_LYRICS] ?: true,
            lyricsFontSizeSp = prefs[Keys.LYRICS_FONT_SIZE] ?: 26f,
            lyricsLineSpacingSp = prefs[Keys.LYRICS_LINE_SPACING] ?: 16f,
            lyricsAlignment = prefs[Keys.LYRICS_ALIGNMENT]?.let { runCatching { LyricsAlignment.valueOf(it) }.getOrNull() }
                ?: LyricsAlignment.START,
            lyricsDisplayMode = prefs[Keys.LYRICS_DISPLAY_MODE]?.let { runCatching { LyricsDisplayMode.valueOf(it) }.getOrNull() }
                ?: LyricsDisplayMode.ORIGINAL,
            autoTranslateLyrics = prefs[Keys.AUTO_TRANSLATE] ?: false,
            targetTranslationLang = prefs[Keys.TARGET_LANG] ?: "id",
            translationProvider = prefs[Keys.TRANSLATION_PROVIDER]?.let { runCatching { TranslationProviderType.valueOf(it) }.getOrNull() }
                ?: TranslationProviderType.ML_KIT,
            librarySortOption = prefs[Keys.LIBRARY_SORT]?.let { runCatching { LibrarySortOption.valueOf(it) }.getOrNull() }
                ?: LibrarySortOption.TITLE_ASC,
            customScanFolders = prefs[Keys.CUSTOM_FOLDERS] ?: emptySet(),
            excludedFolders = prefs[Keys.EXCLUDED_FOLDERS] ?: emptySet(),
            enabledSources = enabledSources,
            sourcePriorityOrder = priorityOrder,
            customPipedInstanceUrl = prefs[Keys.PIPED_URL] ?: "https://pipedapi.kavin.rocks",
            jamendoClientId = prefs[Keys.JAMENDO_CLIENT_ID] ?: "",
            musixmatchApiKey = prefs[Keys.MUSIXMATCH_KEY] ?: "",
            googleTranslateApiKey = prefs[Keys.GOOGLE_TRANSLATE_KEY] ?: "",
            libreTranslateUrl = prefs[Keys.LIBRE_TRANSLATE_URL] ?: "https://libretranslate.com/translate",
            eqEnabled = prefs[Keys.EQ_ENABLED] ?: false,
            eqPresetIndex = prefs[Keys.EQ_PRESET_INDEX] ?: 0,
            eqBandLevelsDb = bands,
            bassBoostStrength = prefs[Keys.BASS_BOOST] ?: 0,
            virtualizerStrength = prefs[Keys.VIRTUALIZER] ?: 0,
            loudnessGainMb = prefs[Keys.LOUDNESS] ?: 0,
            lastTrackId = prefs[Keys.LAST_TRACK_ID] ?: "",
            lastPositionMs = prefs[Keys.LAST_POSITION_MS] ?: 0L,
            listenUsername = prefs[Keys.LISTEN_USERNAME] ?: ""
        )
    }

    suspend fun updateThemeMode(mode: ThemeMode) = edit { it[Keys.THEME_MODE] = mode.name }
    suspend fun updateAccentPreset(preset: AccentPreset) = edit { it[Keys.ACCENT_PRESET] = preset.name }
    suspend fun updateDynamicColor(enabled: Boolean) = edit { it[Keys.DYNAMIC_COLOR] = enabled }
    suspend fun updateAnimationsEnabled(enabled: Boolean) = edit { it[Keys.ANIMATIONS_ENABLED] = enabled }
    suspend fun updateArtworkStyle(style: ArtworkShapeStyle) = edit { it[Keys.ARTWORK_STYLE] = style.name }
    suspend fun updateVisualizerMode(mode: VisualizerMode) = edit { it[Keys.VISUALIZER_MODE] = mode.name }

    suspend fun updateHighQualityAudio(enabled: Boolean) = edit { it[Keys.HIGH_QUALITY] = enabled }
    suspend fun updateGaplessPlayback(enabled: Boolean) = edit { it[Keys.GAPLESS] = enabled }
    suspend fun updateResumeOnStartup(enabled: Boolean) = edit { it[Keys.RESUME_STARTUP] = enabled }
    suspend fun updateDefaultRepeat(mode: RepeatModeState) = edit { it[Keys.DEFAULT_REPEAT] = mode.name }
    suspend fun updateDefaultShuffle(enabled: Boolean) = edit { it[Keys.DEFAULT_SHUFFLE] = enabled }
    suspend fun updatePlaybackSpeed(speed: Float) = edit { it[Keys.PLAYBACK_SPEED] = speed }

    suspend fun updateAutoLyrics(enabled: Boolean) = edit { it[Keys.AUTO_LYRICS] = enabled }
    suspend fun updateLyricsFontSize(sizeSp: Float) = edit { it[Keys.LYRICS_FONT_SIZE] = sizeSp.coerceIn(14f, 34f) }
    suspend fun updateLyricsLineSpacing(spacingSp: Float) = edit { it[Keys.LYRICS_LINE_SPACING] = spacingSp.coerceIn(4f, 24f) }
    suspend fun updateLyricsAlignment(alignment: LyricsAlignment) = edit { it[Keys.LYRICS_ALIGNMENT] = alignment.name }
    suspend fun updateLyricsDisplayMode(mode: LyricsDisplayMode) = edit { it[Keys.LYRICS_DISPLAY_MODE] = mode.name }
    suspend fun updateAutoTranslate(enabled: Boolean) = edit { it[Keys.AUTO_TRANSLATE] = enabled }
    suspend fun updateTargetLanguage(langCode: String) = edit { it[Keys.TARGET_LANG] = langCode }
    suspend fun updateTranslationProvider(provider: TranslationProviderType) = edit { it[Keys.TRANSLATION_PROVIDER] = provider.name }

    suspend fun updateLibrarySort(sort: LibrarySortOption) = edit { it[Keys.LIBRARY_SORT] = sort.name }
    suspend fun addCustomScanFolder(uriStr: String) = edit { prefs ->
        val cur = prefs[Keys.CUSTOM_FOLDERS] ?: emptySet()
        prefs[Keys.CUSTOM_FOLDERS] = cur + uriStr
    }
    suspend fun removeCustomScanFolder(uriStr: String) = edit { prefs ->
        val cur = prefs[Keys.CUSTOM_FOLDERS] ?: emptySet()
        prefs[Keys.CUSTOM_FOLDERS] = cur - uriStr
    }
    suspend fun toggleExcludedFolder(folder: String) = edit { prefs ->
        val cur = prefs[Keys.EXCLUDED_FOLDERS] ?: emptySet()
        prefs[Keys.EXCLUDED_FOLDERS] = if (folder in cur) cur - folder else cur + folder
    }

    suspend fun toggleSourceEnabled(source: MusicSource, enabled: Boolean) = edit { prefs ->
        val cur = (prefs[Keys.ENABLED_SOURCES_V2] ?: setOf(MusicSource.YOUTUBE_MUSIC.name)).toMutableSet()
        if (enabled) cur.add(source.name) else cur.remove(source.name)
        prefs[Keys.ENABLED_SOURCES_V2] = cur
    }
    suspend fun updateSourcePriority(order: List<MusicSource>) = edit {
        it[Keys.SOURCE_PRIORITY] = order.joinToString(",") { s -> s.name }
    }
    suspend fun updateCustomPipedUrl(url: String) = edit { it[Keys.PIPED_URL] = url.trim().trimEnd('/') }
    suspend fun updateJamendoClientId(clientId: String) = edit { it[Keys.JAMENDO_CLIENT_ID] = clientId.trim() }
    suspend fun updateMusixmatchKey(key: String) = edit { it[Keys.MUSIXMATCH_KEY] = key.trim() }
    suspend fun updateGoogleTranslateKey(key: String) = edit { it[Keys.GOOGLE_TRANSLATE_KEY] = key.trim() }
    suspend fun updateLibreTranslateUrl(url: String) = edit { it[Keys.LIBRE_TRANSLATE_URL] = url.trim() }

    suspend fun updateEqualizer(
        enabled: Boolean,
        presetIndex: Int,
        bandLevelsDb: List<Float>,
        bassBoost: Int,
        virtualizer: Int,
        loudness: Int
    ) = edit {
        it[Keys.EQ_ENABLED] = enabled
        it[Keys.EQ_PRESET_INDEX] = presetIndex
        it[Keys.EQ_BANDS] = bandLevelsDb.joinToString(",") { v -> "%.1f".format(v) }
        it[Keys.BASS_BOOST] = bassBoost.coerceIn(0, 1000)
        it[Keys.VIRTUALIZER] = virtualizer.coerceIn(0, 1000)
        it[Keys.LOUDNESS] = loudness.coerceIn(0, 1500)
    }

    suspend fun saveLastPlayback(trackId: String, positionMs: Long) = edit {
        it[Keys.LAST_TRACK_ID] = trackId
        it[Keys.LAST_POSITION_MS] = positionMs
    }

    suspend fun updateListenUsername(username: String) = edit {
        it[Keys.LISTEN_USERNAME] = username.trim().take(30)
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.koplingfyDataStore.edit(block)
    }
}
