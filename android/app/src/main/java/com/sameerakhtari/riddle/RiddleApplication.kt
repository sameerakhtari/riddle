package com.sameerakhtari.riddle

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.Looper
import com.sameerakhtari.riddle.data.AiProviderMode
import com.sameerakhtari.riddle.data.AppSettings
import com.sameerakhtari.riddle.data.LocalInferenceBackend
import com.sameerakhtari.riddle.local.LocalModelManager
import com.sameerakhtari.riddle.local.OnDeviceOracle
import com.sameerakhtari.riddle.logging.AppLog
import com.sameerakhtari.riddle.logging.UiWatchdog
import java.util.concurrent.Executors

class RiddleApplication : Application() {
    lateinit var onDeviceOracle: OnDeviceOracle
        private set

    private val warmExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val warmLock = Any()
    private val pendingCallbacks = mutableListOf<(Result<Unit>) -> Unit>()
    private val uiWatchdog = UiWatchdog()

    @Volatile
    private var warming = false

    override fun onCreate() {
        super.onCreate()
        val settings = AppSettings(this)
        AppLog.init(this)
        AppLog.configure(settings.diagnosticLoggingEnabled, settings.logMaxKb)
        AppLog.i("Application", "Riddle Diary process started; ${memorySnapshot()}")
        uiWatchdog.start()
        onDeviceOracle = OnDeviceOracle(this)
        SPenIntegration(this).register()
    }

    fun isSelectedModelWarm(): Boolean {
        val settings = AppSettings(this)
        val manager = LocalModelManager(this)
        return settings.providerMode == AiProviderMode.ON_DEVICE &&
            manager.isModelReady() &&
            onDeviceOracle.isWarm(manager.modelFile(), settings.localInferenceBackend)
    }

    fun shouldAutoWarmSelectedModel(): Boolean {
        val settings = AppSettings(this)
        val manager = LocalModelManager(this)
        return settings.providerMode == AiProviderMode.ON_DEVICE &&
            settings.autoWarmLocalModel &&
            manager.isModelReady() &&
            !manager.isLargeModel()
    }

    fun isWarmupRunning(): Boolean = warming

    fun warmSelectedModel(
        forceLarge: Boolean = false,
        callback: ((Result<Unit>) -> Unit)? = null,
    ) {
        val settings = AppSettings(this)
        val manager = LocalModelManager(this)
        if (settings.providerMode != AiProviderMode.ON_DEVICE || !manager.isModelReady()) {
            deliver(callback, Result.failure(IllegalStateException("The selected local model is not ready.")))
            return
        }

        val spec = manager.activeSpec()
        val modelFile = manager.modelFile(spec)
        val backend = settings.localInferenceBackend
        if (manager.isLargeModel(spec) && !forceLarge) {
            deliver(
                callback,
                Result.failure(
                    IllegalStateException(
                        "Large model auto-load skipped for stability. Tap Ask to load it, or select Gemma 4 E2B.",
                    ),
                ),
            )
            return
        }
        if (
            manager.isLargeModel(spec) &&
            Build.MODEL.startsWith("SM-S908", ignoreCase = true) &&
            backend == LocalInferenceBackend.GPU
        ) {
            deliver(
                callback,
                Result.failure(
                    IllegalStateException(
                        "GPU loading for this large model is disabled on the S22 Ultra because it can restart the app. Select CPU or use Gemma 4 E2B.",
                    ),
                ),
            )
            return
        }
        if (onDeviceOracle.isWarm(modelFile, backend)) {
            deliver(callback, Result.success(Unit))
            return
        }

        synchronized(warmLock) {
            callback?.let { pendingCallbacks += it }
            if (warming) return
            warming = true
        }

        AppLog.i(
            "LocalAI",
            "Warming ${spec.displayName} with ${backend.name}; forceLarge=$forceLarge; ${memorySnapshot()}",
        )
        warmExecutor.execute {
            val started = System.currentTimeMillis()
            val result = runCatching { onDeviceOracle.warmUp(modelFile, backend) }
            result.onSuccess {
                AppLog.i(
                    "LocalAI",
                    "Model warm-up completed in ${System.currentTimeMillis() - started}ms; ${memorySnapshot()}",
                )
            }.onFailure { error ->
                AppLog.e(
                    "LocalAI",
                    "Model warm-up failed after ${System.currentTimeMillis() - started}ms; ${memorySnapshot()}",
                    error,
                )
            }
            val callbacks = synchronized(warmLock) {
                warming = false
                pendingCallbacks.toList().also { pendingCallbacks.clear() }
            }
            mainHandler.post { callbacks.forEach { it(result) } }
        }
    }

    override fun onTrimMemory(level: Int) {
        AppLog.w("Memory", "onTrimMemory level=$level; ${memorySnapshot()}")
        super.onTrimMemory(level)
    }

    override fun onLowMemory() {
        AppLog.w("Memory", "onLowMemory; ${memorySnapshot()}")
        super.onLowMemory()
    }

    fun memorySnapshot(): String {
        val runtime = Runtime.getRuntime()
        val used = (runtime.totalMemory() - runtime.freeMemory()) / MIB
        val max = runtime.maxMemory() / MIB
        val native = Debug.getNativeHeapAllocatedSize() / MIB
        val info = ActivityManager.MemoryInfo()
        (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        return "javaHeap=${used}/${max}MB nativeHeap=${native}MB availableRam=${info.availMem / MIB}MB lowMemory=${info.lowMemory}"
    }

    private fun deliver(callback: ((Result<Unit>) -> Unit)?, result: Result<Unit>) {
        callback?.let { mainHandler.post { it(result) } }
    }

    override fun onTerminate() {
        AppLog.i("Application", "Riddle Diary process terminating")
        uiWatchdog.stop()
        onDeviceOracle.close()
        warmExecutor.shutdownNow()
        super.onTerminate()
    }

    companion object {
        private const val MIB = 1024L * 1024L
    }
}
