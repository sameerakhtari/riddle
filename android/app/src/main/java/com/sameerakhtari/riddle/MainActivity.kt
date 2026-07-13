package com.sameerakhtari.riddle

import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.sameerakhtari.riddle.data.AiProviderMode
import com.sameerakhtari.riddle.data.AppSettings
import com.sameerakhtari.riddle.data.MemoryExtractor
import com.sameerakhtari.riddle.data.MemoryStore
import com.sameerakhtari.riddle.data.PageStore
import com.sameerakhtari.riddle.local.LocalModelManager
import com.sameerakhtari.riddle.logging.AppLog
import com.sameerakhtari.riddle.model.DiaryPage
import com.sameerakhtari.riddle.model.InkTool
import com.sameerakhtari.riddle.model.Stroke
import com.sameerakhtari.riddle.network.DiaryPrompt
import com.sameerakhtari.riddle.network.OracleClient
import com.sameerakhtari.riddle.network.OracleResult
import com.sameerakhtari.riddle.ui.DiaryCanvasView
import com.sameerakhtari.riddle.ui.ReplyInkView
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var canvas: DiaryCanvasView
    private lateinit var replyInkView: ReplyInkView
    private lateinit var statusText: TextView
    private lateinit var sessionTitleText: TextView
    private lateinit var askButton: Button
    private lateinit var penButton: Button
    private lateinit var eraserButton: Button
    private lateinit var topBar: View
    private lateinit var toolDock: View
    private lateinit var chromeButton: Button
    private lateinit var bookCover: View

    private lateinit var pageStore: PageStore
    private lateinit var memoryStore: MemoryStore
    private lateinit var settings: AppSettings
    private lateinit var modelManager: LocalModelManager
    private val oracleClient = OracleClient()
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val autoAskRunnable = Runnable { askDiary() }
    private val draftSaveRunnable = Runnable { flushPendingDraft() }
    private var pendingDraftStrokes: List<Stroke>? = null
    private var pendingDraftSessionId: String = ""
    private var chromeVisible = true

    @Volatile
    private var requestRunning = false

    private val riddleApplication: RiddleApplication
        get() = application as RiddleApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pageStore = PageStore(this)
        memoryStore = MemoryStore(this)
        settings = AppSettings(this)
        modelManager = LocalModelManager(this)

        applyWindowPreferences()
        setContentView(R.layout.activity_main)
        bindViews()
        bindActions()
        playBookOpenAnimation()
        AppLog.i("Main", "MainActivity created; ${riddleApplication.memorySnapshot()}")

        val draft = pageStore.loadDraftState()
        if (draft.strokes.isNotEmpty() && pageStore.loadSession(draft.sessionId) != null) {
            pageStore.setActiveSession(draft.sessionId)
        }
        canvas.setStrokes(draft.strokes)
        canvas.onStrokeStarted = {
            replyInkView.clearReply()
            canvas.inkOpacity = 1f
        }
        canvas.onStrokeCommitted = { strokes ->
            persistDraft(strokes)
            scheduleAutoAsk(strokes)
        }

        applyCurrentSettings()
        updateSessionTitle()
        ensureLocalModelWarm()
        if (!canvas.isBlank()) {
            statusText.text = "Recovered your unfinished page. Nothing was lost."
            scheduleAutoAsk(canvas.snapshotStrokes())
        } else if (settings.providerMode != AiProviderMode.ON_DEVICE) {
            updateToolStatus()
        }

        if (!settings.guideSeen) {
            mainHandler.postDelayed({ startActivity(Intent(this, GuideActivity::class.java)) }, 350L)
        }
    }

    override fun onResume() {
        super.onResume()
        applyWindowPreferences()
        if (::canvas.isInitialized) {
            settings = AppSettings(this)
            modelManager = LocalModelManager(this)
            applyCurrentSettings()
            updateSessionTitle()
            ensureLocalModelWarm()
            scheduleAutoAsk(canvas.snapshotStrokes())
        }
    }

    @Deprecated("Deprecated in Android API, retained for the platform Activity base class.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_HISTORY && resultCode == RESULT_OK) {
            val sessionId = data?.getStringExtra(ConversationHistoryActivity.EXTRA_SESSION_ID).orEmpty()
            if (sessionId.isNotBlank()) switchConversation(sessionId)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyWindowPreferences()
    }

    override fun onPause() {
        mainHandler.removeCallbacks(draftSaveRunnable)
        flushPendingDraft()
        super.onPause()
    }

    override fun onDestroy() {
        AppLog.i("Main", "MainActivity destroyed; changingConfigurations=$isChangingConfigurations")
        cancelAutoAsk()
        mainHandler.removeCallbacks(draftSaveRunnable)
        flushPendingDraft()
        executor.shutdown()
        super.onDestroy()
    }

    private fun bindViews() {
        canvas = findViewById(R.id.diaryCanvas)
        replyInkView = findViewById(R.id.replyInkView)
        statusText = findViewById(R.id.statusText)
        sessionTitleText = findViewById(R.id.sessionTitleText)
        askButton = findViewById(R.id.askButton)
        penButton = findViewById(R.id.penButton)
        eraserButton = findViewById(R.id.eraserButton)
        topBar = findViewById(R.id.topBar)
        toolDock = findViewById(R.id.toolDock)
        chromeButton = findViewById(R.id.chromeButton)
        bookCover = findViewById(R.id.bookCoverOverlay)
    }

    private fun bindActions() {
        findViewById<Button>(R.id.undoButton).setOnClickListener {
            cancelAutoAsk()
            if (!canvas.undo()) toast("Nothing to undo.")
        }
        findViewById<Button>(R.id.redoButton).setOnClickListener {
            cancelAutoAsk()
            if (!canvas.redo()) toast("Nothing to redo.")
        }
        penButton.setOnClickListener {
            canvas.selectedTool = InkTool.PEN
            updateToolStatus()
        }
        eraserButton.setOnClickListener {
            canvas.selectedTool = InkTool.ERASER
            updateToolStatus()
        }
        findViewById<Button>(R.id.clearButton).setOnClickListener { confirmClear() }
        askButton.setOnClickListener {
            cancelAutoAsk()
            askDiary()
        }
        findViewById<Button>(R.id.historyButton).setOnClickListener {
            cancelAutoAsk()
            startActivityForResult(
                Intent(this, ConversationHistoryActivity::class.java),
                REQUEST_HISTORY,
            )
        }
        findViewById<Button>(R.id.newConversationButton).setOnClickListener { confirmNewConversation() }
        findViewById<Button>(R.id.settingsButton).setOnClickListener {
            cancelAutoAsk()
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        chromeButton.setOnClickListener {
            chromeVisible = !chromeVisible
            updateChromeVisibility()
        }
        chromeButton.setOnLongClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            true
        }
    }

    private fun updateChromeVisibility() {
        topBar.visibility = if (chromeVisible) View.VISIBLE else View.GONE
        toolDock.visibility = if (chromeVisible) View.VISIBLE else View.GONE
        chromeButton.visibility = View.VISIBLE
        chromeButton.text = if (chromeVisible) "×" else "⋮"
        chromeButton.contentDescription =
            if (chromeVisible) "Hide diary controls" else "Show diary controls; hold for Settings"
        chromeButton.alpha = if (chromeVisible) 0.86f else 1f
    }

    private fun playBookOpenAnimation() {
        if (!settings.bookOpenAnimation) {
            bookCover.visibility = View.GONE
            return
        }
        bookCover.apply {
            visibility = View.VISIBLE
            alpha = 1f
            rotationY = 0f
            pivotX = 0f
            cameraDistance = resources.displayMetrics.density * 8_000f
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            post {
                animate()
                    .rotationY(-108f)
                    .alpha(0.08f)
                    .setStartDelay(240L)
                    .setDuration(1_180L)
                    .withEndAction {
                        visibility = View.GONE
                        rotationY = 0f
                        alpha = 1f
                        setLayerType(View.LAYER_TYPE_NONE, null)
                        AppLog.i("Main", "Book opening animation completed")
                    }
                    .start()
            }
        }
    }

    private fun applyCurrentSettings() {
        canvas.allowFinger = settings.allowFinger
        canvas.setAppearance(
            penWidth = settings.penWidth,
            pressureSensitivity = settings.pressureSensitivity,
            inkColor = settings.userInkColor,
            paperTheme = settings.paperTheme,
        )
        replyInkView.inkColor = settings.replyInkColor
        applyWindowPreferences()
        if (settings.providerMode != AiProviderMode.ON_DEVICE || riddleApplication.isSelectedModelWarm()) {
            updateToolStatus()
        }
    }

    private fun ensureLocalModelWarm() {
        if (settings.providerMode != AiProviderMode.ON_DEVICE || !modelManager.isModelReady()) return
        if (riddleApplication.isSelectedModelWarm()) {
            if (!requestRunning) statusText.text = "Local model ready · write, then pause."
            return
        }
        if (!riddleApplication.shouldAutoWarmSelectedModel()) {
            if (!requestRunning) {
                statusText.text =
                    if (modelManager.isLargeModel()) {
                        "Large model selected · it will load only after Ask. Gemma 4 E2B is recommended."
                    } else {
                        "Local model auto-preparation is off · tap Ask when ready."
                    }
            }
            return
        }
        if (!requestRunning) statusText.text = "Preparing the compact local model in the background…"
        riddleApplication.warmSelectedModel { result ->
            if (isFinishing || isDestroyed) return@warmSelectedModel
            result.onSuccess {
                if (!requestRunning) statusText.text = "Local model ready · write, then pause."
                val strokes = canvas.snapshotStrokes()
                if (strokes.isNotEmpty()) scheduleAutoAsk(strokes)
            }.onFailure { error ->
                if (!requestRunning) {
                    statusText.text = "Local model could not start. Your ink is still saved."
                    toast(error.message ?: "Could not prepare the local model.")
                }
            }
        }
    }

    private fun persistDraft(strokes: List<Stroke>) {
        pendingDraftStrokes = strokes
        pendingDraftSessionId = pageStore.activeSession().id
        mainHandler.removeCallbacks(draftSaveRunnable)
        mainHandler.postDelayed(draftSaveRunnable, DRAFT_SAVE_DEBOUNCE_MS)
    }

    private fun flushPendingDraft() {
        val strokes = pendingDraftStrokes ?: return
        val sessionId = pendingDraftSessionId
        pendingDraftStrokes = null
        executor.execute {
            runCatching {
                if (strokes.isEmpty()) pageStore.clearDraft()
                else pageStore.saveDraft(strokes, sessionId)
            }.onFailure { AppLog.e("Draft", "Could not save unfinished ink", it) }
        }
    }

    private fun scheduleAutoAsk(strokes: List<Stroke>) {
        cancelAutoAsk()
        if (strokes.none { it.tool == InkTool.PEN && it.points.isNotEmpty() }) {
            updateToolStatus()
            return
        }
        val ready = settings.isProviderReady(modelManager.isModelReady())
        if (!settings.autoSubmit || !ready || requestRunning) {
            if (!ready) statusText.text = "Ink saved locally · finish configuring AI in Settings."
            else if (!requestRunning) updateToolStatus()
            return
        }
        if (settings.providerMode == AiProviderMode.ON_DEVICE && !riddleApplication.isSelectedModelWarm()) {
            statusText.text = "Ink saved · finishing the one-time local model preparation…"
            ensureLocalModelWarm()
            return
        }
        val seconds = settings.autoSubmitDelayMs / 1_000.0
        statusText.text = "Pen resting… reading begins in ${"%.1f".format(seconds)} seconds."
        mainHandler.postDelayed(autoAskRunnable, settings.autoSubmitDelayMs)
    }

    private fun cancelAutoAsk() {
        mainHandler.removeCallbacks(autoAskRunnable)
    }

    private fun askDiary() {
        cancelAutoAsk()
        if (requestRunning) return
        if (canvas.isBlank()) {
            toast("Write something first.")
            return
        }
        val mode = settings.providerMode
        if (!settings.isProviderReady(modelManager.isModelReady())) {
            statusText.text = "Your writing is still here and saved. Configure AI in Settings."
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        if (mode == AiProviderMode.ON_DEVICE && !riddleApplication.isSelectedModelWarm()) {
            statusText.text = "Loading the selected local model in the background…"
            riddleApplication.warmSelectedModel(forceLarge = true) { result ->
                if (isFinishing || isDestroyed) return@warmSelectedModel
                result.onSuccess { askDiary() }
                    .onFailure { error ->
                        statusText.text = "Local model could not start. Your ink is still saved."
                        toast(error.message ?: "Could not prepare the local model.")
                    }
            }
            return
        }

        mainHandler.removeCallbacks(draftSaveRunnable)
        pendingDraftStrokes = null
        val strokes = canvas.snapshotStrokes()
        val userInkBottom = canvas.inkBottomFraction()
        val png = runCatching { canvas.exportPng() }.getOrElse { error ->
            toast(error.message ?: "Could not render the page.")
            return
        }
        val page = pageStore.createPage(strokes, png)
        val recentPages = pageStore.memoryPages(
            sessionId = page.sessionId,
            maxCurrentTurns = 10,
            includeCrossSession = settings.crossSessionMemory,
        ).filterNot { it.id == page.id }
        val persistentFacts = if (settings.crossSessionMemory) memoryStore.list() else emptyList()
        val stableFacts = buildList {
            addAll(persistentFacts)
            addAll(MemoryExtractor.fromPages(recentPages))
            addAll(DiaryPrompt.instructionEntries(settings.customInstructions))
        }.distinctBy(String::lowercase).takeLast(48)
        val image = pageStore.imageFile(page)

        requestRunning = true
        canvas.isEnabled = false
        askButton.isEnabled = false
        replyInkView.clearReply()
        statusText.text = when (mode) {
            AiProviderMode.ON_DEVICE -> "Reading your ink locally and preparing the complete answer…"
            AiProviderMode.LOCAL_SERVER -> "Reading your ink through the local server…"
            else -> "Reading your ink and preparing the complete answer…"
        }

        executor.execute {
            runCatching {
                askConfiguredProvider(mode, image, recentPages, stableFacts)
            }.onSuccess { result ->
                val deterministicFacts = MemoryExtractor.fromTranscript(result.transcript)
                val knownFacts = (stableFacts.filterNot { it.startsWith("[instruction] ") } +
                    deterministicFacts + result.memoryFacts).distinctBy(String::lowercase)
                val transcriptLower = result.transcript.lowercase()
                val asksForIdentity = listOf(
                    "what is my name", "who am i", "remember my name", "do you remember me",
                ).any(transcriptLower::contains)
                val hasSavedName = knownFacts.any { it.startsWith("User's name is", ignoreCase = true) }
                val finalResult = when {
                    asksForIdentity && hasSavedName ->
                        result.copy(reply = MemoryExtractor.answerFromFacts(knownFacts))
                    MemoryExtractor.isMemoryQuestion(result.transcript) && knownFacts.isNotEmpty() &&
                        MemoryExtractor.isMemoryDenial(result.reply) ->
                        result.copy(reply = MemoryExtractor.answerFromFacts(knownFacts))
                    MemoryExtractor.isMemoryDenial(result.reply) && deterministicFacts.isNotEmpty() ->
                        result.copy(reply = MemoryExtractor.answerFromFacts(deterministicFacts))
                    else -> result
                }
                pageStore.updateResult(
                    id = page.id,
                    transcript = finalResult.transcript,
                    reply = finalResult.reply,
                    suggestedTitle = finalResult.sessionTitle,
                )
                if (settings.automaticMemoryFacts && settings.crossSessionMemory) {
                    memoryStore.addAll(finalResult.memoryFacts + deterministicFacts)
                }
                pageStore.clearDraft()
                runOnUiThread {
                    requestRunning = false
                    canvas.isEnabled = true
                    askButton.isEnabled = true
                    updateSessionTitle()
                    statusText.text = "Answer received · the ink is settling…"
                    fadeCommittedInk(finalResult, userInkBottom)
                }
            }.onFailure { error ->
                pageStore.updateError(page.id, error.message ?: error.javaClass.simpleName)
                AppLog.e("Oracle", "Answer request failed", error)
                runOnUiThread {
                    requestRunning = false
                    canvas.isEnabled = true
                    askButton.isEnabled = true
                    canvas.inkOpacity = 1f
                    statusText.text = "Could not answer. Your page is still here and saved."
                    toast(error.message ?: "Unknown AI error")
                }
            }
        }
    }

    private fun askConfiguredProvider(
        mode: AiProviderMode,
        image: File,
        recentPages: List<DiaryPage>,
        stableFacts: List<String>,
    ): OracleResult = when (mode) {
        AiProviderMode.RIDDLE_BACKEND -> oracleClient.askProxy(
            backendUrl = settings.proxyUrl,
            appToken = settings.proxyToken,
            pageImage = image,
            recentPages = recentPages,
            stableFacts = stableFacts,
            voice = settings.diaryVoice,
            answerLength = settings.answerLength,
            answerStyle = settings.answerStyle,
        )
        AiProviderMode.DIRECT_OPENAI -> oracleClient.askOpenAiCompatible(
            baseUrl = settings.directBaseUrl,
            apiKey = settings.directApiKey,
            model = settings.directModel,
            pageImage = image,
            recentPages = recentPages,
            stableFacts = stableFacts,
            voice = settings.diaryVoice,
            answerLength = settings.answerLength,
            answerStyle = settings.answerStyle,
        )
        AiProviderMode.LOCAL_SERVER -> oracleClient.askOpenAiCompatible(
            baseUrl = settings.localServerBaseUrl,
            apiKey = settings.localServerToken,
            model = settings.localServerModel,
            pageImage = image,
            recentPages = recentPages,
            stableFacts = stableFacts,
            voice = settings.diaryVoice,
            answerLength = settings.answerLength,
            answerStyle = settings.answerStyle,
        )
        AiProviderMode.ON_DEVICE -> riddleApplication.onDeviceOracle.ask(
            modelFile = modelManager.modelFile(),
            backendChoice = settings.localInferenceBackend,
            pageImage = image,
            recentPages = recentPages,
            stableFacts = stableFacts,
            voice = settings.diaryVoice,
            answerLength = settings.answerLength,
            answerStyle = settings.answerStyle,
        )
        AiProviderMode.NONE -> error("Choose an AI provider in Settings.")
    }

    private fun fadeCommittedInk(result: OracleResult, userInkBottom: Float) {
        ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 720L
            interpolator = DecelerateInterpolator()
            addUpdateListener { canvas.inkOpacity = it.animatedValue as Float }
            addListener(
                object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        canvas.clear()
                        canvas.inkOpacity = 1f
                        mainHandler.postDelayed(
                            {
                                replyInkView.showReply(
                                    result.reply,
                                    settings.replySpeedMsPerCharacter,
                                    userInkBottom,
                                )
                                statusText.text = "The diary has answered."
                            },
                            settings.replyStartDelayMs,
                        )
                    }
                },
            )
            start()
        }
    }

    private fun confirmClear() {
        cancelAutoAsk()
        if (canvas.isBlank()) return
        AlertDialog.Builder(this)
            .setTitle("Clear this page?")
            .setMessage("The current unsent writing will be removed.")
            .setNegativeButton("Cancel") { _, _ -> scheduleAutoAsk(canvas.snapshotStrokes()) }
            .setPositiveButton("Clear") { _, _ ->
                canvas.clear()
                pageStore.clearDraft()
                replyInkView.clearReply()
                updateToolStatus()
            }
            .show()
    }

    private fun confirmNewConversation() {
        cancelAutoAsk()
        val start = {
            val session = pageStore.newSession()
            canvas.clear()
            canvas.inkOpacity = 1f
            replyInkView.clearReply()
            updateSessionTitle()
            statusText.text = "New conversation · write with the S Pen."
            AppLog.i("Conversation", "Started ${session.id}")
        }
        if (canvas.isBlank()) {
            start()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Start a new conversation?")
                .setMessage("The unfinished page will be discarded. Completed exchanges remain in History.")
                .setNegativeButton("Cancel") { _, _ -> scheduleAutoAsk(canvas.snapshotStrokes()) }
                .setPositiveButton("Start new") { _, _ -> start() }
                .show()
        }
    }

    private fun switchConversation(sessionId: String) {
        pageStore.setActiveSession(sessionId)
        pageStore.clearDraft()
        canvas.clear()
        canvas.inkOpacity = 1f
        replyInkView.clearReply()
        updateSessionTitle()
        statusText.text = "Conversation resumed · write the next message."
    }

    private fun updateSessionTitle() {
        if (!::sessionTitleText.isInitialized) return
        val title = pageStore.activeSession().title
        sessionTitleText.text = if (title == "New conversation") "RIDDLE DIARY" else title.uppercase()
    }

    private fun updateToolStatus() {
        if (requestRunning || !::canvas.isInitialized) return
        val inputName = if (canvas.allowFinger) "S Pen or finger" else "S Pen only"
        val provider = when {
            settings.providerMode == AiProviderMode.ON_DEVICE && riddleApplication.isSelectedModelWarm() ->
                "local model ready"
            else -> settings.providerMode.label
        }
        val autoName = if (settings.autoSubmit) "auto-read" else "manual Ask"
        statusText.text = when (canvas.selectedTool) {
            InkTool.PEN -> "Fountain pen · $inputName · $provider · $autoName"
            InkTool.ERASER -> "Eraser · $inputName · $provider · $autoName"
        }
        penButton.alpha = if (canvas.selectedTool == InkTool.PEN) 1f else 0.48f
        eraserButton.alpha = if (canvas.selectedTool == InkTool.ERASER) 1f else 0.48f
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

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val REQUEST_HISTORY = 8104
        private const val DRAFT_SAVE_DEBOUNCE_MS = 420L
    }
}
