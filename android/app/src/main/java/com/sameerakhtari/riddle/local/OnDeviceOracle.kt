package com.sameerakhtari.riddle.local

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.SamplerConfig
import com.sameerakhtari.riddle.data.AnswerLength
import com.sameerakhtari.riddle.data.AnswerStyle
import com.sameerakhtari.riddle.data.DiaryVoice
import com.sameerakhtari.riddle.data.LocalInferenceBackend
import com.sameerakhtari.riddle.data.MemoryExtractor
import com.sameerakhtari.riddle.logging.AppLog
import com.sameerakhtari.riddle.model.DiaryPage
import com.sameerakhtari.riddle.network.DiaryPrompt
import com.sameerakhtari.riddle.network.OracleResult
import java.io.File

class OnDeviceOracle(private val context: Context) : AutoCloseable {
    enum class WarmState { COLD, LOADING, READY, FAILED }

    private var engine: Engine? = null
    private var loadedKey: String? = null

    @Volatile var warmState: WarmState = WarmState.COLD
        private set
    @Volatile var lastWarmError: String = ""
        private set

    @Synchronized
    fun isWarm(modelFile: File, backendChoice: LocalInferenceBackend): Boolean =
        engine != null && loadedKey == keyFor(modelFile, backendChoice) && warmState == WarmState.READY

    @Synchronized
    fun warmUp(modelFile: File, backendChoice: LocalInferenceBackend) {
        require(modelFile.isFile) { "Download the selected on-device model in Settings first." }
        if (isWarm(modelFile, backendChoice)) return
        warmState = WarmState.LOADING
        lastWarmError = ""
        try {
            engineFor(modelFile, backendChoice)
            warmState = WarmState.READY
        } catch (error: Throwable) {
            warmState = WarmState.FAILED
            lastWarmError = error.message ?: error.javaClass.simpleName
            closeEngineOnly()
            throw error
        }
    }

    @Synchronized
    fun ask(
        modelFile: File,
        backendChoice: LocalInferenceBackend,
        pageImage: File,
        recentPages: List<DiaryPage>,
        stableFacts: List<String>,
        voice: DiaryVoice,
        answerLength: AnswerLength,
        answerStyle: AnswerStyle,
    ): OracleResult {
        require(modelFile.isFile) { "Download the selected on-device model in Settings first." }
        require(pageImage.isFile) { "The page image could not be found." }
        if (!isWarm(modelFile, backendChoice)) warmUp(modelFile, backendChoice)
        val localEngine = requireNotNull(engine) { "The local model did not initialize." }
        val contextText = DiaryPrompt.memoryText(recentPages, stableFacts)

        // Keep vision/OCR and answering in separate fresh conversations. Small edge models are much
        // more reliable when they are not asked to transcribe, answer, title, and emit metadata in
        // one structured response.
        val transcriptRaw = localEngine.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(DiaryPrompt.localTranscriptionSystem()),
                samplerConfig = SamplerConfig(topK = 8, topP = 0.55, temperature = 0.0, seed = 17),
            ),
        ).use { conversation ->
            conversation.sendMessage(
                Contents.of(
                    Content.ImageFile(pageImage.absolutePath),
                    Content.Text(DiaryPrompt.localTranscriptionTask()),
                ),
                maxOutputToken = 180,
            ).toString()
        }
        val transcript = DiaryPrompt.parsePlainTranscript(transcriptRaw)
        AppLog.i(
            "LocalAI",
            "Transcription completed rawChars=${transcriptRaw.length} transcriptChars=${transcript.length}",
        )
        if (transcript.isBlank()) {
            return OracleResult(
                reply = "I could not read that clearly. Please write it again with a little more spacing.",
                transcript = "",
                sessionTitle = "Unreadable handwriting",
            )
        }

        val answerRaw = generateText(
            engine = localEngine,
            system = DiaryPrompt.localAnswerSystem(voice, answerLength, answerStyle),
            prompt = DiaryPrompt.localAnswerTask(transcript, contextText, answerLength, answerStyle),
            answerLength = answerLength,
            strict = answerStyle == AnswerStyle.VALUE_ONLY || voice == DiaryVoice.DIRECT,
        )
        var answer = DiaryPrompt.parsePlainAnswer(answerRaw, transcript, answerLength)
        AppLog.i(
            "LocalAI",
            "Initial answer completed rawChars=${answerRaw.length} answerChars=${answer.length} unsafe=${DiaryPrompt.isUnsafeVisibleReply(answer, transcript)}",
        )

        if (answer.isBlank() || DiaryPrompt.isUnsafeVisibleReply(answer, transcript)) {
            val repairedRaw = generateText(
                engine = localEngine,
                system = DiaryPrompt.localAnswerSystem(voice, answerLength, answerStyle),
                prompt = DiaryPrompt.localRepairTask(
                    transcript = transcript,
                    candidate = answerRaw,
                    context = contextText,
                    answerLength = answerLength,
                    answerStyle = answerStyle,
                ),
                answerLength = answerLength,
                strict = true,
            )
            answer = DiaryPrompt.parsePlainAnswer(repairedRaw, transcript, answerLength)
            AppLog.w(
                "LocalAI",
                "Reply repair used rawChars=${repairedRaw.length} answerChars=${answer.length} unsafe=${DiaryPrompt.isUnsafeVisibleReply(answer, transcript)}",
            )
        }

        // A short independent verifier improves numerical/date/unit answers without carrying the
        // first generation's schema echo or repetition into the second conversation.
        if (answer.isNotBlank() &&
            !DiaryPrompt.isUnsafeVisibleReply(answer, transcript) &&
            DiaryPrompt.shouldVerifyFactualAnswer(transcript, answer)
        ) {
            val verifiedRaw = generateText(
                engine = localEngine,
                system = "You are a careful factual verifier. Return only the corrected final answer. Never add labels, JSON, commentary, or repeat the question.",
                prompt = DiaryPrompt.localVerificationTask(transcript, answer, answerLength, answerStyle),
                answerLength = answerLength,
                strict = true,
            )
            val verified = DiaryPrompt.parsePlainAnswer(verifiedRaw, transcript, answerLength)
            if (verified.isNotBlank() && !DiaryPrompt.isUnsafeVisibleReply(verified, transcript)) {
                answer = verified
                AppLog.i("LocalAI", "Factual verification accepted answerChars=${verified.length}")
            } else {
                AppLog.w("LocalAI", "Factual verification rejected as unusable")
            }
        }

        if (answer.isBlank() || DiaryPrompt.isUnsafeVisibleReply(answer, transcript)) {
            answer = DiaryPrompt.localFallbackReply(transcript, stableFacts)
            AppLog.w("LocalAI", "Used deterministic safe fallback for an unusable model reply")
        }

        return DiaryPrompt.sanitizeResultForDisplay(
            OracleResult(
                reply = answer,
                transcript = transcript,
                sessionTitle = DiaryPrompt.safeSessionTitle(transcript),
                memoryFacts = MemoryExtractor.fromTranscript(transcript),
            ),
            answerLength,
        )
    }

    private fun generateText(
        engine: Engine,
        system: String,
        prompt: String,
        answerLength: AnswerLength,
        strict: Boolean,
    ): String {
        val maxTokens = when (answerLength) {
            AnswerLength.BRIEF -> 120
            AnswerLength.STANDARD -> 240
            AnswerLength.DETAILED -> 420
        }
        val config = ConversationConfig(
            systemInstruction = Contents.of(system),
            samplerConfig = SamplerConfig(
                topK = if (strict) 6 else 12,
                topP = if (strict) 0.52 else 0.68,
                temperature = if (strict) 0.0 else 0.08,
                seed = 29,
            ),
        )
        return engine.createConversation(config).use { conversation ->
            conversation.sendMessage(prompt, maxOutputToken = maxTokens).toString()
        }
    }

    @Synchronized
    private fun engineFor(modelFile: File, choice: LocalInferenceBackend): Engine {
        val key = keyFor(modelFile, choice)
        engine?.takeIf { loadedKey == key }?.let { return it }
        closeEngineOnly()
        Engine.setNativeMinLogSeverity(LogSeverity.ERROR)
        val backend = when (choice) {
            LocalInferenceBackend.CPU -> Backend.CPU()
            LocalInferenceBackend.GPU -> Backend.GPU()
        }
        val config = EngineConfig(
            modelPath = modelFile.absolutePath,
            backend = backend,
            visionBackend = backend,
            cacheDir = context.cacheDir.absolutePath,
        )
        return Engine(config).also {
            it.initialize()
            engine = it
            loadedKey = key
        }
    }

    private fun keyFor(modelFile: File, choice: LocalInferenceBackend): String =
        "${modelFile.absolutePath}:${modelFile.length()}:${modelFile.lastModified()}:${choice.name}"

    @Synchronized override fun close() {
        closeEngineOnly()
        warmState = WarmState.COLD
        lastWarmError = ""
    }

    private fun closeEngineOnly() {
        runCatching { engine?.close() }
        engine = null
        loadedKey = null
    }
}
