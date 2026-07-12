package com.duq.android.network

import com.duq.android.config.AppConfig
import com.duq.android.data.SettingsRepository
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.header
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/** Общий JSON ядра: лишние поля игнорим, null-поля с дефолтом не кодируем (как Gson omit-null). */
val duqJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    isLenient = true
}

/**
 * Платформенный движок Ktor: OkHttp (Android, + DoH в actual) / Darwin (iOS).
 * Общая конфигурация (JSON, WebSockets, edge-токен) задаётся здесь, в commonMain.
 */
expect fun platformHttpClient(block: HttpClientConfig<*>.() -> Unit): HttpClient

/**
 * Единый HTTP/WS-клиент DUQ: edge-токен X-Auth-Token на всех запросах + JSON + WebSockets.
 * Токен берётся ДИНАМИЧЕСКИ из настроек (юзер вводит на экране регистрации при первом входе);
 * фолбэк на build-time AppConfig.SERVER_TOKEN, если в настройках пусто (совместимость).
 */
fun createDuqHttpClient(settings: SettingsRepository): HttpClient = platformHttpClient {
    install(ContentNegotiation) { json(duqJson) }
    install(WebSockets)
    install(HttpTimeout) {
        connectTimeoutMillis = AppConfig.CONNECT_TIMEOUT_S * 1000
        requestTimeoutMillis = AppConfig.READ_TIMEOUT_S * 1000
    }
    install(DefaultRequest) {
        val token = settings.getServerToken().ifBlank { AppConfig.SERVER_TOKEN }
        if (token.isNotEmpty()) {
            header(AppConfig.SERVER_TOKEN_HEADER, token)
        }
    }
    expectSuccess = false
}
