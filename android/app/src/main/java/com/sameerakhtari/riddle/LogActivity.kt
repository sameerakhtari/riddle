package com.sameerakhtari.riddle

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.sameerakhtari.riddle.logging.AppLog

class LogActivity : Activity() {
    private lateinit var logText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_logs)
        logText = findViewById(R.id.logText)
        findViewById<Button>(R.id.refreshLogsButton).setOnClickListener { refresh() }
        findViewById<Button>(R.id.clearLogsButton).setOnClickListener { confirmClear() }
        findViewById<Button>(R.id.exportLogsButton).setOnClickListener { exportLogs() }
        findViewById<Button>(R.id.closeLogsButton).setOnClickListener { finish() }
        refresh()
    }

    private fun refresh() {
        logText.text = AppLog.header() + AppLog.readText()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("Clear diagnostic log?")
            .setMessage("This removes the current local log file only.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear") { _, _ ->
                AppLog.clear()
                AppLog.i("Logs", "Log cleared by user")
                refresh()
            }
            .show()
    }

    private fun exportLogs() {
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/plain"
                putExtra(Intent.EXTRA_TITLE, "riddle-diary-log.txt")
            },
            REQUEST_EXPORT,
        )
    }

    @Deprecated("Deprecated in Android API; retained for minSdk-compatible document export")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_EXPORT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        runCatching {
            contentResolver.openOutputStream(uri, "w").use { output ->
                requireNotNull(output)
                output.write((AppLog.header() + AppLog.readText()).toByteArray())
            }
        }.onSuccess {
            Toast.makeText(this, "Log exported.", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(this, "Could not export log: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val REQUEST_EXPORT = 901
    }
}
