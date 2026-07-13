package com.sameerakhtari.riddle.data

import android.content.Context
import com.sameerakhtari.riddle.model.DiaryPage
import com.sameerakhtari.riddle.model.DiarySession
import com.sameerakhtari.riddle.model.DraftState
import com.sameerakhtari.riddle.model.Stroke
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class PageStore(context: Context) {
    private val root = File(context.filesDir, "riddle")
    private val pagesDir = File(root, "pages")
    private val imagesDir = File(root, "images")
    private val sessionsDir = File(root, "sessions")
    private val draftFile = File(root, "draft.json")
    private val activeSessionFile = File(root, "active_session.txt")

    init {
        pagesDir.mkdirs()
        imagesDir.mkdirs()
        sessionsDir.mkdirs()
        migrateLegacyPages()
        activeSession()
    }

    @Synchronized
    fun saveDraft(strokes: List<Stroke>, sessionId: String = activeSession().id) {
        val array = JSONArray()
        strokes.forEach { array.put(it.toJson()) }
        atomicWrite(
            draftFile,
            JSONObject()
                .put("sessionId", sessionId)
                .put("strokes", array)
                .toString(),
        )
    }

    @Synchronized
    fun loadDraftState(): DraftState {
        if (!draftFile.isFile) return DraftState(activeSession().id, emptyList())
        return runCatching {
            val json = JSONObject(draftFile.readText())
            val array = json.optJSONArray("strokes") ?: JSONArray()
            val strokes = buildList {
                for (index in 0 until array.length()) {
                    add(Stroke.fromJson(array.getJSONObject(index)))
                }
            }
            val sessionId = json.optString("sessionId").ifBlank { activeSession().id }
            if (loadSession(sessionId) != null) setActiveSession(sessionId)
            DraftState(sessionId, strokes)
        }.getOrDefault(DraftState(activeSession().id, emptyList()))
    }

    /** Compatibility helper used by older call sites. */
    @Synchronized
    fun loadDraft(): List<Stroke> = loadDraftState().strokes

    @Synchronized
    fun clearDraft() {
        draftFile.delete()
    }

    @Synchronized
    fun activeSession(): DiarySession {
        val storedId = activeSessionFile.takeIf(File::isFile)?.readText()?.trim().orEmpty()
        loadSession(storedId)?.let { return it }
        return newSession(clearDraft = false)
    }

    @Synchronized
    fun newSession(clearDraft: Boolean = true): DiarySession {
        val session = DiarySession.new()
        writeSession(session)
        setActiveSession(session.id)
        if (clearDraft) clearDraft()
        return session
    }

    @Synchronized
    fun setActiveSession(id: String): DiarySession {
        val session = loadSession(id) ?: error("Conversation not found.")
        atomicWrite(activeSessionFile, session.id)
        return session
    }

    @Synchronized
    fun listSessions(limit: Int = 200): List<DiarySession> = sessionsDir
        .listFiles { file -> file.extension == "json" }
        .orEmpty()
        .mapNotNull { file ->
            runCatching { DiarySession.fromJson(JSONObject(file.readText())) }.getOrNull()
        }
        .sortedByDescending { it.updatedAt }
        .take(limit)

    @Synchronized
    fun loadSession(id: String): DiarySession? {
        if (id.isBlank()) return null
        val file = File(sessionsDir, "$id.json")
        if (!file.isFile) return null
        return runCatching { DiarySession.fromJson(JSONObject(file.readText())) }.getOrNull()
    }

    @Synchronized
    fun renameSession(id: String, title: String): DiarySession? {
        val existing = loadSession(id) ?: return null
        val clean = title.replace(Regex("\\s+"), " ").trim().take(72)
        val updated = existing.copy(
            title = clean.ifBlank { "New conversation" },
            updatedAt = System.currentTimeMillis(),
        )
        writeSession(updated)
        return updated
    }

    @Synchronized
    fun createPage(strokes: List<Stroke>, pngBytes: ByteArray): DiaryPage {
        val session = activeSession()
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val imageName = "$id.png"
        atomicWriteBytes(File(imagesDir, imageName), pngBytes)
        val page = DiaryPage(
            id = id,
            sessionId = session.id,
            createdAt = now,
            updatedAt = now,
            imageFileName = imageName,
            strokes = strokes.map { it.deepCopy() },
        )
        writePage(page)
        writeSession(
            session.copy(
                updatedAt = now,
                pageIds = (session.pageIds + page.id).distinct(),
            ),
        )
        return page
    }

    @Synchronized
    fun updateResult(
        id: String,
        transcript: String,
        reply: String,
        suggestedTitle: String = "",
    ): DiaryPage? {
        val existing = loadPage(id) ?: return null
        val now = System.currentTimeMillis()
        val updated = existing.copy(
            updatedAt = now,
            transcript = transcript,
            reply = reply,
            error = "",
        )
        writePage(updated)
        val session = loadSession(existing.sessionId)
        if (session != null) {
            val generatedTitle = suggestedTitle
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(64)
                .ifBlank { transcript.replace(Regex("\\s+"), " ").trim().take(48) }
                .ifBlank { session.title }
            val title = if (session.title == "New conversation" || suggestedTitle.isNotBlank()) {
                generatedTitle
            } else {
                session.title
            }
            writeSession(
                session.copy(
                    updatedAt = now,
                    title = title.ifBlank { "New conversation" },
                    summary = reply.replace(Regex("\\s+"), " ").trim().take(280),
                    pageIds = (session.pageIds + id).distinct(),
                ),
            )
        }
        return updated
    }

    @Synchronized
    fun updateError(id: String, error: String): DiaryPage? {
        val existing = loadPage(id) ?: return null
        val updated = existing.copy(
            updatedAt = System.currentTimeMillis(),
            error = error,
        )
        writePage(updated)
        return updated
    }

    @Synchronized
    fun loadPage(id: String): DiaryPage? {
        val file = File(pagesDir, "$id.json")
        if (!file.isFile) return null
        return runCatching { DiaryPage.fromJson(JSONObject(file.readText())) }.getOrNull()
    }

    @Synchronized
    fun listPages(limit: Int = 100): List<DiaryPage> = pagesDir
        .listFiles { file -> file.extension == "json" }
        .orEmpty()
        .mapNotNull { file ->
            runCatching { DiaryPage.fromJson(JSONObject(file.readText())) }.getOrNull()
        }
        .sortedByDescending { it.createdAt }
        .take(limit)

    @Synchronized
    fun sessionPages(sessionId: String, limit: Int = 100): List<DiaryPage> {
        val session = loadSession(sessionId)
        val byId = session?.pageIds.orEmpty().mapNotNull(::loadPage)
        val discovered = listPages(Int.MAX_VALUE).filter { it.sessionId == sessionId }
        return (byId + discovered)
            .distinctBy { it.id }
            .sortedBy { it.createdAt }
            .takeLast(limit)
    }

    /**
     * Context is deliberately bounded so small phone models remain responsive. Current-conversation
     * turns are preferred; one closing turn from recent conversations may be included when enabled.
     */
    @Synchronized
    fun memoryPages(
        sessionId: String = activeSession().id,
        maxCurrentTurns: Int = 10,
        includeCrossSession: Boolean = true,
    ): List<DiaryPage> {
        val current = sessionPages(sessionId, maxCurrentTurns)
            .filter { it.transcript.isNotBlank() || it.reply.isNotBlank() }
        if (!includeCrossSession) return current
        val older = listSessions(limit = 8)
            .filterNot { it.id == sessionId }
            .mapNotNull { session ->
                sessionPages(session.id, 1).lastOrNull {
                    it.transcript.isNotBlank() || it.reply.isNotBlank()
                }
            }
            .take(4)
        return (older + current).distinctBy { it.id }
    }

    fun imageFile(page: DiaryPage): File = File(imagesDir, page.imageFileName)

    @Synchronized
    fun deletePage(page: DiaryPage) {
        File(pagesDir, "${page.id}.json").delete()
        imageFile(page).delete()
        loadSession(page.sessionId)?.let { session ->
            writeSession(
                session.copy(
                    updatedAt = System.currentTimeMillis(),
                    pageIds = session.pageIds.filterNot { it == page.id },
                ),
            )
        }
    }

    @Synchronized
    fun deleteSession(session: DiarySession) {
        sessionPages(session.id, Int.MAX_VALUE).forEach { page ->
            File(pagesDir, "${page.id}.json").delete()
            imageFile(page).delete()
        }
        File(sessionsDir, "${session.id}.json").delete()
        if (activeSessionFile.takeIf(File::isFile)?.readText()?.trim() == session.id) {
            activeSessionFile.delete()
            clearDraft()
            activeSession()
        }
    }

    private fun writePage(page: DiaryPage) {
        atomicWrite(File(pagesDir, "${page.id}.json"), page.toJson().toString())
    }

    private fun writeSession(session: DiarySession) {
        atomicWrite(File(sessionsDir, "${session.id}.json"), session.toJson().toString())
    }

    private fun migrateLegacyPages() {
        val pages = pagesDir
            .listFiles { file -> file.extension == "json" }
            .orEmpty()
            .mapNotNull { file ->
                runCatching { DiaryPage.fromJson(JSONObject(file.readText())) }.getOrNull()
            }
        val legacy = pages.filter { it.sessionId.isBlank() }
        if (legacy.isEmpty()) return
        val id = "legacy-pages"
        val first = legacy.minOfOrNull { it.createdAt } ?: System.currentTimeMillis()
        val last = legacy.maxOfOrNull { it.updatedAt } ?: first
        legacy.forEach { page -> writePage(page.copy(sessionId = id)) }
        writeSession(
            DiarySession(
                id = id,
                createdAt = first,
                updatedAt = last,
                title = "Previous pages",
                summary = legacy.lastOrNull()?.reply.orEmpty().take(280),
                pageIds = legacy.sortedBy { it.createdAt }.map { it.id },
            ),
        )
    }

    private fun atomicWrite(file: File, text: String) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(text)
        if (!temporary.renameTo(file)) {
            file.writeText(text)
            temporary.delete()
        }
    }

    private fun atomicWriteBytes(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeBytes(bytes)
        if (!temporary.renameTo(file)) {
            file.writeBytes(bytes)
            temporary.delete()
        }
    }
}
