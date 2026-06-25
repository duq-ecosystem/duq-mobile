package com.duq.android.service

import com.duq.android.error.DuqError

/**
 * Маппинг исключений в [DuqError]. Вынесен из [VoiceCommandProcessor] ради SRP.
 *
 * Перенесён из app-модуля (Hilt `@Inject` убран — Koin инстанцирует [DefaultErrorMapper]
 * напрямую). Логика 1:1 с оригиналом.
 */
interface ErrorMapper {
    fun mapException(e: Exception): DuqError
}

class DefaultErrorMapper : ErrorMapper {

    override fun mapException(e: Exception): DuqError {
        return when (e) {
            is java.net.SocketTimeoutException -> DuqError.NetworkError.timeout()
            is java.net.UnknownHostException -> DuqError.NetworkError.noConnection()
            is java.net.ConnectException -> DuqError.NetworkError.noConnection("Connection refused: ${e.message}")
            is java.io.IOException -> DuqError.NetworkError(e.message ?: "Network error")
            else -> DuqError.AudioError(e.message ?: "Unknown error", e)
        }
    }
}
