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

    // Тот же динамический edge-токен, что и в X-Auth-Token/WS (введённый юзером при входе,
    // фолбэк на build-time) — иначе Bearer расходился бы с остальным стеком при своём токене.
    private val bearer get() = "Bearer ${settings.getServerToken().ifBlank { AppConfig.SERVER_TOKEN }}"

    // Сериализует вход: без него два конкурентных вызова могли бы оба POST'нуть.
    private val regMutex = Mutex()

    /**
     * Вход/регистрация по ИМЕНИ (+ общий токен уже в X-Auth-Token). Ядро: член семьи с таким
     * именем есть → ВХОД (его user_id); нет → регистрация нового (первый = admin). Аккаунт
     * (user_id/имя/роль) сохраняется на устройстве и становится активным — мультиаккаунт.
     */
    suspend fun login(name: String): String = regMutex.withLock {
        val resp = client.post(url("auth/register")) {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(name = name))
        }
        if (!resp.status.isSuccess()) throw DuqApiException("login ${resp.status}")
        val r = resp.body<RegisterResponse>()
        val uid = r.userId ?: throw DuqApiException("login: no user_id")
        settings.upsertActiveAccount(uid, r.name.ifBlank { name }, r.role)
        uid
    }

    /** Google OAuth: получить URL для входа в браузере (per-user). Бросает с текстом, если
     *  сервер не настроен (нет GOOGLE_CLIENT_ID) — app покажет внятно. */
    suspend fun googleAuthUrl(): String {
        val uid = settings.getUserId()
        if (uid.isBlank()) throw DuqApiException("not logged in")
        val resp = client.get(url("auth/google/link") + "?user_id=$uid&chat_id=0")
        val body = resp.body<GoogleLinkResponse>()
        if (!resp.status.isSuccess() || body.url.isBlank()) {
            throw DuqApiException(body.error.ifBlank { "google link ${resp.status}" })
        }
        return body.url
    }

    /** Все члены семьи — ТОЛЬКО для admin (для секции «все пользователи» в профиле). */
    suspend fun familyMembers(): List<FamilyMember> {
        val uid = settings.getUserId()
        if (uid.isBlank()) return emptyList()
        val resp = client.get(url("family/members") + "?user_id=$uid")
        if (!resp.status.isSuccess()) return emptyList()  // не admin → 403, просто пусто
        return resp.body<FamilyMembersResponse>().members
    }

    /** Обновить имя уже зарегистрированного юзера (панель «Сохранить»; ensureRegistered тут
     *  делал бы early-return и имя не писалось бы в БД). */
    suspend fun updateProfile(name: String) {
        val uid = settings.getUserId()
        if (uid.isBlank()) throw DuqApiException("not registered")
        val resp = client.post(url("auth/profile")) {
            contentType(ContentType.Application.Json)
            setBody(ProfileUpdateRequest(uid, name))
        }
        if (!resp.status.isSuccess()) throw DuqApiException("profile ${resp.status}")
        settings.renameActive(name)
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
        // user_id активного аккаунта ОБЯЗАТЕЛЕН: бэкенд фильтрует историю по нему (мультиюзер).
        // Без него история приходила пустой (фильтр по пустому owner-sub).
        val uid = settings.getUserId()
        val params = buildList {
            if (uid.isNotBlank()) add("user_id=$uid")
            if (agentId != null) add("agent_id=$agentId")
        }
        val u = url("conversations") + if (params.isNotEmpty()) "?" + params.joinToString("&") else ""
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
        val uid = settings.getUserId()
        val u = url("conversations/$convId/messages") + if (uid.isNotBlank()) "?user_id=$uid" else ""
        val resp = client.get(u) { header("Authorization", bearer) }
        if (!resp.status.isSuccess()) throw DuqApiException("messages ${resp.status}")
        return resp.body()
    }

    // ───────── Скиллы ─────────

    suspend fun listSkills(): List<SkillDto> {
        // user_id ОБЯЗАТЕЛЕН: бэкенд фильтрует per-user скиллы (heartbeat-<uid>) по нему —
        // иначе юзер видит и чужие (мнимые «дубли»).
        val uid = settings.getUserId()
        val u = url("skills") + if (uid.isNotBlank()) "?user_id=$uid" else ""
        val resp = client.get(u)
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
        // user_id ОБЯЗАТЕЛЕН: бэкенд фильтрует кроны по нему — иначе юзер видит чужие
        // (Лерин heartbeat-крон у Дениса и т.п. = мнимые «дубли»).
        val uid = settings.getUserId()
        val u = url("scheduler/tasks") + if (uid.isNotBlank()) "?user_id=$uid" else ""
        val resp = client.get(u)
        if (!resp.status.isSuccess()) throw DuqApiException("scheduler ${resp.status}")
        return resp.body<CronTaskListDto>().tasks ?: emptyList()
    }

    suspend fun createCronTask(name: String, cron: String, skill: String, timezone: String, agentId: String = "main") {
        // user_id создателя: иначе бэкенд вешает крон на первого админа (мультиюзер-баг —
        // крон Леры уезжал бы Денису).
        val uid = settings.getUserId().ifBlank { null }
        val resp = client.post(url("scheduler/tasks")) {
            contentType(ContentType.Application.Json)
            setBody(CronCreateBody(name, cron, skill, timezone, agentId, uid))
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
