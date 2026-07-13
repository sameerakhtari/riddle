package com.sameerakhtari.riddle.logging

import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class UiWatchdog {
    private val running = AtomicBoolean(false)
    private val lastAcknowledged = AtomicLong(SystemClock.elapsedRealtime())
    private val mainHandler = Handler(Looper.getMainLooper())
    private var worker: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        worker = Thread({
            var lastReportedAt = 0L
            while (running.get()) {
                mainHandler.post { lastAcknowledged.set(SystemClock.elapsedRealtime()) }
                SystemClock.sleep(PING_INTERVAL_MS)
                val now = SystemClock.elapsedRealtime()
                val blockedFor = now - lastAcknowledged.get()
                if (blockedFor >= STALL_THRESHOLD_MS && now - lastReportedAt >= REPORT_COOLDOWN_MS) {
                    lastReportedAt = now
                    val runtime = Runtime.getRuntime()
                    val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / MIB
                    val heapMb = runtime.maxMemory() / MIB
                    val nativeMb = Debug.getNativeHeapAllocatedSize() / MIB
                    AppLog.w(
                        "ANRWatchdog",
                        "Main thread has not responded for ${blockedFor}ms; " +
                            "javaHeap=${usedMb}/${heapMb}MB nativeHeap=${nativeMb}MB",
                    )
                }
            }
        }, "riddle-ui-watchdog").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
        worker?.interrupt()
        worker = null
    }

    companion object {
        private const val PING_INTERVAL_MS = 1_000L
        private const val STALL_THRESHOLD_MS = 5_000L
        private const val REPORT_COOLDOWN_MS = 15_000L
        private const val MIB = 1024L * 1024L
    }
}
