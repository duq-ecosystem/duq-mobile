package com.duq.android.config

/**
 * Centralized application configuration.
 * All timeouts and limits in one place for easy tuning.
 */
object AppConfig {
    // Backend endpoints (single source of truth — no hardcoded URLs scattered in code)
    const val BASE_URL = "https://on-za-menya.online"
    const val STT_URL = "$BASE_URL/stt/v1/audio/transcriptions"
    // Local Silero TTS (tts.sh → :8766/tts), exposed via nginx like /stt.
    // Used for contextual TTS: speak a reply only when the user spoke.
    const val TTS_URL = "$BASE_URL/tts"
    // Core-update handle (ручка): backend FastAPI that triggers the CONTROLLED
    // openclaw core update via scripts/update-openclaw.sh (NOT the engine npm
    // self-update — that one breaks local memory). GET status, POST run. The
    // «Движок» screen reads status + the «Обновить ядро» button POSTs run.
    const val CORE_UPDATE_STATUS_URL = "$BASE_URL/core-update/status"
    const val CORE_UPDATE_RUN_URL = "$BASE_URL/core-update/run"

    // Log timestamp timezone. The process default resolves to UTC on this device, so
    // FileLogger timestamps were +5h off from Danny's wall clock — pin it explicitly.
    const val LOG_TIMEZONE = "Asia/Almaty"

    // Self-update — straight from GitHub Releases of the PRIVATE monorepo. CI builds
    // and publishes the signed APK as a release asset; the app reads the latest release
    // via the GitHub API with a read-only token (private repo assets require auth).
    // versionCode is derived from the release tag (build-<code>); the APK is the
    // "app-release.apk" asset, downloaded by its asset id with Accept: octet-stream.
    const val UPDATE_REPO = "duq-ecosystem/duq-next-generation"
    const val UPDATE_LATEST_RELEASE_URL = "https://api.github.com/repos/$UPDATE_REPO/releases/latest"
    // Read-only fine-grained token (contents:read on this repo only), injected at build
    // time from CI secret / local.properties — NOT committed. Empty → updater disabled.
    val UPDATE_GITHUB_TOKEN: String get() = com.duq.android.BuildConfig.GH_RELEASE_TOKEN

    // Network timeouts (seconds)
    const val CONNECT_TIMEOUT_S = 30L
    const val READ_TIMEOUT_S = 60L
    const val WRITE_TIMEOUT_S = 120L

    // Retry configuration
    const val MAX_RETRIES = 3
    const val INITIAL_RETRY_DELAY_MS = 1000L
    const val MAX_RETRY_DELAY_MS = 60000L  // gentler reconnect cap during outages (battery)
    const val RETRY_MULTIPLIER = 2.0

    // Audio recording limits (milliseconds)
    const val MIN_RECORDING_MS = 1000L

    // Voice Activity Detection
    const val VAD_SILENCE_TIMEOUT_MS = 2000L
    const val VAD_MIN_RECORDING_MS = 500L
    const val SILERO_SILENCE_DURATION_MS = 800
    const val SILERO_SPEECH_DURATION_MS = 100
    const val SILERO_FRAME_SIZE = 512

    // Audio formats
    const val AUDIO_SAMPLE_RATE = 16000
    const val AUDIO_TEMP_FILENAME = "voice_command.wav"
    const val RESPONSE_AUDIO_FILENAME = "response_audio.ogg"

    // ── On-device STT (whisper.cpp) ──
    // Переносим STT с сервера (faster-whisper :8765, 2-CPU VPS) на устройство:
    // разгружает VPS, убирает сетевую латентность upload→inference→download, голос
    // не покидает телефон. При STT_ON_DEVICE=false — fallback на серверный STT_URL.
    const val STT_ON_DEVICE = true
    const val STT_LANGUAGE = "ru"                       // зеркалит серверный language=ru
    // ggml small q5_1 (multilingual, ~190MB) — паритет с серверным faster-whisper small.
    // Качается в filesDir при первом запуске (НЕ в APK). URL проверен с VPS (206 OK;
    // ggml-org-зеркало даёт 401 — берём ggerganov).
    const val WHISPER_MODEL_FILE = "ggml-small-q5_1.bin"
    const val WHISPER_MODEL_URL =
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin"

    // Wake word (0.0-1.0, higher = more sensitive, may cause false positives)
    const val WAKE_WORD_SENSITIVITY = 0.9f
    const val WAKE_WORD_FILENAME = "hey_duck.ppn"

    // Service
    const val WAKE_LOCK_TIMEOUT_MS = 600000L  // 10 minutes
    const val SERVICE_BIND_TIMEOUT_MS = 5000L  // 5 seconds

    // WebSocket
    const val WS_CONNECT_TIMEOUT_MS = 5_000L   // 5 seconds

    // Auth
    const val AUTH_TIMEOUT_S = 10L
    const val AUTH_TIMEOUT_MS = AUTH_TIMEOUT_S * 1000  // For HttpURLConnection (uses Int ms)
    const val DEFAULT_TOKEN_EXPIRES_S = 300  // 5 minutes
    const val TOKEN_EXPIRY_BUFFER_MS = 60_000L  // Refresh if expires in < 60s

    // Pagination
    const val DEFAULT_MESSAGES_LIMIT = 50
}
