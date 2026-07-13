package com.sameerakhtari.riddle

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.sameerakhtari.riddle.data.PageStore
import com.sameerakhtari.riddle.model.DiarySession
import java.util.Date

class ConversationHistoryActivity : Activity() {
    private lateinit var store: PageStore
    private lateinit var listView: ListView
    private var sessions: List<DiarySession> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_conversation_history)
        store = PageStore(this)
        listView = findViewById(R.id.sessionList)
        findViewById<Button>(R.id.newConversationButton).setOnClickListener {
            val session = store.newSession()
            returnSession(session.id)
        }
        findViewById<Button>(R.id.closeHistoryButton).setOnClickListener { finish() }
        listView.setOnItemClickListener { _, _, position, _ -> showSession(sessions[position]) }
        listView.setOnItemLongClickListener { _, _, position, _ ->
            confirmDelete(sessions[position])
            true
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        sessions = store.listSessions()
        findViewById<TextView>(R.id.emptyHistoryText).visibility =
            if (sessions.isEmpty()) View.VISIBLE else View.GONE
        listView.adapter = object : ArrayAdapter<DiarySession>(
            this,
            android.R.layout.simple_list_item_2,
            android.R.id.text1,
            sessions,
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                val session = getItem(position) ?: return view
                val title = view.findViewById<TextView>(android.R.id.text1)
                val subtitle = view.findViewById<TextView>(android.R.id.text2)
                title.text = session.title
                title.setTextColor(getColor(R.color.diary_text))
                title.textSize = 18f
                val date = DateFormat.getMediumDateFormat(this@ConversationHistoryActivity)
                    .format(Date(session.updatedAt))
                val count = store.sessionPages(session.id, Int.MAX_VALUE).size
                subtitle.text = "$date · $count ${if (count == 1) "exchange" else "exchanges"}\n${session.summary.take(100)}"
                subtitle.setTextColor(getColor(R.color.diary_muted))
                subtitle.maxLines = 3
                view.setBackgroundResource(R.drawable.settings_panel_background)
                view.setPadding(dp(14), dp(12), dp(14), dp(12))
                return view
            }
        }
    }

    private fun showSession(session: DiarySession) {
        val pages = store.sessionPages(session.id, 100)
        val transcript = buildString {
            if (pages.isEmpty()) append("This conversation has no completed exchanges yet.")
            pages.forEachIndexed { index, page ->
                if (index > 0) append("\n\n")
                append("YOU\n")
                append(page.transcript.ifBlank { "[Handwriting transcription unavailable]" })
                append("\n\nDIARY\n")
                append(page.reply.ifBlank { page.error.ifBlank { "[No answer saved]" } })
            }
        }
        val textView = TextView(this).apply {
            text = transcript
            setTextColor(getColor(R.color.privacy_text))
            setBackgroundColor(getColor(R.color.privacy_surface))
            textSize = 17f
            setTextIsSelectable(true)
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }
        AlertDialog.Builder(this)
            .setTitle(session.title)
            .setView(textView)
            .setNegativeButton("Close", null)
            .setNeutralButton("Delete") { _, _ -> confirmDelete(session) }
            .setPositiveButton("Continue") { _, _ -> returnSession(session.id) }
            .show()
    }

    private fun confirmDelete(session: DiarySession) {
        AlertDialog.Builder(this)
            .setTitle("Delete conversation?")
            .setMessage("All exchanges and page images in “${session.title}” will be permanently removed from this phone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                store.deleteSession(session)
                Toast.makeText(this, "Conversation deleted.", Toast.LENGTH_SHORT).show()
                refresh()
            }
            .show()
    }

    private fun returnSession(id: String) {
        store.setActiveSession(id)
        setResult(RESULT_OK, Intent().putExtra(EXTRA_SESSION_ID, id))
        finish()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_SESSION_ID = "session_id"
    }
}
