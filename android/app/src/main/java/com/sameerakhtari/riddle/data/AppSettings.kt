package com.sameerakhtari.riddle.data

import android.content.Context
import android.graphics.Color

enum class AiProviderMode(val label: String) {
    NONE("Writing only — no AI"),
    RIDDLE_BACKEND("Private Riddle backend"),
    DIRECT_OPENAI("Direct OpenAI-compatible API"),
    LOCAL_SERVER("Local/OpenAI-compatible server"),
    ON_DEVICE("On-device model"),
}

enum class LocalInferenceBackend(val label: String) {
    CPU("CPU — safest compatibility"),
    GPU("GPU — faster when supported"),
}

enum class PenButtonAction(val label: String) {
    TOGGLE_ERASER("Toggle quill / eraser"),
    UNDO("Undo last stroke"),
    ASK("Ask immediately"),
    TOGGLE_CONTROLS("Show / hide controls"),
}

enum class DiaryVoice(val label: String, val instruction: String) {
    ENCHANTED_FACTUAL(
        "Enchanted and factual",
        "Write like an old, intelligent enchanted diary: restrained, elegant and slightly archaic, " +
            "but always factually correct. Never claim to be a fictional character and never invent magic.",
    ),
    DIRECT(
        "Plain factual assistant",
        "Use plain, direct modern language and prioritize accuracy over atmosphere.",
    ),
    SCHOLARLY(
        "Old scholarly journal",
        "Use measured scholarly prose with a subtle antique cadence, while remaining clear and factual.",
    ),
    WARM(
        "Warm companion",
        "Use a warm, encouraging tone without becoming sentimental or sacrificing factual accuracy.",
    ),
}

enum class AnswerLength(val label: String, val maxWords: Int) {
    BRIEF("Brief — usually one line", 32),
    STANDARD("Standard — one or two sentences", 70),
    DETAILED("Detailed — short paragraph", 140),
}

enum class AnswerStyle(val label: String, val instruction: String) {
    AUTO(
        "Automatic — shortest complete answer",
        "Choose the shortest format that completely answers the exact request. For a requested value, date, name, command, or yes/no fact, return only that answer and its essential unit or qualifier. Do not restate or classify the question.",
    ),
    VALUE_ONLY(
        "Exact value or name only",
        "Return only the requested value, name, date, command, or short fact. Include an essential unit, but no introduction or explanation unless the answer would otherwise be misleading.",
    ),
    CONCISE(
        "Concise direct answer",
        "Answer directly in one compact sentence. Do not restate the question or announce its topic.",
    ),
    EXPLAINED(
        "Brief explanation when useful",
        "Give the direct answer first, followed by only the minimum explanation needed for understanding.",
    ),
}

enum class LocalModelPreset(
    val id: String,
    val label: String,
    val fileName: String,
    val downloadUrl: String,
    val modelPageUrl: String,
    val expectedSize: String,
) {
    GEMMA_3N_E2B(
        id = "builtin-gemma-3n-e2b",
        label = "Gemma 3n E2B IT",
        fileName = "gemma-3n-E2B-it-int4.litertlm",
        downloadUrl =
            "https://huggingface.co/google/gemma-3n-E2B-it-litert-lm/resolve/main/" +
                "gemma-3n-E2B-it-int4.litertlm?download=true",
        modelPageUrl = "https://huggingface.co/google/gemma-3n-E2B-it-litert-lm",
        expectedSize = "about 3.66 GB",
    ),
    GEMMA_4_E2B(
        id = "builtin-gemma-4-e2b",
        label = "Gemma 4 E2B IT — recommended for S22 Ultra",
        fileName = "gemma-4-E2B-it.litertlm",
        downloadUrl =
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/" +
                "gemma-4-E2B-it.litertlm?download=true",
        modelPageUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm",
        expectedSize = "about 2.59 GB",
    ),
    GEMMA_4_E4B(
        id = "builtin-gemma-4-e4b",
        label = "Gemma 4 E4B IT — experimental on S22",
        fileName = "gemma-4-E4B-it.litertlm",
        downloadUrl =
            "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/" +
                "gemma-4-E4B-it.litertlm?download=true",
        modelPageUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm",
        expectedSize = "about 3.66 GB",
    );

    companion object {
        fun fromFileName(fileName: String): LocalModelPreset =
            entries.firstOrNull { it.fileName == fileName } ?: GEMMA_3N_E2B
    }
}

class AppSettings(context: Context) {
    private val preferences =
        context.getSharedPreferences("riddle_settings_v2", Context.MODE_PRIVATE)
    private val secrets = SecretStore(context)

    init {
        migrateInkDefaultsOnce()
        migrateModelSelectionOnce()
        migrateCatalogUrlOnce()
    }

    var providerMode: AiProviderMode
        get() = enumValue(KEY_PROVIDER, AiProviderMode.NONE)
        set(value) = put(KEY_PROVIDER, value.name)

    var proxyUrl: String
        get() = get(KEY_PROXY_URL, "")
        set(value) = put(KEY_PROXY_URL, value.trim().trimEnd('/'))

    var proxyToken: String
        get() = secrets.get(KEY_PROXY_TOKEN)
        set(value) = secrets.put(KEY_PROXY_TOKEN, value)

    var directBaseUrl: String
        get() = get(KEY_DIRECT_BASE, "https://api.openai.com/v1")
        set(value) = put(KEY_DIRECT_BASE, value.trim().trimEnd('/'))

    var directApiKey: String
        get() = secrets.get(KEY_DIRECT_KEY)
        set(value) = secrets.put(KEY_DIRECT_KEY, value)

    var directModel: String
        get() = get(KEY_DIRECT_MODEL, "gpt-4o-mini")
        set(value) = put(KEY_DIRECT_MODEL, value.trim())

    var localServerBaseUrl: String
        get() = get(KEY_LOCAL_SERVER_BASE, "http://192.168.1.2:11434/v1")
        set(value) = put(KEY_LOCAL_SERVER_BASE, value.trim().trimEnd('/'))

    var localServerToken: String
        get() = secrets.get(KEY_LOCAL_SERVER_TOKEN)
        set(value) = secrets.put(KEY_LOCAL_SERVER_TOKEN, value)

    var localServerModel: String
        get() = get(KEY_LOCAL_SERVER_MODEL, "")
        set(value) = put(KEY_LOCAL_SERVER_MODEL, value.trim())

    var huggingFaceToken: String
        get() = secrets.get(KEY_HF_TOKEN)
        set(value) = secrets.put(KEY_HF_TOKEN, value)

    /** Compatibility fields kept so existing installs migrate without losing their model choice. */
    var localModelFileName: String
        get() = get(KEY_LOCAL_MODEL_FILE, LocalModelPreset.GEMMA_3N_E2B.fileName)
        set(value) = put(KEY_LOCAL_MODEL_FILE, value.trim())

    var localModelUrl: String
        get() = get(KEY_LOCAL_MODEL_URL, LocalModelPreset.GEMMA_3N_E2B.downloadUrl)
        set(value) = put(KEY_LOCAL_MODEL_URL, value.trim())

    var localModelPreset: LocalModelPreset
        get() = LocalModelPreset.entries.firstOrNull { it.id == activeModelId }
            ?: LocalModelPreset.fromFileName(localModelFileName)
        set(value) {
            activeModelId = value.id
            localModelFileName = value.fileName
            localModelUrl = value.downloadUrl
        }

    var activeModelId: String
        get() = get(KEY_ACTIVE_MODEL_ID, LocalModelPreset.GEMMA_3N_E2B.id)
        set(value) = put(KEY_ACTIVE_MODEL_ID, value.trim())

    var modelLibraryTreeUri: String
        get() = get(KEY_MODEL_LIBRARY_URI, "")
        set(value) = put(KEY_MODEL_LIBRARY_URI, value.trim())

    var modelCatalogUrl: String
        get() = get(KEY_MODEL_CATALOG_URL, DEFAULT_MODEL_CATALOG_URL)
        set(value) = put(KEY_MODEL_CATALOG_URL, value.trim())

    var localInferenceBackend: LocalInferenceBackend
        get() = enumValue(KEY_LOCAL_BACKEND, LocalInferenceBackend.CPU)
        set(value) = put(KEY_LOCAL_BACKEND, value.name)

    var diaryVoice: DiaryVoice
        get() = enumValue(KEY_DIARY_VOICE, DiaryVoice.ENCHANTED_FACTUAL)
        set(value) = put(KEY_DIARY_VOICE, value.name)

    var answerLength: AnswerLength
        get() = enumValue(KEY_ANSWER_LENGTH, AnswerLength.BRIEF)
        set(value) = put(KEY_ANSWER_LENGTH, value.name)

    var answerStyle: AnswerStyle
        get() = enumValue(KEY_ANSWER_STYLE, AnswerStyle.AUTO)
        set(value) = put(KEY_ANSWER_STYLE, value.name)

    var customInstructions: String
    get() = get(KEY_CUSTOM_INSTRUCTIONS, "").take(MAX_CUSTOM_INSTRUCTIONS)
    set(value) = put(KEY_CUSTOM_INSTRUCTIONS, value.replace("\r\n", "\n").trim().take(MAX_CUSTOM_INSTRUCTIONS))

    var crossSessionMemory: Boolean
        get() = getBoolean(KEY_CROSS_SESSION_MEMORY, true)
        set(value) = putBoolean(KEY_CROSS_SESSION_MEMORY, value)

    var automaticMemoryFacts: Boolean
        get() = getBoolean(KEY_AUTOMATIC_MEMORY_FACTS, true)
        set(value) = putBoolean(KEY_AUTOMATIC_MEMORY_FACTS, value)

    var immersiveFullscreen: Boolean
        get() = getBoolean(KEY_FULLSCREEN, true)
        set(value) = putBoolean(KEY_FULLSCREEN, value)

    var bookOpenAnimation: Boolean
        get() = getBoolean(KEY_BOOK_OPEN_ANIMATION, true)
        set(value) = putBoolean(KEY_BOOK_OPEN_ANIMATION, value)

    var autoWarmLocalModel: Boolean
        get() = getBoolean(KEY_AUTO_WARM_LOCAL_MODEL, true)
        set(value) = putBoolean(KEY_AUTO_WARM_LOCAL_MODEL, value)

    var allowScreenshots: Boolean
        get() = getBoolean(KEY_ALLOW_SCREENSHOTS, true)
        set(value) = putBoolean(KEY_ALLOW_SCREENSHOTS, value)

    var autoSubmit: Boolean
        get() = getBoolean(KEY_AUTO_SUBMIT, true)
        set(value) = putBoolean(KEY_AUTO_SUBMIT, value)

    var autoSubmitDelayMs: Long
        get() = preferences.getLong(KEY_AUTO_DELAY, 2_800L).coerceIn(1_000L, 15_000L)
        set(value) = preferences.edit()
            .putLong(KEY_AUTO_DELAY, value.coerceIn(1_000L, 15_000L))
            .apply()

    var allowFinger: Boolean
        get() = getBoolean(KEY_ALLOW_FINGER, false)
        set(value) = putBoolean(KEY_ALLOW_FINGER, value)

    var spenHoverGestures: Boolean
        get() = getBoolean(KEY_SPEN_GESTURES, true)
        set(value) = putBoolean(KEY_SPEN_GESTURES, value)

    var penButtonAction: PenButtonAction
        get() = enumValue(KEY_SPEN_BUTTON_ACTION, PenButtonAction.TOGGLE_ERASER)
        set(value) = put(KEY_SPEN_BUTTON_ACTION, value.name)

    var penWidth: Float
        get() = preferences.getFloat(KEY_PEN_WIDTH, DEFAULT_PEN_WIDTH).coerceIn(4f, 22f)
        set(value) = preferences.edit().putFloat(KEY_PEN_WIDTH, value.coerceIn(4f, 22f)).apply()

    var pressureSensitivity: Float
        get() = preferences.getFloat(KEY_PRESSURE, 0.82f).coerceIn(0f, 1.5f)
        set(value) = preferences.edit().putFloat(KEY_PRESSURE, value.coerceIn(0f, 1.5f)).apply()

    var replySpeedMsPerCharacter: Long
        get() = preferences.getLong(KEY_REPLY_SPEED, 48L).coerceIn(16L, 140L)
        set(value) = preferences.edit().putLong(KEY_REPLY_SPEED, value.coerceIn(16L, 140L)).apply()

    var replyStartDelayMs: Long
        get() = preferences.getLong(KEY_REPLY_START_DELAY, 650L).coerceIn(0L, 3_000L)
        set(value) = preferences.edit().putLong(KEY_REPLY_START_DELAY, value.coerceIn(0L, 3_000L)).apply()

    var paperTheme: Int
        get() = preferences.getInt(KEY_PAPER_THEME, 0).coerceIn(0, 2)
        set(value) = preferences.edit().putInt(KEY_PAPER_THEME, value.coerceIn(0, 2)).apply()

    var userInkColor: Int
        get() = preferences.getInt(KEY_USER_INK, Color.rgb(40, 24, 13))
        set(value) = preferences.edit().putInt(KEY_USER_INK, value).apply()

    var replyInkColor: Int
        get() = preferences.getInt(KEY_REPLY_INK, Color.rgb(31, 18, 10))
        set(value) = preferences.edit().putInt(KEY_REPLY_INK, value).apply()

    var wifiOnlyModelDownload: Boolean
        get() = getBoolean(KEY_WIFI_ONLY, true)
        set(value) = putBoolean(KEY_WIFI_ONLY, value)

    var diagnosticLoggingEnabled: Boolean
        get() = getBoolean(KEY_LOGGING_ENABLED, true)
        set(value) = putBoolean(KEY_LOGGING_ENABLED, value)

    var logMaxKb: Int
        get() = preferences.getInt(KEY_LOG_MAX_KB, 1_024).coerceIn(256, 4_096)
        set(value) = preferences.edit().putInt(KEY_LOG_MAX_KB, value.coerceIn(256, 4_096)).apply()

    var guideSeen: Boolean
        get() = getBoolean(KEY_GUIDE_SEEN, false)
        set(value) = putBoolean(KEY_GUIDE_SEEN, value)

    var privacySeen: Boolean
        get() = getBoolean(KEY_PRIVACY_SEEN, false)
        set(value) = putBoolean(KEY_PRIVACY_SEEN, value)

    fun isProviderReady(localModelExists: Boolean): Boolean = when (providerMode) {
        AiProviderMode.NONE -> false
        AiProviderMode.RIDDLE_BACKEND -> proxyUrl.isNotBlank()
        AiProviderMode.DIRECT_OPENAI ->
            directBaseUrl.isNotBlank() && directApiKey.isNotBlank() && directModel.isNotBlank()
        AiProviderMode.LOCAL_SERVER ->
            localServerBaseUrl.isNotBlank() && localServerModel.isNotBlank()
        AiProviderMode.ON_DEVICE -> localModelExists
    }

    private fun migrateInkDefaultsOnce() {
        if (preferences.getBoolean(KEY_THICK_INK_MIGRATED, false)) return
        val previous = preferences.getFloat(KEY_PEN_WIDTH, 6.2f)
        preferences.edit()
            .putFloat(KEY_PEN_WIDTH, if (previous < 8.5f) DEFAULT_PEN_WIDTH else previous)
            .putBoolean(KEY_THICK_INK_MIGRATED, true)
            .apply()
    }

    private fun migrateModelSelectionOnce() {
        if (preferences.contains(KEY_ACTIVE_MODEL_ID)) return
        val oldFile = preferences.getString(
            KEY_LOCAL_MODEL_FILE,
            LocalModelPreset.GEMMA_3N_E2B.fileName,
        ).orEmpty()
        val migrated = LocalModelPreset.fromFileName(oldFile)
        preferences.edit().putString(KEY_ACTIVE_MODEL_ID, migrated.id).apply()
    }

    private fun migrateCatalogUrlOnce() {
        val current = get(KEY_MODEL_CATALOG_URL, "")
        if (current.isBlank() || current.contains("codex-test") || current.contains("raw.githubusercontent.com")) {
            put(KEY_MODEL_CATALOG_URL, DEFAULT_MODEL_CATALOG_URL)
        }
    }

    private inline fun <reified T : Enum<T>> enumValue(key: String, fallback: T): T =
        runCatching { enumValueOf<T>(get(key, fallback.name)) }.getOrDefault(fallback)

    private fun get(key: String, fallback: String): String =
        preferences.getString(key, fallback).orEmpty()

    private fun put(key: String, value: String) {
        preferences.edit().putString(key, value).apply()
    }

    private fun getBoolean(key: String, fallback: Boolean): Boolean =
        preferences.getBoolean(key, fallback)

    private fun putBoolean(key: String, value: Boolean) {
        preferences.edit().putBoolean(key, value).apply()
    }

    companion object {
        const val DEFAULT_PEN_WIDTH = 10.8f
        const val MAX_CUSTOM_INSTRUCTIONS = 4_000
        const val DEFAULT_MODEL_CATALOG_URL =
            "https://api.github.com/repos/sameerakhtari/riddle/contents/" +
                "android/app/src/main/assets/model_catalog.json?ref=main"

        private const val KEY_PROVIDER = "provider"
        private const val KEY_PROXY_URL = "proxy_url"
        private const val KEY_PROXY_TOKEN = "proxy_token"
        private const val KEY_DIRECT_BASE = "direct_base"
        private const val KEY_DIRECT_KEY = "direct_key"
        private const val KEY_DIRECT_MODEL = "direct_model"
        private const val KEY_LOCAL_SERVER_BASE = "local_server_base"
        private const val KEY_LOCAL_SERVER_TOKEN = "local_server_token"
        private const val KEY_LOCAL_SERVER_MODEL = "local_server_model"
        private const val KEY_HF_TOKEN = "hf_token"
        private const val KEY_LOCAL_MODEL_FILE = "local_model_file"
        private const val KEY_LOCAL_MODEL_URL = "local_model_url"
        private const val KEY_ACTIVE_MODEL_ID = "active_model_id"
        private const val KEY_MODEL_LIBRARY_URI = "model_library_tree_uri"
        private const val KEY_MODEL_CATALOG_URL = "model_catalog_url"
        private const val KEY_LOCAL_BACKEND = "local_backend"
        private const val KEY_DIARY_VOICE = "diary_voice"
        private const val KEY_ANSWER_LENGTH = "answer_length"
        private const val KEY_ANSWER_STYLE = "answer_style"
        private const val KEY_CUSTOM_INSTRUCTIONS = "custom_instructions"
        private const val KEY_CROSS_SESSION_MEMORY = "cross_session_memory"
        private const val KEY_AUTOMATIC_MEMORY_FACTS = "automatic_memory_facts"
        private const val KEY_FULLSCREEN = "fullscreen"
        private const val KEY_BOOK_OPEN_ANIMATION = "book_open_animation"
        private const val KEY_AUTO_WARM_LOCAL_MODEL = "auto_warm_local_model"
        private const val KEY_ALLOW_SCREENSHOTS = "allow_screenshots"
        private const val KEY_AUTO_SUBMIT = "auto_submit"
        private const val KEY_AUTO_DELAY = "auto_delay"
        private const val KEY_ALLOW_FINGER = "allow_finger"
        private const val KEY_SPEN_GESTURES = "spen_hover_gestures"
        private const val KEY_SPEN_BUTTON_ACTION = "spen_button_action"
        private const val KEY_PEN_WIDTH = "pen_width"
        private const val KEY_PRESSURE = "pressure"
        private const val KEY_REPLY_SPEED = "reply_speed"
        private const val KEY_REPLY_START_DELAY = "reply_start_delay"
        private const val KEY_PAPER_THEME = "paper_theme"
        private const val KEY_USER_INK = "user_ink"
        private const val KEY_REPLY_INK = "reply_ink"
        private const val KEY_WIFI_ONLY = "wifi_only"
        private const val KEY_LOGGING_ENABLED = "logging_enabled"
        private const val KEY_LOG_MAX_KB = "log_max_kb"
        private const val KEY_GUIDE_SEEN = "guide_seen"
        private const val KEY_PRIVACY_SEEN = "privacy_seen"
        private const val KEY_THICK_INK_MIGRATED = "thick_ink_migrated"
    }
}
