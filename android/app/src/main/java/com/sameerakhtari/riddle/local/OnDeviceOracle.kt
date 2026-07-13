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
        val prompt = "${DiaryPrompt.localCurrentTask(answerLength, answerStyle)}\n\n$contextText"
        val conversationConfig = ConversationConfig(
            systemInstruction = Contents.of(
                "${DiaryPrompt.persona(voice, answerLength, answerStyle)}\n\n${DiaryPrompt.localOutputRule(answerLength)}",
            ),
            samplerConfig = SamplerConfig(
                topK = 18,
                topP = 0.78,
                temperature = if (voice == DiaryVoice.DIRECT || answerStyle == AnswerStyle.VALUE_ONLY) 0.08 else 0.16,
            ),
        )

        return localEngine.createConversation(conversationConfig).use { conversation ->
            val firstRaw = conversation.sendMessage(
                Contents.of(Content.ImageFile(pageImage.absolutePath), Content.Text(prompt)),
            ).toString()
            val first = DiaryPrompt.parseModelResponse(firstRaw, answerLength)
            if (!DiaryPrompt.needsRepair(first, stableFacts) || first.transcript.isBlank()) return@use first

            val repairedRaw = conversation.sendMessage(
                Contents.of(Content.Text(DiaryPrompt.repairTask(first.transcript, answerLength, answerStyle, contextText))),
            ).toString()
            DiaryPrompt.mergeRepair(first, DiaryPrompt.parseModelResponse(repairedRaw, answerLength))
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
