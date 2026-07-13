package com.sameerakhtari.riddle.data

import com.sameerakhtari.riddle.model.DiaryPage

object MemoryExtractor {
    fun fromPages(pages: List<DiaryPage>): List<String> = pages
        .takeLast(20)
        .flatMap { fromTranscript(it.transcript) }
        .distinctBy(String::lowercase)
        .takeLast(32)

    fun fromTranscript(value: String): List<String> {
        val text = value.replace(Regex("\\s+"), " ").trim()
        if (text.isBlank()) return emptyList()
        val facts = mutableListOf<String>()

        Regex("(?i)\\b(?:my name is|i am called|call me)\\s+([\\p{L}][\\p{L}'-]*(?:\\s+[\\p{L}][\\p{L}'-]*){0,2})")
            .find(text)?.groupValues?.getOrNull(1)?.cleanValue()?.let { facts += "User's name is $it." }
        Regex("^\\s*I am\\s+([A-Z][\\p{L}'-]{1,30})[.!]?\\s*$")
            .find(text)?.groupValues?.getOrNull(1)?.cleanValue()?.let { facts += "User's name is $it." }
        Regex("(?i)\\b(?:i live in|i am from|i'm from|my city is|my country is)\\s+([^.!?]{2,70})")
            .find(text)?.groupValues?.getOrNull(1)?.cleanValue()?.let { facts += "User lives in or is from $it." }
        Regex("(?i)\\b(?:i prefer|i like|i love|my favourite is|my favorite is)\\s+([^.!?]{2,110})")
            .find(text)?.groupValues?.getOrNull(1)?.cleanValue()?.let { facts += "User prefers $it." }

        return facts.map { it.take(220) }.distinctBy(String::lowercase).take(8)
    }

    fun isMemoryQuestion(value: String): Boolean {
        val text = value.lowercase()
        return listOf(
            "do you remember", "remember me", "what do you remember", "what do you know about me",
            "who am i", "what is my name", "do you know me",
        ).any(text::contains)
    }

    fun isMemoryDenial(value: String): Boolean {
        val text = value.lowercase()
        return listOf(
            "do not possess personal memories", "don't possess personal memories", "i have no memory",
            "cannot remember you", "do not remember you", "no personal memory", "cannot retain memories",
        ).any(text::contains)
    }

    fun answerFromFacts(facts: List<String>): String {
        val human = facts.distinctBy(String::lowercase).take(4).map { fact ->
            fact.replace("User's name is", "Your name is")
                .replace("User lives in or is from", "You live in or are from")
                .replace("User prefers", "You prefer")
        }
        return if (human.isEmpty()) "I do not yet have a saved fact about you." else "Yes. ${human.joinToString(" ")}"
    }

    private fun String.cleanValue(): String = trim().trim('"', '\'', ',', ';', ':').replace(Regex("\\s+"), " ")
}
