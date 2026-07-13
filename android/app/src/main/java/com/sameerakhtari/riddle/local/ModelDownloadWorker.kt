package com.sameerakhtari.riddle.local

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.sameerakhtari.riddle.ModelLibraryActivity
import com.sameerakhtari.riddle.R
import com.sameerakhtari.riddle.data.AppSettings
import com.sameerakhtari.riddle.data.ModelCatalog
import com.sameerakhtari.riddle.logging.AppLog
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max

class ModelDownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : Worker(appContext, workerParams) {

    override fun doWork(): Result {
        val modelId = inputData.getString(KEY_MODEL_ID).orEmpty()
        val catalog = ModelCatalog(applicationContext)
        val spec = catalog.find(modelId)
            ?: return failure("The selected model is no longer registered.")
        if (!spec.downloadable) return failure("This model has no download URL. Import its .litertlm file instead.")

        val manager = LocalModelManager(applicationContext)
        val target = manager.modelFile(spec)
        val partial = manager.partialFile(spec)
        target.parentFile?.mkdirs()

        val notificationId = notificationId(modelId)
        createNotificationChannel()
        setForegroundAsync(foregroundInfo(notificationId, spec.displayName, 0, true)).get()
        AppLog.i("ModelDownload", "Starting ${spec.displayName} from ${spec.downloadUrl}")

        return try {
            downloadWithResume(spec.downloadUrl, partial, target, notificationId, spec.displayName)
            runCatching {
                ModelLibraryStorage.copyToVisibleLibrary(applicationContext, spec, target)
            }.onFailure { error ->
                AppLog.w("ModelDownload", "Model ready, but visible-folder copy failed", error)
            }
            publishProgress(target.length(), target.length(), 100)
            notify(notificationId, spec.displayName, 100, false, "Download complete")
            AppLog.i("ModelDownload", "Completed ${spec.displayName}; ${target.length()} bytes")
            Result.success(
                Data.Builder()
                    .putString(KEY_MODEL_ID, modelId)
                    .putLong(KEY_DOWNLOADED_BYTES, target.length())
                    .putLong(KEY_TOTAL_BYTES, target.length())
                    .putInt(KEY_PERCENT, 100)
                    .build(),
            )
        } catch (cancelled: InterruptedException) {
            Thread.currentThread().interrupt()
            AppLog.w("ModelDownload", "Download interrupted for ${spec.displayName}")
            Result.failure()
        } catch (error: Throwable) {
            AppLog.e("ModelDownload", "Download failed for ${spec.displayName}", error)
            notify(notificationId, spec.displayName, 0, false, error.message ?: "Download failed")
            if (runAttemptCount < 2 && error is IOException) Result.retry()
            else failure(error.message ?: "Model download failed.")
        }
    }

    private fun downloadWithResume(
        sourceUrl: String,
        partial: File,
        target: File,
        notificationId: Int,
        label: String,
    ) {
        var existing = partial.takeIf { it.isFile }?.length() ?: 0L
        var connection = openConnection(sourceUrl, existing)
        var responseCode = connection.responseCode

        if (responseCode == 416) {
            connection.disconnect()
            partial.delete()
            existing = 0L
            connection = openConnection(sourceUrl, 0L)
            responseCode = connection.responseCode
        }
        if (responseCode !in listOf(HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_PARTIAL)) {
            val detail = runCatching { connection.errorStream?.bufferedReader()?.readText() }
                .getOrNull().orEmpty().take(500)
            throw IOException("HTTP $responseCode${if (detail.isBlank()) "" else ": $detail"}")
        }

        val resumed = responseCode == HttpURLConnection.HTTP_PARTIAL && existing > 0L
        if (!resumed) existing = 0L
        val responseLength = connection.getHeaderFieldLong("Content-Length", -1L)
        val total = contentRangeTotal(connection)
            ?: responseLength.takeIf { it >= 0L }?.let { it + existing }
            ?: -1L

        RandomAccessFile(partial, "rw").use { output ->
            if (resumed) output.seek(existing) else output.setLength(0L)
            var downloaded = existing
            var lastPublishedAt = 0L
            var lastPublishedBytes = downloaded
            val buffer = ByteArray(1024 * 1024)

            connection.inputStream.buffered(1024 * 1024).use { input ->
                while (true) {
                    if (isStopped) throw InterruptedException("Download cancelled")
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    downloaded += count

                    val now = System.currentTimeMillis()
                    if (now - lastPublishedAt >= 700L || downloaded - lastPublishedBytes >= 8L * 1024L * 1024L) {
                        val percent = if (total > 0L) {
                            ((downloaded * 100L) / total).toInt().coerceIn(0, 99)
                        } else {
                            0
                        }
                        publishProgress(downloaded, total, percent)
                        notify(notificationId, label, percent, total <= 0L, humanProgress(downloaded, total))
                        lastPublishedAt = now
                        lastPublishedBytes = downloaded
                    }
                }
            }
        }
        connection.disconnect()

        if (total > 0L && partial.length() < total) {
            throw IOException("Download ended early (${partial.length()} of $total bytes). It can resume next time.")
        }
        if (target.exists() && !target.delete()) throw IOException("Could not replace the previous model file.")
        if (!partial.renameTo(target)) {
            partial.copyTo(target, overwrite = true)
            partial.delete()
        }
        if (target.length() < MIN_MODEL_BYTES) {
            target.delete()
            throw IOException("The downloaded file is too small to be a LiteRT-LM model.")
        }
    }

    private fun openConnection(initialUrl: String, offset: Long): HttpURLConnection {
        var current = URL(initialUrl)
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val connection = (current.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 30_000
                readTimeout = 45_000
                useCaches = false
                requestMethod = "GET"
                setRequestProperty("Accept", "application/octet-stream,*/*")
                setRequestProperty(
                    "User-Agent",
                    "RiddleDiary/${applicationContext.packageManager.getPackageInfo(applicationContext.packageName, 0).versionName}",
                )
                if (offset > 0L) setRequestProperty("Range", "bytes=$offset-")
                if (current.host.contains("huggingface.co", ignoreCase = true)) {
                    AppSettings(applicationContext).huggingFaceToken
                        .takeIf { it.isNotBlank() }
                        ?.let { setRequestProperty("Authorization", "Bearer $it") }
                }
            }
            val code = connection.responseCode
            if (code !in 300..399) return connection
            val location = connection.getHeaderField("Location")
                ?: throw IOException("Redirect without a destination (HTTP $code).")
            connection.disconnect()
            if (redirectCount >= MAX_REDIRECTS) throw IOException("Too many download redirects.")
            current = URL(current, location)
        }
        throw IOException("Could not open the model URL.")
    }

    private fun contentRangeTotal(connection: HttpURLConnection): Long? {
        val value = connection.getHeaderField("Content-Range") ?: return null
        return value.substringAfterLast('/', "").toLongOrNull()
    }

    private fun publishProgress(downloaded: Long, total: Long, percent: Int) {
        setProgressAsync(
            Data.Builder()
                .putLong(KEY_DOWNLOADED_BYTES, downloaded)
                .putLong(KEY_TOTAL_BYTES, total)
                .putInt(KEY_PERCENT, percent)
                .build(),
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Model downloads",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Background downloads for on-device AI models"
                setShowBadge(false)
            },
        )
    }

    private fun foregroundInfo(id: Int, label: String, percent: Int, indeterminate: Boolean): ForegroundInfo {
        val notification = buildNotification(label, percent, indeterminate, "Downloading in the background")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }
    }

    private fun notify(id: Int, label: String, percent: Int, indeterminate: Boolean, text: String) {
        applicationContext.getSystemService(NotificationManager::class.java)
            .notify(id, buildNotification(label, percent, indeterminate, text))
    }

    private fun buildNotification(
        label: String,
        percent: Int,
        indeterminate: Boolean,
        text: String,
    ): Notification {
        val intent = Intent(applicationContext, ModelLibraryActivity::class.java)
        val pending = PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download_notification)
            .setContentTitle(label)
            .setContentText(text)
            .setContentIntent(pending)
            .setOnlyAlertOnce(true)
            .setOngoing(indeterminate || percent in 0..99)
            .setProgress(100, percent.coerceIn(0, 100), indeterminate)
            .build()
    }

    private fun humanProgress(downloaded: Long, total: Long): String {
        val downloadedGb = downloaded / GIB
        return if (total > 0L) {
            "%.2f / %.2f GB".format(downloadedGb, total / GIB)
        } else {
            "%.2f GB downloaded".format(downloadedGb)
        }
    }

    private fun failure(message: String): Result = Result.failure(
        Data.Builder().putString(KEY_ERROR, message).build(),
    )

    companion object {
        const val KEY_MODEL_ID = "model_id"
        const val KEY_DOWNLOADED_BYTES = "downloaded_bytes"
        const val KEY_TOTAL_BYTES = "total_bytes"
        const val KEY_PERCENT = "percent"
        const val KEY_ERROR = "error"
        const val CHANNEL_ID = "riddle_model_downloads"

        private const val MAX_REDIRECTS = 8
        private const val MIN_MODEL_BYTES = 500L * 1024L * 1024L
        private const val GIB = 1024.0 * 1024.0 * 1024.0

        fun uniqueWorkName(modelId: String): String = "riddle-model-download-$modelId"
        fun notificationId(modelId: String): Int = max(10_000, modelId.hashCode() and 0x7fffffff)
    }
}
