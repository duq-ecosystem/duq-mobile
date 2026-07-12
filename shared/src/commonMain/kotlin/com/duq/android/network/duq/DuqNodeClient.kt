package com.duq.android.network.duq

import com.duq.android.config.AppConfig
import com.duq.android.logging.Logger
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.concurrent.Volatile
import kotlin.math.pow

/**
 * DUQ **node** host — the "bot → phone" direction over the core's bidirectional
 * /duq/ws socket (replaces the legacy `node.invoke` mechanism), on Ktor websockets.
 *
 * The core streams native commands as {type:"phone.command", request_id, command,
 * params}; we run them via [PhoneCommandExecutor] and answer with {type:"phone.result",
 * request_id, ok, payload|error}. The server tracks presence for the lifetime of this
 * socket, so simply staying connected makes phone_invoke reachable. It also pushes
 * TEXT_* (response stream), REASONING_* (tool steps) and chat.message (live sync) →
 * relayed into [DuqChatClient].
 *
 * Auth: the edge-token — X-Auth-Token header (nginx gate, via the client's
 * DefaultRequest) + ?token= query (the core's ws auth) — same single SERVER_TOKEN.
 */
class DuqNodeClient(
    private val executor: PhoneCommandExecutor,
    private val chatClient: DuqChatClient,
    private val http: HttpClient,
    private val logger: Logger,
    private val settings: com.duq.android.data.SettingsRepository,
) {
    private companion object {
        const val TAG = "DuqNode"
        const val DEVICE_ID = "duq-android"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Volatile private var session: WebSocketSession? = null

    @Volatile private var runLoop: Job? = null

    @Volatile private var isManualStop = false

    private val _state = MutableStateFlow(GatewayConnectionState.DISCONNECTED)
    val state: StateFlow<GatewayConnectionState> = _state.asStateFlow()

    fun start() {
        isManualStop = false
        if (runLoop?.isActive == true) return
        runLoop = scope.launch { connectLoop() }
    }

    fun stop() {
        isManualStop = true
        runLoop?.cancel()
        runLoop = null
        // Ссылку фиксируем ДО обнуления поля: корутина закрытия стартует позже и читала бы
        // уже null → close не вызывался бы (сокет закрывала только отмена runLoop).
        val s = session
        session = null
        if (s != null) scope.launch { runCatching { s.close() } }
        _state.value = GatewayConnectionState.DISCONNECTED
    }

    /** Переустановить сокет с актуальными token/user_id (после логина/смены аккаунта):
     *  роняем текущую сессию — connectLoop переподключится и перечитает getUserId().
     *  Без этого до-логиновой сокет несёт пустой user_id → сервер не подписывает
     *  устройство на durable-шину, и live-доставка (reasoning/TEXT_DONE) не доходит. */
    fun reconnect() {
        if (runLoop?.isActive != true) {
            start()
            return
        }
        val s = session
        session = null
        if (s != null) scope.launch { runCatching { s.close() } }
    }

    /** Reconnect loop with exponential backoff. Each iteration owns one ws session. */
    private suspend fun connectLoop() {
        var attempt = 0
        while (!isManualStop) {
            _state.value = GatewayConnectionState.CONNECTING
            // Токен — введённый юзером (мультиаккаунт), фолбэк на build-time. user_id —
            // активный аккаунт: сервер ключует phone/reasoning-каналы по нему (мультиюзер),
            // без него дайджест/уведомления/reasoning не доходят на это устройство.
            val token = settings.getServerToken().ifBlank { AppConfig.SERVER_TOKEN }
            val uid = settings.getUserId()
            val url = "${AppConfig.DUQ_WS_URL}?token=$token&device_id=$DEVICE_ID" +
                if (uid.isNotBlank()) "&user_id=$uid" else ""
            logger.d(TAG, "node connecting → /duq/ws")
            try {
                http.webSocket(url) {
                    session = this
                    attempt = 0
                    _state.value = GatewayConnectionState.CONNECTED
                    logger.d(TAG, "node connected (presence active)")
                    for (frame in incoming) {
                        if (frame is Frame.Text) onFrame(frame.readText())
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e(TAG, "node failure: ${e.message}")
            } finally {
                session = null
                if (_state.value != GatewayConnectionState.DISCONNECTED) {
                    _state.value = GatewayConnectionState.DISCONNECTED
                }
            }
            if (isManualStop) break
            val backoff = (AppConfig.INITIAL_RETRY_DELAY_MS * 2.0.pow(attempt))
                .toLong().coerceAtMost(AppConfig.MAX_RETRY_DELAY_MS)
            attempt++
            logger.d(TAG, "node reconnect in ${backoff}ms")
            delay(backoff)
        }
    }

    private fun onFrame(text: String) {
        val frame = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        when (frame.str("type")) {
            "phone.command" -> handleCommand(frame)
            "chat.message" -> handleChatMessage(frame)
            else -> {
                // События ядра несут "event_type" (не "type"). REASONING_* → live-шаги
                // агента; TEXT_* → стрим текста ответа (рендер на лету вместо REST-поллинга).
                val et = frame.str("event_type") ?: return
                when {
                    et.startsWith("REASONING_") -> handleReasoning(et, frame)
                    // Финал ассистента идёт ЕДИНЫМ путём chat.message (см. handleChatMessage) —
                    // TEXT_DONE снят (один механизм). TEXT_DELTA (стрим по фразам) оставлен на
                    // будущее, TEXT_RESET чистит частичный пузырь.
                    et == "TEXT_DELTA" -> handleTextDelta(frame)
                    et == "TEXT_RESET" -> chatClient.onStreamReset()
                }
            }
        }
        // At-least-once: подтверждаем ПОЛУЧЕНИЕ кадра шины по его stream id (_sid) — только
        // после client-ack сервер делает XACK. Обрыв до ack → кадр переживёт в PEL и придёт
        // снова на реконнекте. Дубли безвредны (дедуп по message_id / идемпотентный TEXT_DONE).
        frame.str("_sid")?.let(::ackFrame)
    }

    /** Отправить серверу подтверждение получения кадра шины (client-ack). */
    private fun ackFrame(sid: String) {
        scope.launch {
            runCatching { session?.send(Frame.Text("""{"type":"ack","id":"$sid"}""")) }
        }
    }

    /** TEXT_DELTA/TEXT_DONE → стрим текста ответа (data.message = КУМУЛЯТИВНЫЙ текст). */
    /** TEXT_DELTA → потоковый префикс ответа (кумулятивный). Финал идёт единым путём chat.message. */
    private fun handleTextDelta(frame: JsonObject) {
        val cumulative = frame.obj("data")?.str("message") ?: return
        chatClient.onStreamDelta(cumulative)
    }

    /** REASONING_* фрейм → шаг агента в текущем тёрне (через DuqChatClient). */
    private fun handleReasoning(eventType: String, frame: JsonObject) {
        val data = frame.obj("data")
        val toolName = data?.str("tool_name")
        val message = data?.str("message") ?: ""
        val iteration = data?.int("iteration") ?: 0
        logger.d(TAG, "reasoning $eventType tool=$toolName it=$iteration")
        chatClient.onReasoning(eventType, toolName, message, iteration)
    }

    /** Live chat message pushed from the core — hand to the chat client to render. */
    private fun handleChatMessage(frame: JsonObject) {
        val messageId = frame.str("message_id") ?: return
        val role = frame.str("role") ?: "assistant"
        val content = frame.str("content") ?: "" // "" = сигнал финализации (NO_REPLY) — не return
        val conversationId = frame.str("conversation_id")
        val voice = frame.bool("voice") ?: false
        // Задача 15: лейбл реально ответившей модели (единый кадр chat.message).
        val model = frame.str("model") ?: ""
        val provider = frame.str("provider") ?: ""
        val isFallback = frame.bool("is_fallback") ?: false
        logger.d(
            TAG,
            "chat.message id=${messageId.take(
                8
            )} role=$role len=${content.length} voice=$voice model=$model fb=$isFallback"
        )
        chatClient.onIncomingMessage(messageId, role, content, conversationId, voice, model, provider, isFallback)
    }

    private fun handleCommand(frame: JsonObject) {
        val command = frame.str("command") ?: return
        // request_id ОПЦИОНАЛЕН: с ним — round-trip (исполняем + шлём phone.result); без него —
        // fire-and-forget (durable notify.show и т.п.): просто исполняем, ответ не нужен. Раньше
        // кадр без request_id молча дропался (`?: return`) → notify.show не доходил.
        val requestId = frame.str("request_id")
        val params = frame.obj("params")?.toAnyMap() ?: emptyMap<String, Any?>()
        scope.launch {
            logger.d(TAG, "phone.command $command req=${requestId?.take(8) ?: "-"}")
            try {
                val payload = executor.execute(command, params)
                if (requestId != null) sendResult(requestId, payload, null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e(TAG, "command $command failed: ${e.message}")
                if (requestId != null) sendResult(requestId, null, e.message ?: "command failed")
            }
        }
    }

    private suspend fun sendResult(requestId: String, payload: Map<String, Any?>?, error: String?) {
        val ws = session ?: return
        val frame = buildJsonObject {
            put("type", "phone.result")
            put("request_id", requestId)
            put("ok", error == null)
            if (error != null) {
                put("error", error)
            } else {
                put("payload", anyMapToJson(payload ?: emptyMap()))
            }
        }
        runCatching { ws.send(frame.toString()) }
            .onSuccess { logger.d(TAG, "→ phone.result req=${requestId.take(8)} ok=${error == null}") }
            .onFailure { logger.e(TAG, "phone.result send failed: ${it.message}") }
    }
}

// ── JsonObject helpers (lenient frame access) ──

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.bool(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.content?.toIntOrNull()

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

/** phone.command params → plain Map<String,Any?> for [PhoneCommandExecutor].
 *  Numbers become Double (the Android executor casts `params["maxMs"] as? Double`). */
private fun JsonObject.toAnyMap(): Map<String, Any?> =
    mapValues { (_, v) -> v.toAny() }

private fun JsonElement.toAny(): Any? = when (this) {
    is JsonNull -> null
    is JsonObject -> toAnyMap()
    is JsonArray -> map { it.toAny() }
    is JsonPrimitive -> when {
        isString -> content
        booleanOrNull != null -> booleanOrNull
        else -> content.toDoubleOrNull() ?: content
    }
}

/** phone.result payload (Map<String,Any?>) → JsonObject for the ws reply. */
private fun anyMapToJson(map: Map<String, Any?>): JsonObject = buildJsonObject {
    for ((k, v) in map) put(k, anyToJson(v))
}

private fun anyToJson(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is JsonElement -> v
    is String -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    is Map<*, *> -> buildJsonObject {
        for ((k, value) in v) put(k.toString(), anyToJson(value))
    }
    is List<*> -> JsonArray(v.map { anyToJson(it) })
    else -> JsonPrimitive(v.toString())
}
