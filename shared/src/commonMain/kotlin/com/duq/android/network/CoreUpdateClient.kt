package com.duq.android.network

import com.duq.android.config.AppConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Client for the backend core-update handle (ручка), multiplatform (Ktor). Lets the
 * «Движок» screen show the current/available core version and trigger a CONTROLLED
 * update (scripts/update-core.sh on the VPS — git pull + rebuild of the memory index),
 * NOT the engine's naive npm self-update which breaks local memory.
 *
 * /status|/run are exposed via nginx behind the edge-token (X-Auth-Token rides the
 * client's DefaultRequest — see DuqHttpClient). /run runs detached on the backend and
 * survives the bot restart it performs.
 */
class CoreUpdateClient(private val http: HttpClient) {

    @Serializable
    data class Status(
        val current: String? = null,
        val latest: String? = null,
        @SerialName("updateAvailable") val updateAvailable: Boolean = false,
        val running: Boolean = false,
        val log: String = "",
        // self-check после апдейта (пишет update-core.sh) — для уведомления.
        val result: Result? = null,
    )

    /** Результат self-check после апдейта ядра: версия, успех, человекочитаемая сводка. */
    @Serializable
    data class Result(
        val version: String? = null,
        val ok: Boolean = false,
        val summary: String = "",
        val ts: String = "",
    )

    /** Результат запуска: запущено / уже идёт (409) / не удалось (сеть/ошибка). */
    enum class RunResult { STARTED, ALREADY_RUNNING, FAILED }

    /** Current + latest core version, updateAvailable, and in-progress flag. */
    suspend fun status(): Status? = runCatching {
        val resp = http.get(AppConfig.CORE_UPDATE_STATUS_URL)
        if (!resp.status.isSuccess()) null else resp.body<Status>()
    }.getOrNull()

    /**
     * Kick off the controlled update on the backend (runs detached, survives the bot
     * restart it performs). 409 = уже идёт (НЕ ошибка, отличаем от FAILED).
     */
    suspend fun run(): RunResult = runCatching {
        val resp = http.post(AppConfig.CORE_UPDATE_RUN_URL) { setBody(ByteArray(0)) }
        when {
            resp.status.isSuccess() -> RunResult.STARTED
            resp.status.value == 409 -> RunResult.ALREADY_RUNNING
            else -> RunResult.FAILED
        }
    }.getOrDefault(RunResult.FAILED)
}
