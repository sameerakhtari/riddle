package com.sameerakhtari.riddle

import android.app.Activity
import android.os.Bundle
import android.text.Html
import android.widget.Button
import android.widget.TextView
import com.sameerakhtari.riddle.data.AppSettings

class GuideActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_guide)
        findViewById<TextView>(R.id.guideText).text =
            Html.fromHtml(GUIDE_HTML, Html.FROM_HTML_MODE_LEGACY)
        findViewById<Button>(R.id.closeGuideButton).setOnClickListener { finish() }
        AppSettings(this).guideSeen = true
    }

    companion object {
        private const val GUIDE_HTML = """
            <h1>Riddle Diary Guide</h1>

            <h2>Write and ask</h2>
            <p>Write naturally inside the parchment with the S Pen. Keep finger drawing disabled for palm rejection. Pause for the configured delay or tap the gold <b>Ask</b> button. The complete answer is generated first; only then does it appear slowly as settled fountain ink.</p>

            <h2>Conversations</h2>
            <p>The menu button opens <b>Conversations</b>. History is organized by complete chats instead of individual messages. Tap a conversation to read or continue it. Use the plus button to start a clean conversation. Older versions' saved pages are migrated into a <b>Previous pages</b> conversation.</p>

            <h2>Memory</h2>
            <p>Within a conversation, recent exchanges are included for follow-up questions. Optional cross-session memory can also use compact durable facts and selected recent context. Open <b>Settings → View and edit remembered facts</b> to inspect, edit, or clear this information. Disabling cross-session memory does not delete your conversation history.</p>

            <h2>Direct answers and custom instructions</h2>
            <p>Use <b>Settings → Answer format</b> to choose automatic, exact-value, concise, or briefly explained replies. <b>Attach or edit AI instructions</b> accepts typed text or an imported text, Markdown, or JSON instruction file. Instructions apply to all providers but cannot override accuracy, safety, or the required response format.</p>

            <h2>Diary voice</h2>
            <p>The answer wrapper is separate from the AI model. Choose an enchanted factual, direct, scholarly, or warm voice in Settings. The same wrapper is applied to on-device, cloud, private-backend, and LAN providers. Atmosphere never overrides factual accuracy, and the assistant does not impersonate a named fictional character.</p>

            <h2>AI provider choices</h2>
            <p><b>Writing only</b> sends nothing. <b>Private backend</b> keeps a billed API key off the phone. <b>Direct API</b> calls an OpenAI-compatible provider from the device. <b>Local server</b> uses a compatible vision server on your network. <b>On-device</b> runs a compatible LiteRT-LM model fully offline after download.</p>

            <h2>Finding provider models</h2>
            <p>For Direct API and Local Server, tap <b>Load available models</b>. The app queries the standard <code>/models</code> endpoint and lets you select an ID. A listed model is not automatically image-capable; select one that accepts image input.</p>

            <h2>Stability and large models</h2>
            <p>Compact models may prepare in the background when the diary opens. Large experimental models are never loaded automatically. On an S22 Ultra, use Gemma 4 E2B first; if a larger model is selected, CPU mode is safer and the model loads only after you tap Ask.</p>
            <p>The overflow button at the top right always stays visible. Tap it to show or hide controls; hold it to open Settings.</p>

            <h2>On-device model library</h2>
            <p>Open <b>Settings → Model library</b>. Search local entries, refresh the HTTPS catalog, add any compatible model URL, or import a downloaded <code>.litertlm</code> file. The app cannot guarantee every internet model is compatible. Handwritten pages require a LiteRT-LM package with vision support.</p>

            <h2>Background downloads and Files</h2>
            <p>Downloads use Android WorkManager and a foreground notification. They continue after the diary screen closes and resume from a partial file. Android may pause work until the selected Wi-Fi/network constraint is available. A folder button opens the visible directory you selected; the app also keeps a private runtime copy for reliable inference.</p>

            <h2>S Pen controls</h2>
            <p>While touching the page, hold the S Pen button for temporary erasing. While hovering, a short press performs the configured action. With hover gestures enabled, hold the button and move: <b>left undo</b>, <b>right redo</b>, <b>up Ask</b>, <b>down hide/show controls</b>. Gesture availability can vary by Samsung firmware.</p>

            <h2>Ink, recovery and errors</h2>
            <p>Pressure, writing speed, and stroke direction affect fountain-pen width. Every completed stroke is autosaved. Unfinished ink returns after reopening. The page clears only after a successful answer; errors leave your writing intact.</p>

            <h2>Diagnostics</h2>
            <p>Settings can keep a bounded local error log for model, download, and provider failures. Old entries are automatically truncated. You can view, export, clear, or disable logs, and keys/tokens are not deliberately written to them.</p>

            <h2>Privacy</h2>
            <p>Open the readable privacy policy from Settings. Writing-only and on-device modes keep page processing local. Other modes send the current page and relevant context to the endpoint you configured. Screenshots are allowed by default and can be blocked. Provider tokens are encrypted at rest with Android Keystore.</p>

            <h2>About .env</h2>
            <p>The Android app requires no <code>.env</code> file before building. All app configuration is available after installation. <code>backend/.env</code> is used only when you deploy the optional private backend.</p>
        """
    }
}
