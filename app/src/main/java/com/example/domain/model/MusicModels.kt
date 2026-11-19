package com.example.domain.model

enum class MusicSource(val displayName: String, val badgeLabel: String, val allowsDownload: Boolean) {
    LOCAL("Local Device", "LOCAL", false),
    AUDIUS("Audius", "AUDIUS", true),
    YOUTUBE_MUSIC("YouTube Music", "YT MUSIC", false),
    JAMENDO("Jamendo", "JAMENDO", true),
    RADIO("Internet Radio", "RADIO", false)
}

data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val albumArtist: String = "",
    val genre: String = "",
    val year: Int = 0,
    val trackNumber: Int = 0,
    val durationMs: Long = 0L,
    val artworkUrl: String? = null,
    val streamUrl: String? = null,
    val localPath: String? = null,
    val source: MusicSource = MusicSource.LOCAL,
    val sourceTrackId: String = id,
    val isDownloadable: Boolean = false,
    val downloadUrl: String? = null,
    val isFavorite: Boolean = false,
    val playCount: Int = 0,
    val dateAdded: Long = System.currentTimeMillis(),
    val folderPath: String = "",
    val mimeType: String = "",
    val fileSizeBytes: Long = 0L,
    val isLiveStream: Boolean = false
)

data class ArtistItem(
    val id: String,
    val name: String,
    val artworkUrl: String? = null,
    val source: MusicSource,
    val trackCount: Int = 0,
    val followerCount: Int = 0
)

data class AlbumItem(
    val id: String,
    val title: String,
    val artist: String,
    val artworkUrl: String? = null,
    val year: Int = 0,
    val source: MusicSource,
    val trackCount: Int = 0
)

data class PlaylistSummary(
    val id: String,
    val name: String,
    val description: String = "",
    val artworkUrl: String? = null,
    val source: MusicSource = MusicSource.LOCAL,
    val isSmart: Boolean = false,
    val smartType: SmartPlaylistType? = null,
    val trackCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

enum class SmartPlaylistType(val title: String) {
    RECENTLY_PLAYED("Recently Played"),
    MOST_PLAYED("Most Played"),
    FAVORITES("Favorites"),
    RECENTLY_ADDED("Recently Added"),
    DOWNLOADED("Offline Downloads")
}

data class RadioStation(
    val id: String,
    val name: String,
    val streamUrl: String,
    val artworkUrl: String? = null,
    val country: String = "",
    val genre: String = "",
    val bitrateKbps: Int = 128,
    val isFavorite: Boolean = false,
    val isCustom: Boolean = false,
    val lastPlayedAt: Long = 0L
) {
    fun toTrack(): Track = Track(
        id = "radio_$id",
        title = name,
        artist = listOfNotNull(
            genre.takeIf { it.isNotBlank() },
            country.takeIf { it.isNotBlank() }
        ).joinToString(" • ").ifBlank { "Live Internet Radio" },
        album = if (bitrateKbps > 0) "${bitrateKbps} kbps Live Stream" else "Internet Radio",
        genre = genre,
        durationMs = 0L,
        artworkUrl = artworkUrl,
        streamUrl = streamUrl,
        source = MusicSource.RADIO,
        sourceTrackId = id,
        isDownloadable = false,
        isFavorite = isFavorite,
        isLiveStream = true
    )
}

enum class ProviderHealthState {
    ONLINE, DEGRADED, REQUIRES_KEY, OFFLINE, DISABLED
}

data class ProviderStatus(
    val source: MusicSource,
    val enabled: Boolean,
    val priority: Int,
    val health: ProviderHealthState,
    val message: String,
    val latencyMs: Long = 0L
)

enum class SearchCategory(val label: String) {
    ALL("All"),
    SONGS("Songs"),
    ARTISTS("Artists"),
    ALBUMS("Albums"),
    PLAYLISTS("Playlists"),
    RADIO("Radio")
}

enum class SearchFieldFilter(val label: String) {
    ANY("Any Field"),
    TITLE("Title"),
    ARTIST("Artist"),
    ALBUM("Album"),
    GENRE("Genre")
}

data class GlobalSearchResult(
    val query: String = "",
    val tracks: List<Track> = emptyList(),
    val artists: List<ArtistItem> = emptyList(),
    val albums: List<AlbumItem> = emptyList(),
    val playlists: List<PlaylistSummary> = emptyList(),
    val radioStations: List<RadioStation> = emptyList(),
    val isLoading: Boolean = false,
    val providerErrors: Map<MusicSource, String> = emptyMap()
)

enum class VisualizerMode(val label: String) {
    OFF("Artwork"),
    SPECTRUM("Spectrum"),
    BARS("Freq Bars"),
    WAVEFORM("Waveform"),
    CIRCULAR("Circular")
}

enum class ThemeMode(val label: String) {
    AMOLED("AMOLED Black"),
    DARK("Obsidian Dark"),
    LIGHT("Studio Light")
}

enum class AccentPreset(val label: String, val hex: Long) {
    AMBER_GOLD("Amber Gold", 0xFFF5A623),
    ELECTRIC_CORAL("Electric Coral", 0xFFFF5252),
    CYBER_TEAL("Cyber Teal", 0xFF00E5CC),
    ROYAL_VIOLET("Royal Violet", 0xFF9C6BFF),
    EMERALD_STUDIO("Emerald Studio", 0xFF10D981)
}

enum class ArtworkShapeStyle(val label: String, val cornerDp: Int) {
    ROUNDED("Rounded", 24),
    SHARP("Studio Square", 6),
    VINYL_CIRCLE("Vinyl Disc", 999)
}

enum class LyricsDisplayMode(val label: String) {
    ORIGINAL("Original"),
    TRANSLATED("Translated"),
    DUAL("Dual Language")
}

enum class LyricsAlignment(val label: String) {
    START("Left"),
    CENTER("Center"),
    END("Right")
}

enum class TranslationProviderType(val label: String) {
    ML_KIT("ML Kit On-Device (Free)"),
    GOOGLE_CLOUD("Google Cloud Translate"),
    LIBRE_TRANSLATE("LibreTranslate / Custom")
}

enum class RepeatModeState(val label: String) {
    OFF("Repeat Off"),
    ALL("Repeat All"),
    ONE("Repeat One")
}

enum class DownloadStatus {
    QUEUED, DOWNLOADING, PAUSED, COMPLETED, FAILED, CANCELLED
}

enum class LibrarySortOption(val label: String) {
    TITLE_ASC("Title A–Z"),
    ARTIST_ASC("Artist A–Z"),
    ALBUM_ASC("Album A–Z"),
    DATE_ADDED_DESC("Recently Added"),
    DURATION_DESC("Duration"),
    MOST_PLAYED("Most Played")
}

data class EqualizerPresetSpec(
    val name: String,
    val bandLevelsDb: List<Float> // 5 bands: 60Hz, 230Hz, 910Hz, 3.6kHz, 14kHz (-12f..12f dB)
)

object EqualizerPresets {
    val ALL = listOf(
        EqualizerPresetSpec("Normal", listOf(0f, 0f, 0f, 0f, 0f)),
        EqualizerPresetSpec("Bass", listOf(7f, 4.5f, 0.5f, 0f, 1f)),
        EqualizerPresetSpec("Pop", listOf(-1f, 2.5f, 4.5f, 3f, -1f)),
        EqualizerPresetSpec("Rock", listOf(5f, 2f, -1.5f, 3.5f, 5.5f)),
        EqualizerPresetSpec("Jazz", listOf(3.5f, 2f, -1f, 2.5f, 4f)),
        EqualizerPresetSpec("Classical", listOf(4f, 2.5f, -1f, 3f, 4.5f)),
        EqualizerPresetSpec("Electronic", listOf(6f, 3.5f, 0f, 4f, 6f)),
        EqualizerPresetSpec("Vocal", listOf(-2.5f, 1.5f, 5f, 4f, -1f)),
        EqualizerPresetSpec("Acoustic", listOf(3f, 2f, 1.5f, 3f, 2.5f))
    )
}

data class SupportedLanguage(val code: String, val name: String, val nativeName: String)

object TranslationLanguages {
    val ALL = listOf(
        SupportedLanguage("id", "Indonesian", "Bahasa Indonesia"),
        SupportedLanguage("en", "English", "English"),
        SupportedLanguage("ja", "Japanese", "日本語"),
        SupportedLanguage("ko", "Korean", "한국어"),
        SupportedLanguage("zh", "Chinese", "中文"),
        SupportedLanguage("es", "Spanish", "Español"),
        SupportedLanguage("fr", "French", "Français"),
        SupportedLanguage("de", "German", "Deutsch"),
        SupportedLanguage("ar", "Arabic", "العربية"),
        SupportedLanguage("pt", "Portuguese", "Português")
    )
}
