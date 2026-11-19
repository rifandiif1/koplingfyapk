package com.example.download

import android.content.Context
import android.os.Environment
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.KoplingfyApplication
import com.example.domain.model.DownloadStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

class DownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_TRACK_ID = "track_id"
        const val KEY_DOWNLOAD_URL = "download_url"
        const val KEY_FILE_NAME = "file_name"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val trackId = inputData.getString(KEY_TRACK_ID) ?: return@withContext Result.failure()
        val downloadUrl = inputData.getString(KEY_DOWNLOAD_URL) ?: return@withContext Result.failure()
        val safeName = inputData.getString(KEY_FILE_NAME) ?: trackId

        val container = (applicationContext as KoplingfyApplication).container
        val downloadDao = container.database.downloadDao()
        val trackDao = container.database.trackDao()

        val targetDir = File(
            applicationContext.getExternalFilesDir(Environment.DIRECTORY_MUSIC),
            "KoplingfyDownloads"
        ).apply { mkdirs() }
        val outputFile = File(targetDir, "${safeName.replace(Regex("[^a-zA-Z0-9._-]"), "_")}.mp3")

        downloadDao.updateDownloadProgress(
            trackId = trackId,
            status = DownloadStatus.DOWNLOADING.name,
            progress = 0,
            downloaded = 0L,
            total = 0L,
            localPath = null,
            error = null
        )

        return@withContext try {
            val request = Request.Builder().url(downloadUrl).build()
            container.httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    downloadDao.updateDownloadProgress(
                        trackId, DownloadStatus.FAILED.name, 0, 0L, 0L, null, "HTTP ${response.code}"
                    )
                    return@withContext Result.retry()
                }
                val body = response.body ?: return@withContext Result.failure()
                val totalBytes = body.contentLength().coerceAtLeast(0L)
                var downloadedBytes = 0L
                var lastProgress = -1

                body.byteStream().use { input ->
                    FileOutputStream(outputFile).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            if (isStopped) {
                                downloadDao.updateDownloadProgress(
                                    trackId, DownloadStatus.PAUSED.name, lastProgress.coerceAtLeast(0),
                                    downloadedBytes, totalBytes, null, null
                                )
                                return@withContext Result.failure()
                            }
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloadedBytes += read
                            val pct = if (totalBytes > 0L) ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100) else 50
                            if (pct != lastProgress) {
                                lastProgress = pct
                                downloadDao.updateDownloadProgress(
                                    trackId, DownloadStatus.DOWNLOADING.name, pct,
                                    downloadedBytes, totalBytes, null, null
                                )
                            }
                        }
                        output.flush()
                    }
                }

                val localUri = outputFile.toURI().toString()
                downloadDao.updateDownloadProgress(
                    trackId = trackId,
                    status = DownloadStatus.COMPLETED.name,
                    progress = 100,
                    downloaded = downloadedBytes,
                    total = if (totalBytes > 0L) totalBytes else downloadedBytes,
                    localPath = localUri,
                    error = null
                )
                trackDao.updateLocalPath(trackId, localUri)
                Result.success()
            }
        } catch (e: Exception) {
            downloadDao.updateDownloadProgress(
                trackId = trackId,
                status = DownloadStatus.FAILED.name,
                progress = 0,
                downloaded = 0L,
                total = 0L,
                localPath = null,
                error = e.localizedMessage ?: "Download failed"
            )
            Result.failure()
        }
    }
}
