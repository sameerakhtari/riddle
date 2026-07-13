package com.sameerakhtari.riddle.local

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.sameerakhtari.riddle.data.AppSettings
import com.sameerakhtari.riddle.data.LocalModelSpec
import com.sameerakhtari.riddle.data.ModelCatalog
import com.sameerakhtari.riddle.logging.AppLog
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

class LocalModelManager(private val context: Context) {
    data class DownloadState(
        val status: Status,
        val downloadedBytes: Long = 0L,
        val totalBytes: Long = -1L,
        val percent: Int = 0,
        val message: String = "",
    ) {
        enum class Status { IDLE, ENQUEUED, RUNNING, SUCCESS, FAILED, CANCELLED }
    }

    private val settings = AppSettings(context)
    private val catalog = ModelCatalog(context)
    private val workManager = WorkManager.getInstance(context)

    init {
        discoverRuntimeModels()
    }

    fun allModels(): List<LocalModelSpec> = catalog.allModels()
    fun activeSpec(): LocalModelSpec = catalog.active(settings)
    fun findSpec(id: String): LocalModelSpec? = catalog.find(id)
    fun catalogUpdatedAt(): Long = catalog.catalogUpdatedAt()
    fun bundledCatalogCount(): Int = catalog.bundledCount()

    fun isLargeModel(spec: LocalModelSpec = activeSpec()): Boolean {
        val bytes = modelFile(spec).takeIf { it.isFile }?.length() ?: 0L
        return bytes >= LARGE_MODEL_BYTES ||
            spec.id.contains("e4b", ignoreCase = true) ||
            spec.expectedSize.contains("3.6", ignoreCase = true)
    }

    fun refreshCatalog(url: String = settings.modelCatalogUrl): Int {
        val count = catalog.refreshRemoteCatalog(url)
        AppLog.i("ModelManager", "Refreshed catalog with $count entries")
        return count
    }

    fun selectModel(id: String): LocalModelSpec {
        val spec = catalog.find(id) ?: error("The selected model is no longer registered.")
        settings.activeModelId = spec.id
        settings.localModelFileName = spec.fileName
        settings.localModelUrl = spec.downloadUrl
        AppLog.i("ModelManager", "Selected model ${spec.displayName}")
        return spec
    }

    fun addCustomModel(
        displayName: String,
        downloadUrl: String,
        fileName: String,
        expectedSize: String = "size unknown",
        modelPageUrl: String = "",
        description: String = "",
        supportsVision: Boolean = true,
    ): LocalModelSpec = catalog.addCustom(
        displayName = displayName,
        downloadUrl = downloadUrl,
        fileName = fileName,
        expectedSize = expectedSize,
        modelPageUrl = modelPageUrl,
        description = description,
        supportsVision = supportsVision,
    )

    fun removeCustomModel(id: String): Boolean = catalog.removeCustom(id)
    fun modelFile(): File = modelFile(activeSpec())

    fun modelFile(spec: LocalModelSpec): File {
        val root = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            "riddle-models",
        )
        root.mkdirs()
        return File(root, spec.fileName)
    }

    fun partialFile(spec: LocalModelSpec): File = File(modelFile(spec).parentFile, "${spec.fileName}.part")
    fun isModelReady(): Boolean = isModelReady(activeSpec())

    fun isModelReady(spec: LocalModelSpec): Boolean =
        modelFile(spec).isFile && modelFile(spec).length() > MIN_MODEL_BYTES

    fun humanModelSize(spec: LocalModelSpec = activeSpec()): String {
        val bytes = modelFile(spec).takeIf { it.isFile }?.length() ?: 0L
        if (bytes <= 0L) return "not downloaded"
        val gib = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        return "%.2f GB".format(gib)
    }

    fun startDownload(modelId: String): UUID {
        val spec = catalog.find(modelId) ?: error("Unknown model.")
        require(spec.downloadable) { "This model has no download URL. Import its .litertlm file instead." }
        require(spec.supportsVision) {
            "This catalog entry is text-only. Riddle Diary requires image input to read handwriting."
        }
        val network = if (settings.wifiOnlyModelDownload) NetworkType.UNMETERED else NetworkType.CONNECTED
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
            .setInputData(Data.Builder().putString(ModelDownloadWorker.KEY_MODEL_ID, spec.id).build())
            .addTag(TAG_MODEL_DOWNLOAD)
            .addTag(spec.id)
            .build()
        workManager.enqueueUniqueWork(
            ModelDownloadWorker.uniqueWorkName(spec.id),
            ExistingWorkPolicy.REPLACE,
            request,
        )
        AppLog.i("ModelManager", "Queued background download for ${spec.displayName}")
        return request.id
    }

    fun cancelDownload(modelId: String) {
        workManager.cancelUniqueWork(ModelDownloadWorker.uniqueWorkName(modelId))
        AppLog.i("ModelManager", "Cancelled download for $modelId")
    }

    fun query(modelId: String = activeSpec().id): DownloadState {
        val spec = catalog.find(modelId) ?: return DownloadState(DownloadState.Status.IDLE)
        if (isModelReady(spec)) {
            return DownloadState(
                status = DownloadState.Status.SUCCESS,
                downloadedBytes = modelFile(spec).length(),
                totalBytes = modelFile(spec).length(),
                percent = 100,
            )
        }
        val info = runCatching {
            workManager.getWorkInfosForUniqueWork(ModelDownloadWorker.uniqueWorkName(modelId))
                .get(40, TimeUnit.MILLISECONDS)
                .maxByOrNull { it.runAttemptCount }
        }.getOrNull() ?: return DownloadState(DownloadState.Status.IDLE)

        val progress = info.progress
        val downloaded = progress.getLong(ModelDownloadWorker.KEY_DOWNLOADED_BYTES, 0L)
        val total = progress.getLong(ModelDownloadWorker.KEY_TOTAL_BYTES, -1L)
        val percent = progress.getInt(ModelDownloadWorker.KEY_PERCENT, 0)
        val outputMessage = info.outputData.getString(ModelDownloadWorker.KEY_ERROR).orEmpty()
        val status = when (info.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> DownloadState.Status.ENQUEUED
            WorkInfo.State.RUNNING -> DownloadState.Status.RUNNING
            WorkInfo.State.SUCCEEDED -> DownloadState.Status.SUCCESS
            WorkInfo.State.FAILED -> DownloadState.Status.FAILED
            WorkInfo.State.CANCELLED -> DownloadState.Status.CANCELLED
        }
        return DownloadState(status, downloaded, total, percent, outputMessage)
    }

    fun deleteModel(spec: LocalModelSpec) {
        cancelDownload(spec.id)
        partialFile(spec).delete()
        modelFile(spec).delete()
        AppLog.i("ModelManager", "Deleted ${spec.fileName}")
    }

    fun importModel(uri: Uri, preferredDisplayName: String = ""): LocalModelSpec {
        val resolver = context.contentResolver
        var displayName = "imported-model.litertlm"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                displayName = cursor.getString(0).orEmpty().ifBlank { displayName }
            }
        }
        val safeName = ModelCatalog.sanitizeFileName(displayName)
        require(safeName.endsWith(".litertlm", ignoreCase = true)) {
            "Choose a LiteRT-LM .litertlm model file."
        }
        val spec = catalog.registerImported(
            fileName = safeName,
            displayName = preferredDisplayName.ifBlank { safeName.removeSuffix(".litertlm") },
        )
        val target = modelFile(spec)
        val temp = File(target.parentFile, "${target.name}.importing")
        resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Could not open the selected model file." }
            temp.outputStream().buffered(1024 * 1024).use { output ->
                input.buffered(1024 * 1024).copyTo(output, 1024 * 1024)
            }
        }
        require(temp.length() > MIN_MODEL_BYTES) { "The selected model file is unexpectedly small." }
        if (target.exists()) target.delete()
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        runCatching { ModelLibraryStorage.copyToVisibleLibrary(context, spec, target) }
            .onFailure { AppLog.w("ModelManager", "Imported model ready; visible copy failed", it) }
        AppLog.i("ModelManager", "Imported ${spec.displayName}; ${target.length()} bytes")
        return spec
    }

    fun modelDirectory(): File = modelFile(activeSpec()).parentFile!!

    private fun discoverRuntimeModels() {
        val directory = modelDirectory()
        catalog.registerDiscovered(directory.listFiles()?.toList().orEmpty())
    }

    companion object {
        const val TAG_MODEL_DOWNLOAD = "riddle_model_download"
        private const val MIN_MODEL_BYTES = 500L * 1024L * 1024L
        private const val LARGE_MODEL_BYTES = 3_200L * 1024L * 1024L
    }
}
