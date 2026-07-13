from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(relative_path: str, old: str, new: str) -> None:
    path = ROOT / relative_path
    text = path.read_text()
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"Expected exactly one match in {relative_path}, found {count}: {old[:140]!r}")
    path.write_text(text.replace(old, new, 1))


main = "android/app/src/main/java/com/sameerakhtari/riddle/MainActivity.kt"
canvas = "android/app/src/main/java/com/sameerakhtari/riddle/ui/DiaryCanvasView.kt"

replace_once(
    main,
    '''    private val autoAskRunnable = Runnable { askDiary() }
    private var chromeVisible = true
''',
    '''    private val autoAskRunnable = Runnable { askDiary() }
    private val draftSaveRunnable = Runnable { flushPendingDraft() }
    private var pendingDraftStrokes: List<Stroke>? = null
    private var pendingDraftSessionId: String = ""
    private var chromeVisible = true
''',
)

replace_once(
    main,
    '''    override fun onDestroy() {
        AppLog.i("Main", "MainActivity destroyed; changingConfigurations=$isChangingConfigurations")
        cancelAutoAsk()
        executor.shutdown()
        super.onDestroy()
    }
''',
    '''    override fun onPause() {
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
''',
)

replace_once(
    main,
    '''    private fun persistDraft(strokes: List<Stroke>) {
        val sessionId = pageStore.activeSession().id
        executor.execute {
            runCatching {
                if (strokes.isEmpty()) pageStore.clearDraft()
                else pageStore.saveDraft(strokes, sessionId)
            }.onFailure { AppLog.e("Draft", "Could not save unfinished ink", it) }
        }
    }
''',
    '''    private fun persistDraft(strokes: List<Stroke>) {
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
''',
)

replace_once(
    main,
    '''        val strokes = canvas.snapshotStrokes()
        val userInkBottom = canvas.inkBottomFraction()
''',
    '''        mainHandler.removeCallbacks(draftSaveRunnable)
        pendingDraftStrokes = null
        val strokes = canvas.snapshotStrokes()
        val userInkBottom = canvas.inkBottomFraction()
''',
)

replace_once(
    main,
    '''                val finalResult = if (
                    MemoryExtractor.isMemoryQuestion(result.transcript) && knownFacts.isNotEmpty() &&
                    MemoryExtractor.isMemoryDenial(result.reply)
                ) {
                    result.copy(reply = MemoryExtractor.answerFromFacts(knownFacts))
                } else result
''',
    '''                val transcriptLower = result.transcript.lowercase()
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
''',
)

replace_once(
    main,
    '''    companion object {
        private const val REQUEST_HISTORY = 8104
    }
''',
    '''    companion object {
        private const val REQUEST_HISTORY = 8104
        private const val DRAFT_SAVE_DEBOUNCE_MS = 420L
    }
''',
)

replace_once(
    canvas,
    '''    fun snapshotStrokes(): List<Stroke> = strokes.map { it.deepCopy() }
''',
    '''    /** Completed strokes are immutable after pen-up; a shallow list snapshot avoids O(n) deep copies per stroke. */
    fun snapshotStrokes(): List<Stroke> = strokes.toList()
''',
)

print("Final memory and long-page performance polish applied.")
