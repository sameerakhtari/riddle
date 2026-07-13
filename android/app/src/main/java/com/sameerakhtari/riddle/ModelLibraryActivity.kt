package com.sameerakhtari.riddle

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateFormat
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.sameerakhtari.riddle.data.AiProviderMode
import com.sameerakhtari.riddle.data.AppSettings
import com.sameerakhtari.riddle.data.LocalInferenceBackend
import com.sameerakhtari.riddle.data.LocalModelSpec
import com.sameerakhtari.riddle.local.LocalModelManager
import com.sameerakhtari.riddle.local.ModelLibraryStorage
import com.sameerakhtari.riddle.logging.AppLog
import java.util.Date
import java.util.concurrent.Executors

class ModelLibraryActivity : Activity() {
    private lateinit var settings: AppSettings
    private lateinit var manager: LocalModelManager
    private lateinit var modelSpinner: Spinner
    private lateinit var modelDetails: TextView
    private lateinit var modelStatus: TextView
    private lateinit var folderStatus: TextView
    private lateinit var catalogStatus: TextView
    private lateinit var progress: ProgressBar
    private lateinit var downloadButton: Button
    private lateinit var cancelButton: Button
    private lateinit var deleteButton: Button
    private lateinit var openPageButton: Button
    private lateinit var searchInput: EditText
    private lateinit var visionOnlyCheck: CheckBox
    private lateinit var catalogUrlInput: EditText

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var allModels: List<LocalModelSpec> = emptyList()
    private var models: List<LocalModelSpec> = emptyList()
    private var selected: LocalModelSpec? = null
    private var pendingDownloadId: String? = null

    private val poll = object : Runnable {
        override fun run() {
            updateStatus()
            val state = selected?.let { manager.query(it.id).status }
            if (state == LocalModelManager.DownloadState.Status.ENQUEUED ||
                state == LocalModelManager.DownloadState.Status.RUNNING
            ) {
                handler.postDelayed(this, 1_000L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AppSettings(this)
        manager = LocalModelManager(this)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        setContentView(R.layout.activity_model_library)

        modelSpinner = findViewById(R.id.modelSpinner)
        modelDetails = findViewById(R.id.modelDetailsText)
        modelStatus = findViewById(R.id.modelStatusText)
        folderStatus = findViewById(R.id.folderStatusText)
        catalogStatus = findViewById(R.id.catalogStatusText)
        progress = findViewById(R.id.modelProgress)
        downloadButton = findViewById(R.id.downloadModelButton)
        cancelButton = findViewById(R.id.cancelDownloadButton)
        deleteButton = findViewById(R.id.deleteModelButton)
        openPageButton = findViewById(R.id.openModelPageButton)
        searchInput = findViewById(R.id.modelSearchInput)
        visionOnlyCheck = findViewById(R.id.visionOnlyCheck)
        catalogUrlInput = findViewById(R.id.catalogUrlInput)

        findViewById<EditText>(R.id.hfTokenInput).setText(settings.huggingFaceToken)
        findViewById<CheckBox>(R.id.wifiOnlyCheck).isChecked = settings.wifiOnlyModelDownload
        catalogUrlInput.setText(settings.modelCatalogUrl)
        findViewById<Spinner>(R.id.modelBackendSpinner).apply {
            adapter = ArrayAdapter(
                this@ModelLibraryActivity,
                android.R.layout.simple_spinner_dropdown_item,
                LocalInferenceBackend.entries.map { it.label },
            )
            setSelection(settings.localInferenceBackend.ordinal)
        }

        modelSpinner.onItemSelectedListener = ModelItemSelectedListener { position ->
            selected = models.getOrNull(position)
            updateSelectedDetails()
            updateStatus()
        }
        searchInput.addTextChangedListener(SimpleTextWatcher { applyFilters(selected?.id) })
        visionOnlyCheck.setOnCheckedChangeListener { _, _ -> applyFilters(selected?.id) }

        findViewById<Button>(R.id.refreshCatalogButton).setOnClickListener { reloadBundledCatalog() }
        findViewById<Button>(R.id.selectModelButton).setOnClickListener { selectCurrentModel() }
        downloadButton.setOnClickListener { prepareDownload() }
        cancelButton.setOnClickListener {
            selected?.let { manager.cancelDownload(it.id) }
            handler.postDelayed({ updateStatus() }, 250L)
        }
        deleteButton.setOnClickListener { confirmDelete() }
        findViewById<Button>(R.id.addCustomModelButton).setOnClickListener { showAddCustomModelDialog() }
        findViewById<Button>(R.id.importModelButton).setOnClickListener { importModelFile() }
        findViewById<Button>(R.id.chooseFolderButton).setOnClickListener { chooseVisibleFolder(false) }
        findViewById<Button>(R.id.openFolderButton).setOnClickListener {
            val intent = ModelLibraryStorage.openFolderIntent(this)
            if (intent == null) {
                chooseVisibleFolder(false)
            } else {
                runCatching { startActivity(intent) }.onFailure { chooseVisibleFolder(false) }
            }
        }
        openPageButton.setOnClickListener {
            selected?.modelPageUrl?.takeIf(String::isNotBlank)?.let {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it)))
            }
        }
        findViewById<Button>(R.id.closeModelLibraryButton).setOnClickListener {
            saveCommonFields()
            finish()
        }

        refreshModels(settings.activeModelId)
        updateFolderStatus()
        updateCatalogStatus()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(poll)
        handler.post(poll)
    }

    override fun onPause() {
        handler.removeCallbacks(poll)
        saveCommonFields()
        super.onPause()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun refreshModels(preferId: String? = selected?.id) {
        allModels = manager.allModels()
        applyFilters(preferId)
    }

    private fun applyFilters(preferId: String? = selected?.id) {
        val query = searchInput.text.toString().trim().lowercase()
        val visionOnly = visionOnlyCheck.isChecked
        models = allModels.filter { spec ->
            val searchable = listOf(
                spec.displayName,
                spec.fileName,
                spec.description,
                spec.source,
                spec.expectedSize,
            ).joinToString(" ").lowercase()
            (!visionOnly || spec.supportsVision) && (query.isBlank() || searchable.contains(query))
        }
        if (models.isEmpty()) {
            modelSpinner.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("No matching models"),
            )
            selected = null
            modelDetails.text = "Change the search or refresh the catalog. You can also add a custom URL or import a .litertlm file."
            modelStatus.text = "No model selected"
            progress.progress = 0
            downloadButton.isEnabled = false
            cancelButton.isEnabled = false
            deleteButton.isEnabled = false
            openPageButton.isEnabled = false
            return
        }
        modelSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            models.map { model ->
                val marker = when {
                    manager.isModelReady(model) && model.id == settings.activeModelId -> "★✓ "
                    manager.isModelReady(model) -> "✓ "
                    model.id == settings.activeModelId -> "★ "
                    else -> ""
                }
                marker + model.displayName
            },
        )
        val targetId = preferId ?: settings.activeModelId
        val index = models.indexOfFirst { it.id == targetId }.takeIf { it >= 0 } ?: 0
        modelSpinner.setSelection(index)
        selected = models[index]
        updateSelectedDetails()
        updateStatus()
    }

    private fun updateSelectedDetails() {
        val spec = selected ?: return
        modelDetails.text = buildString {
            append(spec.fileName)
            append("\n")
            append(spec.expectedSize)
            append(" · ")
            append(spec.source)
            append("\n")
            append(if (spec.supportsVision) "Vision input: supported by catalog metadata" else "Text only: unsuitable for handwritten pages")
            if (spec.description.isNotBlank()) append("\n${spec.description}")
            if (spec.downloadUrl.isBlank()) append("\nNo download URL — import the file from Files.")
        }
        openPageButton.isEnabled = spec.modelPageUrl.isNotBlank()
        downloadButton.isEnabled = spec.downloadable && spec.supportsVision
        deleteButton.isEnabled = manager.isModelReady(spec) || !spec.builtIn
    }

    private fun updateStatus() {
        val spec = selected ?: return
        if (manager.isModelReady(spec)) {
            val active = if (spec.id == settings.activeModelId) " · active" else ""
            modelStatus.text = "Ready · ${manager.humanModelSize(spec)}$active"
            progress.progress = 100
            downloadButton.text = if (spec.downloadable) "Download again" else "Import replacement"
            cancelButton.isEnabled = false
            deleteButton.isEnabled = true
            return
        }
        val state = manager.query(spec.id)
        when (state.status) {
            LocalModelManager.DownloadState.Status.ENQUEUED -> {
                modelStatus.text = "Queued — waiting for the selected network constraint"
                progress.progress = state.percent
                cancelButton.isEnabled = true
            }
            LocalModelManager.DownloadState.Status.RUNNING -> {
                val downloaded = state.downloadedBytes / GIB
                val total = if (state.totalBytes > 0L) state.totalBytes / GIB else -1.0
                modelStatus.text = if (total > 0) {
                    "Downloading in background · %.2f / %.2f GB · %d%%".format(downloaded, total, state.percent)
                } else {
                    "Downloading in background · %.2f GB".format(downloaded)
                }
                progress.progress = state.percent
                cancelButton.isEnabled = true
            }
            LocalModelManager.DownloadState.Status.FAILED -> {
                modelStatus.text = "Download failed${state.message.takeIf(String::isNotBlank)?.let { ": $it" } ?: ""}"
                progress.progress = 0
                cancelButton.isEnabled = false
            }
            LocalModelManager.DownloadState.Status.CANCELLED -> {
                modelStatus.text = "Download cancelled. A partial file is kept for resume."
                progress.progress = 0
                cancelButton.isEnabled = false
            }
            LocalModelManager.DownloadState.Status.SUCCESS -> {
                modelStatus.text = "Finishing model file…"
                progress.progress = 100
                handler.postDelayed({ refreshModels(spec.id) }, 500L)
            }
            LocalModelManager.DownloadState.Status.IDLE -> {
                modelStatus.text = if (spec.supportsVision) {
                    "Not downloaded · ${spec.expectedSize}"
                } else {
                    "Text-only model · cannot read the page image"
                }
                progress.progress = 0
                cancelButton.isEnabled = false
            }
        }
    }

    private fun reloadBundledCatalog() {
        settings.modelCatalogUrl = AppSettings.DEFAULT_MODEL_CATALOG_URL
        manager = LocalModelManager(this)
        refreshModels(settings.activeModelId)
        updateCatalogStatus("Built-in compatible model list reloaded. No browser or network lookup was required.")
        toast("Compatible models loaded from the app.")
    }

    private fun refreshRemoteCatalog() {
        val url = catalogUrlInput.text.toString().trim()
        if (!url.startsWith("https://")) {
            catalogUrlInput.error = "Use an HTTPS catalog URL"
            return
        }
        saveCommonFields()
        settings.modelCatalogUrl = url
        val button = findViewById<Button>(R.id.refreshCatalogButton)
        button.isEnabled = false
        button.text = "Refreshing catalog…"
        catalogStatus.text = "Contacting the catalog…"
        executor.execute {
            runCatching { manager.refreshCatalog(url) }
                .onSuccess { count ->
                    runOnUiThread {
                        button.isEnabled = true
                        button.text = "Reload compatible model list"
                        manager = LocalModelManager(this)
                        refreshModels(settings.activeModelId)
                        updateCatalogStatus("Loaded $count online entries plus the bundled catalog.")
                        toast("Model catalog refreshed.")
                    }
                }
                .onFailure { error ->
                    AppLog.e("ModelLibrary", "Catalog refresh failed", error)
                    runOnUiThread {
                        button.isEnabled = true
                        button.text = "Reload compatible model list"
                        manager = LocalModelManager(this)
                        refreshModels(settings.activeModelId)
                        catalogStatus.text =
                            "Online refresh unavailable (${error.message}). " +
                                "The ${manager.bundledCatalogCount()} bundled compatible models remain ready to download inside the app."
                        toast("Using the built-in model list; online refresh was unavailable.")
                    }
                }
        }
    }

    private fun updateCatalogStatus(prefix: String = "") {
        val updated = manager.catalogUpdatedAt()
        val stamp = if (updated > 0L) {
            DateFormat.getMediumDateFormat(this).format(Date(updated)) + " " +
                DateFormat.getTimeFormat(this).format(Date(updated))
        } else {
            "not refreshed yet"
        }
        catalogStatus.text = buildString {
            if (prefix.isNotBlank()) append("$prefix\n")
            append("${allModels.size} registered entries · ${manager.bundledCatalogCount()} bundled · online catalog $stamp")
            append("\nBundled models can be downloaded directly in this screen even when catalog refresh is offline.")
        }
    }

    private fun prepareDownload() {
        val spec = selected ?: return
        if (!spec.supportsVision) {
            toast("This model is marked text-only and cannot read handwritten page images.")
            return
        }
        if (!spec.downloadable) {
            toast("This entry has no URL. Use Import model file.")
            return
        }
        saveCommonFields()
        if (ModelLibraryStorage.selectedTreeUri(this) == null) {
            AlertDialog.Builder(this)
                .setTitle("Choose a visible model folder")
                .setMessage(
                    "Choose a folder in Files so downloaded models are visible and easy to copy. " +
                        "The app also keeps its runtime copy for reliable local inference.",
                )
                .setNegativeButton("Private app storage only") { _, _ -> requestNotificationAndDownload(spec.id) }
                .setPositiveButton("Choose folder") { _, _ ->
                    pendingDownloadId = spec.id
                    chooseVisibleFolder(true)
                }
                .show()
        } else {
            requestNotificationAndDownload(spec.id)
        }
    }

    private fun requestNotificationAndDownload(modelId: String) {
        pendingDownloadId = modelId
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATION)
        } else {
            startDownloadNow(modelId)
        }
    }

    private fun startDownloadNow(modelId: String) {
        pendingDownloadId = null
        runCatching { manager.startDownload(modelId) }
            .onSuccess {
                AppLog.i("ModelLibrary", "User started model download: $modelId")
                toast("Background download started. You may close the app.")
                handler.removeCallbacks(poll)
                handler.post(poll)
            }
            .onFailure { toast(it.message ?: "Could not start download.") }
    }

    private fun selectCurrentModel() {
        val spec = selected ?: return
        if (!spec.supportsVision) {
            toast("Select a vision-capable model for handwritten pages.")
            return
        }
        if (!manager.isModelReady(spec)) {
            toast("Download or import this model first.")
            return
        }
        saveCommonFields()
        manager.selectModel(spec.id)
        val app = application as RiddleApplication
        app.onDeviceOracle.close()
        if (manager.isLargeModel(spec)) {
            settings.localInferenceBackend = LocalInferenceBackend.CPU
            findViewById<Spinner>(R.id.modelBackendSpinner).setSelection(LocalInferenceBackend.CPU.ordinal)
            toast("${spec.displayName} selected. CPU mode was chosen for stability; it will load only after Ask.")
        } else {
            if (settings.providerMode == AiProviderMode.ON_DEVICE && settings.autoWarmLocalModel) app.warmSelectedModel()
            toast("${spec.displayName} selected.")
        }
        refreshModels(spec.id)
    }

    private fun confirmDelete() {
        val spec = selected ?: return
        AlertDialog.Builder(this)
            .setTitle("Delete ${spec.displayName}?")
            .setMessage(
                if (spec.builtIn) {
                    "The local model file and partial download will be removed. The catalog entry remains."
                } else {
                    "The local file, partial download, and custom/imported entry will be removed. Remote catalog entries may return after the next refresh."
                },
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                manager.deleteModel(spec)
                if (!spec.builtIn) manager.removeCustomModel(spec.id)
                val remaining = manager.allModels()
                if (settings.activeModelId == spec.id && remaining.isNotEmpty()) {
                    settings.activeModelId = remaining.first().id
                }
                (application as RiddleApplication).onDeviceOracle.close()
                refreshModels(settings.activeModelId)
            }
            .show()
    }

    private fun showAddCustomModelDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val name = EditText(this).apply { hint = "Display name" }
        val url = EditText(this).apply { hint = "HTTPS .litertlm download URL" }
        val file = EditText(this).apply { hint = "Filename ending in .litertlm" }
        val size = EditText(this).apply { hint = "Expected size, e.g. about 2.6 GB" }
        val page = EditText(this).apply { hint = "Model information/license page URL (optional)" }
        val description = EditText(this).apply { hint = "Description (optional)" }
        val vision = CheckBox(this).apply {
            text = "Model supports image input"
            isChecked = true
        }
        listOf(name, url, file, size, page, description, vision).forEach(container::addView)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Add compatible model")
            .setMessage(
                "The file must use LiteRT-LM .litertlm format. Mark image support only when the model package actually includes a vision pathway.",
            )
            .setView(container)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Add", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                runCatching {
                    manager.addCustomModel(
                        displayName = name.text.toString(),
                        downloadUrl = url.text.toString(),
                        fileName = file.text.toString(),
                        expectedSize = size.text.toString(),
                        modelPageUrl = page.text.toString(),
                        description = description.text.toString(),
                        supportsVision = vision.isChecked,
                    )
                }.onSuccess { spec ->
                    dialog.dismiss()
                    refreshModels(spec.id)
                }.onFailure { error -> toast(error.message ?: "Could not add model.") }
            }
        }
        dialog.show()
    }

    private fun importModelFile() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            },
            REQUEST_IMPORT,
        )
    }

    private fun chooseVisibleFolder(forDownload: Boolean) {
        if (forDownload) pendingDownloadId = selected?.id
        startActivityForResult(
            ModelLibraryStorage.folderPickerIntent(ModelLibraryStorage.selectedTreeUri(this)),
            REQUEST_FOLDER,
        )
    }

    private fun saveCommonFields() {
        settings.huggingFaceToken = findViewById<EditText>(R.id.hfTokenInput).text.toString().trim()
        settings.wifiOnlyModelDownload = findViewById<CheckBox>(R.id.wifiOnlyCheck).isChecked
        settings.localInferenceBackend = LocalInferenceBackend.entries[
            findViewById<Spinner>(R.id.modelBackendSpinner).selectedItemPosition
        ]
        settings.modelCatalogUrl = AppSettings.DEFAULT_MODEL_CATALOG_URL
    }

    private fun updateFolderStatus() {
        folderStatus.text = ModelLibraryStorage.selectedTreeUri(this)?.let {
            "Visible model folder selected\n$it\nA private runtime copy is also kept because LiteRT-LM needs a normal filesystem path."
        } ?: "No visible model folder selected. Runtime models remain in private app storage."
    }

    @Deprecated("Deprecated API retained for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) {
            if (requestCode == REQUEST_FOLDER) pendingDownloadId = null
            return
        }
        when (requestCode) {
            REQUEST_FOLDER -> {
                val uri = data?.data ?: return
                runCatching { ModelLibraryStorage.persistTree(this, uri, data.flags) }
                    .onSuccess {
                        updateFolderStatus()
                        pendingDownloadId?.let { startDownloadNow(it) }
                    }
                    .onFailure { toast("Could not use that folder: ${it.message}") }
            }
            REQUEST_IMPORT -> {
                val uri = data?.data ?: return
                val dialog = ProgressDialog.show(
                    this,
                    "Importing model",
                    "Copying the model into Tom Riddle Diary…",
                    true,
                    false,
                )
                executor.execute {
                    runCatching { manager.importModel(uri) }
                        .onSuccess { spec ->
                            runOnUiThread {
                                dialog.dismiss()
                                manager.selectModel(spec.id)
                                refreshModels(spec.id)
                                toast("${spec.displayName} imported and selected.")
                            }
                        }
                        .onFailure { error ->
                            AppLog.e("ModelLibrary", "Model import failed", error)
                            runOnUiThread {
                                dialog.dismiss()
                                toast(error.message ?: "Could not import model.")
                            }
                        }
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATION) pendingDownloadId?.let { startDownloadNow(it) }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_FOLDER = 801
        private const val REQUEST_IMPORT = 802
        private const val REQUEST_NOTIFICATION = 803
        private const val GIB = 1024.0 * 1024.0 * 1024.0
    }
}

private class ModelItemSelectedListener(
    private val onSelected: (Int) -> Unit,
) : android.widget.AdapterView.OnItemSelectedListener {
    override fun onItemSelected(
        parent: android.widget.AdapterView<*>?,
        view: View?,
        position: Int,
        id: Long,
    ) = onSelected(position)

    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
}

private class SimpleTextWatcher(
    private val after: () -> Unit,
) : TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
    override fun afterTextChanged(s: Editable?) = after()
}
