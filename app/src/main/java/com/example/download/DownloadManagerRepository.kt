package com.example.download

import android.content.Context
import android.os.Environment
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.data.local.db.DownloadDao
import com.example.data.local.db.DownloadEntity
import com.example.data.local.db.TrackDao
import com.example.data.local.db.TrackEntity
import com.example.domain.model.DownloadStatus
import com.example.domain.model.Track
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.net.URI

class DownloadManagerRepository(
    private val context: Context,
    private val downloadDao: DownloadDao,
    private val trackDao: TrackDao
) {
    val downloadsFlow: Flow<List<DownloadEntity>> = downloadDao.observeDownloads()
    val completedDownloadsFlow: Flow<List<DownloadEntity>> = downloadDao.observeCompletedDownloads()

    suspend fun enqueueDownload(track: Track): Result<Unit> {
        if (!track.source.allowsDownload || !track.isDownloadable) {
            return Result.failure(
                IllegalStateException("${track.source.displayName} does not permit offline downloads for this track.")
            )
        }
        val url = track.downloadUrl ?: track.streamUrl
            ?: return Result.failure(IllegalStateException("No downloadable audio URL available."))

        trackDao.upsertTrack(TrackEntity.fromDomain(track))
        downloadDao.upsertDownload(
            DownloadEntity(
                trackId = track.id,
                title = track.title,
                artist = track.artist,
                artworkUrl = track.artworkUrl,
                source = track.source.name,
                downloadUrl = url,
                localFilePath = null,
                status = DownloadStatus.QUEUED.name,
                progressPercent = 0,
                downloadedBytes = 0L,
                totalBytes = 0L
            )
        )

        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setInputData(
                workDataOf(
                    DownloadWorker.KEY_TRACK_ID to track.id,
                    DownloadWorker.KEY_DOWNLOAD_URL to url,
                    DownloadWorker.KEY_FILE_NAME to "${track.artist}_${track.title}_${track.sourceTrackId}"
                )
            )
            .addTag("download_${track.id}")
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "koplingfy_dl_${track.id}",
            ExistingWorkPolicy.REPLACE,
            request
        )
        return Result.success(Unit)
    }

    suspend fun cancelDownload(trackId: String) {
        WorkManager.getInstance(context).cancelUniqueWork("koplingfy_dl_$trackId")
        downloadDao.updateDownloadProgress(
            trackId, DownloadStatus.CANCELLED.name, 0, 0L, 0L, null, "Cancelled by user"
        )
    }

    suspend fun retryDownload(entity: DownloadEntity) {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setInputData(
                workDataOf(
                    DownloadWorker.KEY_TRACK_ID to entity.trackId,
                    DownloadWorker.KEY_DOWNLOAD_URL to entity.downloadUrl,
                    DownloadWorker.KEY_FILE_NAME to "${entity.artist}_${entity.title}"
                )
            )
            .build()
        downloadDao.updateDownloadProgress(
            entity.trackId, DownloadStatus.QUEUED.name, 0, 0L, 0L, null, null
        )
        WorkManager.getInstance(context).enqueueUniqueWork(
            "koplingfy_dl_${entity.trackId}",
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    suspend fun deleteDownload(entity: DownloadEntity) {
        WorkManager.getInstance(context).cancelUniqueWork("koplingfy_dl_${entity.trackId}")
        entity.localFilePath?.let { path ->
            runCatching {
                val f = if (path.startsWith("file:")) File(URI(path)) else File(path)
                if (f.exists()) f.delete()
            }
        }
        trackDao.updateLocalPath(entity.trackId, null)
        downloadDao.deleteDownload(entity.trackId)
    }

    fun getDownloadDirectoryPath(): String {
        return File(
            context.getExternalFilesDir(Environment.DIRECTORY_MUSIC),
            "KoplingfyDownloads"
        ).absolutePath
    }
}
