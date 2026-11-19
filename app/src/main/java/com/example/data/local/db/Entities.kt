package com.example.data.local.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.domain.model.DownloadStatus
import com.example.domain.model.MusicSource
import com.example.domain.model.RadioStation
import com.example.domain.model.Track

@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val genre: String,
    val year: Int,
    val trackNumber: Int,
    val durationMs: Long,
    val artworkUrl: String?,
    val streamUrl: String?,
    val localPath: String?,
    val source: String,
    val sourceTrackId: String,
    val isDownloadable: Boolean,
    val downloadUrl: String?,
    val isFavorite: Boolean,
    val playCount: Int,
    val dateAdded: Long,
    val folderPath: String,
    val mimeType: String,
    val fileSizeBytes: Long,
    val isLiveStream: Boolean
) {
    fun toDomain(): Track = Track(
        id = id,
        title = title,
        artist = artist,
        album = album,
        albumArtist = albumArtist,
        genre = genre,
        year = year,
        trackNumber = trackNumber,
        durationMs = durationMs,
        artworkUrl = artworkUrl,
        streamUrl = streamUrl,
        localPath = localPath,
        source = runCatching { MusicSource.valueOf(source) }.getOrDefault(MusicSource.LOCAL),
        sourceTrackId = sourceTrackId,
        isDownloadable = isDownloadable,
        downloadUrl = downloadUrl,
        isFavorite = isFavorite,
        playCount = playCount,
        dateAdded = dateAdded,
        folderPath = folderPath,
        mimeType = mimeType,
        fileSizeBytes = fileSizeBytes,
        isLiveStream = isLiveStream
    )

    companion object {
        fun fromDomain(track: Track): TrackEntity = TrackEntity(
            id = track.id,
            title = track.title,
            artist = track.artist,
            album = track.album,
            albumArtist = track.albumArtist,
            genre = track.genre,
            year = track.year,
            trackNumber = track.trackNumber,
            durationMs = track.durationMs,
            artworkUrl = track.artworkUrl,
            streamUrl = track.streamUrl,
            localPath = track.localPath,
            source = track.source.name,
            sourceTrackId = track.sourceTrackId,
            isDownloadable = track.isDownloadable,
            downloadUrl = track.downloadUrl,
            isFavorite = track.isFavorite,
            playCount = track.playCount,
            dateAdded = track.dateAdded,
            folderPath = track.folderPath,
            mimeType = track.mimeType,
            fileSizeBytes = track.fileSizeBytes,
            isLiveStream = track.isLiveStream
        )
    }
}

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val artworkUrl: String?,
    val source: String,
    val createdAt: Long
)

@Entity(
    tableName = "playlist_tracks",
    primaryKeys = ["playlistId", "trackId"],
    indices = [Index("playlistId"), Index("trackId")]
)
data class PlaylistTrackEntity(
    val playlistId: String,
    val trackId: String,
    val position: Int,
    val addedAt: Long
)

@Entity(tableName = "playback_history", indices = [Index("playedAt"), Index("trackId")])
data class PlaybackHistoryEntity(
    @PrimaryKey(autoGenerate = true) val entryId: Long = 0,
    val trackId: String,
    val playedAt: Long,
    val positionMs: Long = 0L
)

@Entity(tableName = "lyrics_cache")
data class LyricsEntity(
    @PrimaryKey val trackId: String,
    val title: String,
    val artist: String,
    val syncedLrc: String?,
    val plainLyrics: String?,
    val providerName: String,
    val offsetMs: Long = 0L,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "translated_lyrics",
    primaryKeys = ["trackId", "targetLanguage"]
)
data class TranslatedLyricsEntity(
    val trackId: String,
    val targetLanguage: String,
    val translatedSyncedLrc: String?,
    val translatedPlainLyrics: String?,
    val providerName: String,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val trackId: String,
    val title: String,
    val artist: String,
    val artworkUrl: String?,
    val source: String,
    val downloadUrl: String,
    val localFilePath: String?,
    val status: String,
    val progressPercent: Int,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val errorMessage: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun statusEnum(): DownloadStatus =
        runCatching { DownloadStatus.valueOf(status) }.getOrDefault(DownloadStatus.QUEUED)
}

@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey val query: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "radio_stations")
data class RadioStationEntity(
    @PrimaryKey val id: String,
    val name: String,
    val streamUrl: String,
    val artworkUrl: String?,
    val country: String,
    val genre: String,
    val bitrateKbps: Int,
    val isFavorite: Boolean,
    val isCustom: Boolean,
    val lastPlayedAt: Long
) {
    fun toDomain(): RadioStation = RadioStation(
        id = id,
        name = name,
        streamUrl = streamUrl,
        artworkUrl = artworkUrl,
        country = country,
        genre = genre,
        bitrateKbps = bitrateKbps,
        isFavorite = isFavorite,
        isCustom = isCustom,
        lastPlayedAt = lastPlayedAt
    )

    companion object {
        fun fromDomain(station: RadioStation): RadioStationEntity = RadioStationEntity(
            id = station.id,
            name = station.name,
            streamUrl = station.streamUrl,
            artworkUrl = station.artworkUrl,
            country = station.country,
            genre = station.genre,
            bitrateKbps = station.bitrateKbps,
            isFavorite = station.isFavorite,
            isCustom = station.isCustom,
            lastPlayedAt = station.lastPlayedAt
        )
    }
}

@Entity(tableName = "local_tag_overrides")
data class LocalTagOverrideEntity(
    @PrimaryKey val trackId: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val genre: String,
    val year: Int,
    val trackNumber: Int,
    val artworkUrl: String?
)
