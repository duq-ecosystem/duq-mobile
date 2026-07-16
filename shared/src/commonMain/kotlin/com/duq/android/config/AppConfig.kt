package com.duq.android.config

/**
 * Централизованная конфигурация (multiplatform). Все URL/таймауты/лимиты — в одном месте.
 *
 * Секреты (edge-токен, GitHub-токен) НЕ читаются из BuildConfig напрямую (BuildConfig
 * принадлежит androidApp и не виден из shared) — их кладёт платформа в [AppSecrets]
 * при старте: androidApp из BuildConfig, iosApp из Info.plist/build-конфига.
 */
object AppConfig {
    // Backend endpoints (единый источник правды — без хардкода URL по коду)
    const val BASE_URL = "https://on-za-menya.online"
    const val STT_URL = "$BASE_URL/stt/v1/audio/transcriptions"
    const val TTS_URL = "$BASE_URL/tts"
    const val CORE_UPDATE_STATUS_URL = "$BASE_URL/core-update/status"
    const val CORE_UPDATE_RUN_URL = "$BASE_URL/core-update/run"

    // Ядро DUQ (Python duq-core за nginx, префикс /duq)
    const val DUQ_API_BASE_URL = "$BASE_URL/duq/api/"
    const val DUQ_WS_URL = "wss://on-za-menya.online/duq/ws"

    // Вход через Telegram Login Widget: страница с виджетом (открывается в браузере/Custom Tab),
    // callback редиректит обратно в приложение по deep link ниже. Self-hosted: свой BASE_URL.
    const val TELEGRAM_LOGIN_URL = "$BASE_URL/api/auth/telegram/login"

    // Deep link, которым сервер возвращает управление в приложение (см. TELEGRAM_LOGIN_APP_REDIRECT
    // на сервере + intent-filter в AndroidManifest). scheme=duq, host=auth, path=/telegram.
    const val TELEGRAM_LOGIN_DEEPLINK_SCHEME = "duq"
    const val TELEGRAM_LOGIN_DEEPLINK_HOST = "auth"

    // Native Telegram Login SDK (бесшовный вход через приложение Telegram, id_token/OIDC).
    // client_id = bot client id (для init SDK и aud id_token на сервере). Redirect App Link —
    // ОТДЕЛЬНЫЙ домен app{app_registration_id}-login.tg.dev, который BotFather сгенерил при
    // регистрации native-приложения (число НЕ равно client_id). Self-hosted: свои значения.
    const val TELEGRAM_CLIENT_ID = "8278156173"
    const val TELEGRAM_NATIVE_REDIRECT_HOST = "app2402587810-login.tg.dev"
    const val TELEGRAM_NATIVE_REDIRECT_URI = "https://$TELEGRAM_NATIVE_REDIRECT_HOST/tglogin"

    // Endpoint ядра для native-входа: приложение шлёт id_token, получает сессию.
    // На /api/* (не /duq/*) — как telegram/google callback, открыт в nginx без edge-токена.
    const val TELEGRAM_NATIVE_LOGIN_URL = "$BASE_URL/api/auth/telegram/native"

    // Привязка Telegram к текущему юзеру (кнопка «Привязать телеграм» в профиле).
    const val TELEGRAM_NATIVE_LINK_URL = "$BASE_URL/api/auth/telegram/link"

    const val LOG_TIMEZONE = "Asia/Almaty"

    // Self-update (GitHub Releases). На фазе CI цель будет переключена на duq-mobile.
    const val UPDATE_REPO = "duq-ecosystem/duq-mobile"
    const val UPDATE_LATEST_RELEASE_URL = "https://api.github.com/repos/$UPDATE_REPO/releases/latest"

    // Edge-токен периметра (X-Auth-Token на все серверные запросы).
    val SERVER_TOKEN: String get() = AppSecrets.serverToken
    val UPDATE_GITHUB_TOKEN: String get() = AppSecrets.githubReleaseToken
    const val SERVER_TOKEN_HEADER = "X-Auth-Token"

    // DNS-over-HTTPS (обход «Unable to resolve host»). Реализация — expect/actual (DohDns).
    const val DOH_RESOLVER_URL = "https://cloudflare-dns.com/dns-query"
    val DOH_BOOTSTRAP_IPS = listOf("1.1.1.1", "1.0.0.1", "162.159.36.1", "162.159.46.1")

    // Network timeouts (seconds)
    const val CONNECT_TIMEOUT_S = 30L
    const val READ_TIMEOUT_S = 60L
    const val WRITE_TIMEOUT_S = 120L

    // Retry
    const val MAX_RETRIES = 3
    const val INITIAL_RETRY_DELAY_MS = 1000L
    const val MAX_RETRY_DELAY_MS = 60000L
    const val RETRY_MULTIPLIER = 2.0

    // Audio recording
    const val MIN_RECORDING_MS = 1000L
    const val VAD_SILENCE_TIMEOUT_MS = 2000L
    const val VAD_MIN_RECORDING_MS = 500L
    const val SILERO_SILENCE_DURATION_MS = 800
    const val SILERO_SPEECH_DURATION_MS = 100
    const val SILERO_FRAME_SIZE = 512
    const val AUDIO_SAMPLE_RATE = 16000
    const val AUDIO_TEMP_FILENAME = "voice_command.wav"
    const val RESPONSE_AUDIO_FILENAME = "response_audio.ogg"

    // On-device STT (whisper.cpp) — Android; iOS на старте серверный /stt fallback.
    const val STT_ON_DEVICE = true
    const val STT_LANGUAGE = "ru"
    const val WHISPER_MODEL_FILE = "ggml-small-q5_1.bin"
    const val WHISPER_MODEL_URL =
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin"

    // On-device TTS (sherpa-onnx + Piper VITS RU) — Android; iOS на старте серверный /tts.
    const val TTS_ON_DEVICE = true
    const val TTS_MODEL_BUNDLE = "vits-piper-ru_RU-ruslan-medium"
    const val TTS_MODEL_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$TTS_MODEL_BUNDLE.tar.bz2"
    val TTS_MODEL_FILE = "$TTS_MODEL_BUNDLE/${TTS_MODEL_BUNDLE.removePrefix("vits-piper-")}.onnx"
    const val TTS_TOKENS_FILE = "$TTS_MODEL_BUNDLE/tokens.txt"
    const val TTS_ESPEAK_DATA_DIR = "$TTS_MODEL_BUNDLE/espeak-ng-data"
    const val TTS_SPEAKER_ID = 0
    const val TTS_SPEED = 1.0f
    const val TTS_SAMPLE_RATE = 22050

    // Service
    const val WAKE_LOCK_TIMEOUT_MS = 600000L
    const val SERVICE_BIND_TIMEOUT_MS = 5000L

    // WebSocket
    const val WS_CONNECT_TIMEOUT_MS = 5_000L

    // Pagination
    const val DEFAULT_MESSAGES_LIMIT = 50
}

/**
 * Секреты сборки. Заполняются платформой при старте (androidApp из BuildConfig,
 * iosApp из Info.plist). Пусто → серверные заголовки/updater отключаются (отладка).
 */
object AppSecrets {
    var serverToken: String = ""
    var githubReleaseToken: String = ""
}
