package com.sameerakhtari.riddle.data

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID

data class LocalModelSpec(
    val id: String,
    val displayName: String,
    val fileName: String,
    val downloadUrl: String = "",
    val modelPageUrl: String = "",
    val expectedSize: String = "size unknown",
    val description: String = "",
    val supportsVision: Boolean = true,
    val source: String = "custom",
    val builtIn: Boolean = false,
) {
    val downloadable: Boolean get() = downloadUrl.startsWith("https://")
}

class ModelCatalog(private val context: Context) {
    private val preferences =
        context.getSharedPreferences("riddle_model_catalog_v4", Context.MODE_PRIVATE)

    private val bundledModels: List<LocalModelSpec> by lazy {
        runCatching {
            context.assets.open("model_catalog.json").bufferedReader().use { reader ->
                parseCatalog(reader.readText(), builtIn = true, defaultSource = "bundled catalog")
            }
        }.getOrDefault(emptyList())
    }

    fun allModels(): List<LocalModelSpec> =
        (bundledModels + BUILT_INS + readRemoteModels() + readCustomModels())
            .distinctBy { it.fileName.lowercase() }
            .sortedWith(
                compareByDescending<LocalModelSpec> { it.builtIn }
                    .thenByDescending { it.supportsVision }
                    .thenBy { it.displayName.lowercase() },
            )

    fun bundledCount(): Int = (bundledModels + BUILT_INS).distinctBy { it.fileName.lowercase() }.size
    fun find(id: String): LocalModelSpec? = allModels().firstOrNull { it.id == id }
    fun active(settings: AppSettings): LocalModelSpec =
        find(settings.activeModelId) ?: allModels().firstOrNull() ?: BUILT_INS.first()
    fun catalogUpdatedAt(): Long = preferences.getLong(KEY_REMOTE_UPDATED, 0L)

    fun refreshRemoteCatalog(url: String): Int {
        require(url.startsWith("https://")) { "The catalog URL must use HTTPS." }
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 45_000
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "RiddleDiary/${com.sameerakhtari.riddle.BuildConfig.VERSION_NAME}")
        }
        try {
            val status = connection.responseCode
            val responseText = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) error("Catalog returned HTTP $status")
            val payload = unwrapGitHubContentsResponse(responseText)
            val models = parseCatalog(payload, builtIn = false, defaultSource = "online catalog")
            require(models.isNotEmpty()) { "The catalog did not contain compatible model entries." }
            writeModels(KEY_REMOTE_MODELS, models)
            preferences.edit().putLong(KEY_REMOTE_UPDATED, System.currentTimeMillis()).apply()
            return models.size
        } finally {
            connection.disconnect()
        }
    }

    fun addCustom(
        displayName: String,
        downloadUrl: String,
        fileName: String,
        expectedSize: String = "size unknown",
        modelPageUrl: String = "",
        description: String = "",
        supportsVision: Boolean = true,
    ): LocalModelSpec {
        val safeName = sanitizeFileName(fileName)
        require(safeName.endsWith(".litertlm", ignoreCase = true)) {
            "The model filename must end with .litertlm"
        }
        if (downloadUrl.isNotBlank()) {
            require(downloadUrl.startsWith("https://")) { "Use an HTTPS model URL." }
        }
        allModels().firstOrNull { it.fileName.equals(safeName, ignoreCase = true) }?.let { return it }
        val spec = LocalModelSpec(
            id = "custom-${UUID.randomUUID()}",
            displayName = displayName.trim().ifBlank { safeName.removeSuffix(".litertlm") },
            fileName = safeName,
            downloadUrl = downloadUrl.trim(),
            modelPageUrl = modelPageUrl.trim(),
            expectedSize = expectedSize.trim().ifBlank { "size unknown" },
            description = description.trim(),
            supportsVision = supportsVision,
            source = "custom",
            builtIn = false,
        )
        writeModels(KEY_CUSTOM_MODELS, readCustomModels() + spec)
        return spec
    }

    fun registerImported(fileName: String, displayName: String = ""): LocalModelSpec {
        val safeName = sanitizeFileName(fileName)
        allModels().firstOrNull { it.fileName.equals(safeName, ignoreCase = true) }?.let { return it }
        return addCustom(
            displayName = displayName.ifBlank { safeName.removeSuffix(".litertlm") },
            downloadUrl = "",
            fileName = safeName,
            expectedSize = "imported file",
            description = "Imported from Android Files.",
            supportsVision = true,
        )
    }

    fun registerDiscovered(files: List<File>) {
        files.filter { it.isFile && it.name.endsWith(".litertlm", ignoreCase = true) }
            .forEach { file ->
                if (allModels().none { it.fileName.equals(file.name, ignoreCase = true) }) {
                    registerImported(file.name, file.nameWithoutExtension)
                }
            }
    }

    fun removeCustom(id: String): Boolean {
        val custom = readCustomModels().toMutableList()
        val removed = custom.removeAll { it.id == id }
        if (removed) writeModels(KEY_CUSTOM_MODELS, custom)
        return removed
    }

    private fun unwrapGitHubContentsResponse(text: String): String {
        val objectValue = runCatching { JSONObject(text) }.getOrNull() ?: return text
        if (!objectValue.optString("encoding").equals("base64", ignoreCase = true)) return text
        val encoded = objectValue.optString("content").replace("\n", "")
        if (encoded.isBlank()) return text
        return String(Base64.decode(encoded, Base64.DEFAULT), StandardCharsets.UTF_8)
    }

    private fun parseCatalog(
        text: String,
        builtIn: Boolean,
        defaultSource: String,
    ): List<LocalModelSpec> {
        val trimmed = text.trim()
        val array = if (trimmed.startsWith("[")) {
            JSONArray(trimmed)
        } else {
            JSONObject(trimmed).optJSONArray("models") ?: JSONArray()
        }
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val fileName = sanitizeFileName(item.optString("fileName"))
                val url = item.optString("downloadUrl").trim()
                if (!fileName.endsWith(".litertlm", true) || !url.startsWith("https://")) continue
                add(
                    LocalModelSpec(
                        id = item.optString("id").ifBlank { "catalog-${fileName.lowercase()}" },
                        displayName = item.optString("name", item.optString("displayName"))
                            .ifBlank { fileName.removeSuffix(".litertlm") },
                        fileName = fileName,
                        downloadUrl = url,
                        modelPageUrl = item.optString("modelPageUrl"),
                        expectedSize = item.optString("expectedSize", "size unknown"),
                        description = item.optString("description"),
                        supportsVision = item.optBoolean("supportsVision", true),
                        source = item.optString("source", defaultSource),
                        builtIn = builtIn,
                    ),
                )
            }
        }
    }

    private fun readRemoteModels(): List<LocalModelSpec> = readModels(KEY_REMOTE_MODELS)
    private fun readCustomModels(): List<LocalModelSpec> = readModels(KEY_CUSTOM_MODELS)

    private fun readModels(key: String): List<LocalModelSpec> {
        val raw = preferences.getString(key, "[]").orEmpty()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id")
                    val fileName = sanitizeFileName(item.optString("fileName"))
                    if (id.isBlank() || !fileName.endsWith(".litertlm", true)) continue
                    add(
                        LocalModelSpec(
                            id = id,
                            displayName = item.optString("displayName")
                                .ifBlank { fileName.removeSuffix(".litertlm") },
                            fileName = fileName,
                            downloadUrl = item.optString("downloadUrl"),
                            modelPageUrl = item.optString("modelPageUrl"),
                            expectedSize = item.optString("expectedSize", "size unknown"),
                            description = item.optString("description"),
                            supportsVision = item.optBoolean("supportsVision", true),
                            source = item.optString("source", "custom"),
                            builtIn = false,
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun writeModels(key: String, models: List<LocalModelSpec>) {
        val array = JSONArray()
        models.distinctBy { it.fileName.lowercase() }.forEach { model ->
            array.put(
                JSONObject()
                    .put("id", model.id)
                    .put("displayName", model.displayName)
                    .put("fileName", model.fileName)
                    .put("downloadUrl", model.downloadUrl)
                    .put("modelPageUrl", model.modelPageUrl)
                    .put("expectedSize", model.expectedSize)
                    .put("description", model.description)
                    .put("supportsVision", model.supportsVision)
                    .put("source", model.source),
            )
        }
        preferences.edit().putString(key, array.toString()).apply()
    }

    companion object {
        val BUILT_INS: List<LocalModelSpec> = LocalModelPreset.entries.map { preset ->
            LocalModelSpec(
                id = preset.id,
                displayName = preset.label,
                fileName = preset.fileName,
                downloadUrl = preset.downloadUrl,
                modelPageUrl = preset.modelPageUrl,
                expectedSize = preset.expectedSize,
                description = when (preset) {
                    LocalModelPreset.GEMMA_3N_E2B -> "Multimodal edge model with image input."
                    LocalModelPreset.GEMMA_4_E2B -> "Compact multimodal model recommended for the S22 Ultra."
                    LocalModelPreset.GEMMA_4_E4B -> "Large experimental model; not loaded automatically on phones."
                },
                supportsVision = true,
                source = "bundled",
                builtIn = true,
            )
        }

        fun sanitizeFileName(value: String): String {
            val base = value.substringAfterLast('/').substringBefore('?').trim()
            return base.replace(Regex("[^A-Za-z0-9._-]"), "_").take(180)
        }

        private const val KEY_CUSTOM_MODELS = "custom_models"
        private const val KEY_REMOTE_MODELS = "remote_models"
        private const val KEY_REMOTE_UPDATED = "remote_updated"
    }
}
