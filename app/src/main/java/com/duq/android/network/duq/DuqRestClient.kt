package com.duq.android.network.duq

import com.duq.android.config.AppConfig
import com.duq.android.logging.Logger
import com.duq.android.network.withDuqDns
import com.duq.android.network.withServerAuth
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * REST-клиент нового ядра DUQ (duq-core за nginx, база [AppConfig.DUQ_API_BASE_URL]).
 * Чат-цикл: enqueue ([sendMessage]) → poll ([pollTask]) → итоговый ответ
 * ([awaitResponse]). История — [conversations]/[messages].
 *
 * Все запросы несут edge-токен (X-Auth-Token) через [withServerAuth]. Сетевые вызовы
 * выполняются на [Dispatchers.IO]; HTTP-клиент шарит пул соединений (один на инстанс).
 */
@Singleton
class DuqRestClient @Inject constructor(
    private val logger: Logger
) {
    companion object {
        private const val TAG = "DuqRest"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }

    private val gson = Gson()

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .withDuqDns()
            .connectTimeout(AppConfig.CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(AppConfig.READ_TIMEOUT_S, TimeUnit.SECONDS)
            .build()
    }

    private fun url(path: String) = AppConfig.DUQ_API_BASE_URL + path

    // /conversations и /messages на ядре защищены HTTPBearer (Depends(keycloak_sub)),
    // в отличие от /message и /task. Они принимают тот же edge-токен, но в заголовке
    // Authorization: Bearer (не X-Auth-Token). Без него — 401 и пустая история.
    private fun Request.Builder.withBearer(): Request.Builder =
        if (AppConfig.SERVER_TOKEN.isNotEmpty())
            header("Authorization", "Bearer ${AppConfig.SERVER_TOKEN}")
        else this

    /**
     * Ставит сообщение в очередь ядра. Возвращает task_id для последующего поллинга.
     * [conversationId] — отправка в конкретную беседу (null = активная);
     * [newConversation] — начать новый диалог (true только для первого сообщения нового чата).
     */
    suspend fun sendMessage(
        text: String,
        conversationId: String? = null,
        newConversation: Boolean = false,
        agentId: String? = null,
    ): String = withContext(Dispatchers.IO) {
        val req0 = MessageRequest(
            message = text,
            conversationId = conversationId,
            newConversation = if (newConversation) true else null,
            agentId = agentId,
        )
        val body = gson.toJson(req0).toRequestBody(JSON)
        val req = Request.Builder().url(url("message")).withServerAuth().post(body).build()
        httpClient.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw DuqApiException("message ${resp.code}: ${raw.take(200)}")
            val enqueued = gson.fromJson(raw, MessageEnqueued::class.java)
                ?: throw DuqApiException("message: empty body")
            logger.d(TAG, "enqueued task=${enqueued.taskId} status=${enqueued.status}")
            enqueued.taskId
        }
    }

    /** Один опрос состояния задачи. */
    suspend fun pollTask(taskId: String): TaskResult = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url("task/$taskId")).withServerAuth().get().build()
        httpClient.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw DuqApiException("task ${resp.code}: ${raw.take(200)}")
            gson.fromJson(raw, TaskResult::class.java)
                ?: throw DuqApiException("task: empty body")
        }
    }

    /**
     * Поллит задачу до терминального состояния и возвращает текст ответа.
     * Бросает [DuqApiException] на failed/таймауте.
     */
    suspend fun awaitResponse(
        taskId: String,
        timeoutMs: Long = AppConfig.DUQ_TASK_TIMEOUT_MS,
        intervalMs: Long = AppConfig.DUQ_TASK_POLL_INTERVAL_MS
    ): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val result = pollTask(taskId)
            when {
                result.isCompleted -> return result.result?.response.orEmpty()
                result.isFailed -> throw DuqApiException("task failed: ${result.error ?: result.status}")
                else -> delay(intervalMs)
            }
        }
        throw DuqApiException("task $taskId: timeout after ${timeoutMs}ms")
    }

    /** Список диалогов пользователя. */
    suspend fun conversations(): List<ConversationDto> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url("conversations")).withServerAuth().withBearer().get().build()
        httpClient.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw DuqApiException("conversations ${resp.code}")
            val type = object : TypeToken<List<ConversationDto>>() {}.type
            gson.fromJson<List<ConversationDto>>(raw, type) ?: emptyList()
        }
    }

    /** Реестр агентов ядра (профили тулсета) для пикера. */
    suspend fun listAgents(): List<AgentInfo> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url("agents")).withServerAuth().get().build()
        httpClient.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw DuqApiException("agents ${resp.code}")
            gson.fromJson(raw, AgentsResponse::class.java)?.agents ?: emptyList()
        }
    }

    /** Сообщения одного диалога. */
    suspend fun messages(convId: String): List<HistoryMsg> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url("conversations/$convId/messages")).withServerAuth().withBearer().get().build()
        httpClient.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw DuqApiException("messages ${resp.code}")
            val type = object : TypeToken<List<HistoryMsg>>() {}.type
            gson.fromJson<List<HistoryMsg>>(raw, type) ?: emptyList()
        }
    }

    // ───────── Скиллы (md-промпты) — /duq/api/skills ─────────

    suspend fun listSkills(): List<SkillDto> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url("skills")).withServerAuth().get().build()
        httpClient.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw DuqApiException("skills ${resp.code}")
            gson.fromJson(raw, SkillListDto::class.java)?.skills ?: emptyList()
        }
    }

    suspend fun createSkill(name: String, content: String, description: String?) =
        withContext(Dispatchers.IO) {
            val body = gson.toJson(SkillCreateBody(name, content, description)).toRequestBody(JSON)
            val req = Request.Builder().url(url("skills")).withServerAuth().post(body).build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful)
                    throw DuqApiException("createSkill ${resp.code}: ${resp.body?.string()?.take(200)}")
            }
        }

    suspend fun updateSkill(
        name: String, content: String? = null, description: String? = null, enabled: Boolean? = null
    ) = withContext(Dispatchers.IO) {
        val body = gson.toJson(SkillUpdateBody(content, description, enabled)).toRequestBody(JSON)
        val req = Request.Builder().url(url("skills/$name")).withServerAuth().put(body).build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw DuqApiException("updateSkill ${resp.code}")
        }
    }

    suspend fun deleteSkill(name: String) = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url("skills/$name")).withServerAuth().delete().build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful && resp.code != 404) throw DuqApiException("deleteSkill ${resp.code}")
        }
    }

    // ───────── Крон-задачи — /duq/api/scheduler/tasks ─────────

    suspend fun listCronTasks(): List<CronTaskDto> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url("scheduler/tasks")).withServerAuth().get().build()
        httpClient.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw DuqApiException("scheduler ${resp.code}")
            gson.fromJson(raw, CronTaskListDto::class.java)?.tasks ?: emptyList()
        }
    }

    suspend fun createCronTask(name: String, cron: String, skill: String, timezone: String) =
        withContext(Dispatchers.IO) {
            val body = gson.toJson(CronCreateBody(name, cron, skill, timezone)).toRequestBody(JSON)
            val req = Request.Builder().url(url("scheduler/tasks")).withServerAuth().post(body).build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful)
                    throw DuqApiException("createCron ${resp.code}: ${resp.body?.string()?.take(200)}")
            }
        }

    suspend fun deleteCronTask(taskId: String) = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url("scheduler/tasks/$taskId")).withServerAuth().delete().build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful && resp.code != 404) throw DuqApiException("deleteCron ${resp.code}")
        }
    }

    suspend fun setCronEnabled(taskId: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        val body = gson.toJson(CronEnabledBody(enabled)).toRequestBody(JSON)
        val req = Request.Builder().url(url("scheduler/tasks/$taskId")).withServerAuth().patch(body).build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw DuqApiException("setCronEnabled ${resp.code}")
        }
    }

    /** Правка крон-задачи: любое подмножество полей (Gson опускает null). */
    suspend fun updateCronTask(
        taskId: String, cron: String? = null, skill: String? = null, name: String? = null
    ) = withContext(Dispatchers.IO) {
        val body = gson.toJson(CronPatchBody(cron, skill, name)).toRequestBody(JSON)
        val req = Request.Builder().url(url("scheduler/tasks/$taskId")).withServerAuth().patch(body).build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw DuqApiException("updateCron ${resp.code}: ${resp.body?.string()?.take(200)}")
        }
    }
}

/** Ошибка вызова REST-API ядра DUQ. */
class DuqApiException(message: String) : Exception(message)

// ───────── DTO скиллов/крона ─────────

data class SkillDto(
    val name: String,
    val description: String?,
    val content: String,
    val enabled: Boolean,
    val allowed_tools: List<String>?
)
data class SkillListDto(val skills: List<SkillDto>?, val total: Int?)
data class SkillCreateBody(val name: String, val content: String, val description: String?)
data class SkillUpdateBody(val content: String?, val description: String?, val enabled: Boolean?)

data class CronTaskDto(
    val task_id: String,
    val name: String?,
    val cron: String?,
    val timezone: String?,
    val next_run: String?,
    val skill: String?,
    val enabled: Boolean = true
)
data class CronTaskListDto(val tasks: List<CronTaskDto>?, val total: Int?)
data class CronCreateBody(val name: String, val cron: String, val skill: String, val timezone: String)
data class CronEnabledBody(val enabled: Boolean)
data class CronPatchBody(val cron: String?, val skill: String?, val name: String?)
