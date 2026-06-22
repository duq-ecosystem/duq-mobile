package com.duq.android.network.duq

import com.duq.android.audio.WhisperLocal
import com.duq.android.config.AppConfig
import com.duq.android.logging.Logger
import com.duq.android.network.withServerAuth
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Фасад чата поверх нового ядра DUQ ([DuqRestClient]). Держит ТОТ ЖЕ публичный API,
 * что ожидают потребители чата (ConversationViewModel,
 * DuqListenerService) — чтобы переключение прошло без правок их логики:
 *
 *  - [start], [connectionState]
 *  - [sendMessage] → enqueue + poll-await → эмит одного терминального [OcChatEvent]
 *  - [chatEvents], [agentSteps] (потоки)
 *  - [fetchHistory], [switchAgent], [transcribeAudio]
 *
 * Контракт ядра — REST + поллинг задачи (нет стрима дельт). Поэтому ответ приходит
 * одним кадром: на send эмитим `OcChatEvent(state="final", fullText=ответ)`, что
 * корректно отрисовывает ConversationViewModel (вставит сообщение, снимет спиннер) и
 * показывает фоновое уведомление в DuqListenerService. Ошибка ядра → `state="error"`.
 *
 * Заглушки (у ядра нет аналога — Ф3a):
 *  - [agentSteps] — всегда пустой поток (нет tool-шагов в контракте ядра);
 *  - [switchAgent] — ноп (одно ядро/один агент);
 *  - [activeAgentId] — фиксировано "main".
 */
@Singleton
class DuqChatClient @Inject constructor(
    private val rest: DuqRestClient,
    private val whisper: WhisperLocal,
    private val logger: Logger
) {
    companion object {
        private const val TAG = "DuqChat"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()

    private val _connectionState = MutableStateFlow(GatewayConnectionState.DISCONNECTED)
    val connectionState: StateFlow<GatewayConnectionState> = _connectionState.asStateFlow()

    private val _chatEvents = MutableSharedFlow<OcChatEvent>(extraBufferCapacity = 128)
    val chatEvents: SharedFlow<OcChatEvent> = _chatEvents.asSharedFlow()

    // Tool-шаги ядром не передаются — пустой поток-заглушка (ничего не эмитит).
    private val _agentSteps = MutableSharedFlow<OcAgentStep>(extraBufferCapacity = 1)
    val agentSteps: SharedFlow<OcAgentStep> = _agentSteps.asSharedFlow()

    // Live-синк: сообщения беседы, пришедшие пушем по /duq/ws (ответ бота, проактив,
    // REST из другого источника). Заполняется из [DuqNodeClient]; ViewModel рендерит
    // с дедупом. См. [onIncomingMessage].
    private val _incomingMessages = MutableSharedFlow<DuqIncomingMessage>(extraBufferCapacity = 64)
    val incomingMessages: SharedFlow<DuqIncomingMessage> = _incomingMessages.asSharedFlow()

    /** Вызывается WS-клиентом ([DuqNodeClient]) на каждый live-фрейм chat.message. */
    fun onIncomingMessage(messageId: String, role: String, content: String) {
        scope.launch { _incomingMessages.emit(DuqIncomingMessage(messageId, role, content)) }
    }

    // Одно ядро/агент. Оставлено для совместимости API с прежним gateway.
    val activeAgentId: String = "main"

    /**
     * REST stateless — реального коннекта нет. Помечаем CONNECTED, чтобы потребители,
     * ждущие `connectionState.first { it == CONNECTED }` (restoreServerHistory), пошли
     * дальше. Сетевые ошибки всплывут на конкретном запросе (send/fetch), не здесь.
     */
    fun start() {
        _connectionState.value = GatewayConnectionState.CONNECTED
    }

    fun stop() {
        _connectionState.value = GatewayConnectionState.DISCONNECTED
    }

    /**
     * Отправляет сообщение в ядро. Возвращается СРАЗУ (как прежний gateway, который
     * лишь ставил кадр в WS) — enqueue+поллинг+эмит идут асинхронно на [scope], чтобы
     * вызывающий (ViewModel) успел взвести watchdog/спиннер до прихода ответа. Ответ
     * публикуется терминальным chat-event с собственным runId; ошибка → state="error".
     */
    // runId текущего in-flight тёрна — reasoning-шаги (приходят по /duq/ws, несут
    // trace_id ядра, а не наш runId) вешаются на него (один тёрн за раз — однопользов.).
    @Volatile private var currentRunId: String? = null

    suspend fun sendMessage(text: String, runId: String = UUID.randomUUID().toString()) {
        currentRunId = runId
        scope.launch {
            try {
                val taskId = rest.sendMessage(text)
                val answer = rest.awaitResponse(taskId)
                _chatEvents.emit(
                    OcChatEvent(
                        runId = runId,
                        sessionKey = activeAgentId,
                        seq = 0,
                        state = "final",
                        fullText = answer
                    )
                )
            } catch (e: Exception) {
                logger.e(TAG, "sendMessage failed: ${e.message}")
                _chatEvents.emit(
                    OcChatEvent(
                        runId = runId,
                        sessionKey = activeAgentId,
                        seq = 0,
                        state = "error",
                        errorMessage = e.message ?: "Send failed"
                    )
                )
            } finally {
                if (currentRunId == runId) currentRunId = null
            }
        }
    }

    /**
     * Reasoning-событие из ядра (по /duq/ws, [DuqNodeClient]) → шаг агента в пузыре
     * текущего тёрна. Показываем порядок действий агента (какой tool вызвал) live —
     * раньше reasoning ядро слало, но app его игнорировал (Ф3a-заглушка). Привязка к
     * [currentRunId] (один активный тёрн), т.к. reasoning несёт trace_id ядра, не runId.
     */
    fun onReasoning(eventType: String, toolName: String?, message: String, iteration: Int) {
        val runId = currentRunId ?: return
        // Интересен порядок ДЕЙСТВИЙ (вызовов инструментов). REASONING_ACTION с tool_name.
        if (eventType != "REASONING_ACTION") return
        val tool = (toolName ?: message).ifBlank { "tool" }
        scope.launch {
            _agentSteps.emit(
                OcAgentStep(
                    runId = runId,
                    itemId = "tool:$tool-$iteration",
                    kind = "tool",
                    title = tool,
                    status = "running",
                    phase = "update",
                )
            )
        }
    }

    /**
     * История из ядра: последний диалог из /conversations → его /messages. Если
     * диалогов нет — пусто. Маппится в прежний [OcHistoryMsg] (role/text).
     */
    suspend fun fetchHistory(limit: Int = 100): List<OcHistoryMsg> {
        val convs = runCatching { rest.conversations() }.getOrElse {
            logger.w(TAG, "conversations failed: ${it.message}"); return emptyList()
        }
        val latest = convs.firstOrNull() ?: return emptyList()
        val msgs = runCatching { rest.messages(latest.id) }.getOrElse {
            logger.w(TAG, "messages failed: ${it.message}"); return emptyList()
        }
        return msgs
            .filter { it.role == "user" || it.role == "assistant" }
            .map { OcHistoryMsg(it.role, it.content) }
            .takeLast(limit)
    }

    /** Ноп: ядро одноагентное (заглушка для совместимости с API gateway). */
    fun switchAgent(agentId: String) {
        logger.d(TAG, "switchAgent($agentId) — no-op (single-agent core)")
    }

    /** On-device whisper с fallback на серверный /stt (как в прежнем gateway). */
    suspend fun transcribeAudio(file: File): String =
        whisper.tryTranscribe(file) ?: transcribeAudioOnServer(file)

    // ── серверный STT-fallback (faster-whisper за nginx, edge-токен) ──
    private val sttClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(AppConfig.CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(AppConfig.READ_TIMEOUT_S, TimeUnit.SECONDS)
            .build()
    }

    private suspend fun transcribeAudioOnServer(file: File): String = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", "whisper-1")
            .addFormDataPart("language", AppConfig.STT_LANGUAGE)
            .addFormDataPart("file", file.name, file.asRequestBody("audio/wav".toMediaType()))
            .build()
        val req = Request.Builder().url(AppConfig.STT_URL).withServerAuth().post(body).build()
        sttClient.newCall(req).execute().use { response ->
            if (!response.isSuccessful) throw DuqApiException("STT ${response.code}")
            val result = gson.fromJson<Map<String, Any?>>(
                response.body?.string() ?: "{}",
                object : TypeToken<Map<String, Any?>>() {}.type
            )
            result["text"] as? String ?: throw DuqApiException("No text in STT response")
        }
    }
}
