package com.duq.android.error

/**
 * Иерархия ошибок DUQ (sealed) для единообразной обработки. (WakeWordError убран —
 * Porcupine/wake-word вырезаны.)
 */
sealed class DuqError(val message: String) {

    /** Сетевые ошибки (таймаут, соединение, серверные). */
    class NetworkError(
        message: String,
        val code: Int? = null,
        val isRetryable: Boolean = true
    ) : DuqError(message) {
        companion object {
            fun timeout(message: String = "Request timed out") = NetworkError(message, isRetryable = true)
            fun noConnection(message: String = "No internet connection") = NetworkError(message, isRetryable = true)
            fun serverError(code: Int, message: String) = NetworkError(message, code, isRetryable = code >= 500)
            fun clientError(code: Int, message: String) = NetworkError(message, code, isRetryable = false)
        }
    }

    /** Ошибки записи/воспроизведения аудио. */
    class AudioError(message: String, val cause: Throwable? = null) : DuqError(message) {
        companion object {
            fun recordingFailed(cause: Throwable? = null) = AudioError("Recording failed", cause)
            fun playbackFailed(cause: Throwable? = null) = AudioError("Playback failed", cause)
            fun noAudioData() = AudioError("No audio data received")
        }
    }

    /** Ошибки прав. */
    class PermissionError(val permission: String) : DuqError("Missing permission: $permission") {
        companion object {
            fun microphone() = PermissionError("RECORD_AUDIO")
        }
    }

    /** Ошибки конфигурации/настройки. */
    class ConfigurationError(message: String) : DuqError(message) {
        companion object {
            fun missingApiKey(keyName: String) = ConfigurationError("$keyName not configured")
            fun invalidConfig(reason: String) = ConfigurationError("Invalid configuration: $reason")
        }
    }

    /** Ошибки аутентификации. */
    class AuthError(message: String, val requiresReauth: Boolean = false) : DuqError(message) {
        companion object {
            fun tokenExpired() = AuthError("Session expired", requiresReauth = true)
            fun invalidToken() = AuthError("Invalid authentication", requiresReauth = true)
            fun refreshFailed(reason: String) = AuthError("Token refresh failed: $reason", requiresReauth = true)
        }
    }

    /** Дружелюбное сообщение для показа юзеру. */
    fun toDisplayMessage(): String = when (this) {
        is NetworkError -> when {
            code != null && code >= 500 -> "Server error. Please try again."
            code == 401 || code == 403 -> "Authentication error. Please re-login."
            code == 404 -> "Not found."
            message.contains("timeout", ignoreCase = true) ||
                message.contains("timed out", ignoreCase = true) ->
                "Connection timed out. Please try again."
            else -> "Network error. Check your connection."
        }
        is AudioError -> "Audio error: $message"
        is PermissionError -> "Permission required: $permission"
        is ConfigurationError -> "Setup error: $message"
        is AuthError -> if (requiresReauth) "Please log in again" else message
    }
}
