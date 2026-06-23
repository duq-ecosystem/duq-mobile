package com.duq.android.network

import android.content.Context
import com.duq.android.config.AppConfig
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client for the backend core-update handle (ручка). Lets the «Движок» screen show
 * the current/available core version and trigger a CONTROLLED update
 * (scripts/update-core.sh on the VPS — git pull + compose rebuild of the
 * memory index), NOT the engine's naive npm self-update which breaks local memory.
 *
 * HTTP/1.1 pinned (same as TtsClient): OkHttp HTTP/2 can stall behind nginx. /run
 * is exposed internally via nginx like /stt — no secret in this public-repo app.
 */
@Singleton
class CoreUpdateClient @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    // @Keep: parsed by Gson via reflection — without it R8 renames the fields in
    // release and the status loads blank (same trap as NotificationInbox.Item).
    @androidx.annotation.Keep
    data class Status(
        val current: String? = null,
        val latest: String? = null,
        @SerializedName("updateAvailable") val updateAvailable: Boolean = false,
        val running: Boolean = false,
        val log: String = "",
        // self-check после апдейта (пишет update-core.sh) — для уведомления.
        val result: Result? = null,
    )

    /** Результат self-check после апдейта ядра: версия, успех, человекочитаемая сводка. */
    @androidx.annotation.Keep
    data class Result(
        val version: String? = null,
        val ok: Boolean = false,
        val summary: String = "",
        val ts: String = "",
    )

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .withDuqDns()
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectTimeout(10, TimeUnit.SECONDS)
        // /status может отвечать дольше дефолтных 10с (npm view + версия ядра) — без
        // явного readTimeout запрос падал по SocketTimeout и Движок показывал «Бэкенд
        // недоступен». Держим readTimeout в рамках callTimeout.
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Current + latest core version, updateAvailable, and in-progress flag. */
    suspend fun status(): Status? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(AppConfig.CORE_UPDATE_STATUS_URL).withServerAuth().get().build()
        runCatching {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                resp.body?.string()?.let { gson.fromJson(it, Status::class.java) }
            }
        }.getOrNull()
    }

    /** Результат запуска: запущено / уже идёт (409) / не удалось (сеть/ошибка). */
    enum class RunResult { STARTED, ALREADY_RUNNING, FAILED }

    /**
     * Kick off the controlled update on the backend (runs detached, survives the
     * bot restart it performs). 409 = уже идёт (НЕ ошибка, отличаем от FAILED).
     */
    suspend fun run(): RunResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(AppConfig.CORE_UPDATE_RUN_URL)
            .withServerAuth()
            .post(ByteArray(0).toRequestBody())
            .build()
        runCatching {
            client.newCall(request).execute().use { resp ->
                when {
                    resp.isSuccessful -> RunResult.STARTED
                    resp.code == 409 -> RunResult.ALREADY_RUNNING
                    else -> RunResult.FAILED
                }
            }
        }.getOrDefault(RunResult.FAILED)
    }
}
