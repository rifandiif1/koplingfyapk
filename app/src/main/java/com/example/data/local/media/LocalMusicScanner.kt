package com.example.data.local.media

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.example.data.local.db.LocalTagOverrideEntity
import com.example.data.local.db.TagOverrideDao
import com.example.data.local.db.TrackDao
import com.example.data.local.db.TrackEntity
import com.example.domain.model.MusicSource
import com.example.domain.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LocalMusicScanner(
    private val context: Context,
    private val trackDao: TrackDao,
    private val tagOverrideDao: TagOverrideDao
) {
    private val supportedExtensions = setOf("mp3", "flac", "wav", "aac", "m4a", "ogg", "opus", "wma", "amr")

    suspend fun scanDeviceMusic(
        customFolderUris: Set<String> = emptySet(),
        excludedFolders: Set<String> = emptySet()
    ): List<Track> = withContext(Dispatchers.IO) {
        val overrides = tagOverrideDao.getAllOverrides().associateBy { it.trackId }
        val existingFavorites = mutableMapOf<String, Pair<Boolean, Int>>()
        val scanned = mutableListOf<Track>()

        // 1. Scan Android MediaStore Audio
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.ALBUM_ID)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.DATE_ADDED)
            add(MediaStore.Audio.Media.YEAR)
            add(MediaStore.Audio.Media.TRACK)
            add(MediaStore.Audio.Media.MIME_TYPE)
            add(MediaStore.Audio.Media.SIZE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Audio.Media.RELATIVE_PATH)
            } else {
                @Suppress("DEPRECATION")
                add(MediaStore.Audio.Media.DATA)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(MediaStore.Audio.Media.ALBUM_ARTIST)
                add(MediaStore.Audio.Media.GENRE)
            }
        }.toTypedArray()

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} > 5000"
        val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC"

        runCatching {
            context.contentResolver.query(collection, projection, selection, null, sortOrder)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val yearCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
                val trackCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val pathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
                } else {
                    @Suppress("DEPRECATION")
                    cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                }
                val albumArtistCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ARTIST)
                } else -1
                val genreCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    cursor.getColumnIndex(MediaStore.Audio.Media.GENRE)
                } else -1

                while (cursor.moveToNext()) {
                    val mediaId = cursor.getLong(idCol)
                    val trackId = "local_$mediaId"
                    val contentUri = ContentUris.withAppendedId(collection, mediaId)
                    val albumId = cursor.getLong(albumIdCol)
                    val albumArtUri = ContentUris.withAppendedId(
                        Uri.parse("content://media/external/audio/albumart"),
                        albumId
                    ).toString()

                    val rawPath = if (pathCol >= 0) cursor.getString(pathCol).orEmpty() else ""
                    val folder = extractFolderName(rawPath)
                    if (excludedFolders.any { it.equals(folder, ignoreCase = true) }) continue

                    val existing = trackDao.getTrackById(trackId)
                    if (existing != null) {
                        existingFavorites[trackId] = existing.isFavorite to existing.playCount
                    }
                    val override = overrides[trackId]

                    val track = Track(
                        id = trackId,
                        title = override?.title ?: cursor.getString(titleCol).orEmpty().ifBlank { "Untitled Audio" },
                        artist = override?.artist ?: cursor.getString(artistCol).orEmpty().let {
                            if (it == "<unknown>" || it.isBlank()) "Unknown Artist" else it
                        },
                        album = override?.album ?: cursor.getString(albumCol).orEmpty().ifBlank { "Unknown Album" },
                        albumArtist = override?.albumArtist ?: (if (albumArtistCol >= 0) cursor.getString(albumArtistCol) else null).orEmpty(),
                        genre = override?.genre ?: (if (genreCol >= 0) cursor.getString(genreCol) else null).orEmpty().ifBlank { "Local Audio" },
                        year = override?.year ?: cursor.getInt(yearCol),
                        trackNumber = override?.trackNumber ?: (cursor.getInt(trackCol) % 1000),
                        durationMs = cursor.getLong(durationCol),
                        artworkUrl = override?.artworkUrl ?: albumArtUri,
                        streamUrl = contentUri.toString(),
                        localPath = contentUri.toString(),
                        source = MusicSource.LOCAL,
                        sourceTrackId = mediaId.toString(),
                        isDownloadable = false,
                        isFavorite = existingFavorites[trackId]?.first ?: false,
                        playCount = existingFavorites[trackId]?.second ?: 0,
                        dateAdded = cursor.getLong(dateAddedCol) * 1000L,
                        folderPath = folder,
                        mimeType = cursor.getString(mimeCol).orEmpty(),
                        fileSizeBytes = cursor.getLong(sizeCol)
                    )
                    scanned.add(track)
                }
            }
        }

        // 2. Scan Custom SAF Folders
        customFolderUris.forEach { treeUriStr ->
            runCatching {
                val treeUri = Uri.parse(treeUriStr)
                scanned.addAll(scanSafTree(treeUri, overrides))
            }
        }

        val uniqueScanned = scanned.distinctBy { it.id }
        if (uniqueScanned.isNotEmpty()) {
            trackDao.upsertTracks(uniqueScanned.map { TrackEntity.fromDomain(it) })
            trackDao.pruneMissingLocalTracks(uniqueScanned.map { it.id })
        }
        uniqueScanned
    }

    private fun scanSafTree(
        treeUri: Uri,
        overrides: Map<String, LocalTagOverrideEntity>
    ): List<Track> {
        val results = mutableListOf<Track>()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri)
        )
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        )
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            while (cursor.moveToNext()) {
                val docId = cursor.getString(idIdx)
                val name = cursor.getString(nameIdx).orEmpty()
                val mime = cursor.getString(mimeIdx).orEmpty()
                val size = cursor.getLong(sizeIdx)
                val ext = name.substringAfterLast('.', "").lowercase()
                if (!mime.startsWith("audio/") && ext !in supportedExtensions) continue

                val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                val trackId = "local_saf_${docUri.toString().hashCode()}"
                val override = overrides[trackId]
                val meta = extractMetadataSafely(docUri, name.substringBeforeLast('.'))
                results.add(
                    Track(
                        id = trackId,
                        title = override?.title ?: meta.title,
                        artist = override?.artist ?: meta.artist,
                        album = override?.album ?: meta.album,
                        albumArtist = override?.albumArtist ?: meta.albumArtist,
                        genre = override?.genre ?: meta.genre,
                        year = override?.year ?: meta.year,
                        trackNumber = override?.trackNumber ?: meta.trackNumber,
                        durationMs = meta.durationMs,
                        artworkUrl = override?.artworkUrl,
                        streamUrl = docUri.toString(),
                        localPath = docUri.toString(),
                        source = MusicSource.LOCAL,
                        sourceTrackId = docId,
                        folderPath = "SAF Folder",
                        mimeType = mime,
                        fileSizeBytes = size
                    )
                )
            }
        }
        return results
    }

    suspend fun importSingleAudioUri(uri: Uri): Track? = withContext(Dispatchers.IO) {
        val meta = extractMetadataSafely(uri, uri.lastPathSegment?.substringBeforeLast('.') ?: "Imported Audio")
        val trackId = "local_uri_${uri.toString().hashCode()}"
        val track = Track(
            id = trackId,
            title = meta.title,
            artist = meta.artist,
            album = meta.album,
            albumArtist = meta.albumArtist,
            genre = meta.genre,
            year = meta.year,
            trackNumber = meta.trackNumber,
            durationMs = meta.durationMs,
            streamUrl = uri.toString(),
            localPath = uri.toString(),
            source = MusicSource.LOCAL,
            sourceTrackId = trackId,
            folderPath = "Imported Files",
            mimeType = context.contentResolver.getType(uri).orEmpty()
        )
        trackDao.upsertTrack(TrackEntity.fromDomain(track))
        track
    }

    suspend fun updateLocalTrackTags(
        track: Track,
        title: String,
        artist: String,
        album: String,
        albumArtist: String,
        genre: String,
        year: Int,
        trackNumber: Int,
        artworkUrl: String?
    ): Track = withContext(Dispatchers.IO) {
        // 1. Always persist cleanly in Room Tag Overrides so edits survive rescans & scoped storage restrictions
        val override = LocalTagOverrideEntity(
            trackId = track.id,
            title = title.ifBlank { track.title },
            artist = artist.ifBlank { track.artist },
            album = album,
            albumArtist = albumArtist,
            genre = genre,
            year = year,
            trackNumber = trackNumber,
            artworkUrl = artworkUrl ?: track.artworkUrl
        )
        tagOverrideDao.saveOverride(override)

        val updated = track.copy(
            title = override.title,
            artist = override.artist,
            album = override.album,
            albumArtist = override.albumArtist,
            genre = override.genre,
            year = override.year,
            trackNumber = override.trackNumber,
            artworkUrl = override.artworkUrl
        )
        trackDao.upsertTrack(TrackEntity.fromDomain(updated))

        // 2. Best-effort MediaStore metadata update where Android Scoped Storage permits write
        if (track.localPath?.startsWith("content://media/") == true) {
            runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.TITLE, updated.title)
                    put(MediaStore.Audio.Media.ARTIST, updated.artist)
                    put(MediaStore.Audio.Media.ALBUM, updated.album)
                    if (updated.year > 0) put(MediaStore.Audio.Media.YEAR, updated.year)
                    if (updated.trackNumber > 0) put(MediaStore.Audio.Media.TRACK, updated.trackNumber)
                }
                context.contentResolver.update(Uri.parse(track.localPath), values, null, null)
            }
        }
        updated
    }

    private data class ExtractedMeta(
        val title: String,
        val artist: String,
        val album: String,
        val albumArtist: String,
        val genre: String,
        val year: Int,
        val trackNumber: Int,
        val durationMs: Long
    )

    private fun extractMetadataSafely(uri: Uri, fallbackTitle: String): ExtractedMeta {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() } ?: fallbackTitle
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.takeIf { it.isNotBlank() } ?: "Unknown Artist"
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                ?.takeIf { it.isNotBlank() } ?: "Unknown Album"
            val albumArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST).orEmpty()
            val genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)
                ?.takeIf { it.isNotBlank() } ?: "Local Audio"
            val year = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)?.toIntOrNull() ?: 0
            val trackNo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                ?.substringBefore("/")?.toIntOrNull() ?: 0
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            ExtractedMeta(title, artist, album, albumArtist, genre, year, trackNo, duration)
        } catch (_: Exception) {
            ExtractedMeta(fallbackTitle, "Unknown Artist", "Unknown Album", "", "Local Audio", 0, 0, 0L)
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun extractFolderName(rawPath: String): String {
        if (rawPath.isBlank()) return "Music"
        val cleaned = rawPath.trimEnd('/')
        return if (cleaned.contains("/")) {
            val parts = cleaned.split("/").filter { it.isNotBlank() }
            if (parts.size >= 2 && parts.last().contains('.')) {
                parts[parts.size - 2]
            } else {
                parts.lastOrNull() ?: "Music"
            }
        } else cleaned
    }
}
