package com.example.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        TrackEntity::class,
        PlaylistEntity::class,
        PlaylistTrackEntity::class,
        PlaybackHistoryEntity::class,
        LyricsEntity::class,
        TranslatedLyricsEntity::class,
        DownloadEntity::class,
        SearchHistoryEntity::class,
        RadioStationEntity::class,
        LocalTagOverrideEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class KoplingfyDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun historyDao(): HistoryDao
    abstract fun lyricsDao(): LyricsDao
    abstract fun downloadDao(): DownloadDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun radioDao(): RadioDao
    abstract fun tagOverrideDao(): TagOverrideDao

    companion object {
        @Volatile
        private var INSTANCE: KoplingfyDatabase? = null

        fun getInstance(context: Context): KoplingfyDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    KoplingfyDatabase::class.java,
                    "koplingfy_database.db"
                ).fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
