package com.duq.android.network.duq

import com.duq.android.audio.WhisperLocal
import com.duq.android.config.AppConfig
import com.duq.android.logging.Logger
import com.duq.android.network.withDuqDns
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
 * Фасад чата поверх ядра DUQ ([DuqRestClient]). Публичный API для потребителей
 * чата (ConversationViewModel, DuqListenerService):
 *
 *  - [start], [connectionState]
 *  - [sendMessage] → enqueue + poll-await → эмит одного терминального [OcChatEvent]
 *    (опц. адресуется в конкретную беседу conversationId / стартует новую newConversation)
 *  - [chatEvents], [agentSteps] (потоки)
 *  - [listConversations] / [loadMessages] — список диалогов и история выбранного
 *  - [transcribeAudio]
 *
 * Контракт ядра — REST + поллинг задачи (нет стрима дельт). Поэтому ответ приходит
 * одним кадром: на send эмитим `OcChatEvent(state="final", fullText=ответ)`, что
 * корректно отрисовывает ConversationViewModel (вставит сообщение, снимет спиннер) и
 * показывает фоновое уведомление в DuqListenerService. Ошибка ядра → `state="error"`.
 *
 * Агент выбирается на отправке ([sendMessage] agentId). Tool-шаги ([agentSteps])
 * приходят live через reasoning по /duq/ws (см. [onReasoning]).
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

    // Tool-шаги агента приходят live из ядра по reasoning-стриму (см. [onReasoning]).
    private val _agentSteps = MutableSharedFlow<OcAgentStep>(extraBufferCapacity = 8)
    val agentSteps: SharedFlow<OcAgentStep> = _agentSteps.asSharedFlow()

    // Live-синк: сообщения беседы, пришедшие пушем по /duq/ws (ответ бота, проактив,
    // REST из другого источника). Заполняется из [DuqNodeClient]; ViewModel рендерит
    // с дедупом. См. [onIncomingMessage].
    private val _incomingMessages = MutableSharedFlow<DuqIncomingMessage>(extraBufferCapacity = 64)
    val incomingMessages: SharedFlow<DuqIncomingMessage> = _incomingMessages.asSharedFlow()

    /** Вызывается WS-клиентом ([DuqNodeClient]) на каждый live-фрейм chat.message. */
    fun onIncomingMessage(
        messageId: String, role: String, content: String,
        conversationId: String?, voice: Boolean = false,
    ) {
        scope.launch { _incomingMessages.emit(DuqIncomingMessage(messageId, role, content, conversationId, voice)) }
    }

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

    suspend fun sendMessage(
        text: String,
        runId: String = UUID.randomUUID().toString(),
        conversationId: String? = null,
        newConversation: Boolean = false,
        agentId: String? = null,
    ) {
        currentRunId = runId
        scope.launch {
            try {
                // ТОЛЬКО ставим задачу в очередь. Ответ приходит СТРИМОМ по /ws:
                // TEXT_DELTA (рендер на лету) → onStreamDelta, TEXT_DONE → onStreamDone
                // (финал). REST-поллинг (rest.awaitResponse) ВЫРЕЗАН — больше не ждём
                // целый ответ опросом, токены летят сразу. Нет ответа за окно → watchdog
                // в ConversationViewModel покажет таймаут.
                rest.sendMessage(text, conversationId, newConversation, agentId)
            } catch (e: Exception) {
                logger.e(TAG, "sendMessage failed: ${e.message}")
                _chatEvents.emit(
                    OcChatEvent(runId = runId, state = "error", errorMessage = e.message ?: "Send failed")
                )
                if (currentRunId == runId) currentRunId = null
            }
        }
    }

    /** TEXT_DELTA — кумулятивный текст ответа на лету. Биндим к in-flight тёрну: для
     *  app-sent currentRunId уже задан в sendMessage; для server-sent (проактив/REST из
     *  другого источника/мой curl-тест) currentRunId нет → заводим стрим-runId на первой
     *  дельте (один на весь ответ), чтобы пузырь рос и для таких сообщений тоже. */
    fun onStreamDelta(cumulative: String, voice: Boolean = false) {
        val rid = currentRunId ?: java.util.UUID.randomUUID().toString().also { currentRunId = it }
        scope.launch { _chatEvents.emit(OcChatEvent(runId = rid, state = "delta", fullText = cumulative, voice = voice)) }
    }

    /** TEXT_DONE — финал стрима: финализируем пузырь (как прежний REST-final), тёрн завершён. */
    fun onStreamDone(cumulative: String, voice: Boolean = false) {
        val rid = currentRunId ?: return
        currentRunId = null
        scope.launch { _chatEvents.emit(OcChatEvent(runId = rid, state = "final", fullText = cumulative, voice = voice)) }
    }

    /** TEXT_RESET — стримленный «текст» оказался tool-call'ом (llama-recovery) → чистим частичный пузырь. */
    fun onStreamReset() {
        val rid = currentRunId ?: return
        scope.launch { _chatEvents.emit(OcChatEvent(runId = rid, state = "delta", fullText = "")) }
    }

    /** Реестр агентов ядра (профили тулсета) для пикера. */
    suspend fun listAgents(): List<AgentInfo> = rest.listAgents()

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
     * Список диалогов пользователя (для переключателя бесед). Отсортирован ядром по
     * last_message_at DESC — первый элемент = самый свежий/активный. Пусто при ошибке.
     */
    suspend fun listConversations(agentId: String? = null): List<DuqConversation> {
        val convs = runCatching { rest.conversations(agentId) }.getOrElse {
            logger.w(TAG, "conversations failed: ${it.message}"); return emptyList()
        }
        // dateLabel — человеческая русская дата (Сегодня/Вчера/«20 июня») для шапки.
        // summary — серверный topic-title (генерит ядро лёгкой моделью), null пока беседа
        // не оттайтлена (тогда серверный title ещё англ. дата-дефолт "June 22, 2026").
        return convs.map {
            DuqConversation(
                id = it.id,
                summary = topicSummaryOrNull(it.title),
                dateLabel = dateLabel(it.startedAt, it.title),
                lastMessageAt = it.lastMessageAt,
                isActive = it.isActive,
            )
        }
    }

    /** Серверный title как саммари темы, либо null если это ещё авто-дата-дефолт/пусто. */
    private fun topicSummaryOrNull(serverTitle: String?): String? {
        val t = serverTitle?.trim().orEmpty()
        if (t.isEmpty()) return null
        // Авто-дефолт ядра — английская дата "June 22, 2026" → ещё не оттайтлено темой.
        if (Regex("^[A-Z][a-z]+ \\d{1,2}, \\d{4}$").matches(t)) return null
        return t
    }

    /** Русский относительный заголовок беседы по её started_at (epoch seconds).
     *  В UTC — ядро бакетит беседы по UTC-дню (started_at >= UTC-полночь), и серверный
     *  датный title тоже в UTC; локальная TZ телефона (напр. Almaty +5) рассинхронит
     *  лейбл с бакетом (беседа «вчера по UTC» стала бы «сегодня» по локали). */
    private fun dateLabel(startedAtEpoch: Long, fallback: String?): String {
        if (startedAtEpoch <= 0L) return fallback?.takeIf { it.isNotBlank() } ?: "Чат"
        val zone = java.time.ZoneOffset.UTC
        val date = java.time.Instant.ofEpochSecond(startedAtEpoch).atZone(zone).toLocalDate()
        val today = java.time.LocalDate.now(zone)
        return when (date) {
            today -> "Сегодня"
            today.minusDays(1) -> "Вчера"
            else -> {
                val pattern = if (date.year == today.year) "d MMMM" else "d MMMM yyyy"
                date.format(java.time.format.DateTimeFormatter.ofPattern(pattern, java.util.Locale("ru")))
            }
        }
    }

    /**
     * Сообщения конкретной беседы по её id (для выбора/переключения диалога).
     * Маппится в прежний [OcHistoryMsg] (role/text), отфильтровано до user/assistant.
     */
    suspend fun loadMessages(conversationId: String, limit: Int = 100): List<OcHistoryMsg> {
        val msgs = runCatching { rest.messages(conversationId) }.getOrElse {
            logger.w(TAG, "messages($conversationId) failed: ${it.message}"); return emptyList()
        }
        return msgs
            .filter { it.role == "user" || it.role == "assistant" }
            .map { OcHistoryMsg(it.role, it.content, it.id, it.hasAudio) }
            .takeLast(limit)
    }

    /** On-device whisper с fallback на серверный /stt (как в прежнем gateway). */
    suspend fun transcribeAudio(file: File): String =
        whisper.tryTranscribe(file) ?: transcribeAudioOnServer(file)

    // ── серверный STT-fallback (faster-whisper за nginx, edge-токен) ──
    private val sttClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .withDuqDns()
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
