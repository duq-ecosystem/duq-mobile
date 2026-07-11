package com.duq.android.network.duq

import com.duq.android.audio.LocalStt
import com.duq.android.config.AppConfig
import com.duq.android.logging.Logger
import com.duq.android.util.fileNameOf
import com.duq.android.util.readFileBytes
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
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
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlin.concurrent.Volatile
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Фасад чата поверх ядра DUQ ([DuqRestClient]). Публичный API для потребителей
 * чата (ConversationViewModel, DuqListenerService):
 *
 *  - [start], [connectionState]
 *  - [sendMessage] → enqueue; ответ приходит СТРИМОМ по /ws (TEXT_DELTA/TEXT_DONE)
 *  - [chatEvents], [agentSteps], [incomingMessages] (потоки)
 *  - [listConversations] / [loadMessages] — список диалогов и история выбранного
 *  - [transcribeAudio]
 *
 * Multiplatform: Ktor (REST через [DuqRestClient] + multipart STT-fallback на том же
 * [HttpClient]); on-device STT — через интерфейс [LocalStt] (androidMain: whisper.cpp,
 * iosMain: деградация → серверный /stt). Без Gson/OkHttp/Hilt/java.time.
 */
@OptIn(ExperimentalUuidApi::class)
class DuqChatClient(
    private val rest: DuqRestClient,
    private val stt: LocalStt,
    private val http: HttpClient,
    private val logger: Logger,
) {
    private companion object {
        const val TAG = "DuqChat"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Мультиюзер: НИКАКОЙ авто-регистрации (решение Дениса). Регистрация — только явная через
    // экран первого входа (RegistrationScreen: имя + общий токен). Прежний init-вызов
    // ensureRegistered() без имени создавал юзера с username=NULL — убран.

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
        model: String = "", provider: String = "", isFallback: Boolean = false,
    ) {
        scope.launch {
            _incomingMessages.emit(
                DuqIncomingMessage(messageId, role, content, conversationId, voice, model, provider, isFallback)
            )
        }
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

    // runId текущего in-flight тёрна — reasoning-шаги (приходят по /duq/ws, несут
    // trace_id ядра, а не наш runId) вешаются на него (один тёрн за раз — однопользов.).
    @Volatile private var currentRunId: String? = null

    /**
     * Отправляет сообщение в ядро. Возвращается СРАЗУ — enqueue идёт асинхронно на
     * [scope], чтобы вызывающий (ViewModel) успел взвести watchdog/спиннер до прихода
     * ответа. Ответ приходит СТРИМОМ по /ws (TEXT_DELTA → onStreamDelta, TEXT_DONE →
     * onStreamDone). Ошибка enqueue → терминальный chat-event state="error".
     */
    suspend fun sendMessage(
        text: String,
        runId: String = Uuid.random().toString(),
        conversationId: String? = null,
        newConversation: Boolean = false,
        agentId: String? = null,
        modelId: String? = null,
    ) {
        currentRunId = runId
        scope.launch {
            try {
                rest.sendMessage(text, conversationId, newConversation, agentId, modelId)
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
     *  другого источника) currentRunId нет → заводим стрим-runId на первой дельте (один
     *  на весь ответ), чтобы пузырь рос и для таких сообщений тоже. */
    fun onStreamDelta(cumulative: String) {
        val rid = currentRunId ?: Uuid.random().toString().also { currentRunId = it }
        scope.launch { _chatEvents.emit(OcChatEvent(runId = rid, state = "delta", fullText = cumulative)) }
    }

    // onStreamDone (TEXT_DONE-финал) УДАЛЁН: финал ассистента идёт единым путём chat.message.

    /** Цепь моделей ядра (GET /api/models) для пикера модели в чате (Задача 16). */
    suspend fun listModels(): List<ModelInfo> = rest.listModels()

    /** TEXT_RESET — стримленный «текст» оказался tool-call'ом (llama-recovery) → чистим частичный пузырь. */
    fun onStreamReset() {
        val rid = currentRunId ?: return
        scope.launch { _chatEvents.emit(OcChatEvent(runId = rid, state = "delta", fullText = "")) }
    }

    /** Реестр агентов ядра (профили тулсета) для пикера. */
    suspend fun listAgents(): List<AgentInfo> = rest.listAgents()

    /**
     * Reasoning-событие из ядра (по /duq/ws, [DuqNodeClient]) → шаг агента в пузыре
     * текущего тёрна. Показываем порядок действий агента (какой tool вызвал) live.
     * Привязка к [currentRunId] (один активный тёрн), т.к. reasoning несёт trace_id ядра.
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
     *  датный title тоже в UTC; локальная TZ телефона рассинхронит лейбл с бакетом. */
    private fun dateLabel(startedAtEpoch: Long, fallback: String?): String {
        if (startedAtEpoch <= 0L) return fallback?.takeIf { it.isNotBlank() } ?: "Чат"
        val zone = TimeZone.UTC
        val date = Instant.fromEpochSeconds(startedAtEpoch).toLocalDateTime(zone).date
        val today = nowDateUtc(zone)
        return when (date) {
            today -> "Сегодня"
            today.minus(1, DateTimeUnit.DAY) -> "Вчера"
            else -> {
                val day = date.dayOfMonth
                val month = MONTHS_RU[date.monthNumber - 1]
                if (date.year == today.year) "$day $month" else "$day $month ${date.year}"
            }
        }
    }

    private fun nowDateUtc(zone: TimeZone): LocalDate =
        Instant.fromEpochMilliseconds(com.duq.android.util.nowMillis()).toLocalDateTime(zone).date

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
            .map { OcHistoryMsg(it.role, it.content, it.id, it.hasAudio, it.createdAt, it.model, it.provider, it.isFallback) }
            .takeLast(limit)
    }

    /** On-device STT (whisper) с fallback на серверный /stt (как в прежнем gateway).
     *  Путь к WAV — String (commonMain без java.io.File); байты читает [readFileBytes]. */
    suspend fun transcribeAudio(wavPath: String): String =
        stt.tryTranscribe(wavPath) ?: transcribeAudioOnServer(wavPath)

    // ── серверный STT-fallback (faster-whisper за nginx, edge-токен через DefaultRequest) ──
    private suspend fun transcribeAudioOnServer(wavPath: String): String {
        val bytes = readFileBytes(wavPath)
        if (bytes.isEmpty()) throw DuqApiException("STT: empty audio file")
        val resp = http.post(AppConfig.STT_URL) {
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("model", "whisper-1")
                        append("language", AppConfig.STT_LANGUAGE)
                        append(
                            "file",
                            bytes,
                            Headers.build {
                                append(HttpHeaders.ContentType, "audio/wav")
                                append(HttpHeaders.ContentDisposition, "filename=\"${fileNameOf(wavPath)}\"")
                            }
                        )
                    }
                )
            )
        }
        if (!resp.status.isSuccess()) throw DuqApiException("STT ${resp.status}")
        return resp.body<SttResponse>().text ?: throw DuqApiException("No text in STT response")
    }
}

private val MONTHS_RU = listOf(
    "января", "февраля", "марта", "апреля", "мая", "июня",
    "июля", "августа", "сентября", "октября", "ноября", "декабря"
)
