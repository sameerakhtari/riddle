package com.sameerakhtari.riddle

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.Html
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.sameerakhtari.riddle.data.AppSettings
import com.sameerakhtari.riddle.data.MemoryStore
import com.sameerakhtari.riddle.data.PageStore
import com.sameerakhtari.riddle.logging.AppLog

class PrivacyActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_privacy)
        findViewById<TextView>(R.id.privacyText).text =
            Html.fromHtml(POLICY_HTML, Html.FROM_HTML_MODE_LEGACY)
        findViewById<Button>(R.id.closePrivacyButton).setOnClickListener { finish() }
        findViewById<Button>(R.id.deleteDiaryDataButton).setOnClickListener { confirmDeleteData() }
        AppSettings(this).privacySeen = true
    }

    private fun confirmDeleteData() {
        AlertDialog.Builder(this)
            .setTitle("Delete local diary data?")
            .setMessage(
                "This permanently removes conversations, page images, unfinished writing, remembered facts, " +
                    "and diagnostic logs. Downloaded AI model files and provider settings are kept.",
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                val store = PageStore(this)
                store.listSessions(Int.MAX_VALUE).forEach(store::deleteSession)
                MemoryStore(this).clear()
                AppLog.clear()
                Toast.makeText(this, "Local diary data deleted.", Toast.LENGTH_LONG).show()
            }
            .show()
    }

    companion object {
        private const val POLICY_HTML = """
            <h1>Privacy Policy</h1>
            <p><b>Effective date: 13 July 2026</b></p>
            <p>Riddle Diary is designed to keep you in control of handwritten pages, conversations, model files, and provider credentials.</p>

            <h2>Information stored on this device</h2>
            <p>The app can store unfinished S Pen strokes, rendered page images, conversation transcripts, AI replies, conversation titles, compact memory facts, settings, downloaded model files, and bounded diagnostic logs. These files remain in the app's private storage unless you explicitly export a log, select a visible model folder, or uninstall/delete data.</p>

            <h2>When data leaves the device</h2>
            <p><b>Writing only</b> and <b>on-device model</b> modes do not send diary pages to an AI provider. In Private Backend, Direct API, or Local Server mode, the current page image and relevant conversation context are sent to the endpoint you configured so it can generate an answer. That provider's own privacy terms apply. Riddle Diary does not silently choose an endpoint.</p>

            <h2>Credentials</h2>
            <p>Direct API keys, backend tokens, local-server tokens, and Hugging Face tokens are encrypted at rest with Android Keystore. A rooted or compromised device can weaken mobile security, so a private backend is recommended for keys with significant billing access.</p>

            <h2>Memory and conversation history</h2>
            <p>History is grouped into conversations. Cross-session memory is optional, visible, editable, and removable under Settings. When enabled, relevant stored facts and prior exchanges may be included with the current request. You can disable cross-session memory without deleting conversation history.</p>

            <h2>Diagnostics</h2>
            <p>Diagnostic logging is local, optional, and automatically truncated at the size you select. The app does not deliberately write API keys or tokens to logs. You can view, export, clear, or disable logs at any time.</p>

            <h2>Screenshots and recordings</h2>
            <p>Screenshots and screen recording are allowed by default. The privacy toggle can enable Android's secure-window protection for diary and settings screens.</p>

            <h2>Advertising and sale of data</h2>
            <p>The app contains no advertising SDK and does not sell personal data. A future Play Store release must update this policy before adding analytics, advertising, accounts, cloud sync, or any materially different data handling.</p>

            <h2>Retention and deletion</h2>
            <p>Local data remains until you delete a conversation, clear memory/logs, use the delete button on this page, clear app storage, or uninstall the app. Downloaded model files can be removed from Model Library.</p>

            <h2>Permissions</h2>
            <p>Internet access is used only for configured AI endpoints, model catalogs, and model downloads. Notification permission may be used for background download progress. File access uses Android's document picker and only the folders/files you select.</p>

            <h2>Contact and publication</h2>
            <p>Before public Play Store publication, replace this paragraph with a valid support email or website and publish the same policy at a stable public URL. Also complete the Play Console Data safety form so it matches the providers and optional features offered in the release.</p>
        """
    }
}
