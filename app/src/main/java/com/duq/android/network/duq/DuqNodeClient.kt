package com.duq.android.network.duq

import com.duq.android.config.AppConfig
import com.duq.android.logging.Logger
import com.duq.android.network.withDuqDns
import com.duq.android.network.withServerAuth
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow

/**
 * DUQ **node** host — the "bot → phone" direction over the core's bidirectional
 * /duq/ws socket (replaces the legacy `node.invoke` mechanism).
 *
 * The core streams native commands as {type:"phone.command", request_id, command,
 * params}; we run them via [PhoneCommandExecutor] and answer with {type:"phone.result",
 * request_id, ok, payload|error}. The server tracks presence for the lifetime of
 * this socket, so simply staying connected makes phone_invoke reachable.
 *
 * Auth: the edge-token (X-Auth-Token header for the nginx gate + ?token= for the
 * core's ws auth) — same single SERVER_TOKEN the rest of the app uses.
 */
@Singleton
class DuqNodeClient @Inject constructor(
    private val executor: PhoneCommandExecutor,
    private val chatClient: DuqChatClient,
    private val logger: Logger
) {
    companion object {
        private const val TAG = "DuqNode"
        private const val DEVICE_ID = "duq-android"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()

    @Volatile private var webSocket: WebSocket? = null
    private val isManualStop = AtomicBoolean(false)
    private val isConnecting = AtomicBoolean(false)
    private val reconnectAttempt = AtomicInteger(0)

    private val _state = MutableStateFlow(GatewayConnectionState.DISCONNECTED)
    val state: StateFlow<GatewayConnectionState> = _state

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .withDuqDns()
            .connectTimeout(AppConfig.CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    }

    fun start() {
        isManualStop.set(false)
        if (_state.value == GatewayConnectionState.CONNECTED) return
        connect()
    }

    fun stop() {
        isManualStop.set(true)
        webSocket?.close(1000, "client stop")
        webSocket = null
        _state.value = GatewayConnectionState.DISCONNECTED
    }

    private fun connect() {
        if (!isConnecting.compareAndSet(false, true)) return
        _state.value = GatewayConnectionState.CONNECTING
        val token = AppConfig.SERVER_TOKEN
        val url = "${AppConfig.DUQ_WS_URL}?token=$token&device_id=$DEVICE_ID"
        val request = Request.Builder().url(url).withServerAuth().build()
        logger.d(TAG, "node connecting → /duq/ws")
        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                isConnecting.set(false)
                reconnectAttempt.set(0)
                _state.value = GatewayConnectionState.CONNECTED
                logger.d(TAG, "node connected (presence active)")
            }

            override fun onMessage(ws: WebSocket, text: String) {
                onFrame(text)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                _state.value = GatewayConnectionState.DISCONNECTED
                logger.d(TAG, "node closed code=$code reason=$reason")
                isConnecting.set(false)
                if (!isManualStop.get()) scheduleReconnect()
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                _state.value = GatewayConnectionState.DISCONNECTED
                logger.e(TAG, "node failure: ${t.message}")
                isConnecting.set(false)
                if (!isManualStop.get()) scheduleReconnect()
            }
        })
    }

    private fun onFrame(text: String) {
        val frame = runCatching {
            gson.fromJson<Map<String, Any?>>(text, object : TypeToken<Map<String, Any?>>() {}.type)
        }.getOrNull() ?: return
        when (frame["type"]) {
            "phone.command" -> handleCommand(frame)
            "chat.message" -> handleChatMessage(frame)
            else -> {
                // События ядра несут "event_type" (не "type"). REASONING_* → live-шаги
                // агента; TEXT_* → стрим текста ответа (рендер на лету вместо REST-поллинга).
                val et = frame["event_type"] as? String ?: return
                when {
                    et.startsWith("REASONING_") -> handleReasoning(et, frame)
                    et == "TEXT_DELTA" -> handleTextStream(frame, done = false)
                    et == "TEXT_DONE" -> handleTextStream(frame, done = true)
                    et == "TEXT_RESET" -> chatClient.onStreamReset()
                }
            }
        }
    }

    /** TEXT_DELTA/TEXT_DONE → стрим текста ответа (data.message = КУМУЛЯТИВНЫЙ текст). */
    private fun handleTextStream(frame: Map<String, Any?>, done: Boolean) {
        @Suppress("UNCHECKED_CAST")
        val data = (frame["data"] as? Map<String, Any?>) ?: emptyMap()
        val cumulative = data["message"] as? String ?: return
        logger.d(TAG, "TEXT_${if (done) "DONE" else "DELTA"} len=${cumulative.length}")
        if (done) chatClient.onStreamDone(cumulative) else chatClient.onStreamDelta(cumulative)
    }

    /** REASONING_* фрейм → шаг агента в текущем тёрне (через DuqChatClient). */
    private fun handleReasoning(eventType: String, frame: Map<String, Any?>) {
        @Suppress("UNCHECKED_CAST")
        val data = (frame["data"] as? Map<String, Any?>) ?: emptyMap()
        val toolName = data["tool_name"] as? String
        val message = data["message"] as? String ?: ""
        val iteration = (data["iteration"] as? Number)?.toInt() ?: 0
        logger.d(TAG, "reasoning $eventType tool=$toolName it=$iteration")
        chatClient.onReasoning(eventType, toolName, message, iteration)
    }

    /** Live chat message pushed from the core — hand to the chat client to render. */
    private fun handleChatMessage(frame: Map<String, Any?>) {
        val messageId = frame["message_id"] as? String ?: return
        val role = frame["role"] as? String ?: "assistant"
        val content = frame["content"] as? String ?: return
        val conversationId = frame["conversation_id"] as? String
        val voice = frame["voice"] as? Boolean ?: false
        logger.d(TAG, "chat.message id=${messageId.take(8)} role=$role conv=${conversationId?.take(8)} len=${content.length} voice=$voice")
        chatClient.onIncomingMessage(messageId, role, content, conversationId, voice)
    }

    private fun handleCommand(frame: Map<String, Any?>) {
        val requestId = frame["request_id"] as? String ?: return
        val command = frame["command"] as? String ?: return
        val params = (frame["params"] as? Map<*, *>) ?: emptyMap<String, Any?>()
        scope.launch {
            logger.d(TAG, "phone.command $command req=${requestId.take(8)}")
            try {
                val payload = executor.execute(command, params)
                sendResult(requestId, payload, null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e(TAG, "command $command failed: ${e.message}")
                sendResult(requestId, null, e.message ?: "command failed")
            }
        }
    }

    private fun sendResult(requestId: String, payload: Map<String, Any?>?, error: String?) {
        val ws = webSocket ?: return
        val frame = mutableMapOf<String, Any?>(
            "type" to "phone.result",
            "request_id" to requestId,
            "ok" to (error == null)
        )
        if (error != null) frame["error"] = error else frame["payload"] = (payload ?: emptyMap<String, Any?>())
        val enqueued = ws.send(gson.toJson(frame))
        logger.d(TAG, "→ phone.result req=${requestId.take(8)} ok=${error == null} enqueued=$enqueued")
    }

    private fun scheduleReconnect() {
        scope.launch {
            val attempt = reconnectAttempt.getAndIncrement()
            val backoff = (AppConfig.INITIAL_RETRY_DELAY_MS * 2.0.pow(attempt)).toLong()
                .coerceAtMost(AppConfig.MAX_RETRY_DELAY_MS)
            delay(backoff)
            if (!isManualStop.get()) connect()
        }
    }
}
