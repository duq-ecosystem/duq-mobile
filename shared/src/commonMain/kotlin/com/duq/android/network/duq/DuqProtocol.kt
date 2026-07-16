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
    @SerialName("user_id") val userId: String? = null,
    // Ручной выбор модели из пикера (Задача 16): id из GET /api/models. null → автоцепь.
    @SerialName("model_id") val modelId: String? = null
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
    @SerialName("user_id") val userId: String? = null,
    val name: String = "",
    val role: String = "",
    val token: String = "" // per-user auth_token (Telegram-вход выдаёт; app-регистрация тоже)
)

/** Native-вход через Telegram Login SDK: приложение шлёт подписанный Telegram id_token. */
@Serializable
data class TelegramNativeRequest(
    @SerialName("id_token") val idToken: String
)

/** Привязка Telegram к существующему юзеру: id_token + текущий user_id. */
@Serializable
data class TelegramLinkRequest(
    @SerialName("id_token") val idToken: String,
    @SerialName("user_id") val userId: String
)

/** Член семьи (для admin-списка всех зареганых, GET /api/family/members). */
@Serializable
data class FamilyMember(
    @SerialName("user_id") val userId: String = "",
    val name: String = "",
    val role: String = ""
)

@Serializable
data class FamilyMembersResponse(
    val members: List<FamilyMember> = emptyList()
)

/** Ответ /api/auth/google/link — URL для OAuth-входа в браузере (или error, если не настроен). */
@Serializable
data class GoogleLinkResponse(
    val url: String = "",
    val error: String = ""
)

/** Статусы интеграций юзера (GET /api/integrations?user_id=). */
@Serializable
data class IntegrationsResponse(
    @SerialName("user_id") val userId: String = "",
    val name: String = "",
    val role: String = "",
    val integrations: IntegrationsStatus = IntegrationsStatus()
)

@Serializable
data class IntegrationsStatus(
    val google: Boolean = false,
    val obsidian: Boolean = false,
    val telegram: Boolean = false
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

/** Одна модель из цепи ядра (GET /duq/api/models) — для пикера модели в чате (Задача 16). */
@Serializable
data class ModelInfo(
    val id: String, // "primary" | "fallback_1" … — уходит как model_id в запрос
    val model: String, // напр. "openai/gpt-oss-120b-Turbo"
    val provider: String, // хост, напр. "api.deepinfra.com"
    @SerialName("is_primary") val isPrimary: Boolean = false
)

@Serializable
data class ModelsResponse(
    val models: List<ModelInfo> = emptyList()
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
    val role: String, // "user" | "assistant"
    val content: String,
    @SerialName("has_audio") val hasAudio: Boolean = false,
    // Серверное время создания (Unix-секунды) — канонический порядок сообщений.
    @SerialName("created_at") val createdAt: Long = 0,
    // Задача 15 «лейбл всегда»: сервер отдаёт лейбл модели и для истории.
    val model: String = "",
    val provider: String = "",
    @SerialName("is_fallback") val isFallback: Boolean = false,
)

/**
 * Live-сообщение беседы, пришедшее пушем по /duq/ws (полный синк API↔мобилка).
 */
@Serializable
data class DuqIncomingMessage(
    val messageId: String,
    val role: String, // "user" | "assistant"
    val content: String,
    val conversationId: String? = null,
    val voice: Boolean = false,
    // Задача 15: лейбл реально ответившей модели (едет в ЕДИНОМ кадре chat.message).
    val model: String = "",
    val provider: String = "",
    val isFallback: Boolean = false,
)

// ── Внутренние render-ready типы (не wire-DTO, не сериализуются) ──

/** Терминальное (или стрим-) событие ответа, которое рендерит ConversationViewModel.
 *  Стрим ядра КУМУЛЯТИВНЫЙ: [fullText] на каждой дельте несёт весь текст с начала ответа
 *  (инкрементальных дельт в протоколе нет). */
data class OcChatEvent(
    val runId: String,
    val state: String, // "delta" | "final" | "error" | "aborted"
    val fullText: String? = null,
    val errorMessage: String? = null,
    // Финал: модель решила озвучить (set_response_mode voice) → клиент синтезирует TTS on-device.
    val voice: Boolean = false,
    // Финал (Задача 15): какая модель/провайдер реально ответили + флаг резерва (для лейбла в чате).
    val model: String = "",
    val provider: String = "",
    val isFallback: Boolean = false,
)

/** Шаг агента (tool/command) внутри ответа — live из ядра по reasoning-стриму. */
data class OcAgentStep(
    val runId: String,
    val itemId: String,
    val kind: String, // "tool" | "command"
    val title: String,
    val status: String, // "running" | "completed" | "failed"
    val phase: String // "update" | "end"
)

/** Одно прошлое сообщение из истории беседы, render-ready. */
data class OcHistoryMsg(
    val role: String,
    val text: String,
    val id: String? = null,
    val hasAudio: Boolean = false,
    val createdAt: Long = 0, // серверное время (Unix-секунды) — канонический порядок
    val model: String = "",
    val provider: String = "",
    val isFallback: Boolean = false,
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
