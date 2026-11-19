package com.example.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks ORDER BY dateAdded DESC")
    fun observeAllTracks(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE source = 'LOCAL' ORDER BY title ASC")
    fun observeLocalTracks(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE isFavorite = 1 ORDER BY dateAdded DESC")
    fun observeFavorites(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE playCount > 0 ORDER BY playCount DESC LIMIT 50")
    fun observeMostPlayed(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE id = :id LIMIT 1")
    suspend fun getTrackById(id: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE title LIKE '%' || :query || '%' OR artist LIKE '%' || :query || '%' OR album LIKE '%' || :query || '%' OR genre LIKE '%' || :query || '%' LIMIT 100")
    suspend fun searchTracks(query: String): List<TrackEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTrack(track: TrackEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTracks(tracks: List<TrackEntity>)

    @Query("UPDATE tracks SET isFavorite = :favorite WHERE id = :trackId")
    suspend fun setFavorite(trackId: String, favorite: Boolean)

    @Query("UPDATE tracks SET playCount = playCount + 1 WHERE id = :trackId")
    suspend fun incrementPlayCount(trackId: String)

    @Query("UPDATE tracks SET localPath = :localPath WHERE id = :trackId")
    suspend fun updateLocalPath(trackId: String, localPath: String?)

    @Query("DELETE FROM tracks WHERE source = 'LOCAL' AND id NOT IN (:validIds)")
    suspend fun pruneMissingLocalTracks(validIds: List<String>)

    @Query("DELETE FROM tracks WHERE source != 'LOCAL' AND isFavorite = 0 AND localPath IS NULL")
    suspend fun clearCachedOnlineTracks()
}

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    fun observePlaylists(): Flow<List<PlaylistEntity>>

    @Query(
        """
        SELECT t.* FROM tracks t
        INNER JOIN playlist_tracks pt ON t.id = pt.trackId
        WHERE pt.playlistId = :playlistId
        ORDER BY pt.position ASC
        """
    )
    fun observePlaylistTracks(playlistId: String): Flow<List<TrackEntity>>

    @Query("SELECT COUNT(*) FROM playlist_tracks WHERE playlistId = :playlistId")
    fun observePlaylistTrackCount(playlistId: String): Flow<Int>

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun getMaxPosition(playlistId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlaylist(playlist: PlaylistEntity)

    @Query("UPDATE playlists SET name = :name, description = :description WHERE id = :playlistId")
    suspend fun updatePlaylistMeta(playlistId: String, name: String, description: String)

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deletePlaylist(playlistId: String)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun clearPlaylistTracks(playlistId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addTrackToPlaylist(ref: PlaylistTrackEntity)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId AND trackId = :trackId")
    suspend fun removeTrackFromPlaylist(playlistId: String, trackId: String)

    @Query("UPDATE playlist_tracks SET position = :position WHERE playlistId = :playlistId AND trackId = :trackId")
    suspend fun updateTrackPosition(playlistId: String, trackId: String, position: Int)

    @Transaction
    suspend fun reorderPlaylist(playlistId: String, orderedTrackIds: List<String>) {
        orderedTrackIds.forEachIndexed { index, trackId ->
            updateTrackPosition(playlistId, trackId, index)
        }
    }
}

@Dao
interface HistoryDao {
    @Query(
        """
        SELECT t.* FROM tracks t
        INNER JOIN (
            SELECT trackId, MAX(playedAt) as latestPlay
            FROM playback_history
            GROUP BY trackId
        ) h ON t.id = h.trackId
        ORDER BY h.latestPlay DESC
        LIMIT 50
        """
    )
    fun observeRecentlyPlayed(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM playback_history ORDER BY playedAt DESC LIMIT 1")
    suspend fun getLastPlayedEntry(): PlaybackHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(entry: PlaybackHistoryEntity)

    @Query("DELETE FROM playback_history")
    suspend fun clearHistory()
}

@Dao
interface LyricsDao {
    @Query("SELECT * FROM lyrics_cache WHERE trackId = :trackId LIMIT 1")
    suspend fun getLyrics(trackId: String): LyricsEntity?

    @Query("SELECT * FROM lyrics_cache ORDER BY updatedAt DESC LIMIT 100")
    fun observeLyricsHistory(): Flow<List<LyricsEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveLyrics(lyrics: LyricsEntity)

    @Query("UPDATE lyrics_cache SET offsetMs = :offsetMs WHERE trackId = :trackId")
    suspend fun updateOffset(trackId: String, offsetMs: Long)

    @Query("SELECT * FROM translated_lyrics WHERE trackId = :trackId AND targetLanguage = :lang LIMIT 1")
    suspend fun getTranslation(trackId: String, lang: String): TranslatedLyricsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveTranslation(translation: TranslatedLyricsEntity)

    @Query("DELETE FROM lyrics_cache")
    suspend fun clearLyricsCache()

    @Query("DELETE FROM translated_lyrics")
    suspend fun clearTranslationsCache()
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY updatedAt DESC")
    fun observeDownloads(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE status = 'COMPLETED' ORDER BY updatedAt DESC")
    fun observeCompletedDownloads(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE trackId = :trackId LIMIT 1")
    suspend fun getDownload(trackId: String): DownloadEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDownload(download: DownloadEntity)

    @Query("UPDATE downloads SET status = :status, progressPercent = :progress, downloadedBytes = :downloaded, totalBytes = :total, localFilePath = :localPath, errorMessage = :error, updatedAt = :updatedAt WHERE trackId = :trackId")
    suspend fun updateDownloadProgress(
        trackId: String,
        status: String,
        progress: Int,
        downloaded: Long,
        total: Long,
        localPath: String?,
        error: String?,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("DELETE FROM downloads WHERE trackId = :trackId")
    suspend fun deleteDownload(trackId: String)
}

@Dao
interface SearchHistoryDao {
    @Query("SELECT * FROM search_history ORDER BY timestamp DESC LIMIT 20")
    fun observeRecentSearches(): Flow<List<SearchHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveQuery(entry: SearchHistoryEntity)

    @Query("DELETE FROM search_history WHERE query = :query")
    suspend fun deleteQuery(query: String)

    @Query("DELETE FROM search_history")
    suspend fun clearAll()
}

@Dao
interface RadioDao {
    @Query("SELECT * FROM radio_stations WHERE isFavorite = 1 ORDER BY name ASC")
    fun observeFavoriteStations(): Flow<List<RadioStationEntity>>

    @Query("SELECT * FROM radio_stations WHERE lastPlayedAt > 0 ORDER BY lastPlayedAt DESC LIMIT 20")
    fun observeRecentStations(): Flow<List<RadioStationEntity>>

    @Query("SELECT * FROM radio_stations WHERE isCustom = 1 ORDER BY name ASC")
    fun observeCustomStations(): Flow<List<RadioStationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStation(station: RadioStationEntity)

    @Query("UPDATE radio_stations SET isFavorite = :favorite WHERE id = :stationId")
    suspend fun setFavorite(stationId: String, favorite: Boolean)

    @Query("UPDATE radio_stations SET lastPlayedAt = :timestamp WHERE id = :stationId")
    suspend fun markPlayed(stationId: String, timestamp: Long = System.currentTimeMillis())

    @Query("DELETE FROM radio_stations WHERE id = :stationId AND isCustom = 1")
    suspend fun deleteCustomStation(stationId: String)
}

@Dao
interface TagOverrideDao {
    @Query("SELECT * FROM local_tag_overrides")
    suspend fun getAllOverrides(): List<LocalTagOverrideEntity>

    @Query("SELECT * FROM local_tag_overrides WHERE trackId = :trackId LIMIT 1")
    suspend fun getOverride(trackId: String): LocalTagOverrideEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveOverride(override: LocalTagOverrideEntity)
}
