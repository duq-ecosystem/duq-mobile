package com.duq.android.network.duq

import com.duq.android.config.AppConfig
import com.duq.android.data.SettingsRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * REST-клиент ядра DUQ на Ktor (multiplatform). Чат: enqueue ([sendMessage]) → ответ
 * приходит СТРИМОМ по /ws (TEXT_DELTA/TEXT_DONE), REST-поллинг вырезан. История —
 * [conversations]/[messages]. Edge-токен X-Auth-Token шлётся через DefaultRequest
 * (см. DuqHttpClient); /conversations|/messages дополнительно требуют Bearer.
 */
class DuqRestClient(
    private val client: HttpClient,
    private val settings: SettingsRepository,
) {

    private fun url(path: String) = AppConfig.DUQ_API_BASE_URL + path
    private val bearer get() = "Bearer ${AppConfig.SERVER_TOKEN}"

    // Сериализует регистрацию: без него два конкурентных вызова (LaunchedEffect старта +
    // повторная композиция) оба видят пустой user_id и дважды POST'ят → дубль юзера в БД.
    private val regMutex = Mutex()

    /**
     * Мультиюзер: гарантирует, что у устройства есть персональный user_id. Нет → регистрирует
     * члена семьи (POST /api/auth/register method=app) и сохраняет user_id локально. Идемпотентно
     * (под Mutex: второй конкурентный вызов после первого уже видит сохранённый user_id).
     */
    suspend fun ensureRegistered(name: String? = null): String = regMutex.withLock {
        settings.getUserId().takeIf { it.isNotBlank() }?.let { return@withLock it }
        val resp = client.post(url("auth/register")) {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(name = name ?: settings.getUserName().ifBlank { null }))
        }
        if (!resp.status.isSuccess()) throw DuqApiException("register ${resp.status}")
        val uid = resp.body<RegisterResponse>().userId
            ?: throw DuqApiException("register: no user_id")
        settings.saveUserId(uid)
        uid
    }

    /** Статусы интеграций юзера (google/obsidian) для панели профиля. */
    suspend fun integrations(): IntegrationsResponse {
        val uid = settings.getUserId()
        if (uid.isBlank()) return IntegrationsResponse()
        val resp = client.get(url("integrations") + "?user_id=$uid")
        if (!resp.status.isSuccess()) throw DuqApiException("integrations ${resp.status}")
        return resp.body()
    }

    /** Привязать E2EE-волт юзера (его url/токены/passphrase сохраняются в ядре per-user). */
    suspend fun linkObsidian(
        vaultUrl: String,
        passphrase: String,
        saltB64: String,
        mcpToken: String? = null,
        deviceId: String? = null,
    ) {
        val uid = settings.getUserId()
        if (uid.isBlank()) throw DuqApiException("not registered")
        val resp = client.post(url("integrations/obsidian")) {
            contentType(ContentType.Application.Json)
            setBody(ObsidianLinkRequest(uid, vaultUrl, mcpToken, passphrase, saltB64, deviceId))
        }
        if (!resp.status.isSuccess()) throw DuqApiException("linkObsidian ${resp.status}")
    }

    suspend fun sendMessage(
        text: String,
        conversationId: String? = null,
        newConversation: Boolean = false,
        agentId: String? = null,
    ): String {
        val resp = client.post(url("message")) {
            contentType(ContentType.Application.Json)
            setBody(
                MessageRequest(
                    text, conversationId, if (newConversation) true else null, agentId,
                    userId = settings.getUserId().ifBlank { null },
                )
            )
        }
        if (!resp.status.isSuccess()) throw DuqApiException("message ${resp.status}")
        return resp.body<MessageEnqueued>().taskId
    }

    suspend fun conversations(agentId: String? = null): List<ConversationDto> {
        val u = url("conversations") + (agentId?.let { "?agent_id=$it" } ?: "")
        val resp = client.get(u) { header("Authorization", bearer) }
        if (!resp.status.isSuccess()) throw DuqApiException("conversations ${resp.status}")
        return resp.body()
    }

    suspend fun listAgents(): List<AgentInfo> {
        val resp = client.get(url("agents"))
        if (!resp.status.isSuccess()) throw DuqApiException("agents ${resp.status}")
        return resp.body<AgentsResponse>().agents
    }

    suspend fun listToolCategories(): List<ToolCategory> {
        val resp = client.get(url("tools"))
        if (!resp.status.isSuccess()) throw DuqApiException("tools ${resp.status}")
        return resp.body<ToolsResponse>().categories
    }

    suspend fun createAgent(id: String, displayName: String, description: String, allowedTools: List<String>?) {
        val resp = client.post(url("agents")) {
            contentType(ContentType.Application.Json)
            setBody(AgentCreateBody(id, displayName, description, allowedTools))
        }
        if (!resp.status.isSuccess()) throw DuqApiException("createAgent ${resp.status}")
    }

    suspend fun deleteAgent(id: String) {
        val resp = client.delete(url("agents/$id"))
        if (!resp.status.isSuccess() && resp.status.value != 404) throw DuqApiException("deleteAgent ${resp.status}")
    }

    suspend fun messages(convId: String): List<HistoryMsg> {
        val resp = client.get(url("conversations/$convId/messages")) { header("Authorization", bearer) }
        if (!resp.status.isSuccess()) throw DuqApiException("messages ${resp.status}")
        return resp.body()
    }

    // ───────── Скиллы ─────────

    suspend fun listSkills(): List<SkillDto> {
        val resp = client.get(url("skills"))
        if (!resp.status.isSuccess()) throw DuqApiException("skills ${resp.status}")
        return resp.body<SkillListDto>().skills ?: emptyList()
    }

    suspend fun createSkill(name: String, content: String, description: String?) {
        val resp = client.post(url("skills")) {
            contentType(ContentType.Application.Json)
            setBody(SkillCreateBody(name, content, description))
        }
        if (!resp.status.isSuccess()) throw DuqApiException("createSkill ${resp.status}")
    }

    suspend fun updateSkill(name: String, content: String? = null, description: String? = null, enabled: Boolean? = null) {
        val resp = client.put(url("skills/$name")) {
            contentType(ContentType.Application.Json)
            setBody(SkillUpdateBody(content, description, enabled))
        }
        if (!resp.status.isSuccess()) throw DuqApiException("updateSkill ${resp.status}")
    }

    suspend fun deleteSkill(name: String) {
        val resp = client.delete(url("skills/$name"))
        if (!resp.status.isSuccess() && resp.status.value != 404) throw DuqApiException("deleteSkill ${resp.status}")
    }

    // ───────── Крон-задачи ─────────

    suspend fun listCronTasks(): List<CronTaskDto> {
        val resp = client.get(url("scheduler/tasks"))
        if (!resp.status.isSuccess()) throw DuqApiException("scheduler ${resp.status}")
        return resp.body<CronTaskListDto>().tasks ?: emptyList()
    }

    suspend fun createCronTask(name: String, cron: String, skill: String, timezone: String, agentId: String = "main") {
        val resp = client.post(url("scheduler/tasks")) {
            contentType(ContentType.Application.Json)
            setBody(CronCreateBody(name, cron, skill, timezone, agentId))
        }
        if (!resp.status.isSuccess()) throw DuqApiException("createCron ${resp.status}")
    }

    suspend fun deleteCronTask(taskId: String) {
        val resp = client.delete(url("scheduler/tasks/$taskId"))
        if (!resp.status.isSuccess() && resp.status.value != 404) throw DuqApiException("deleteCron ${resp.status}")
    }

    suspend fun setCronEnabled(taskId: String, enabled: Boolean) {
        val resp = client.patch(url("scheduler/tasks/$taskId")) {
            contentType(ContentType.Application.Json)
            setBody(CronEnabledBody(enabled))
        }
        if (!resp.status.isSuccess()) throw DuqApiException("setCronEnabled ${resp.status}")
    }

    suspend fun updateCronTask(
        taskId: String, cron: String? = null, skill: String? = null, name: String? = null, agentId: String? = null
    ) {
        val resp = client.patch(url("scheduler/tasks/$taskId")) {
            contentType(ContentType.Application.Json)
            setBody(CronPatchBody(cron, skill, name, agentId))
        }
        if (!resp.status.isSuccess()) throw DuqApiException("updateCron ${resp.status}")
    }
}
