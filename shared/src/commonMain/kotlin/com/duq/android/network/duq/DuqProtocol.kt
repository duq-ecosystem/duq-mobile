package com.duq.android.network.duq

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DTO контракта ядра DUQ (Python duq-core за nginx, домен on-za-menya.online, префикс /duq).
 * Multiplatform: kotlinx.serialization (вместо Gson — Gson только JVM).
 *
 * Контракт:
 *  - POST /duq/api/message     {message, conversation_id?, new_conversation?, agent_id?} → {task_id,status}
 *  - GET  /duq/api/conversations                           → [ConversationDto]
 *  - GET  /duq/api/conversations/{id}/messages             → [HistoryMsg]
 *  - WS   /duq/ws  → chat.message (live-синк) + REASONING_ACTION (tool-шаги) + phone.command
 *
 * Запросы несут edge-токен X-Auth-Token; /conversations|/messages — дополнительно Bearer.
 * Json настроен ignoreUnknownKeys=true, encodeDefaults=false (null-поля опускаются — как Gson).
 */

@Serializable
data class MessageRequest(
    val message: String,
    @SerialName("conversation_id") val conversationId: String? = null,
    @SerialName("new_conversation") val newConversation: Boolean? = null,
    @SerialName("agent_id") val agentId: String? = null,
    // Мультиюзер: внутренний UUID юзера (выдан при регистрации). Ядро адресует задачу ему
    // (его память/ресурсы), а не владельцу. null → старый путь (владелец) на сервере.
    @SerialName("user_id") val userId: String? = null
)

/** Обновление имени уже зарегистрированного юзера (POST /api/auth/profile) — панель «Сохранить». */
@Serializable
data class ProfileUpdateRequest(
    @SerialName("user_id") val userId: String,
    val name: String,
)

/** Регистрация члена семьи из приложения (POST /api/auth/register, method=app). */
@Serializable
data class RegisterRequest(
    // @EncodeDefault(ALWAYS): json настроен encodeDefaults=false, поэтому поле с дефолтным
    // значением ("app") выкидывалось из тела → бэкенд получал method="" → 400. Форсим кодирование.
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val method: String = "app",
    val name: String? = null
)

@Serializable
data class RegisterResponse(
    val success: Boolean = false,
    @SerialName("user_id") val userId: String? = null
)

/** Статусы интеграций юзера (GET /api/integrations?user_id=). */
@Serializable
data class IntegrationsResponse(
    @SerialName("user_id") val userId: String = "",
    val integrations: IntegrationsStatus = IntegrationsStatus()
)

@Serializable
data class IntegrationsStatus(
    val google: Boolean = false,
    val obsidian: Boolean = false
)

/** Привязка E2EE-волта юзера (POST /api/integrations/obsidian). */
@Serializable
data class ObsidianLinkRequest(
    @SerialName("user_id") val userId: String,
    val url: String,
    @SerialName("mcp_token") val mcpToken: String? = null,
    val passphrase: String,
    @SerialName("salt_b64") val saltB64: String,
    @SerialName("device_id") val deviceId: String? = null
)

/** Один агент из реестра ядра (GET /duq/api/agents). */
@Serializable
data class AgentInfo(
    val id: String,
    @SerialName("display_name") val displayName: String,
    val description: String = "",
    @SerialName("allowed_tools") val allowedTools: List<String>? = null,
    @SerialName("is_system") val isSystem: Boolean = false
)

@Serializable
data class AgentsResponse(
    val agents: List<AgentInfo> = emptyList()
)

/** Ответ POST /duq/api/message — задача поставлена в очередь. */
@Serializable
data class MessageEnqueued(
    @SerialName("task_id") val taskId: String,
    val status: String
)

/** Один диалог из GET /duq/api/conversations (отсортированы по last_message_at DESC). */
@Serializable
data class ConversationDto(
    val id: String,
    val title: String? = null,
    @SerialName("last_message_at") val lastMessageAt: Long = 0,
    @SerialName("started_at") val startedAt: Long = 0,
    @SerialName("is_active") val isActive: Boolean = false
)

/** Одно сообщение из GET /duq/api/conversations/{id}/messages. */
@Serializable
data class HistoryMsg(
    val id: String? = null,
    val role: String,    // "user" | "assistant"
    val content: String,
    @SerialName("has_audio") val hasAudio: Boolean = false,
    // Серверное время создания (Unix-секунды) — канонический порядок сообщений.
    @SerialName("created_at") val createdAt: Long = 0
)

/**
 * Live-сообщение беседы, пришедшее пушем по /duq/ws (полный синк API↔мобилка).
 */
@Serializable
data class DuqIncomingMessage(
    val messageId: String,
    val role: String,    // "user" | "assistant"
    val content: String,
    val conversationId: String? = null,
    val voice: Boolean = false
)

// ── Внутренние render-ready типы (не wire-DTO, не сериализуются) ──

/** Терминальное (или стрим-) событие ответа, которое рендерит ConversationViewModel. */
data class OcChatEvent(
    val runId: String,
    val state: String,          // "delta" | "final" | "error" | "aborted"
    val deltaText: String? = null,
    val fullText: String? = null,
    val errorMessage: String? = null
)

/** Шаг агента (tool/command) внутри ответа — live из ядра по reasoning-стриму. */
data class OcAgentStep(
    val runId: String,
    val itemId: String,
    val kind: String,    // "tool" | "command"
    val title: String,
    val status: String,  // "running" | "completed" | "failed"
    val phase: String    // "update" | "end"
)

/** Одно прошлое сообщение из истории беседы, render-ready. */
data class OcHistoryMsg(
    val role: String,
    val text: String,
    val id: String? = null,
    val hasAudio: Boolean = false,
    val createdAt: Long = 0        // серверное время (Unix-секунды) — канонический порядок
)

/** Беседа для переключателя диалогов, render-ready. */
data class DuqConversation(
    val id: String,
    val summary: String?,
    val dateLabel: String,
    val lastMessageAt: Long,
    val isActive: Boolean
)

/** Состояние WS-соединения чат-слоя. */
enum class GatewayConnectionState { DISCONNECTED, CONNECTING, CONNECTED }
