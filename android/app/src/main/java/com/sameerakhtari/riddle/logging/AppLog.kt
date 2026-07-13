package com.sameerakhtari.riddle.logging

import android.content.Context
import android.os.Build
import com.sameerakhtari.riddle.BuildConfig
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLog {
    private val lock = Any()
    private val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var enabled: Boolean = true

    @Volatile
    private var maxBytes: Long = 1_024L * 1_024L

    @Volatile
    private var crashHandlerInstalled = false

    fun init(context: Context) {
        appContext = context.applicationContext
        installCrashHandler()
    }

    fun configure(enabled: Boolean, maxKb: Int) {
        this.enabled = enabled
        maxBytes = maxKb.coerceIn(256, 4_096).toLong() * 1_024L
        truncateIfNeeded()
    }

    fun i(tag: String, message: String) = write("INFO", tag, message, null)
    fun w(tag: String, message: String, error: Throwable? = null) = write("WARN", tag, message, error)
    fun e(tag: String, message: String, error: Throwable? = null) = write("ERROR", tag, message, error)

    fun logFile(): File? = appContext?.let { context ->
        File(context.filesDir, "logs").apply { mkdirs() }.resolve("riddle.log")
    }

    fun readText(maxCharacters: Int = 300_000): String {
        val file = logFile() ?: return "Logging is not initialized."
        if (!file.isFile) return "No log entries yet."
        synchronized(lock) {
            val text = runCatching { file.readText() }
                .getOrElse { return "Could not read logs: ${it.message}" }
            return if (text.length <= maxCharacters) text else {
                "…older entries omitted…\n" + text.takeLast(maxCharacters)
            }
        }
    }

    fun clear() {
        val file = logFile() ?: return
        synchronized(lock) {
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText("")
            }
        }
    }

    fun header(): String = buildString {
        append("Tom Riddle Diary ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
        append("Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}\n")
        append("Device ${Build.MANUFACTURER} ${Build.MODEL}\n")
        append("Generated ${timestamp.format(Date())}\n\n")
    }

    private fun installCrashHandler() {
        if (crashHandlerInstalled) return
        synchronized(lock) {
            if (crashHandlerInstalled) return
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                runCatching {
                    write(
                        "FATAL",
                        "Crash",
                        "Uncaught exception on ${thread.name}: ${error.stackTraceToString().take(12_000)}",
                        error,
                    )
                }
                previous?.uncaughtException(thread, error)
            }
            crashHandlerInstalled = true
        }
    }

    private fun write(level: String, tag: String, message: String, error: Throwable?) {
        if (!enabled) return
        val file = logFile() ?: return
        val line = buildString {
            append(timestamp.format(Date()))
            append(" ")
            append(level)
            append("/")
            append(tag.take(40))
            append(": ")
            append(message.replace('\n', ' ').take(12_000))
            if (error != null) {
                append(" | ")
                append(error.javaClass.simpleName)
                append(": ")
                append(error.message.orEmpty().replace('\n', ' ').take(2_000))
            }
            append('\n')
        }
        synchronized(lock) {
            runCatching {
                file.parentFile?.mkdirs()
                file.appendText(line)
                truncateLocked(file)
            }
        }
    }

    private fun truncateIfNeeded() {
        val file = logFile() ?: return
        synchronized(lock) { runCatching { truncateLocked(file) } }
    }

    private fun truncateLocked(file: File) {
        if (!file.isFile || file.length() <= maxBytes) return
        val keepBytes = (maxBytes * 3L / 5L).coerceAtLeast(128L * 1_024L)
        RandomAccessFile(file, "r").use { input ->
            val start = (input.length() - keepBytes).coerceAtLeast(0L)
            input.seek(start)
            val bytes = ByteArray((input.length() - start).toInt())
            input.readFully(bytes)
            var offset = 0
            while (offset < bytes.size && bytes[offset] != '\n'.code.toByte()) offset++
            if (offset < bytes.size) offset++
            val tail = bytes.copyOfRange(offset, bytes.size)
            file.writeBytes(
                ("${timestamp.format(Date())} INFO/Log: older entries automatically truncated\n").toByteArray() + tail,
            )
        }
    }
}
