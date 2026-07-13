package com.sameerakhtari.riddle.data

import android.content.Context
import org.json.JSONArray
import java.io.File

/**
 * Stores short, user-relevant facts suggested by the configured model. The store is local, bounded,
 * inspectable, and can be disabled or cleared from Settings. It never stores provider credentials.
 */
class MemoryStore(context: Context) {
    private val file = File(context.filesDir, "riddle/memory_facts.json")

    @Synchronized
    fun list(): List<String> {
        if (!file.isFile) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            buildList {
                for (index in 0 until array.length()) {
                    array.optString(index)
                        .replace(Regex("\\s+"), " ")
                        .trim()
                        .takeIf { it.length in 3..220 }
                        ?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun addAll(values: List<String>) {
        val clean = values.map {
            it.replace(Regex("\\s+"), " ").trim().take(220)
        }.filter { it.length >= 3 }
        if (clean.isEmpty()) return
        val merged = (list() + clean)
            .distinctBy { it.lowercase() }
            .takeLast(MAX_FACTS)
        write(merged)
    }

    @Synchronized
    fun replace(values: List<String>) {
        write(values.map { it.replace(Regex("\\s+"), " ").trim().take(220) }
            .filter { it.length >= 3 }
            .distinctBy { it.lowercase() }
            .takeLast(MAX_FACTS))
    }

    @Synchronized
    fun clear() {
        file.delete()
    }

    private fun write(values: List<String>) {
        file.parentFile?.mkdirs()
        val array = JSONArray()
        values.forEach(array::put)
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(array.toString())
        if (!temp.renameTo(file)) {
            file.writeText(array.toString())
            temp.delete()
        }
    }

    companion object {
        private const val MAX_FACTS = 48
    }
}
