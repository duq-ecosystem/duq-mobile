package com.duq.android.ui

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.duq.android.audio.AudioRecorderInterface
import com.duq.android.audio.ChatAudioPlaybackManager
import com.duq.android.data.model.Message
import com.duq.android.data.model.MessageRole
import com.duq.android.data.model.VoicePhase
import com.duq.android.error.DuqError
import com.duq.android.network.duq.DuqChatClient
import com.duq.android.network.duq.DuqIncomingMessage
import com.duq.android.network.duq.GatewayConnectionState
import com.duq.android.network.duq.OcAgentStep
import com.duq.android.network.duq.OcChatEvent
import com.duq.android.update.AppUpdater
import com.duq.android.util.ReplyText
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/** Push-to-talk voice input lifecycle (separate from the chat-streaming state). */
enum class VoiceInputState { IDLE, RECORDING, TRANSCRIBING }

@HiltViewModel
class ConversationViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gatewayClient: DuqChatClient,
    private val audioPlaybackManager: ChatAudioPlaybackManager,
    private val audioRecorder: AudioRecorderInterface,
    private val ttsLocal: com.duq.android.audio.TtsLocal,
    private val ttsClient: com.duq.android.network.TtsClient,
    private val notificationInbox: com.duq.android.data.NotificationInbox,
) : ViewModel() {

    /** Центр уведомлений (🔔) — единый, дайджесты тоже здесь (раздел в шторке). */
    val inboxItems = notificationInbox.items

    fun refreshInbox() = notificationInbox.refresh()
    fun clearInbox() = notificationInbox.clear()

    companion object {
        private const val TAG = "ConversationViewModel"
        // No reply (not even a tool step) within this window after the last sign of
        // life ⇒ surface a timeout message rather than spin forever. Generous because
        // a cold memory-recall + model turn can legitimately take ~30-60s.
        private const val REPLY_TIMEOUT_MS = 90_000L
    }

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    val connectionState: StateFlow<GatewayConnectionState> = gatewayClient.connectionState

    private val _error = MutableStateFlow<DuqError?>(null)
    val error: StateFlow<DuqError?> = _error.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _voiceInput = MutableStateFlow(VoiceInputState.IDLE)
    val voiceInput: StateFlow<VoiceInputState> = _voiceInput.asStateFlow()

    // Multi-step progress (tool calls / commands the agent runs mid-reply) is bound
    // to its reply message by runId and rendered inline as a collapsible "tool use"
    // block in the bubble (like Claude) — see [ChatStepReducer]. No separate list.

    // Downloaded-but-not-installed update version (0 = none) → in-app update banner.
    private val _updateReadyVersion = MutableStateFlow(0)
    val updateReadyVersion: StateFlow<Int> = _updateReadyVersion.asStateFlow()

    // True while the 33MB APK is downloading after the user tapped "УСТАНОВИТЬ", so
    // the banner can show "Скачиваю…" instead of looking frozen for ~10-30s.
    private val _updateInstalling = MutableStateFlow(false)
    val updateInstalling: StateFlow<Boolean> = _updateInstalling.asStateFlow()

    // Прогресс скачивания APK (0..1) для прогресс-бара в баннере обновления.
    private val _updateProgress = MutableStateFlow(0f)
    val updateProgress: StateFlow<Float> = _updateProgress.asStateFlow()

    // Push-to-talk recording coroutine; record() suspends until stopVoiceInput()
    // flips the recorder, then the same coroutine runs STT + send.
    private var recordingJob: Job? = null

    // Id исходящего голосового пузыря, который стримит фазы (запись → распознавание)
    // прямо в чате, пока идёт ввод. По готовности он же становится обычным сообщением.
    private var pendingVoiceMsgId: String? = null

    private fun updatePendingVoice(transform: (Message) -> Message) {
        val id = pendingVoiceMsgId ?: return
        _messages.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    private fun removePendingVoice() {
        val id = pendingVoiceMsgId ?: return
        _messages.update { list -> list.filterNot { it.id == id } }
        pendingVoiceMsgId = null
    }

    // Whether the turn currently being answered came from voice — drives contextual
    // TTS (speak the reply only when the user spoke, never for typed messages).
    private var pendingVoiceReplyRunId: String? = null
    private var lastInputWasVoice = false

    private var currentRunId: String? = null
    private val streamBuffer = StringBuilder()

    // Reply watchdog: arms on send and re-arms on every sign of life (delta / tool
    // step). If NOTHING terminal (final/error/aborted) arrives within the window —
    // e.g. the gateway dropped the run, a sessionKey mismatch silently swallowed the
    // frames, or the model hung — we surface a clear message instead of spinning
    // forever. The bug this guards: a stuck spinner with no explanation (06-19).
    private var replyWatchdog: Job? = null

    private fun armReplyWatchdog() {
        replyWatchdog?.cancel()
        _isProcessing.value = true
        replyWatchdog = viewModelScope.launch {
            kotlinx.coroutines.delay(REPLY_TIMEOUT_MS)
            flog.w(TAG, "reply watchdog fired — no terminal chat event in ${REPLY_TIMEOUT_MS}ms")
            currentRunId = null; streamBuffer.clear(); _isProcessing.value = false
            _messages.update { msgs ->
                val cleared = msgs.map { if (it.isStreaming) it.copy(isStreaming = false) else it }
                cleared + Message(
                    role = MessageRole.ASSISTANT,
                    content = "⚠️ Ответ не пришёл за ${REPLY_TIMEOUT_MS / 1000} с — сервер не прислал результат " +
                        "(возможен обрыв сессии или зависший прогон). Повтори запрос; если повторяется — проверь gateway."
                )
            }
            _error.value = DuqError.NetworkError("Reply timeout (${REPLY_TIMEOUT_MS / 1000}s)")
        }
    }

    private fun disarmReplyWatchdog() { replyWatchdog?.cancel(); replyWatchdog = null }
    // Runs that already reached "final"/"aborted". Late frames for them (possible on
    // any reorder) must be ignored so they don't resurrect an orphan bubble. Bounded
    // LRU set — keeping more than one guards against two runs finalizing back-to-back.
    private val finalizedRunIds = object : LinkedHashMap<String, Boolean>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Boolean>) = size > 32
    }

    // Live-синк: серверные id уже отрендеренных push-сообщений (идемпотентность —
    // один и тот же push/реконнект не задвоит). Bounded LRU.
    private val seenServerMsgIds = object : LinkedHashMap<String, Boolean>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Boolean>) = size > 128
    }

    /** Совпадает ли (role, content) с одним из последних сообщений (echo своих
     *  отправок / уже отрисованного REST-ответа / перекрытия с историей). */
    private fun isRecentDuplicate(role: MessageRole, content: String): Boolean {
        val norm = content.trim()
        return _messages.value.takeLast(8).any { it.role == role && it.content.trim() == norm }
    }

    /** Live-сообщение беседы из push (/duq/ws): рендерим, если относится к активной
     *  беседе и не дубль. */
    private fun handleIncomingMessage(msg: DuqIncomingMessage) {
        if (msg.content.isBlank()) return
        // Фильтр по активной беседе: пуш ДРУГОЙ беседы (напр. из telegram) не льём в
        // текущий вид. Новый чат (active==null) ещё не знает свой id — первый пуш его
        // сообщает: усыновляем id и подтягиваем список, чтобы беседа была в переключателе.
        val active = _activeConversationId.value
        val convId = msg.conversationId
        if (active != null && convId != null && convId != active) return
        if (active == null && convId != null) {
            _activeConversationId.value = convId
            loadConversations()
        }
        if (seenServerMsgIds.put(msg.messageId, true) != null) return  // уже видели этот id
        val role = MessageRole.fromApiString(msg.role)
        // Свой тёрн в полёте: ответ ассистента из push заполняет пузырь-плейсхолдер
        // (тот же runId, на нём уже live-висят reasoning-шаги) — НЕ плодим второй пузырь
        // и не теряем шаги. REST-финал (тот же runId) затем идемпотентно заполнит его же.
        if (role == MessageRole.ASSISTANT) {
            val rid = currentRunId
            if (rid != null && _messages.value.any { it.id == rid }) {
                _messages.update { msgs ->
                    val upd = msgs.map {
                        if (it.id == rid) it.copy(content = ReplyText.clean(msg.content), isStreaming = false) else it
                    }
                    ChatStepReducer.markAllStepsDone(upd, rid)
                }
                // Модель решила озвучить → синтез TTS на пузыре этого тёрна.
                if (msg.voice) speakReply(rid, ReplyText.clean(msg.content))
                return
            }
        }
        // Озвучка решения модели — ДО дедупа пузыря: даже если пузырь-дубликат (история/
        // REST), голос озвучить нужно (spokenMsgIds защитит от повторного синтеза).
        if (msg.voice && role == MessageRole.ASSISTANT) speakReply(msg.messageId, msg.content)
        if (isRecentDuplicate(role, msg.content)) return  // своё/REST/история — не дублируем
        _messages.update { it + Message(id = msg.messageId, role = role, content = msg.content) }
    }

    private val flog = com.duq.android.logging.FileLogger(context)

    // ── Переключатель диалогов (ядро одноагентное → единица переключения = беседа,
    //    не агент; прежний picker main/strain/digest был мёртв — switchAgent грузил ту
    //    же историю). Список бесед из /conversations, выбор грузит /messages беседы,
    //    отправка адресуется в активную беседу (conversation_id), «Новый чат» стартует
    //    свежую (new_conversation). ──
    private val _conversations = MutableStateFlow<List<com.duq.android.network.duq.DuqConversation>>(emptyList())
    val conversations: StateFlow<List<com.duq.android.network.duq.DuqConversation>> = _conversations.asStateFlow()

    // Активная беседа: id (null = новый ещё-не-сохранённый чат) + заголовок для шапки.
    private val _activeConversationId = MutableStateFlow<String?>(null)
    val activeConversationId: StateFlow<String?> = _activeConversationId.asStateFlow()
    private val _activeConversationTitle = MutableStateFlow("DUQ")
    val activeConversationTitle: StateFlow<String> = _activeConversationTitle.asStateFlow()

    // Следующая отправка должна начать НОВЫЙ диалог (взведено кнопкой «Новый чат»).
    @Volatile private var pendingNewConversation = false

    // Мульти-агенты: реестр агентов (из /api/agents) + выбранный агент. Каждый
    // агент = своя сессия/память/тулсет (ядро изолирует по agent_id). Смена агента
    // = свой чат: чистим видимую историю и начинаем сессию выбранного агента.
    private val _agents = MutableStateFlow<List<com.duq.android.network.duq.AgentInfo>>(emptyList())
    val agents: StateFlow<List<com.duq.android.network.duq.AgentInfo>> = _agents.asStateFlow()
    private val _activeAgentId = MutableStateFlow("main")
    val activeAgentId: StateFlow<String> = _activeAgentId.asStateFlow()

    fun loadAgents() {
        viewModelScope.launch {
            runCatching { gatewayClient.listAgents() }
                .onSuccess { _agents.value = it }
                .onFailure { Log.w(TAG, "loadAgents failed: ${it.message}") }
        }
    }

    /** Switch active agent — own isolated session: clear visible history, fresh chat. */
    fun switchAgent(agentId: String) {
        if (agentId == _activeAgentId.value) return
        _activeAgentId.value = agentId
        _messages.value = emptyList()
        _activeConversationId.value = null
        pendingNewConversation = true
        _activeConversationTitle.value = _agents.value.firstOrNull { it.id == agentId }?.displayName ?: "DUQ"
    }

    init {
        gatewayClient.start()
        collectChatEvents()
        refreshUpdateState()
        loadAgents()
        restoreServerHistory()
    }

    /** Перечитать список бесед (для переключателя). Тихо игнорит сетевые ошибки. */
    fun loadConversations() {
        viewModelScope.launch {
            val list = runCatching { gatewayClient.listConversations() }.getOrElse {
                flog.w(TAG, "listConversations failed: ${it.message}"); return@launch
            }
            _conversations.value = list
        }
    }

    /** Переключиться на беседу: грузим её сообщения, дальнейшая отправка идёт в неё. */
    fun selectConversation(id: String) {
        if (id == _activeConversationId.value) return
        _activeConversationId.value = id
        pendingNewConversation = false
        _activeConversationTitle.value = _conversations.value.firstOrNull { it.id == id }?.dateLabel ?: "Чат"
        _messages.value = emptyList()
        currentRunId = null
        disarmReplyWatchdog(); _isProcessing.value = false
        viewModelScope.launch {
            val history = runCatching { gatewayClient.loadMessages(id) }.getOrElse {
                flog.e(TAG, "loadMessages($id) failed: ${it.message}", it); emptyList()
            }
            // Применяем только если пользователь не переключился снова за время загрузки.
            if (_activeConversationId.value != id) return@launch
            _messages.value = history.map {
                Message(role = MessageRole.fromApiString(it.role), content = it.text)
            }
            flog.i(TAG, "selectConversation($id): ${history.size} messages")
        }
    }

    /** Начать новый диалог: сбрасываем вид; первое сообщение уйдёт с new_conversation. */
    fun newConversation() {
        _activeConversationId.value = null
        pendingNewConversation = true
        _activeConversationTitle.value = "Новый чат"
        _messages.value = emptyList()
        currentRunId = null
        disarmReplyWatchdog(); _isProcessing.value = false
    }

    /**
     * Restore the chat from the server-side transcript (`chat.history`) once the
     * gateway is connected. The gateway is the single source of truth for history
     * (shared across all devices), so we don't keep a local copy — this is the
     * server-as-source-of-truth way and fixes "chat resets every launch" at the root.
     */
    private fun restoreServerHistory() {
        viewModelScope.launch {
            connectionState.first { it == GatewayConnectionState.CONNECTED }
            val list = runCatching { gatewayClient.listConversations() }.getOrElse {
                flog.e(TAG, "listConversations failed: ${it.message}", it); emptyList()
            }
            _conversations.value = list
            // Самая свежая беседа (ядро вернуло DESC) — активная при старте.
            val first = list.firstOrNull() ?: return@launch
            _activeConversationId.value = first.id
            _activeConversationTitle.value = first.dateLabel
            val history = runCatching { gatewayClient.loadMessages(first.id) }.getOrElse {
                flog.e(TAG, "loadMessages failed: ${it.message}", it); emptyList()
            }
            flog.i(TAG, "restoreServerHistory: conv=${first.id} ${history.size} messages")
            if (history.isEmpty()) return@launch
            val restored = history.map {
                Message(role = MessageRole.fromApiString(it.role), content = it.text)
            }
            // Seed from the server transcript ONLY while the chat is still empty —
            // the normal cold-start case. If a reply already streamed in during the
            // connect window (or the user sent something), DON'T clobber/duplicate it:
            // a streaming placeholder wouldn't match history by text and would survive
            // as a second bubble once it finalized. Skipping restore in that rare case
            // is safe — the transcript reloads on the next launch.
            _messages.update { live -> if (live.isEmpty()) restored else live }
        }
    }

    // Both collectors run on viewModelScope (Dispatchers.Main.immediate), i.e. the
    // same thread, so chat-event and agent-step handlers never touch the step map
    // concurrently — no extra synchronization needed.
    private fun collectChatEvents() {
        viewModelScope.launch {
            gatewayClient.chatEvents.collect { event -> handleChatEvent(event) }
        }
        viewModelScope.launch {
            gatewayClient.agentSteps.collect { step -> handleAgentStep(step) }
        }
        viewModelScope.launch {
            gatewayClient.incomingMessages.collect { msg -> handleIncomingMessage(msg) }
        }
    }

    private fun handleAgentStep(step: OcAgentStep) {
        // No finalized-run guard here: steps live inside their message (keyed by
        // runId+callId), so a late frame just upserts the existing step in place —
        // it can't resurrect an orphan. Crucially this lets a `tool:end` that lands
        // just after the chat `final` still upgrade the step's label/kind from the
        // `command` detail to the authoritative `tool` one.
        val finished = step.phase == "end" || step.status == "completed" || step.status == "failed"
        // the engine emits up to two items per call (tool + its command/patch detail)
        // sharing one toolCallId — itemId is "tool:<callId>"/"command:<callId>". Key
        // by the callId so the pair collapses into one step (the engine's own model).
        // A tool step is a sign of life too (a cold memory recall can run for tens of
        // seconds before the first text delta) — keep the watchdog from firing on it.
        if (!finished) armReplyWatchdog()
        val callId = step.itemId.substringAfter(':', step.itemId)
        _messages.update { msgs ->
            ChatStepReducer.upsertStep(msgs, step.runId, callId, stepLabel(step), step.kind, finished)
        }
    }

    /** Short, friendly label for a tool/command step (icon + trimmed title). */
    private fun stepLabel(step: OcAgentStep): String {
        val t = step.title.lowercase()
        val icon = when {
            "camera" in t || "snap" in t || "photo" in t || "фото" in t -> "📷"
            "screen" in t || "record" in t -> "🎬"
            "mail" in t || "gmail" in t || "почт" in t || "inbox" in t -> "📧"
            "search" in t || "find" in t || "поиск" in t || "grep" in t -> "🔍"
            "image" in t || "vision" in t -> "🖼"
            "location" in t || "geo" in t -> "📍"
            "notify" in t -> "🔔"
            step.kind == "command" -> "⚙️"
            else -> "🔧"
        }
        val title = step.title.removePrefix("command ").removePrefix("nodes ").trim().take(48)
        return "$icon ${title.ifBlank { step.kind }}"
    }

    private fun handleChatEvent(event: OcChatEvent) {
        // Ignore any straggler frame for a run we already closed out.
        if (finalizedRunIds.containsKey(event.runId)) return
        when (event.state) {
            "delta" -> {
                // Sign of life — push the watchdog out while text keeps streaming.
                armReplyWatchdog()
                if (currentRunId != event.runId) {
                    currentRunId = event.runId
                    streamBuffer.clear()
                    // A tool step may have already created this bubble (steps can
                    // precede the first text delta) — only insert if it's absent, so
                    // we keep its steps and never double the bubble.
                    _messages.update { msgs ->
                        if (msgs.any { it.id == event.runId }) msgs
                        else msgs + Message(id = event.runId, role = MessageRole.ASSISTANT, content = "", isStreaming = true)
                    }
                    _isProcessing.value = true
                    // Bind this reply to the voice turn that triggered it, so only
                    // it gets spoken (contextual TTS). Typed turns leave it null.
                    if (lastInputWasVoice) {
                        pendingVoiceReplyRunId = event.runId
                        lastInputWasVoice = false
                    }
                }
                // Prefer the server's cumulative message text (authoritative, robust to
                // any reordering); fall back to accumulating deltas if it's absent.
                val cumulative = event.fullText?.also { streamBuffer.setLength(0); streamBuffer.append(it) }
                    ?: run {
                        val delta = event.deltaText ?: return
                        streamBuffer.append(delta); streamBuffer.toString()
                    }
                val live = ReplyText.clean(cumulative)
                _messages.update { msgs ->
                    msgs.map { if (it.id == event.runId) it.copy(content = live) else it }
                }
            }
            "final" -> {
                disarmReplyWatchdog()
                val finalContent = ReplyText.clean(event.fullText ?: streamBuffer.toString())
                currentRunId = null; streamBuffer.clear(); _isProcessing.value = false
                finalizedRunIds[event.runId] = true

                // Decide + consume the voice flags ONCE here, so they're always
                // cleared — covers replies that stream deltas (pending was set),
                // replies with no deltas (only lastInputWasVoice is set), and empty
                // NO_REPLY turns (clear so the next typed reply isn't spoken).
                // Озвучка по РЕШЕНИЮ модели идёт через chat.message (handleIncomingMessage).
                // Здесь — только legacy-путь «голосом спросил → голосом ответил».
                val speakThisReply = event.runId == pendingVoiceReplyRunId || lastInputWasVoice
                if (event.runId == pendingVoiceReplyRunId) pendingVoiceReplyRunId = null
                lastInputWasVoice = false

                // Pure NO_REPLY sentinel — engine asked to surface nothing. Drop the placeholder.
                if (finalContent.isEmpty()) {
                    _messages.update { msgs -> msgs.filter { it.id != event.runId } }
                    return
                }

                // Update the streaming placeholder, or insert the message if no
                // delta ever created one (e.g. a reply with no streamed deltas).
                // Also settle any tool steps whose "end" frame never arrived, so the
                // collapsed block doesn't keep a spinner after the reply is done.
                _messages.update { msgs ->
                    val updated = when {
                        msgs.any { it.id == event.runId } ->
                            msgs.map { if (it.id == event.runId) it.copy(content = finalContent, isStreaming = false) else it }
                        // Live-push мог уже отрисовать этот же ответ (гонка push↔REST) —
                        // не дублируем по содержимому.
                        msgs.takeLast(8).any { it.role == MessageRole.ASSISTANT && it.content.trim() == finalContent.trim() } ->
                            msgs
                        else ->
                            msgs + Message(id = event.runId, role = MessageRole.ASSISTANT, content = finalContent, isStreaming = false)
                    }
                    ChatStepReducer.markAllStepsDone(updated, event.runId)
                }

                // Contextual TTS: speak the reply only if this turn came from voice.
                if (speakThisReply) speakReply(event.runId, finalContent)
            }
            "aborted", "error" -> {
                disarmReplyWatchdog()
                val errText = event.errorMessage ?: "Error"
                currentRunId = null; streamBuffer.clear(); _isProcessing.value = false
                finalizedRunIds[event.runId] = true
                if (event.runId == pendingVoiceReplyRunId) pendingVoiceReplyRunId = null
                lastInputWasVoice = false
                _messages.update { msgs ->
                    val updated = msgs.map { if (it.id == event.runId) it.copy(content = it.content.ifEmpty { "[$errText]" }, isStreaming = false) else it }
                    ChatStepReducer.markAllStepsDone(updated, event.runId)
                }
                _error.value = DuqError.NetworkError(errText)
            }
        }
    }

    fun sendTextMessage(text: String) {
        if (text.isBlank()) return
        // runId тёрна задаём ЗДЕСЬ и сразу заводим пустой пузырь ассистента (streaming):
        // на него live-вешаются reasoning-шаги (что агент вызывает по порядку), пока ядро
        // думает; финальный текст (тот же runId) заполнит пузырь. Без deltas у ядра это
        // единственный способ показать live-активность агента.
        val runId = java.util.UUID.randomUUID().toString()
        currentRunId = runId
        _messages.update {
            it + Message(role = MessageRole.USER, content = text) +
                Message(id = runId, role = MessageRole.ASSISTANT, content = "", isStreaming = true)
        }
        _isProcessing.value = true
        // Адресуем в активную беседу (null = ядро возьмёт активную). Флаг нового чата
        // потребляем один раз — второе сообщение того же чата уже не создаёт диалог.
        val convId = _activeConversationId.value
        val isNew = pendingNewConversation
        pendingNewConversation = false
        viewModelScope.launch {
            try {
                gatewayClient.sendMessage(
                    text, runId, conversationId = convId, newConversation = isNew,
                    agentId = _activeAgentId.value,
                )
                armReplyWatchdog()
            } catch (e: Exception) {
                Log.e(TAG, "sendMessage failed: ${e.message}")
                disarmReplyWatchdog(); _isProcessing.value = false
                _error.value = DuqError.NetworkError(e.message ?: "Send failed")
            }
        }
    }

    // ---- Push-to-talk (hold-to-talk) ----------------------------------------

    /** Press: start recording. Runs until [stopVoiceInput] or [cancelVoiceInput]. */
    fun startVoiceInput() {
        if (recordingJob != null) return
        _voiceInput.value = VoiceInputState.RECORDING
        // Исходящий пузырь появляется СРАЗУ и стримит фазы (запись → распознавание)
        // внутри себя — как блок tool-use у ответа бота, а не надписью за уткой.
        val msgId = java.util.UUID.randomUUID().toString()
        pendingVoiceMsgId = msgId
        _messages.update {
            it + Message(
                id = msgId, role = MessageRole.USER, content = "",
                voicePhase = VoicePhase.RECORDING, isStreaming = true
            )
        }
        recordingJob = viewModelScope.launch {
            val file = File(context.cacheDir, "ptt_input.wav")
            try {
                // useVad=false: hold-to-talk — the user controls the endpoint, so
                // natural pauses must not cut the recording short.
                val captured = audioRecorder.record(file, useVad = false)
                if (!captured) { removePendingVoice(); return@launch }

                _voiceInput.value = VoiceInputState.TRANSCRIBING
                updatePendingVoice { it.copy(voicePhase = VoicePhase.TRANSCRIBING) }
                val transcript = gatewayClient.transcribeAudio(file)
                if (transcript.isBlank()) { removePendingVoice(); return@launch }

                // Фаза гаснет — пузырь становится обычным сообщением с транскриптом.
                updatePendingVoice {
                    it.copy(content = transcript, voicePhase = null, isStreaming = false)
                }
                // Mark BEFORE sending: the reply's first delta can arrive before
                // sendMessage() returns, and the delta handler reads this flag.
                lastInputWasVoice = true
                val convId = _activeConversationId.value
                val isNew = pendingNewConversation
                pendingNewConversation = false
                gatewayClient.sendMessage(transcript, conversationId = convId, newConversation = isNew)
                armReplyWatchdog()
            } catch (e: CancellationException) {
                removePendingVoice()
                throw e // user cancelled (slide-away / background) — not an error
            } catch (e: Exception) {
                removePendingVoice()
                Log.e(TAG, "Voice input failed: ${e.message}")
                _error.value = DuqError.NetworkError(e.message ?: "Voice input failed")
            } finally {
                file.delete()
                _voiceInput.value = VoiceInputState.IDLE
                recordingJob = null
                pendingVoiceMsgId = null
            }
        }
    }

    /** Release: stop recording; the recording coroutine continues into STT + send. */
    fun stopVoiceInput() {
        if (recordingJob == null) return
        audioRecorder.stopRecording()
    }

    /** Cancel: discard the recording entirely (e.g. slide-to-cancel). */
    fun cancelVoiceInput() {
        audioRecorder.stopRecording()
        recordingJob?.cancel()
        recordingJob = null
        _voiceInput.value = VoiceInputState.IDLE
    }

    /** Synthesize + play the reply audio (contextual TTS for voice turns only). */
    // Уже озвученные ответы — чтобы chat.message-voice и task-result-voice (два пути
    // доставки решения модели) не синтезировали один ответ дважды.
    private val spokenMsgIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private fun speakReply(messageId: String, text: String) {
        if (text.isBlank()) return
        if (!spokenMsgIds.add(messageId)) return
        Log.i(TAG, "speakReply start id=${messageId.take(8)} len=${text.length}")
        viewModelScope.launch {
            val audio = try {
                // On-device синтез (sherpa-onnx + Piper); null → fallback на серверный /tts.
                val onDevice = ttsLocal.trySynthesize(text, messageId)
                Log.i(TAG, "speakReply on-device=${if (onDevice != null) "ok(${onDevice.length()}b)" else "null→server"}")
                onDevice ?: ttsClient.synthesize(text, messageId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "TTS failed: ${e.message}"); null
            } ?: run { Log.e(TAG, "speakReply: no audio (both null)"); return@launch }
            Log.i(TAG, "speakReply play id=${messageId.take(8)} bytes=${audio.length()}")
            // Помечаем сообщение как голосовое → в пузыре появляется кнопка play/pause
            // (как в Telegram): можно переслушать ответ, а не только разовое авто-проигрывание.
            _messages.update { msgs ->
                msgs.map { if (it.id == messageId) it.copy(hasAudio = true) else it }
            }
            audioPlaybackManager.play(messageId, audio)
        }
    }

    /** Re-read the cached "available" version (instant, no network). */
    fun refreshUpdateState() {
        _updateReadyVersion.value = AppUpdater.availableVersion(context)
    }

    /**
     * Fast check on every return to the app: fetch version.json only (~1s, no
     * 33MB download) so the banner appears IMMEDIATELY. Download is deferred to
     * installUpdate() (banner/notification tap).
     */
    fun checkForUpdate() {
        refreshInbox() // pick up items recorded while backgrounded
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val v = try { AppUpdater(context).checkAvailable() } catch (_: Exception) { 0 }
            _updateReadyVersion.value = if (v > 0) v else AppUpdater.availableVersion(context)
        }
    }

    @Volatile private var installing = false

    /** Download + install the available update (in-app banner button). */
    fun installUpdate() {
        if (installing) return // guard double-tap → no parallel downloads/sessions
        installing = true
        _updateInstalling.value = true // instant banner feedback ("Скачиваю…")
        Log.i(TAG, "installUpdate(): starting download+install")
        _updateProgress.value = 0f
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                AppUpdater(context).downloadAndInstall(onProgress = { p -> _updateProgress.value = p })
            } finally {
                installing = false
                _updateInstalling.value = false
                _updateProgress.value = 0f
            }
        }
    }

    fun clearError() { _error.value = null }

    override fun onCleared() {
        super.onCleared()
        audioRecorder.stopRecording()
    }
}
