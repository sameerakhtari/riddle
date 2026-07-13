package com.sameerakhtari.riddle

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import com.sameerakhtari.riddle.data.MemoryStore

class MemoryActivity : Activity() {
    private lateinit var memoryStore: MemoryStore
    private lateinit var memoryEditor: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_memory)
        memoryStore = MemoryStore(this)
        memoryEditor = findViewById(R.id.memoryEditor)
        load()

        findViewById<Button>(R.id.saveMemoryButton).setOnClickListener {
            val facts = memoryEditor.text.toString().lineSequence()
                .map { it.trim().removePrefix("•").removePrefix("-").trim() }
                .filter(String::isNotBlank)
                .toList()
            memoryStore.replace(facts)
            Toast.makeText(this, "Memory saved locally.", Toast.LENGTH_SHORT).show()
            load()
        }
        findViewById<Button>(R.id.clearMemoryButton).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear remembered facts?")
                .setMessage("Conversation transcripts remain in History, but the compact cross-session memory list will be erased.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear") { _, _ ->
                    memoryStore.clear()
                    load()
                }
                .show()
        }
        findViewById<Button>(R.id.closeMemoryButton).setOnClickListener { finish() }
    }

    private fun load() {
        val facts = memoryStore.list()
        memoryEditor.setText(
            if (facts.isEmpty()) {
                ""
            } else {
                facts.joinToString("\n") { "• $it" }
            },
        )
        memoryEditor.hint = if (facts.isEmpty()) {
            "No durable facts are stored. Add one fact per line, or let the diary remember relevant facts after successful answers."
        } else {
            "One fact per line"
        }
    }
}
