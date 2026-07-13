package com.sameerakhtari.riddle

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.sameerakhtari.riddle.data.AiProviderMode
import com.sameerakhtari.riddle.data.AnswerLength
import com.sameerakhtari.riddle.data.AnswerStyle
import com.sameerakhtari.riddle.data.AppSettings
import com.sameerakhtari.riddle.data.DiaryVoice
import com.sameerakhtari.riddle.data.LocalInferenceBackend
import com.sameerakhtari.riddle.data.PenButtonAction
import com.sameerakhtari.riddle.local.LocalModelManager
import com.sameerakhtari.riddle.logging.AppLog
import com.sameerakhtari.riddle.network.OracleClient
import java.util.concurrent.Executors

class SettingsActivity : Activity() {
    private lateinit var settings: AppSettings
    private lateinit var modelManager: LocalModelManager
    private lateinit var providerSpinner: Spinner
    private lateinit var proxySection: View
    private lateinit var directSection: View
    private lateinit var localServerSection: View
    private lateinit var onDeviceSection: View
    private lateinit var activeModelText: TextView
    private val executor = Executors.newSingleThreadExecutor()
    private val oracleClient = OracleClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AppSettings(this)
        modelManager = LocalModelManager(this)
        applyWindowPreferences()
        setContentView(R.layout.activity_settings)

        providerSpinner = findViewById(R.id.providerSpinner)
        proxySection = findViewById(R.id.proxySection)
        directSection = findViewById(R.id.directSection)
        localServerSection = findViewById(R.id.localServerSection)
        onDeviceSection = findViewById(R.id.onDeviceSection)
        activeModelText = findViewById(R.id.activeModelText)

        setupProvider()
        setupGeneral()
        setupSPen()
        setupVoiceAndMemory()
        setupAppearance()
        setupDiagnostics()
        loadProviderFields()
        updateActiveModelText()
        bindNavigation()

        findViewById<Button>(R.id.loadDirectModelsButton).setOnClickListener {
            discoverModels(
                baseUrl = text(R.id.directBaseInput),
                apiKey = text(R.id.directKeyInput),
                targetField = R.id.directModelInput,
                label = "API",
            )
        }
        findViewById<Button>(R.id.loadLocalModelsButton).setOnClickListener {
            discoverModels(
                baseUrl = text(R.id.localServerBaseInput),
                apiKey = text(R.id.localServerTokenInput),
                targetField = R.id.localServerModelInput,
                label = "server",
            )
        }

        findViewById<Button>(R.id.cancelSettingsButton).setOnClickListener { finish() }
        findViewById<Button>(R.id.saveSettingsButton).setOnClickListener {
            if (saveAll()) {
                AppLog.configure(settings.diagnosticLoggingEnabled, settings.logMaxKb)
                if (settings.providerMode == AiProviderMode.ON_DEVICE && modelManager.isModelReady()) {
                    (application as RiddleApplication).warmSelectedModel()
                }
                toast("Diary settings saved.")
                finish()
            }
        }
        findViewById<TextView>(R.id.aboutText).text =
            "Riddle Diary ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
                "No .env file is needed to build the Android app. Provider configuration is entered here after installation. " +
                "backend/.env is used only for the optional private backend."
    }

    override fun onResume() {
        super.onResume()
        settings = AppSettings(this)
        modelManager = LocalModelManager(this)
        applyWindowPreferences()
        updateActiveModelText()
        updateInstructionSummary()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyWindowPreferences()
    }

    private fun bindNavigation() {
        findViewById<Button>(R.id.modelLibraryButton).setOnClickListener {
            startActivity(Intent(this, ModelLibraryActivity::class.java))
        }
        findViewById<Button>(R.id.openMemoryButton).setOnClickListener {
            startActivity(Intent(this, MemoryActivity::class.java))
        }
        findViewById<Button>(R.id.openInstructionsButton).setOnClickListener {
            startActivity(Intent(this, InstructionActivity::class.java))
        }
        findViewById<Button>(R.id.privacyPolicyButton).setOnClickListener {
            startActivity(Intent(this, PrivacyActivity::class.java))
        }
        findViewById<Button>(R.id.openGuideButton).setOnClickListener {
            startActivity(Intent(this, GuideActivity::class.java))
        }
        findViewById<Button>(R.id.viewLogsButton).setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
        findViewById<Button>(R.id.clearLogsButton).setOnClickListener { confirmClearLogs() }
    }

    private fun setupProvider() {
        providerSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            AiProviderMode.entries.map { it.label },
        )
        providerSpinner.setSelection(settings.providerMode.ordinal)
        providerSpinner.onItemSelectedListener = SettingsItemSelectedListener { position ->
            updateProviderSections(AiProviderMode.entries[position])
        }
    }

    private fun setupGeneral() {
        findViewById<CheckBox>(R.id.fullscreenCheck).isChecked = settings.immersiveFullscreen
        findViewById<CheckBox>(R.id.bookAnimationCheck).isChecked = settings.bookOpenAnimation
        findViewById<CheckBox>(R.id.autoWarmModelCheck).isChecked = settings.autoWarmLocalModel
        findViewById<CheckBox>(R.id.screenshotsCheck).isChecked = settings.allowScreenshots
        findViewById<CheckBox>(R.id.autoSubmitCheck).isChecked = settings.autoSubmit
        findViewById<CheckBox>(R.id.fingerCheck).isChecked = settings.allowFinger
        findViewById<EditText>(R.id.autoDelayInput).setText(settings.autoSubmitDelayMs.toString())
    }

    private fun setupSPen() {
        findViewById<CheckBox>(R.id.spenGesturesCheck).isChecked = settings.spenHoverGestures
        findViewById<Spinner>(R.id.penButtonActionSpinner).apply {
            adapter = ArrayAdapter(
                this@SettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                PenButtonAction.entries.map { it.label },
            )
            setSelection(settings.penButtonAction.ordinal)
        }
    }

    private fun setupVoiceAndMemory() {
        findViewById<Spinner>(R.id.diaryVoiceSpinner).apply {
            adapter = ArrayAdapter(
                this@SettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                DiaryVoice.entries.map { it.label },
            )
            setSelection(settings.diaryVoice.ordinal)
        }
        findViewById<Spinner>(R.id.answerLengthSpinner).apply {
            adapter = ArrayAdapter(
                this@SettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                AnswerLength.entries.map { it.label },
            )
            setSelection(settings.answerLength.ordinal)
        }
        findViewById<Spinner>(R.id.answerStyleSpinner).apply {
            adapter = ArrayAdapter(
                this@SettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                AnswerStyle.entries.map { it.label },
            )
            setSelection(settings.answerStyle.ordinal)
        }
        updateInstructionSummary()
        findViewById<CheckBox>(R.id.crossSessionMemoryCheck).isChecked = settings.crossSessionMemory
        findViewById<CheckBox>(R.id.automaticMemoryCheck).isChecked = settings.automaticMemoryFacts
    }

    private fun setupAppearance() {
        val penSeek = findViewById<SeekBar>(R.id.penWidthSeek)
        val penLabel = findViewById<TextView>(R.id.penWidthValue)
        penSeek.progress = ((settings.penWidth - 4f) * 10f).toInt().coerceIn(0, 180)
        penSeek.onProgress { progress ->
            penLabel.text = "Fountain-pen width: ${"%.1f".format(4f + progress / 10f)}"
        }

        val pressureSeek = findViewById<SeekBar>(R.id.pressureSeek)
        val pressureLabel = findViewById<TextView>(R.id.pressureValue)
        pressureSeek.progress = (settings.pressureSensitivity * 100f).toInt().coerceIn(0, 150)
        pressureSeek.onProgress { progress ->
            pressureLabel.text = "Pressure and speed variation: $progress%"
        }

        val replySeek = findViewById<SeekBar>(R.id.replySpeedSeek)
        val replyLabel = findViewById<TextView>(R.id.replySpeedValue)
        replySeek.progress = (settings.replySpeedMsPerCharacter - 16L).toInt().coerceIn(0, 124)
        replySeek.onProgress { progress ->
            replyLabel.text = "Reply writing speed: ${progress + 16} ms per character"
        }
        findViewById<EditText>(R.id.replyDelayInput).setText(settings.replyStartDelayMs.toString())

        findViewById<Spinner>(R.id.paperThemeSpinner).apply {
            adapter = ArrayAdapter(
                this@SettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Warm antique parchment", "Dark scorched parchment", "Pale vellum"),
            )
            setSelection(settings.paperTheme)
        }
        findViewById<Spinner>(R.id.userInkSpinner).apply {
            adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, INK_NAMES)
            setSelection(colorIndex(settings.userInkColor))
        }
        findViewById<Spinner>(R.id.replyInkSpinner).apply {
            adapter = ArrayAdapter(
                this@SettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                REPLY_INK_NAMES,
            )
            setSelection(replyColorIndex(settings.replyInkColor))
        }
        penSeek.progress = penSeek.progress
        pressureSeek.progress = pressureSeek.progress
        replySeek.progress = replySeek.progress
    }

    private fun setupDiagnostics() {
        findViewById<CheckBox>(R.id.loggingCheck).isChecked = settings.diagnosticLoggingEnabled
        findViewById<Spinner>(R.id.logSizeSpinner).apply {
            adapter = ArrayAdapter(
                this@SettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                LOG_SIZE_LABELS,
            )
            setSelection(LOG_SIZE_VALUES.indexOf(settings.logMaxKb).takeIf { it >= 0 } ?: 2)
        }
    }

    private fun loadProviderFields() {
        findViewById<EditText>(R.id.proxyUrlInput).setText(settings.proxyUrl)
        findViewById<EditText>(R.id.proxyTokenInput).setText(settings.proxyToken)
        findViewById<EditText>(R.id.directBaseInput).setText(settings.directBaseUrl)
        findViewById<EditText>(R.id.directKeyInput).setText(settings.directApiKey)
        findViewById<EditText>(R.id.directModelInput).setText(settings.directModel)
        findViewById<EditText>(R.id.localServerBaseInput).setText(settings.localServerBaseUrl)
        findViewById<EditText>(R.id.localServerTokenInput).setText(settings.localServerToken)
        findViewById<EditText>(R.id.localServerModelInput).setText(settings.localServerModel)
        findViewById<Spinner>(R.id.modelBackendSpinner).apply {
            adapter = ArrayAdapter(
                this@SettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                LocalInferenceBackend.entries.map { it.label },
            )
            setSelection(settings.localInferenceBackend.ordinal)
        }
        updateProviderSections(settings.providerMode)
    }

    private fun updateActiveModelText() {
        val spec = modelManager.activeSpec()
        activeModelText.text = buildString {
            append(spec.displayName)
            append("\n")
            append(if (spec.supportsVision) "Vision-capable" else "Text-only — not suitable for handwriting")
            append(" · ")
            append(
                if (modelManager.isModelReady(spec)) {
                    "Ready · ${modelManager.humanModelSize(spec)} · ${settings.localInferenceBackend.label}"
                } else {
                    "Not ready — open Model library to download or import it."
                },
            )
        }
    }

    private fun updateInstructionSummary() {
        val text = settings.customInstructions
        findViewById<TextView>(R.id.instructionsSummaryText).text = if (text.isBlank()) {
            "No extra instruction set is attached. The built-in direct-answer rules are active."
        } else {
            "Custom instruction set attached · ${text.length} characters."
        }
    }

    private fun updateProviderSections(mode: AiProviderMode) {
        proxySection.visibility = if (mode == AiProviderMode.RIDDLE_BACKEND) View.VISIBLE else View.GONE
        directSection.visibility = if (mode == AiProviderMode.DIRECT_OPENAI) View.VISIBLE else View.GONE
        localServerSection.visibility = if (mode == AiProviderMode.LOCAL_SERVER) View.VISIBLE else View.GONE
        onDeviceSection.visibility = if (mode == AiProviderMode.ON_DEVICE) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.providerHelp).text = when (mode) {
            AiProviderMode.NONE -> "Private notebook mode. Ink remains local and Ask is disabled."
            AiProviderMode.RIDDLE_BACKEND -> "Recommended for billed APIs: the provider key stays on your server or Mac."
            AiProviderMode.DIRECT_OPENAI -> "Calls a vision-capable OpenAI-compatible endpoint directly from this phone."
            AiProviderMode.LOCAL_SERVER -> "Uses a vision-capable OpenAI-compatible server on your LAN."
            AiProviderMode.ON_DEVICE -> "Runs the selected compatible LiteRT-LM vision model fully offline."
        }
    }

    private fun discoverModels(baseUrl: String, apiKey: String, targetField: Int, label: String) {
        if (baseUrl.isBlank()) {
            toast("Enter the $label base URL first.")
            return
        }
        val buttonId = if (targetField == R.id.directModelInput) {
            R.id.loadDirectModelsButton
        } else {
            R.id.loadLocalModelsButton
        }
        val button = findViewById<Button>(buttonId)
        button.isEnabled = false
        button.text = "Loading models…"
        executor.execute {
            runCatching { oracleClient.listModels(baseUrl, apiKey) }
                .onSuccess { models ->
                    runOnUiThread {
                        button.isEnabled = true
                        button.text = if (targetField == R.id.directModelInput) {
                            "Load available models from API"
                        } else {
                            "Load available models from server"
                        }
                        showModelChooser(models, targetField, label)
                    }
                }
                .onFailure { error ->
                    AppLog.e("Settings", "Could not list $label models", error)
                    runOnUiThread {
                        button.isEnabled = true
                        button.text = if (targetField == R.id.directModelInput) {
                            "Load available models from API"
                        } else {
                            "Load available models from server"
                        }
                        toast(error.message ?: "Could not list available models.")
                    }
                }
        }
    }

    private fun showModelChooser(models: List<String>, targetField: Int, label: String) {
        if (models.isEmpty()) {
            toast("The $label returned no models. You can still type a model ID manually.")
            return
        }
        val filtered = models.filter { id ->
            val lower = id.lowercase()
            lower.contains("vision") || lower.contains("vl") || lower.contains("gpt-4") ||
                lower.contains("gpt-5") || lower.contains("gemma") || lower.contains("llava") ||
                lower.contains("qwen") || lower.contains("pixtral")
        }
        val shown = if (filtered.isNotEmpty()) filtered else models
        AlertDialog.Builder(this)
            .setTitle("Select a model")
            .setMessage(
                if (filtered.isNotEmpty()) {
                    "Showing likely vision-capable models. Compatibility still depends on the provider."
                } else {
                    "The server did not identify vision support; select a model that accepts images."
                },
            )
            .setItems(shown.toTypedArray()) { _, which ->
                findViewById<EditText>(targetField).setText(shown[which])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveAll(): Boolean {
        val mode = AiProviderMode.entries[providerSpinner.selectedItemPosition]
        val proxyUrl = text(R.id.proxyUrlInput).trimEnd('/')
        val directBase = text(R.id.directBaseInput).trimEnd('/')
        val localBase = text(R.id.localServerBaseInput).trimEnd('/')
        if (!validOptionalHttpUrl(proxyUrl, R.id.proxyUrlInput)) return false
        if (!validOptionalHttpUrl(directBase, R.id.directBaseInput)) return false
        if (!validOptionalHttpUrl(localBase, R.id.localServerBaseInput)) return false

        settings.providerMode = mode
        settings.proxyUrl = proxyUrl
        settings.proxyToken = text(R.id.proxyTokenInput)
        settings.directBaseUrl = directBase
        settings.directApiKey = text(R.id.directKeyInput)
        settings.directModel = text(R.id.directModelInput)
        settings.localServerBaseUrl = localBase
        settings.localServerToken = text(R.id.localServerTokenInput)
        settings.localServerModel = text(R.id.localServerModelInput)
        settings.localInferenceBackend = LocalInferenceBackend.entries[
            findViewById<Spinner>(R.id.modelBackendSpinner).selectedItemPosition
        ]

        settings.diaryVoice = DiaryVoice.entries[
            findViewById<Spinner>(R.id.diaryVoiceSpinner).selectedItemPosition
        ]
        settings.answerLength = AnswerLength.entries[
            findViewById<Spinner>(R.id.answerLengthSpinner).selectedItemPosition
        ]
        settings.answerStyle = AnswerStyle.entries[
            findViewById<Spinner>(R.id.answerStyleSpinner).selectedItemPosition
        ]
        settings.crossSessionMemory = checked(R.id.crossSessionMemoryCheck)
        settings.automaticMemoryFacts = checked(R.id.automaticMemoryCheck)

        settings.immersiveFullscreen = checked(R.id.fullscreenCheck)
        settings.bookOpenAnimation = checked(R.id.bookAnimationCheck)
        settings.autoWarmLocalModel = checked(R.id.autoWarmModelCheck)
        settings.allowScreenshots = checked(R.id.screenshotsCheck)
        settings.autoSubmit = checked(R.id.autoSubmitCheck)
        settings.allowFinger = checked(R.id.fingerCheck)
        settings.autoSubmitDelayMs = text(R.id.autoDelayInput).toLongOrNull() ?: 2_800L
        settings.spenHoverGestures = checked(R.id.spenGesturesCheck)
        settings.penButtonAction = PenButtonAction.entries[
            findViewById<Spinner>(R.id.penButtonActionSpinner).selectedItemPosition
        ]

        settings.penWidth = 4f + findViewById<SeekBar>(R.id.penWidthSeek).progress / 10f
        settings.pressureSensitivity = findViewById<SeekBar>(R.id.pressureSeek).progress / 100f
        settings.replySpeedMsPerCharacter =
            (findViewById<SeekBar>(R.id.replySpeedSeek).progress + 16).toLong()
        settings.replyStartDelayMs = text(R.id.replyDelayInput).toLongOrNull() ?: 650L
        settings.paperTheme = findViewById<Spinner>(R.id.paperThemeSpinner).selectedItemPosition
        settings.userInkColor = INK_COLORS[findViewById<Spinner>(R.id.userInkSpinner).selectedItemPosition]
        settings.replyInkColor =
            REPLY_INK_COLORS[findViewById<Spinner>(R.id.replyInkSpinner).selectedItemPosition]
        settings.diagnosticLoggingEnabled = checked(R.id.loggingCheck)
        settings.logMaxKb = LOG_SIZE_VALUES[findViewById<Spinner>(R.id.logSizeSpinner).selectedItemPosition]

        val missingMessage = when (mode) {
            AiProviderMode.RIDDLE_BACKEND -> if (proxyUrl.isBlank()) "Enter the private backend URL." else null
            AiProviderMode.DIRECT_OPENAI -> if (
                directBase.isBlank() || settings.directApiKey.isBlank() || settings.directModel.isBlank()
            ) "Enter the direct API URL, key, and vision model." else null
            AiProviderMode.LOCAL_SERVER -> if (
                localBase.isBlank() || settings.localServerModel.isBlank()
            ) "Enter the local server URL and a vision-capable model." else null
            AiProviderMode.ON_DEVICE -> if (!modelManager.isModelReady()) {
                "Download or import the selected on-device model in Model library."
            } else null
            AiProviderMode.NONE -> null
        }
        if (missingMessage != null) {
            toast(missingMessage)
            return false
        }
        return true
    }

    private fun confirmClearLogs() {
        AlertDialog.Builder(this)
            .setTitle("Clear diagnostic log?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear") { _, _ ->
                AppLog.clear()
                AppLog.i("Settings", "Log cleared by user")
                toast("Log cleared.")
            }
            .show()
    }

    private fun applyWindowPreferences() {
        if (settings.allowScreenshots) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (settings.immersiveFullscreen) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    private fun validOptionalHttpUrl(value: String, fieldId: Int): Boolean {
        if (value.isBlank() || value.startsWith("http://") || value.startsWith("https://")) return true
        findViewById<EditText>(fieldId).error = "Start the URL with http:// or https://"
        return false
    }

    private fun text(id: Int): String = findViewById<EditText>(id).text.toString().trim()
    private fun checked(id: Int): Boolean = findViewById<CheckBox>(id).isChecked
    private fun colorIndex(value: Int): Int = INK_COLORS.indexOf(value).takeIf { it >= 0 } ?: 0
    private fun replyColorIndex(value: Int): Int = REPLY_INK_COLORS.indexOf(value).takeIf { it >= 0 } ?: 0

    private fun SeekBar.onProgress(block: (Int) -> Unit) {
        setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = block(progress)
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            },
        )
        block(progress)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        private val INK_NAMES = listOf("Deep brown fountain ink", "Near-black iron gall", "Warm sepia")
        private val INK_COLORS = intArrayOf(
            Color.rgb(40, 24, 13),
            Color.rgb(18, 15, 13),
            Color.rgb(80, 43, 20),
        )
        private val REPLY_INK_NAMES = listOf("Dark aged brown", "Faded ancient sepia", "Black iron gall")
        private val REPLY_INK_COLORS = intArrayOf(
            Color.rgb(31, 18, 10),
            Color.rgb(82, 44, 22),
            Color.rgb(16, 13, 11),
        )
        private val LOG_SIZE_LABELS = listOf("256 KB", "512 KB", "1 MB", "2 MB", "4 MB")
        private val LOG_SIZE_VALUES = intArrayOf(256, 512, 1_024, 2_048, 4_096)
    }
}

private class SettingsItemSelectedListener(
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
