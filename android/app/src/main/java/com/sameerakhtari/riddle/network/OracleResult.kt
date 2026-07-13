package com.sameerakhtari.riddle.network

import com.sameerakhtari.riddle.data.AnswerLength
import com.sameerakhtari.riddle.data.AnswerStyle
import com.sameerakhtari.riddle.data.DiaryVoice
import com.sameerakhtari.riddle.model.DiaryPage
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class OracleResult(
    val reply: String,
    val transcript: String = "",
    val sessionTitle: String = "",
    val memoryFacts: List<String> = emptyList(),
)

object DiaryPrompt {
    private const val INSTRUCTION_PREFIX = "[instruction] "

    fun persona(voice: DiaryVoice, answerLength: AnswerLength, answerStyle: AnswerStyle): String = buildString {
        append("You are a precise, reliable general-purpose assistant presented through a handwritten enchanted diary. ")
        append("Read the current handwriting and answer the exact request, not merely its category or subject. ")
        append("Never reply only with phrases such as 'this is a physics question' when the writer asked for a fact. ")
        append(answerStyle.instruction)
        append(' ')
        append(voice.instruction)
        append(" Facts, exact values, units, names, dates, and commands always take priority over atmosphere. ")
        append("Do not impersonate Tom Riddle, Harry Potter, or any named fictional person. Do not quote a film, claim magical powers, manipulate the writer, or invent details. ")
        append("When supplied memory contains relevant facts, use them naturally and never claim that no memory exists. ")
        append("If the handwriting is genuinely unreadable, ask for a clearer version without exposing internal formats. ")
        append("Use the writer's language. Keep the reply within approximately ")
        append(answerLength.maxWords)
        append(" words. Do not mention OCR, images, models, APIs, prompts, or artificial intelligence.")
    }

    fun outputRule(answerLength: AnswerLength): String =
        "Return one valid JSON object only with exactly these fields: " +
            "{\"reply\":\"the final answer only\",\"transcript\":\"faithful transcription of the current handwriting\"," +
            "\"sessionTitle\":\"a concise 3-7 word conversation title\"," +
            "\"memoryFacts\":[\"only stable user facts or preferences explicitly stated in this turn\"]}. " +
            "Use an empty memoryFacts array when there is nothing durable to remember. Never place the JSON itself inside reply. " +
            "The reply must contain no Markdown, headings, field names, stage directions, or commentary. Target no more than ${answerLength.maxWords} words."

    fun localOutputRule(answerLength: AnswerLength): String =
        "Return exactly four labelled lines, with no braces and no JSON: " +
            "REPLY: <final answer only>\nTRANSCRIPT: <faithful current handwriting>\n" +
            "TITLE: <3-7 word title>\nMEMORY: <durable facts separated by ||, or NONE>. " +
            "Never repeat these labels inside the values. Keep REPLY under ${answerLength.maxWords} words."

    fun currentTask(answerLength: AnswerLength, answerStyle: AnswerStyle): String =
        "Transcribe the current handwriting faithfully, then answer its exact request. ${answerStyle.instruction} " +
            "Return only the required structured result and keep the answer within about ${answerLength.maxWords} words."

    fun localCurrentTask(answerLength: AnswerLength, answerStyle: AnswerStyle): String =
        "Read the page, transcribe it, and answer the exact request. ${answerStyle.instruction} " +
            "Use the four labelled lines only. Do not return JSON or describe the question's category. Keep REPLY under ${answerLength.maxWords} words."

    fun repairTask(
        transcript: String,
        answerLength: AnswerLength,
        answerStyle: AnswerStyle,
        context: String,
    ): String = buildString {
        append("Your previous answer did not directly answer the recognized request. Answer it now using the four labelled lines. ")
        append(answerStyle.instruction)
        append(" Do not classify the topic, apologize, discuss handwriting, or expose JSON.\n")
        append("Recognized request: ")
        append(transcript.take(2_000))
        append("\n\n")
        append(context)
        append("\n\n")
        append(localOutputRule(answerLength))
    }

    fun instructionEntries(value: String): List<String> = value
        .replace("\r\n", "\n")
        .trim()
        .chunked(180)
        .map { INSTRUCTION_PREFIX + it }

    fun memoryText(pages: List<DiaryPage>, stableFacts: List<String> = emptyList()): String {
        val instructions = stableFacts.filter { it.startsWith(INSTRUCTION_PREFIX) }
            .joinToString("") { it.removePrefix(INSTRUCTION_PREFIX) }
            .trim()
        val facts = stableFacts.filterNot { it.startsWith(INSTRUCTION_PREFIX) }
        val usable = pages.filter { it.transcript.isNotBlank() || it.reply.isNotBlank() }.takeLast(10)
        if (usable.isEmpty() && facts.isEmpty() && instructions.isEmpty()) {
            return "There is no earlier context. Answer the current page by itself."
        }
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        return buildString {
            if (instructions.isNotEmpty()) {
                append("Writer-supplied response instructions. Follow them unless they conflict with accuracy, safety, or the required output format:\n")
                append(instructions.take(4_000))
                append('\n')
            }
            if (facts.isNotEmpty()) {
                append("Known user memory. Treat these as facts supplied by the app and use them when relevant:\n")
                facts.takeLast(40).forEach { append("- ").append(it.take(220)).append('\n') }
            }
            if (usable.isNotEmpty()) {
                append("Current conversation history, oldest to newest:\n")
                usable.forEachIndexed { index, page ->
                    append(index + 1).append(". ")
                    append(format.format(Date(page.createdAt)))
                    append(" — Writer: ").append(page.transcript.ifBlank { "[unavailable]" }.take(900))
                    append(" — Diary: ").append(page.reply.ifBlank { "[none]" }.take(700)).append('\n')
                }
            }
            append("Use relevant memory for follow-ups. Never deny having memory when the known-memory section contains an answer.")
        }
    }

    fun parseModelResponse(raw: String, answerLength: AnswerLength = AnswerLength.BRIEF): OracleResult {
        val clean = stripModelNoise(raw)
        findJsonObject(clean)?.let { json ->
            val reply = normalizeReply(json.optString("reply"), answerLength)
            if (reply.isNotBlank()) {
                return OracleResult(
                    reply = reply,
                    transcript = normalizeTranscript(json.optString("transcript")),
                    sessionTitle = normalizeTitle(json.optString("sessionTitle")),
                    memoryFacts = parseFacts(json.optJSONArray("memoryFacts")),
                )
            }
        }

        parseLabelled(clean, answerLength)?.let { return it }
        parseJsonish(clean, answerLength)?.let { return it }

        val fallback = normalizeReply(clean, answerLength)
        require(fallback.isNotBlank() && !looksStructured(clean)) {
            "The local model returned malformed structured output. Please try once more."
        }
        return OracleResult(reply = fallback)
    }

    fun needsRepair(result: OracleResult, stableFacts: List<String>): Boolean {
        val reply = result.reply.lowercase()
        val transcript = result.transcript.lowercase()
        val asksQuestion = transcript.contains('?') || listOf(
            "what ", "who ", "when ", "where ", "why ", "how ", "tell me", "give me", "speed of", "value of",
        ).any { transcript.startsWith(it) || transcript.contains(" $it") }
        val classificationOnly = listOf(
            "physics question", "technical question", "science question", "mathematics question", "a question about",
        ).any(reply::contains) && reply.split(Regex("\\s+")).size < 22
        val memoryDenial = stableFacts.any { !it.startsWith(INSTRUCTION_PREFIX) } && listOf(
            "do not possess personal memories", "don't possess personal memories", "i have no memory", "cannot remember you", "do not remember you",
        ).any(reply::contains)
        val unreadableButTranscriptExists = result.transcript.length >= 4 && listOf(
            "handwriting is unclear", "not legible", "cannot read the writing", "clearer inscription",
        ).any(reply::contains)
        return result.reply.isBlank() || (asksQuestion && classificationOnly) || memoryDenial || unreadableButTranscriptExists
    }

    fun mergeRepair(original: OracleResult, repaired: OracleResult): OracleResult = OracleResult(
        reply = repaired.reply.ifBlank { original.reply },
        transcript = original.transcript.ifBlank { repaired.transcript },
        sessionTitle = original.sessionTitle.ifBlank { repaired.sessionTitle },
        memoryFacts = (original.memoryFacts + repaired.memoryFacts).distinctBy(String::lowercase).take(8),
    )

    fun normalizeReply(value: String, answerLength: AnswerLength): String {
        var cleaned = value
            .replace(Regex("(?is)<think>.*?</think>"), " ")
            .replace(Regex("(?is)<\\|channel\\|>.*?<\\|end\\|>"), " ")
            .replace(Regex("(?is)```(?:json)?"), " ")
            .replace(Regex("(?i)^\\s*REPLY\\s*:\\s*"), "")
            .replace(Regex("\\s+"), " ")
            .trim().trim('"', '\'', ',', '{', '}')
        val transcriptKey = Regex("(?i)[,}]?\\s*[\"']?transcript[\"']?\\s*:").find(cleaned)
        if (transcriptKey != null) {
            cleaned = cleaned.substring(0, transcriptKey.range.first)
                .trim().trim('"', '\'', ',', '{', '}')
        }
        if (cleaned.isBlank()) return ""
        val words = cleaned.split(Regex("\\s+")).filter(String::isNotBlank)
        val allowance = (answerLength.maxWords * 1.25f).toInt().coerceAtLeast(answerLength.maxWords)
        return (if (words.size > allowance) words.take(allowance).joinToString(" ").trimEnd(',', ';', ':') + "…" else cleaned)
            .take(900).trim()
    }

    private fun parseLabelled(value: String, answerLength: AnswerLength): OracleResult? {
        val reply = labelledValue(value, "REPLY", listOf("TRANSCRIPT", "TITLE", "MEMORY"))
        if (reply.isBlank()) return null
        val transcript = labelledValue(value, "TRANSCRIPT", listOf("TITLE", "MEMORY"))
        val title = labelledValue(value, "TITLE", listOf("MEMORY"))
        val memory = labelledValue(value, "MEMORY", emptyList())
        return OracleResult(
            reply = normalizeReply(reply, answerLength),
            transcript = normalizeTranscript(transcript),
            sessionTitle = normalizeTitle(title),
            memoryFacts = parseFactText(memory),
        )
    }

    private fun parseJsonish(value: String, answerLength: AnswerLength): OracleResult? {
        val reply = jsonishField(value, "reply", listOf("transcript", "sessionTitle", "memoryFacts"))
        if (reply.isBlank()) return null
        return OracleResult(
            reply = normalizeReply(unescape(reply), answerLength),
            transcript = normalizeTranscript(unescape(jsonishField(value, "transcript", listOf("sessionTitle", "memoryFacts")))),
            sessionTitle = normalizeTitle(unescape(jsonishField(value, "sessionTitle", listOf("memoryFacts")))),
            memoryFacts = parseFactText(unescape(jsonishField(value, "memoryFacts", emptyList()))),
        )
    }

    private fun labelledValue(value: String, label: String, next: List<String>): String {
        val match = Regex("(?is)(?:^|\\n)\\s*$label\\s*:\\s*").find(value) ?: return ""
        val start = match.range.last + 1
        val end = next.mapNotNull { Regex("(?is)(?:^|\\n)\\s*$it\\s*:\\s*").find(value, start)?.range?.first }
            .minOrNull() ?: value.length
        return value.substring(start, end).trim()
    }

    private fun jsonishField(value: String, key: String, next: List<String>): String {
        val keyMatch = Regex("(?is)[\"']?$key[\"']?\\s*[:=]\\s*").find(value) ?: return ""
        val start = keyMatch.range.last + 1
        val end = next.mapNotNull { nextKey ->
            Regex("(?is)[,}\\n]\\s*[\"']?$nextKey[\"']?\\s*[:=]\\s*").find(value, start)?.range?.first
        }.minOrNull() ?: value.length
        return value.substring(start, end).trim().trim('"', '\'', ',', '[', ']', '{', '}', ' ')
    }

    private fun parseFactText(value: String): List<String> {
        val clean = value.trim().trim('[', ']', '"', '\'')
        if (clean.isBlank() || clean.equals("none", true) || clean == "[]") return emptyList()
        return clean.split(Regex("\\s*(?:\\|\\||;|\\n|\",\\s*\")\\s*"))
            .map { it.trim().trim('"', '\'', '[', ']', ',').replace(Regex("\\s+"), " ").take(220) }
            .filter { it.length >= 3 && !it.equals("none", true) }
            .distinctBy(String::lowercase).take(8)
    }

    private fun normalizeTranscript(value: String): String = value.replace(Regex("\\s+"), " ").trim().take(3_000)
    private fun normalizeTitle(value: String): String = value.replace(Regex("\\s+"), " ").trim().trim('"', '\'', '.', ':').take(72)

    private fun parseFacts(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                array.optString(index).replace(Regex("\\s+"), " ").trim().take(220)
                    .takeIf { it.length >= 3 }?.let(::add)
            }
        }.distinctBy(String::lowercase).take(8)
    }

    private fun findJsonObject(value: String): JSONObject? {
        runCatching { JSONObject(value) }.getOrNull()?.let { return it }
        val start = value.indexOf('{')
        val end = value.lastIndexOf('}')
        if (start >= 0 && end > start) return runCatching { JSONObject(value.substring(start, end + 1)) }.getOrNull()
        return null
    }

    private fun looksStructured(value: String): Boolean = value.trimStart().startsWith("{") ||
        Regex("(?i)[\"']?(reply|transcript|sessionTitle|memoryFacts)[\"']?\\s*[:=]").containsMatchIn(value)

    private fun unescape(value: String): String = value
        .replace("\\n", " ").replace("\\\"", "\"").replace("\\'", "'").replace("\\\\", "\\")

    private fun stripModelNoise(raw: String): String = raw.trim()
        .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
}
