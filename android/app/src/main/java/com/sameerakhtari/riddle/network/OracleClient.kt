package com.sameerakhtari.riddle.network

import android.util.Base64
import com.sameerakhtari.riddle.data.AnswerLength
import com.sameerakhtari.riddle.data.AnswerStyle
import com.sameerakhtari.riddle.data.DiaryVoice
import com.sameerakhtari.riddle.logging.AppLog
import com.sameerakhtari.riddle.model.DiaryPage
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID

class OracleClient {
    fun askProxy(
        backendUrl: String,
        appToken: String,
        pageImage: File,
        recentPages: List<DiaryPage>,
        stableFacts: List<String>,
        voice: DiaryVoice,
        answerLength: AnswerLength,
        answerStyle: AnswerStyle,
    ): OracleResult {
        require(backendUrl.isNotBlank()) { "Configure the Riddle backend URL in Settings." }
        require(pageImage.isFile) { "The page image could not be found." }
        val endpoint = URL("${backendUrl.trimEnd('/')}/v1/diary/ask")
        val boundary = "Riddle-${UUID.randomUUID()}"
        val connection = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 20_000; readTimeout = 180_000
            doOutput = true; useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            if (appToken.isNotBlank()) setRequestProperty("X-Riddle-Token", appToken)
        }
        try {
            BufferedOutputStream(connection.outputStream).use { output ->
                writeTextPart(output, boundary, "memory", memoryJson(recentPages, stableFacts))
                writeTextPart(output, boundary, "voice", voice.name, "text/plain")
                writeTextPart(output, boundary, "answer_length", answerLength.name, "text/plain")
                writeTextPart(output, boundary, "answer_style", answerStyle.name, "text/plain")
                writeFilePart(output, boundary, "page", pageImage, "image/png")
                output.write("--$boundary--\r\n".toByteArray(StandardCharsets.UTF_8))
            }
            val json = JSONObject(readResponse(connection))
            return OracleResult(
                reply = DiaryPrompt.normalizeReply(json.optString("reply"), answerLength),
                transcript = json.optString("transcript").trim(),
                sessionTitle = json.optString("sessionTitle").trim(),
                memoryFacts = json.optJSONArray("memoryFacts").toStringList(),
            ).also { require(it.reply.isNotBlank()) { "The diary returned an empty reply." } }
        } finally { connection.disconnect() }
    }

    fun askOpenAiCompatible(
        baseUrl: String,
        apiKey: String,
        model: String,
        pageImage: File,
        recentPages: List<DiaryPage>,
        stableFacts: List<String>,
        voice: DiaryVoice,
        answerLength: AnswerLength,
        answerStyle: AnswerStyle,
    ): OracleResult {
        require(baseUrl.isNotBlank()) { "Configure the API/server URL in Settings." }
        require(model.isNotBlank()) { "Configure a model name in Settings." }
        require(pageImage.isFile) { "The page image could not be found." }
        val imageData = Base64.encodeToString(pageImage.readBytes(), Base64.NO_WRAP)
        val messages = JSONArray().put(
            JSONObject().put("role", "system").put(
                "content", "${DiaryPrompt.persona(voice, answerLength, answerStyle)}\n\n${DiaryPrompt.outputRule(answerLength)}",
            ),
        )
        val userContent = JSONArray()
            .put(JSONObject().put("type", "text").put(
                "text", "${DiaryPrompt.currentTask(answerLength, answerStyle)}\n\n${DiaryPrompt.memoryText(recentPages, stableFacts)}",
            ))
            .put(JSONObject().put("type", "image_url").put(
                "image_url", JSONObject().put("url", "data:image/png;base64,$imageData"),
            ))
        messages.put(JSONObject().put("role", "user").put("content", userContent))
        val maxTokens = when (answerLength) {
            AnswerLength.BRIEF -> 420; AnswerLength.STANDARD -> 760; AnswerLength.DETAILED -> 1_300
        }
        val payload = JSONObject().put("model", model).put("messages", messages)
            .put("temperature", if (voice == DiaryVoice.DIRECT || answerStyle == AnswerStyle.VALUE_ONLY) 0.08 else 0.18)
            .put("max_tokens", maxTokens)
        val connection = (URL(completionEndpoint(baseUrl)).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 20_000; readTimeout = 180_000
            doOutput = true; useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
        }
        try {
            connection.outputStream.use { it.write(payload.toString().toByteArray(StandardCharsets.UTF_8)) }
            val content = JSONObject(readResponse(connection)).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").opt("content")
            return DiaryPrompt.parseModelResponse(contentToText(content), answerLength)
        } finally { connection.disconnect() }
    }

    fun listModels(baseUrl: String, apiKey: String): List<String> {
        require(baseUrl.isNotBlank()) { "Enter an API/server URL first." }
        val connection = (URL(modelsEndpoint(baseUrl)).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 15_000; readTimeout = 45_000; useCaches = false
            setRequestProperty("Accept", "application/json")
            if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
        }
        try {
            val data = JSONObject(readResponse(connection)).optJSONArray("data") ?: JSONArray()
            val models = buildList {
                for (index in 0 until data.length()) when (val item = data.opt(index)) {
                    is JSONObject -> item.optString("id").takeIf(String::isNotBlank)?.let(::add)
                    is String -> item.takeIf(String::isNotBlank)?.let(::add)
                }
            }.distinct().sorted()
            AppLog.i("Models", "Discovered ${models.size} provider models")
            return models
        } finally { connection.disconnect() }
    }

    private fun completionEndpoint(baseUrl: String): String {
        val clean = baseUrl.trimEnd('/')
        return if (clean.endsWith("/chat/completions")) clean else "$clean/chat/completions"
    }
    private fun modelsEndpoint(baseUrl: String): String {
        val clean = baseUrl.trimEnd('/').removeSuffix("/chat/completions")
        return if (clean.endsWith("/models")) clean else "$clean/models"
    }
    private fun readResponse(connection: HttpURLConnection): String {
        val status = connection.responseCode
        val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (status !in 200..299) {
            val detail = runCatching {
                val json = JSONObject(text)
                when {
                    json.has("detail") -> json.optString("detail")
                    json.has("error") -> (json.opt("error") as? JSONObject)?.optString("message") ?: json.opt("error").toString()
                    else -> text
                }
            }.getOrDefault(text)
            error("AI endpoint returned HTTP $status: ${detail.ifBlank { "Unknown error" }}")
        }
        return text
    }
    private fun contentToText(content: Any?): String = when (content) {
        is String -> content
        is JSONArray -> buildString { for (index in 0 until content.length()) when (val part = content.opt(index)) {
            is JSONObject -> append(part.optString("text")); is String -> append(part)
        } }
        else -> content?.toString().orEmpty()
    }
    private fun memoryJson(pages: List<DiaryPage>, facts: List<String>): String {
        val turns = JSONArray()
        pages.filter { it.reply.isNotBlank() || it.transcript.isNotBlank() }.takeLast(10).forEach { page ->
            turns.put(JSONObject().put("createdAt", page.createdAt).put("transcript", page.transcript.take(900)).put("reply", page.reply.take(700)))
        }
        val memoryFacts = JSONArray(); facts.takeLast(40).forEach { memoryFacts.put(it.take(220)) }
        return JSONObject().put("turns", turns).put("facts", memoryFacts).toString()
    }
    private fun writeTextPart(output: BufferedOutputStream, boundary: String, name: String, value: String, contentType: String = "application/json; charset=UTF-8") {
        output.write("--$boundary\r\n".toByteArray(StandardCharsets.UTF_8))
        output.write("Content-Disposition: form-data; name=\"$name\"\r\n".toByteArray(StandardCharsets.UTF_8))
        output.write("Content-Type: $contentType\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
        output.write(value.toByteArray(StandardCharsets.UTF_8)); output.write("\r\n".toByteArray(StandardCharsets.UTF_8))
    }
    private fun writeFilePart(output: BufferedOutputStream, boundary: String, name: String, file: File, contentType: String) {
        output.write("--$boundary\r\n".toByteArray(StandardCharsets.UTF_8))
        output.write("Content-Disposition: form-data; name=\"$name\"; filename=\"${file.name}\"\r\n".toByteArray(StandardCharsets.UTF_8))
        output.write("Content-Type: $contentType\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
        file.inputStream().use { it.copyTo(output) }; output.write("\r\n".toByteArray(StandardCharsets.UTF_8))
    }
    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList { for (index in 0 until length()) optString(index).replace(Regex("\\s+"), " ").trim().take(220)
            .takeIf { it.length >= 3 }?.let(::add) }.distinctBy(String::lowercase).take(8)
    }
}
