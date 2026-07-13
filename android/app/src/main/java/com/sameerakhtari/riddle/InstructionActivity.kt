package com.sameerakhtari.riddle

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.sameerakhtari.riddle.data.AppSettings

class InstructionActivity : Activity() {
    private lateinit var settings: AppSettings
    private lateinit var input: EditText
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AppSettings(this)
        setContentView(R.layout.activity_instructions)
        input = findViewById(R.id.customInstructionsInput)
        status = findViewById(R.id.instructionStatusText)
        input.setText(settings.customInstructions)
        updateStatus()
        findViewById<Button>(R.id.importInstructionsButton).setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/plain", "text/markdown", "application/json"))
            }, REQUEST_IMPORT)
        }
        findViewById<Button>(R.id.resetInstructionsButton).setOnClickListener {
            input.setText(DEFAULT_TEMPLATE)
            updateStatus()
        }
        findViewById<Button>(R.id.clearInstructionsButton).setOnClickListener {
            input.setText("")
            updateStatus()
        }
        findViewById<Button>(R.id.saveInstructionsButton).setOnClickListener {
            settings.customInstructions = input.text.toString()
            toast("Custom instructions saved locally.")
            finish()
        }
        findViewById<Button>(R.id.cancelInstructionsButton).setOnClickListener { finish() }
    }

    @Deprecated("Activity result API retained for minSdk compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_IMPORT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error("Could not read the selected file.")
        }.onSuccess { text ->
            input.setText(text.take(AppSettings.MAX_CUSTOM_INSTRUCTIONS))
            updateStatus()
            toast("Instructions imported. Review and save them.")
        }.onFailure { toast(it.message ?: "Could not import instructions.") }
    }

    private fun updateStatus() {
        status.text = "${input.text.length.coerceAtMost(AppSettings.MAX_CUSTOM_INSTRUCTIONS)} / ${AppSettings.MAX_CUSTOM_INSTRUCTIONS} characters. These instructions apply to every provider but cannot override factual accuracy, safety, or the response format."
    }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        private const val REQUEST_IMPORT = 7301
        private const val DEFAULT_TEMPLATE = "Answer the exact request directly. For simple factual questions, give the shortest complete answer. Preserve exact numbers, units, names, dates, and commands."
    }
}
