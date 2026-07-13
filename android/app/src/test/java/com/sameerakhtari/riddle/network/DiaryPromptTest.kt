package com.sameerakhtari.riddle.network

import com.sameerakhtari.riddle.data.AnswerLength
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiaryPromptTest {
    @Test
    fun repeatedSchemaEchoIsRejected() {
        val raw = "Who are you? REPLY: The handwriting asks the question Who are you? " +
            "REPLY: The text is a direct inquiry about the identity of the recipient. " +
            "REPLY: The question is Who are you?"

        val answer = DiaryPrompt.parsePlainAnswer(raw, "Who are you?", AnswerLength.BRIEF)

        assertEquals("", answer)
    }

    @Test
    fun repeatedFactualAnswerCollapsesToOneCopy() {
        val raw = "REPLY: The speed of sound is approximately 343 m/s in dry air at 20 °C. " +
            "REPLY: The speed of sound is approximately 343 m/s in dry air at 20 °C. " +
            "REPLY: The speed of sound is approximately 343 m/s in dry air at 20 °C."

        val answer = DiaryPrompt.parsePlainAnswer(raw, "What is the speed of sound?", AnswerLength.BRIEF)

        assertEquals("The speed of sound is approximately 343 m/s in dry air at 20 °C.", answer)
        assertFalse(answer.contains("REPLY:", ignoreCase = true))
    }

    @Test
    fun plainValueRemainsUntouched() {
        val answer = DiaryPrompt.parsePlainAnswer(
            "299,792,458 m/s",
            "What is the speed of light?",
            AnswerLength.BRIEF,
        )

        assertEquals("299,792,458 m/s", answer)
    }

    @Test
    fun transcriptParserStopsBeforeAnswerLabels() {
        val transcript = DiaryPrompt.parsePlainTranscript(
            "TRANSCRIPT: Who are you? REPLY: I am the assistant within this diary.",
        )

        assertEquals("Who are you?", transcript)
    }

    @Test
    fun visibleReplySafetyRejectsMetadata() {
        assertTrue(
            DiaryPrompt.isUnsafeVisibleReply(
                "REPLY: Hello TRANSCRIPT: Who are you?",
                "Who are you?",
            ),
        )
        assertFalse(
            DiaryPrompt.isUnsafeVisibleReply(
                "I am the assistant within this diary.",
                "Who are you?",
            ),
        )
    }
}
