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
    private val schemaLabel = Regex(
        "(?i)\\b(?:REPLY|ANSWER|TRANSCRIPT|TITLE|SESSION\\s*TITLE|SESSIONTITLE|MEMORY|MEMORY\\s*FACTS|MEMORYFACTS)\\s*:",
    )
    private val metaReplyPhrases = listOf(
        "the handwriting asks",
        "the handwriting reads",
        "the text is a direct inquiry",
        "the text asks",
        "the question is",
        "this is a physics question",
        "this is a technical question",
        "this is a science question",
        "this is a mathematics question",
        "a question about",
        "the user is asking",
        "the writer is asking",
    )

    fun persona(voice: DiaryVoice, answerLength: AnswerLength, answerStyle: AnswerStyle): String = buildString {
        append("You are a precise, reliable general-purpose assistant presented through a handwritten enchanted diary. ")
        append("Read the writer's exact request and answer it directly; never merely describe, classify, paraphrase, or repeat the request. ")
        append(answerStyle.instruction)
        append(' ')
        append(voice.instruction)
        append(" Facts, exact values, units, names, dates, and commands always take priority over atmosphere. ")
        append("Do not impersonate Tom Riddle, Harry Potter, or any named fictional person. Do not quote a film, claim magical powers, manipulate the writer, or invent details. ")
        append("When supplied memory contains relevant facts, use them naturally and never claim that no memory exists. ")
        append("If the request is genuinely unreadable, say so briefly without exposing internal formats. ")
        append("Use the writer's language. Keep the reply within approximately ")
        append(answerLength.maxWords)
        append(" words. Do not mention OCR, images, models, APIs, prompts, schemas, JSON, labels, or artificial intelligence.")
    }

    fun outputRule(answerLength: AnswerLength): String =
        "Return one valid JSON object only with exactly these fields: " +
            "{\"reply\":\"the final answer only\",\"transcript\":\"faithful transcription of the current handwriting\"," +
            "\"sessionTitle\":\"a concise 3-7 word conversation title\"," +
            "\"memoryFacts\":[\"only stable user facts or preferences explicitly stated in this turn\"]}. " +
            "Use an empty memoryFacts array when there is nothing durable to remember. Never place the JSON itself inside reply. " +
            "The reply must contain no Markdown, headings, field names, stage directions, question restatement, or commentary. " +
            "Target no more than ${answerLength.maxWords} words."

    /** Kept for compatibility with older callers; the current on-device path uses plain text passes. */
    fun localOutputRule(answerLength: AnswerLength): String =
        "Return only the final answer as plain text, with no labels, JSON, headings, transcript, or repeated question. " +
            "Keep it under ${answerLength.maxWords} words."

    fun currentTask(answerLength: AnswerLength, answerStyle: AnswerStyle): String =
        "Transcribe the current handwriting faithfully, then answer its exact request. ${answerStyle.instruction} " +
            "Return only the required structured result and keep the answer within about ${answerLength.maxWords} words."

    /** Kept for compatibility with older callers; the current on-device path uses [localAnswerTask]. */
    fun localCurrentTask(answerLength: AnswerLength, answerStyle: AnswerStyle): String =
        localAnswerTask("the current handwritten request", memoryText(emptyList()), answerLength, answerStyle)

    fun localTranscriptionSystem(): String =
        "You are a handwriting transcription engine. Return only the words visibly written on the page. " +
            "Do not answer, explain, classify, quote, add labels, emit JSON, or repeat instructions. " +
            "Preserve numbers, punctuation, units, names, and spelling. If it is genuinely unreadable, return exactly [UNREADABLE]."

    fun localTranscriptionTask(): String =
        "Transcribe the handwriting on this page exactly. Output only the transcription."

    fun localAnswerSystem(
        voice: DiaryVoice,
        answerLength: AnswerLength,
        answerStyle: AnswerStyle,
    ): String = persona(voice, answerLength, answerStyle) +
        " Return only the final answer text. Never output REPLY:, ANSWER:, TRANSCRIPT:, TITLE:, MEMORY:, JSON, or the original question."

    fun localAnswerTask(
        transcript: String,
        context: String,
        answerLength: AnswerLength,
        answerStyle: AnswerStyle,
    ): String = buildString {
        append("Writer's exact request:\n")
        append(transcript.take(3_000))
        append("\n\n")
        append(context)
        append("\n\n")
        append(answerStyle.instruction)
        append(" Return only the answer itself, under ")
        append(answerLength.maxWords)
        append(" words. Do not restate the request and do not add any label.")
    }

    fun localRepairTask(
        transcript: String,
        candidate: String,
        context: String,
        answerLength: AnswerLength,
        answerStyle: AnswerStyle,
    ): String = buildString {
        append("Answer the exact request below from scratch. The earlier candidate was unusable because it repeated labels, echoed the request, or described the question instead of answering it.\n\n")
        append("REQUEST:\n")
        append(transcript.take(3_000))
        append("\n\nEARLIER CANDIDATE TO REPLACE:\n")
        append(candidate.take(800))
        append("\n\n")
        append(context)
        append("\n\n")
        append(answerStyle.instruction)
        append(" Return only one clean final answer, with no labels, no JSON, no question restatement, and no commentary. Limit: ")
        append(answerLength.maxWords)
        append(" words.")
    }

    fun localVerificationTask(
        transcript: String,
        candidate: String,
        answerLength: AnswerLength,
        answerStyle: AnswerStyle,
    ): String = buildString {
        append("Fact-check the candidate answer against the exact request. Correct wrong numbers, units, dates, names, or assumptions. ")
        append("If it is already correct, keep it. ")
        append(answerStyle.instruction)
        append(" Return only the corrected final answer, with no explanation of the checking process and no labels.\n\n")
        append("REQUEST: ").append(transcript.take(2_000))
        append("\nCANDIDATE: ").append(candidate.take(700))
        append("\nLIMIT: ").append(answerLength.maxWords).append(" words.")
    }

    /** Compatibility wrapper used by older repair paths. */
    fun repairTask(
        transcript: String,
        answerLength: AnswerLength,
        answerStyle: AnswerStyle,
        context: String,
    ): String = localRepairTask(transcript, "", context, answerLength, answerStyle)

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
            return "There is no earlier context. Answer the current request by itself."
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
            val transcript = normalizeTranscript(json.optString("transcript"))
            val reply = parsePlainAnswer(json.optString("reply"), transcript, answerLength)
            if (reply.isNotBlank()) {
                return OracleResult(
                    reply = reply,
                    transcript = transcript,
                    sessionTitle = normalizeTitle(json.optString("sessionTitle")),
                    memoryFacts = parseFacts(json.optJSONArray("memoryFacts")),
                )
            }
        }

        parseLabelled(clean, answerLength)?.let { return sanitizeResultForDisplay(it, answerLength) }
        parseJsonish(clean, answerLength)?.let { return sanitizeResultForDisplay(it, answerLength) }

        val fallback = parsePlainAnswer(clean, "", answerLength)
        require(fallback.isNotBlank() && !looksStructured(clean)) {
            "The model returned malformed structured output. Please try once more."
        }
        return OracleResult(reply = fallback)
    }

    fun parsePlainTranscript(raw: String): String {
        val clean = stripModelNoise(raw)
        findJsonObject(clean)?.optString("transcript")?.let { jsonTranscript ->
            normalizeTranscript(jsonTranscript).takeIf { it.isNotBlank() }?.let { return it }
        }
        val labelled = labelledSections(clean)
            .firstOrNull { it.first == "TRANSCRIPT" }
            ?.second
        var candidate = labelled ?: clean
        candidate = candidate
            .replace(Regex("(?i)^\\s*(?:TRANSCRIPT|TEXT|HANDWRITING)\\s*:\\s*"), "")
            .substringBefore(Regex("(?i)\\b(?:REPLY|ANSWER|TITLE|MEMORY)\\s*:"))
            .replace(schemaLabel, " ")
            .replace(Regex("(?is)<think>.*?</think>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim().trim('"', '\'', '`')
        candidate = collapseImmediateRepetition(candidate)
        if (candidate.equals("[UNREADABLE]", true) || candidate.equals("UNREADABLE", true)) return ""
        if (looksStructured(candidate) || candidate.length < 2) return ""
        return candidate.take(3_000)
    }

    fun parsePlainAnswer(
        raw: String,
        transcript: String,
        answerLength: AnswerLength,
    ): String {
        val clean = stripModelNoise(raw)
        findJsonObject(clean)?.optString("reply")?.takeIf { it.isNotBlank() }?.let {
            return sanitizeAnswerCandidate(it, transcript, answerLength)
        }

        val labelledCandidates = labelledSections(clean)
            .filter { it.first == "REPLY" || it.first == "ANSWER" }
            .map { it.second }
        val rawCandidates = if (labelledCandidates.isNotEmpty()) labelledCandidates else listOf(clean)
        val candidates = rawCandidates
            .flatMap { splitRepeatedReplySegments(it) }
            .map { sanitizeAnswerCandidate(it, transcript, answerLength) }
            .filter(String::isNotBlank)
            .distinctBy { fingerprint(it) }

        return candidates.maxByOrNull { answerCandidateScore(it, transcript) }
            ?.takeIf { !isUnsafeVisibleReply(it, transcript) }
            .orEmpty()
    }

    fun sanitizeResultForDisplay(result: OracleResult, answerLength: AnswerLength): OracleResult {
        val transcript = normalizeTranscript(result.transcript)
        val reply = parsePlainAnswer(result.reply, transcript, answerLength)
        require(reply.isNotBlank() && !isUnsafeVisibleReply(reply, transcript)) {
            "The model produced an unusable repeated or structured reply. Please try again."
        }
        return result.copy(
            reply = reply,
            transcript = transcript,
            sessionTitle = normalizeTitle(result.sessionTitle).ifBlank { safeSessionTitle(transcript) },
            memoryFacts = result.memoryFacts
                .map { it.replace(Regex("\\s+"), " ").trim().take(220) }
                .filter { it.length >= 3 }
                .distinctBy(String::lowercase)
                .take(8),
        )
    }

    fun isUnsafeVisibleReply(reply: String, transcript: String = ""): Boolean {
        val clean = reply.trim()
        if (clean.isBlank()) return true
        if (schemaLabel.containsMatchIn(clean)) return true
        if (Regex("(?i)[\"']?(reply|transcript|sessionTitle|memoryFacts)[\"']?\\s*[:=]").containsMatchIn(clean)) return true
        if ((clean.count { it == '{' } + clean.count { it == '}' }) >= 2) return true
        if (metaReplyPhrases.any { clean.lowercase().contains(it) }) return true
        if (hasHeavyRepetition(clean)) return true
        val normalizedTranscript = fingerprint(transcript)
        val normalizedReply = fingerprint(clean)
        if (normalizedTranscript.length >= 8 && normalizedReply == normalizedTranscript) return true
        if (normalizedTranscript.length >= 12 && normalizedReply.startsWith(normalizedTranscript)) return true
        return false
    }

    fun needsRepair(result: OracleResult, stableFacts: List<String>): Boolean {
        val reply = result.reply.lowercase()
        val transcript = result.transcript.lowercase()
        val asksQuestion = transcript.contains('?') || listOf(
            "what ", "who ", "when ", "where ", "why ", "how ", "tell me", "give me", "speed of", "value of",
        ).any { transcript.startsWith(it) || transcript.contains(" $it") }
        val classificationOnly = metaReplyPhrases.any(reply::contains) && reply.split(Regex("\\s+")).size < 36
        val memoryDenial = stableFacts.any { !it.startsWith(INSTRUCTION_PREFIX) } && listOf(
            "do not possess personal memories", "don't possess personal memories", "i have no memory", "cannot remember you", "do not remember you",
        ).any(reply::contains)
        val unreadableButTranscriptExists = result.transcript.length >= 4 && listOf(
            "handwriting is unclear", "not legible", "cannot read the writing", "clearer inscription",
        ).any(reply::contains)
        return result.reply.isBlank() || isUnsafeVisibleReply(result.reply, result.transcript) ||
            (asksQuestion && classificationOnly) || memoryDenial || unreadableButTranscriptExists
    }

    fun shouldVerifyFactualAnswer(transcript: String, answer: String): Boolean {
        val request = transcript.lowercase()
        if (answer.isBlank()) return false
        if (Regex("\\d").containsMatchIn(answer)) return true
        return listOf(
            "speed of", "value of", "how many", "how much", "when was", "what year", "distance", "temperature",
            "voltage", "current", "frequency", "date", "capital of", "who invented", "who discovered",
        ).any(request::contains)
    }

    fun mergeRepair(original: OracleResult, repaired: OracleResult): OracleResult = OracleResult(
        reply = repaired.reply.ifBlank { original.reply },
        transcript = original.transcript.ifBlank { repaired.transcript },
        sessionTitle = original.sessionTitle.ifBlank { repaired.sessionTitle },
        memoryFacts = (original.memoryFacts + repaired.memoryFacts).distinctBy(String::lowercase).take(8),
    )

    fun normalizeReply(value: String, answerLength: AnswerLength): String =
        parsePlainAnswer(value, "", answerLength)

    fun safeSessionTitle(transcript: String): String {
        val words = transcript
            .replace(Regex("[^\\p{L}\\p{N}'-]+"), " ")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .take(6)
        return if (words.isEmpty()) "Diary conversation" else words.joinToString(" ").take(72)
    }

    fun localFallbackReply(transcript: String, stableFacts: List<String>): String {
        val lower = transcript.lowercase()
        if (listOf("who are you", "what are you").any(lower::contains)) {
            return "I am the assistant within this diary."
        }
        val savedName = stableFacts.firstOrNull { it.startsWith("User's name is", ignoreCase = true) }
            ?.removePrefix("User's name is")?.trim()?.trimEnd('.')
        if (savedName != null && listOf("what is my name", "who am i", "remember my name").any(lower::contains)) {
            return "Your name is $savedName."
        }
        val statedName = Regex("(?i)\\b(?:my name is|i am called|call me|i am)\\s+([\\p{L}][\\p{L}'-]{1,40})")
            .find(transcript)?.groupValues?.getOrNull(1)?.trim()
        if (!statedName.isNullOrBlank() && !transcript.contains('?')) {
            return "I’ll remember that your name is $statedName."
        }
        return "I could not produce a reliable answer. Please try again or choose a larger model."
    }

    private fun parseLabelled(value: String, answerLength: AnswerLength): OracleResult? {
        val sections = labelledSections(value)
        val transcript = sections.firstOrNull { it.first == "TRANSCRIPT" }?.second.orEmpty()
        val replyRaw = sections.firstOrNull { it.first == "REPLY" || it.first == "ANSWER" }?.second.orEmpty()
        val reply = parsePlainAnswer(replyRaw, transcript, answerLength)
        if (reply.isBlank()) return null
        val title = sections.firstOrNull { it.first == "TITLE" || it.first == "SESSIONTITLE" }?.second.orEmpty()
        val memory = sections.firstOrNull { it.first == "MEMORY" || it.first == "MEMORYFACTS" }?.second.orEmpty()
        return OracleResult(
            reply = reply,
            transcript = normalizeTranscript(transcript),
            sessionTitle = normalizeTitle(title),
            memoryFacts = parseFactText(memory),
        )
    }

    private fun parseJsonish(value: String, answerLength: AnswerLength): OracleResult? {
        val transcript = unescape(jsonishField(value, "transcript", listOf("sessionTitle", "memoryFacts")))
        val reply = parsePlainAnswer(
            unescape(jsonishField(value, "reply", listOf("transcript", "sessionTitle", "memoryFacts"))),
            transcript,
            answerLength,
        )
        if (reply.isBlank()) return null
        return OracleResult(
            reply = reply,
            transcript = normalizeTranscript(transcript),
            sessionTitle = normalizeTitle(unescape(jsonishField(value, "sessionTitle", listOf("memoryFacts")))),
            memoryFacts = parseFactText(unescape(jsonishField(value, "memoryFacts", emptyList()))),
        )
    }

    private fun labelledSections(value: String): List<Pair<String, String>> {
        val matches = schemaLabel.findAll(value).toList()
        if (matches.isEmpty()) return emptyList()
        return matches.mapIndexed { index, match ->
            val rawLabel = match.value.substringBefore(':').replace(Regex("\\s+"), "").uppercase(Locale.US)
            val label = when (rawLabel) {
                "ANSWER" -> "ANSWER"
                "SESSIONTITLE" -> "SESSIONTITLE"
                "MEMORYFACTS" -> "MEMORYFACTS"
                else -> rawLabel
            }
            val start = match.range.last + 1
            val end = matches.getOrNull(index + 1)?.range?.first ?: value.length
            label to value.substring(start, end).trim()
        }
    }

    private fun splitRepeatedReplySegments(value: String): List<String> {
        val matches = Regex("(?i)\\b(?:REPLY|ANSWER)\\s*:").findAll(value).toList()
        if (matches.isEmpty()) return listOf(value)
        return buildList {
            val prefix = value.substring(0, matches.first().range.first).trim()
            if (prefix.isNotBlank()) add(prefix)
            matches.forEachIndexed { index, match ->
                val start = match.range.last + 1
                val end = matches.getOrNull(index + 1)?.range?.first ?: value.length
                value.substring(start, end).trim().takeIf(String::isNotBlank)?.let(::add)
            }
        }
    }

    private fun sanitizeAnswerCandidate(value: String, transcript: String, answerLength: AnswerLength): String {
        var cleaned = value
            .replace(Regex("(?is)<think>.*?</think>"), " ")
            .replace(Regex("(?is)<\\|channel\\|>.*?<\\|end\\|>"), " ")
            .replace(Regex("(?is)```(?:json)?"), " ")
            .replace(schemaLabel, " ")
            .replace(Regex("(?i)[\"']?(sessionTitle|memoryFacts|transcript)[\"']?\\s*[:=].*$"), " ")
            .replace(Regex("\\s+"), " ")
            .trim().trim('"', '\'', ',', '{', '}', '[', ']', '`')
        if (cleaned.isBlank()) return ""

        val transcriptClean = normalizeTranscript(transcript)
        if (transcriptClean.isNotBlank()) {
            cleaned = cleaned.removePrefixIgnoringCase(transcriptClean).trimStart(' ', '-', '—', ':', ';')
        }
        cleaned = collapseImmediateRepetition(cleaned)
        cleaned = collapseRepeatedSentences(cleaned)
        cleaned = cleaned.replace(Regex("(?i)^\\s*(?:the answer is|answer)\\s*[:,-]?\\s*"), "")
            .replace(Regex("\\s+"), " ")
            .trim().trim('"', '\'', ',', '{', '}', '[', ']')
        if (cleaned.isBlank()) return ""

        val words = cleaned.split(Regex("\\s+")).filter(String::isNotBlank)
        val allowance = (answerLength.maxWords * 1.25f).toInt().coerceAtLeast(answerLength.maxWords)
        return (if (words.size > allowance) {
            words.take(allowance).joinToString(" ").trimEnd(',', ';', ':') + "…"
        } else cleaned).take(900).trim()
    }

    private fun answerCandidateScore(candidate: String, transcript: String): Int {
        var score = 100
        val lower = candidate.lowercase()
        if (metaReplyPhrases.any(lower::contains)) score -= 120
        if (schemaLabel.containsMatchIn(candidate)) score -= 150
        if (hasHeavyRepetition(candidate)) score -= 100
        val replyFingerprint = fingerprint(candidate)
        val transcriptFingerprint = fingerprint(transcript)
        if (transcriptFingerprint.length >= 8 && replyFingerprint == transcriptFingerprint) score -= 120
        if (candidate.length in 2..260) score += 12
        if (candidate.contains(Regex("[.!?]$"))) score += 2
        return score
    }

    private fun collapseImmediateRepetition(value: String): String {
        val tokens = value.split(Regex("\\s+")).filter(String::isNotBlank)
        if (tokens.size < 2) return value.trim()
        val result = mutableListOf<String>()
        for (token in tokens) {
            val normalized = token.lowercase().trim { !it.isLetterOrDigit() }
            val previous = result.lastOrNull()?.lowercase()?.trim { !it.isLetterOrDigit() }
            if (normalized.isNotBlank() && normalized == previous) continue
            result += token
        }
        var collapsed = result.joinToString(" ")
        for (window in 2..12) {
            val words = collapsed.split(Regex("\\s+")).filter(String::isNotBlank)
            if (words.size < window * 2) continue
            val output = mutableListOf<String>()
            var index = 0
            while (index < words.size) {
                if (index + window * 2 <= words.size) {
                    val first = words.subList(index, index + window).joinToString(" ") { fingerprint(it) }
                    val second = words.subList(index + window, index + window * 2).joinToString(" ") { fingerprint(it) }
                    if (first == second) {
                        output += words.subList(index, index + window)
                        index += window * 2
                        while (index + window <= words.size &&
                            words.subList(index, index + window).joinToString(" ") { fingerprint(it) } == first
                        ) index += window
                        continue
                    }
                }
                output += words[index]
                index++
            }
            collapsed = output.joinToString(" ")
        }
        return collapsed.trim()
    }

    private fun collapseRepeatedSentences(value: String): String {
        val units = value.split(Regex("(?<=[.!?])\\s+|\\s*[|•]\\s*"))
            .map(String::trim)
            .filter(String::isNotBlank)
        if (units.size <= 1) return value.trim()
        val seen = mutableSetOf<String>()
        return units.filter { seen.add(fingerprint(it)) }.joinToString(" ").trim()
    }

    private fun hasHeavyRepetition(value: String): Boolean {
        val tokens = value.lowercase().split(Regex("\\W+")).filter { it.length >= 2 }
        if (tokens.size < 6) return false
        val uniqueRatio = tokens.distinct().size.toDouble() / tokens.size
        if (uniqueRatio < 0.42) return true
        val replyMarkers = Regex("(?i)\\b(?:reply|answer)\\s*:").findAll(value).count()
        if (replyMarkers >= 2) return true
        return false
    }

    private fun String.removePrefixIgnoringCase(prefix: String): String =
        if (startsWith(prefix, ignoreCase = true)) substring(prefix.length) else this

    private fun fingerprint(value: String): String = value.lowercase(Locale.US)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

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

    private fun normalizeTranscript(value: String): String = value
        .replace(schemaLabel, " ")
        .replace(Regex("\\s+"), " ")
        .trim().take(3_000)

    private fun normalizeTitle(value: String): String = value
        .replace(schemaLabel, " ")
        .replace(Regex("\\s+"), " ")
        .trim().trim('"', '\'', '.', ':').take(72)

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
        if (start >= 0 && end > start) {
            return runCatching { JSONObject(value.substring(start, end + 1)) }.getOrNull()
        }
        return null
    }

    private fun looksStructured(value: String): Boolean = value.trimStart().startsWith("{") ||
        Regex("(?i)[\"']?(reply|transcript|sessionTitle|memoryFacts)[\"']?\\s*[:=]").containsMatchIn(value)

    private fun unescape(value: String): String = value
        .replace("\\n", " ").replace("\\\"", "\"").replace("\\'", "'").replace("\\\\", "\\")

    private fun stripModelNoise(raw: String): String = raw.trim()
        .replace(Regex("(?is)^```(?:json)?\\s*"), "")
        .replace(Regex("(?is)\\s*```$"), "")
        .replace(Regex("(?is)<think>.*?</think>"), " ")
        .replace(Regex("(?is)<\\|channel\\|>.*?<\\|end\\|>"), " ")
        .trim()
}
