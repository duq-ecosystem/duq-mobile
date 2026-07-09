package com.duq.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.duq.android.audio.AudioPlaybackManager
import com.duq.android.audio.AudioRecorderInterface
import com.duq.android.audio.LocalTts
import com.duq.android.audio.StreamingTtsController
import com.duq.android.config.AppConfig
import com.duq.android.data.model.Message
import com.duq.android.data.model.MessageRole
import com.duq.android.data.model.VoicePhase
import com.duq.android.error.DuqError
import com.duq.android.logging.Logger
import com.duq.android.network.TtsClient
import com.duq.android.network.duq.AgentInfo
import com.duq.android.network.duq.DuqChatClient
import com.duq.android.network.duq.DuqConversation
import com.duq.android.network.duq.DuqIncomingMessage
import com.duq.android.network.duq.GatewayConnectionState
import com.duq.android.network.duq.OcAgentStep
import com.duq.android.network.duq.OcChatEvent
import com.duq.android.network.duq.OcHistoryMsg
import com.duq.android.util.ReplyText
import com.duq.android.util.nowMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Push-to-talk voice input lifecycle (separate from the chat-streaming state). */
enum class VoiceInputState { IDLE, RECORDING, TRANSCRIBING }

/**
 * Главный ViewModel чата DUQ: чат/голос/стрим/сессии/мульти-агенты. Перенесён в commonMain
 * (KMP). Hilt/@Inject убраны — зависимости через конструктор (Koin предоставит). Android-only
 * сняты на мультиплатформенные абстракции: `Context`/`File`/`cacheDir` → String-пути через
 * [AudioRecorderInterface]/[AudioFileCache]; `Log`/FileLogger → [Logger]; `java.util.UUID` →
 * [Uuid]; `java.time` → Unix epoch millis ([Message.createdAt]: Long, [nowMillis]); AppUpdater
 * → [AppUpdateController]; NotificationInbox → [NotificationInbox]. StateFlow/SharedFlow и весь
 * контракт (методы/флоу, см. MainScreen) сохранены 1:1.
 */
@OptIn(ExperimentalUuidApi::class)
class ConversationViewModel(
    private val gatewayClient: DuqChatClient,
    private val audioPlaybackManager: AudioPlaybackManager,
    private val audioRecorder: AudioRecorderInterface,
    private val ttsLocal: LocalTts,
    private val ttsClient: TtsClient,
    private val streamingTts: StreamingTtsController,
    private val notificationInbox: NotificationInbox,
    private val appUpdater: AppUpdateController,
    private val audioFileCache: AudioFileCache,
    private val logger: Logger,
) : ViewModel() {

    private fun randomId(): String = Uuid.random().toString()

    /** Центр уведомлений (🔔) — единый, дайджесты тоже здесь (раздел в шторке). */
    val inboxItems = notificationInbox.items

    fun refreshInbox() = notificationInbox.refresh()
    fun clearInbox() = notificationInbox.clear()

    private companion object {
        const val TAG = "ConversationViewModel"
    }

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    // Канонический порядок = серверный createdAt (стабильная сортировка: при равном времени
    // сохраняется порядок вставки, история уже приходит ordered). Корень-фикс «сообщение
    // встало не по порядку»: раньше список рос аппендом в порядке ПРИХОДА + Instant.now(),
    // поэтому live-сообщение, пришедшее после уже отрисованного ответа, ложилось ниже него.
    val messages: StateFlow<List<Message>> = _messages
        .map { list -> list.sortedBy { it.createdAt } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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

    // True while the APK is downloading after the user tapped "УСТАНОВИТЬ", so
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

    // Reply-таймаут/watchdog ВЫРЕЗАН ЦЕЛИКОМ (2026-07-09, решение Дениса): агент ВСЕГДА
    // отвечает (доставка гарантированная — at-least-once client-ack на /ws; надёжность
    // ответа обеспечивается на бэкенде). Никакого таймаута ожидания ответа в клиенте нет.
    // Спиннер обработки управляется напрямую через _isProcessing на отправке/получении.

    // Runs that already reached "final"/"aborted". Late frames for them (possible on
    // any reorder) must be ignored so they don't resurrect an orphan bubble. Bounded
    // LRU set — keeping more than one guards against two runs finalizing back-to-back.
    private val finalizedRunIds = BoundedKeySet(maxSize = 32)

    // Live-синк: серверные id уже отрендеренных push-сообщений (идемпотентность —
    // один и тот же push/реконнект не задвоит). Bounded LRU.
    private val seenServerMsgIds = BoundedKeySet(maxSize = 128)

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
        if (!seenServerMsgIds.add(msg.messageId)) return  // уже видели этот id
        val role = MessageRole.fromApiString(msg.role)
        // Свой тёрн в полёте: ответ ассистента из push заполняет пузырь-плейсхолдер
        // (тот же runId, на нём уже live-висят reasoning-шаги) — НЕ плодим второй пузырь
        // и не теряем шаги. REST-финал (тот же runId) затем идемпотентно заполнит его же.
        if (role == MessageRole.ASSISTANT) {
            val rid = currentRunId
            if (rid != null && _messages.value.any { it.id == rid }) {
                _messages.update { msgs ->
                    val upd = msgs.map {
                        if (it.id == rid) it.copy(content = ReplyText.clean(msg.content), isStreaming = false, hasAudio = it.hasAudio || msg.voice) else it
                    }
                    ChatStepReducer.markAllStepsDone(upd, rid)
                }
                // Ответ доставлен — тёрн завершён, снимаем спиннер обработки.
                _isProcessing.value = false
                // Модель решила озвучить → синтез TTS на пузыре этого тёрна.
                if (msg.voice) speakReply(rid, ReplyText.clean(msg.content))
                return
            }
        }
        // Озвучка решения модели — ДО дедупа пузыря: даже если пузырь-дубликат (история/
        // REST), голос озвучить нужно (spokenMsgIds/spokenContents защитят от повтора).
        // clean() — чтобы (а) markdown не озвучивался символами, (б) дедуп по spokenContents
        // совпал с тем, что положил инкрементальный догон (он кладёт cleaned-вариант).
        if (msg.voice && role == MessageRole.ASSISTANT) speakReply(msg.messageId, ReplyText.clean(msg.content))
        if (isRecentDuplicate(role, msg.content)) {
            // Пузырь уже отрисован стримом (TEXT_DONE финализировал) — этот chat.message
            // несёт СЕРВЕРНЫЙ id + has_audio. Реконсилим: пузырь усыновляет серверный id
            // (ключ кэша озвучки = как в истории) и hasAudio (кнопка play появляется live).
            if (role == MessageRole.ASSISTANT) reconcileServerMessage(msg)
            return
        }
        // hasAudio=msg.voice СРАЗУ — кнопка play на озвученном ответе появляется без задержки
        // (раньше ставилась постфактум в speakReply после синтеза; если беседа перезагружалась
        // из REST до конца синтеза — обновление терялось до следующей перезагрузки).
        // NB: DuqIncomingMessage не несёт серверного времени → ставим nowMillis() (новейшее,
        // встанет в конец по канонической сортировке — корректно для live-пуша).
        _messages.update {
            it + Message(
                id = msg.messageId, role = role, content = msg.content, hasAudio = msg.voice,
                createdAt = nowMillis(),
            )
        }
    }

    /** chat.message-дубль уже отрисованного стрим-пузыря → усыновить серверный id (ключ
     *  кэша озвучки/replay, как в истории) и has_audio (кнопка play появляется live). */
    private fun reconcileServerMessage(msg: DuqIncomingMessage) {
        val norm = msg.content.trim()
        // Пузырь меняет runId → серверный id: переносим кэш озвучки (иначе replay по серверному
        // id не найдёт синтез, был под runId, и ре-синтезирует). renameCache — ДО update и вне
        // его лямбды.
        val oldId = _messages.value.lastOrNull { it.role == MessageRole.ASSISTANT && it.content.trim() == norm }?.id
        if (oldId != null && oldId != msg.messageId) audioPlaybackManager.renameCache(oldId, msg.messageId)
        _messages.update { list ->
            val idx = list.indexOfLast { it.role == MessageRole.ASSISTANT && it.content.trim() == norm }
            if (idx < 0) list
            else list.mapIndexed { i, m ->
                if (i == idx) m.copy(id = msg.messageId, hasAudio = m.hasAudio || msg.voice) else m
            }
        }
    }

    // ── Переключатель диалогов: список бесед из /conversations (опц. по agent_id),
    //    выбор грузит /messages беседы; отправка адресуется в активную беседу
    //    (conversation_id), «Новый чат» стартует свежую (new_conversation). Мульти-агенты:
    //    switchAgent грузит РАЗДЕЛЬНЫЕ истории выбранного агента (своя сессия per agent). ──
    private val _conversations = MutableStateFlow<List<DuqConversation>>(emptyList())
    val conversations: StateFlow<List<DuqConversation>> = _conversations.asStateFlow()

    // Активная беседа: id (null = новый ещё-не-сохранённый чат) + заголовок для шапки.
    private val _activeConversationId = MutableStateFlow<String?>(null)
    val activeConversationId: StateFlow<String?> = _activeConversationId.asStateFlow()
    private val _activeConversationTitle = MutableStateFlow("DUQ")
    val activeConversationTitle: StateFlow<String> = _activeConversationTitle.asStateFlow()

    // Следующая отправка должна начать НОВЫЙ диалог (взведено кнопкой «Новый чат»).
    private var pendingNewConversation = false

    // Мульти-агенты: реестр агентов (из /api/agents) + выбранный агент. Каждый
    // агент = своя сессия/память/тулсет (ядро изолирует по agent_id). Смена агента
    // = свой чат: чистим видимую историю и начинаем сессию выбранного агента.
    private val _agents = MutableStateFlow<List<AgentInfo>>(emptyList())
    val agents: StateFlow<List<AgentInfo>> = _agents.asStateFlow()
    private val _activeAgentId = MutableStateFlow("main")
    val activeAgentId: StateFlow<String> = _activeAgentId.asStateFlow()

    fun loadAgents() {
        viewModelScope.launch {
            runCatching { gatewayClient.listAgents() }
                .onSuccess { agents ->
                    _agents.value = agents
                    // Активный агент исчез из списка (удалён на сервере) → сброс на main.
                    // Иначе чат висит на несуществующем агенте: «агента нет, а чат есть».
                    if (agents.isNotEmpty() && agents.none { it.id == _activeAgentId.value }) {
                        switchAgent("main")
                    }
                }
                .onFailure { logger.w(TAG, "loadAgents failed: ${it.message}") }
        }
    }

    /** Переключиться на агента: грузим ЕГО диалоги (раздельные истории per agent) и
     *  последнюю беседу. Нет бесед у агента → пустой чат (первое сообщение создаст). */
    fun switchAgent(agentId: String) {
        if (agentId == _activeAgentId.value) return
        _activeAgentId.value = agentId
        _messages.value = emptyList()
        _activeConversationId.value = null
        currentRunId = null
        // Сброс голос-флагов прошлого агента: иначе ответ нового агента мог бы
        // озвучиться по залипшему lastInputWasVoice/pendingVoiceReplyRunId.
        pendingVoiceReplyRunId = null
        lastInputWasVoice = false
        _isProcessing.value = false
        _activeConversationTitle.value = _agents.value.firstOrNull { it.id == agentId }?.displayName ?: "DUQ"
        viewModelScope.launch {
            val list = runCatching { gatewayClient.listConversations(agentId) }.getOrElse {
                logger.w(TAG, "switchAgent($agentId) listConversations failed: ${it.message}")
                emptyList()
            }
            if (_activeAgentId.value != agentId) return@launch
            _conversations.value = list
            val first = list.firstOrNull()
            if (first == null) { pendingNewConversation = true; return@launch }
            _activeConversationId.value = first.id
            _activeConversationTitle.value = first.dateLabel
            pendingNewConversation = false
            val history = runCatching { gatewayClient.loadMessages(first.id) }.getOrElse {
                logger.w(TAG, "switchAgent($agentId) loadMessages failed: ${it.message}")
                emptyList()
            }
            if (_activeAgentId.value != agentId) return@launch
            _messages.value = history.map { it.toMessage() }
        }
    }

    /** История беседы (REST) → Message. Серверный id = ключ кэша озвучки (replay), hasAudio
     *  → кнопка play переживает перезагрузку. Единая точка маппинга для всех загрузок истории. */
    private fun OcHistoryMsg.toMessage(): Message {
        if (hasAudio && id == null) {
            logger.w(TAG, "voiced history message without server id — replay re-synthesizes под новым id")
        }
        val mid = id ?: randomId()
        val cached = audioPlaybackManager.isCached(mid)
        return Message(
            id = mid,
            role = MessageRole.fromApiString(role),
            content = text,
            // Кнопка play живёт, если ответ озвучен (серверный флаг) ИЛИ озвучка лежит в
            // локальном кэше. Догон синтезирует на КЛИЕНТЕ — сервер про это не знает
            // (has_audio=false), но msg_<id>.wav в кэше есть → после рестарта кнопка пропадала.
            hasAudio = hasAudio || cached,
            // Длительность из кэш-WAV — чтобы показывалась на кнопке сразу после рестарта.
            audioDurationMs = if (cached) audioPlaybackManager.cachedDurationMs(mid).takeIf { it > 0 } else null,
            // Серверное время (Unix-сек) — канонический порядок сортировки. Без него nowMillis().
            createdAt = if (createdAt > 0) createdAt * 1000L else nowMillis(),
        )
    }

    init {
        gatewayClient.start()
        collectChatEvents()
        refreshUpdateState()
        loadAgents()
        restoreServerHistory()
        warmTtsModel()
    }

    /** Прогрев on-device TTS-модели в фоне на старте: к первому голос-ответу модель уже
     *  скачана → догон стартует и озвучивает ПО ХОДУ генерации (синтез фраз по мере дельт). */
    private fun warmTtsModel() {
        if (!AppConfig.TTS_ON_DEVICE) return
        viewModelScope.launch {
            runCatching { ttsLocal.ensureModel() }
                .onFailure { logger.w(TAG, "TTS warm failed: ${it.message}") }
        }
    }

    /** Остановить ВСЁ аудио — потоковый догон и replay. Зовётся при уходе app в фон/закрытии. */
    fun stopAllAudio() {
        streamingTts.cancel()
        audioPlaybackManager.stop()
    }

    /** Перечитать список бесед (для переключателя). Тихо игнорит сетевые ошибки. */
    fun loadConversations() {
        viewModelScope.launch {
            // Только диалоги активного агента (раздельные истории per agent).
            val list = runCatching { gatewayClient.listConversations(_activeAgentId.value) }.getOrElse {
                logger.w(TAG, "listConversations failed: ${it.message}"); return@launch
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
        _isProcessing.value = false
        viewModelScope.launch {
            val history = runCatching { gatewayClient.loadMessages(id) }.getOrElse {
                logger.e(TAG, "loadMessages($id) failed: ${it.message}", it); emptyList()
            }
            // Применяем только если пользователь не переключился снова за время загрузки.
            if (_activeConversationId.value != id) return@launch
            _messages.value = history.map { it.toMessage() }
            logger.i(TAG, "selectConversation($id): ${history.size} messages")
        }
    }

    /** Начать новый диалог: сбрасываем вид; первое сообщение уйдёт с new_conversation. */
    fun newConversation() {
        _activeConversationId.value = null
        pendingNewConversation = true
        _activeConversationTitle.value = "Новый чат"
        _messages.value = emptyList()
        currentRunId = null
        _isProcessing.value = false
    }

    /**
     * Restore the chat from the server-side transcript once the gateway is connected.
     * The gateway is the single source of truth for history (shared across all devices),
     * so we don't keep a local copy — this fixes "chat resets every launch" at the root.
     */
    private fun restoreServerHistory() {
        viewModelScope.launch {
            connectionState.first { it == GatewayConnectionState.CONNECTED }
            val list = runCatching { gatewayClient.listConversations() }.getOrElse {
                logger.e(TAG, "listConversations failed: ${it.message}", it); emptyList()
            }
            _conversations.value = list
            // Самая свежая беседа (ядро вернуло DESC) — активная при старте.
            val first = list.firstOrNull() ?: return@launch
            _activeConversationId.value = first.id
            _activeConversationTitle.value = first.dateLabel
            val history = runCatching { gatewayClient.loadMessages(first.id) }.getOrElse {
                logger.e(TAG, "loadMessages failed: ${it.message}", it); emptyList()
            }
            logger.i(TAG, "restoreServerHistory: conv=${first.id} ${history.size} messages")
            if (history.isEmpty()) return@launch
            val restored = history.map { it.toMessage() }
            // Seed from the server transcript ONLY while the chat is still empty — the
            // normal cold-start case. If a reply already streamed in during the connect
            // window (or the user sent something), DON'T clobber/duplicate it.
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
        // runId+callId), so a late frame just upserts the existing step in place.
        val finished = step.phase == "end" || step.status == "completed" || step.status == "failed"
        // the engine emits up to two items per call (tool + its command/patch detail)
        // sharing one toolCallId — itemId is "tool:<callId>"/"command:<callId>". Key
        // by the callId so the pair collapses into one step. Идёт tool-шаг → спиннер вкл.
        if (!finished) _isProcessing.value = true
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
        if (finalizedRunIds.contains(event.runId)) return
        when (event.state) {
            "delta" -> {
                // Идёт стрим текста → спиннер обработки включён.
                _isProcessing.value = true
                if (currentRunId != event.runId) {
                    currentRunId = event.runId
                    streamBuffer.clear()
                    // A tool step may have already created this bubble (steps can
                    // precede the first text delta) — only insert if it's absent.
                    _messages.update { msgs ->
                        if (msgs.any { it.id == event.runId }) msgs
                        else msgs + Message(id = event.runId, role = MessageRole.ASSISTANT, content = "", isStreaming = true)
                    }
                    _isProcessing.value = true
                    // Bind this reply to the voice turn that triggered it, so only it
                    // gets spoken (contextual TTS). Догон озвучки: потоковый on-device TTS
                    // по фразам по мере стрима. ТОЛЬКО если движок готов (модель скачана).
                    if (lastInputWasVoice && ttsLocal.isReady()) {
                        pendingVoiceReplyRunId = event.runId
                        lastInputWasVoice = false
                        streamingTts.start(event.runId)
                    }
                }
                // Стрим кумулятивный: каждая дельта несёт ВЕСЬ текст (авторитетно, стойко
                // к реордеру). streamBuffer хранит последний кумулятив для финал-фолбэка.
                val cumulative = event.fullText ?: return
                streamBuffer.clear(); streamBuffer.append(cumulative)
                val live = ReplyText.clean(cumulative)
                _messages.update { msgs ->
                    msgs.map { if (it.id == event.runId) it.copy(content = live) else it }
                }
                // Скармливаем СЫРОЙ кумулятив догону (стабильный префикс; markdown чистится
                // у каждой фразы внутри StreamingTts).
                if (event.runId == pendingVoiceReplyRunId) streamingTts.feed(event.runId, cumulative)
            }
            "final" -> {
                _isProcessing.value = false
                val finalRaw = event.fullText ?: streamBuffer.toString()
                val finalContent = ReplyText.clean(finalRaw)
                currentRunId = null; streamBuffer.clear(); _isProcessing.value = false
                finalizedRunIds.add(event.runId)

                // Decide + consume the voice flags ONCE here, so they're always cleared.
                // Озвучиваем on-device если: (а) голосом спросил → голосом ответил (legacy),
                // ИЛИ (б) модель сама решила озвучить (set_response_mode voice) — `event.voice`
                // с финала TEXT_DONE. Синтез on-device (synthesizeToPath), серверный TTS = фолбек.
                // Чинит: voiced-реплай typed/api-тёрна приходил текстом (флаг voice не смотрели).
                val speakThisReply =
                    event.runId == pendingVoiceReplyRunId || lastInputWasVoice || event.voice
                if (event.runId == pendingVoiceReplyRunId) pendingVoiceReplyRunId = null
                lastInputWasVoice = false

                // Pure NO_REPLY sentinel — engine asked to surface nothing. Drop the placeholder.
                if (finalContent.isEmpty()) {
                    _messages.update { msgs -> msgs.filter { it.id != event.runId } }
                    return
                }

                // Update the streaming placeholder, or insert the message if no delta ever
                // created one. Also settle any tool steps whose "end" frame never arrived.
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
                if (streamingTts.isStreaming(event.runId)) {
                    // Догон уже озвучивает по фразам — финалим остатком (СЫРОЙ текст),
                    // НЕ дублируем полным speakReply.
                    streamingTts.finish(event.runId, finalRaw)
                    // Дедуп: последующий chat.message (серверный id) озвучивает msg.content
                    // (СЫРОЙ текст) — помечаем и сырой, и cleaned, чтобы повторного полного
                    // синтеза не было (speakReply дедупит по spokenContents).
                    spokenContents.add(finalContent.trim())
                    spokenContents.add(finalRaw.trim())
                    _messages.update { msgs -> msgs.map { if (it.id == event.runId) it.copy(hasAudio = true) else it } }
                } else if (speakThisReply) {
                    speakReply(event.runId, finalContent)
                }
            }
            "aborted", "error" -> {
                _isProcessing.value = false
                val errText = event.errorMessage ?: "Error"
                currentRunId = null; streamBuffer.clear(); _isProcessing.value = false
                finalizedRunIds.add(event.runId)
                if (streamingTts.isStreaming(event.runId)) streamingTts.cancel()
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
        // на него live-вешаются reasoning-шаги, пока ядро думает; финальный текст (тот же
        // runId) заполнит пузырь. Без deltas у ядра это единственный способ показать live.
        val runId = randomId()
        currentRunId = runId
        // Буфер может держать хвост прерванного прошлого стрима: delta-обработчик чистит его
        // только при СМЕНЕ runId, а здесь runId уже выставлен — чистим явно.
        streamBuffer.clear()
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
                _isProcessing.value = true
            } catch (e: Exception) {
                logger.e(TAG, "sendMessage failed: ${e.message}")
                _isProcessing.value = false
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
        val msgId = randomId()
        pendingVoiceMsgId = msgId
        _messages.update {
            it + Message(
                id = msgId, role = MessageRole.USER, content = "",
                voicePhase = VoicePhase.RECORDING, isStreaming = true
            )
        }
        recordingJob = viewModelScope.launch {
            // Платформа-агностик: путь во временной директории под фиксированным именем
            // (записываем PTT-вход; STT читает его же, затем удаляем).
            val path = audioFileCache.tempWavPath("ptt_input.wav")
            try {
                // useVad=false: hold-to-talk — the user controls the endpoint, so natural
                // pauses must not cut the recording short.
                val captured = audioRecorder.record(path, useVad = false)
                if (!captured) { removePendingVoice(); return@launch }

                _voiceInput.value = VoiceInputState.TRANSCRIBING
                updatePendingVoice { it.copy(voicePhase = VoicePhase.TRANSCRIBING) }
                val transcript = gatewayClient.transcribeAudio(path)
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
                gatewayClient.sendMessage(
                    transcript, conversationId = convId, newConversation = isNew,
                    agentId = _activeAgentId.value,   // голос тоже уходит выбранному агенту
                )
                _isProcessing.value = true
            } catch (e: CancellationException) {
                removePendingVoice()
                throw e // user cancelled (slide-away / background) — not an error
            } catch (e: Exception) {
                removePendingVoice()
                logger.e(TAG, "Voice input failed: ${e.message}")
                _error.value = DuqError.NetworkError(e.message ?: "Voice input failed")
            } finally {
                audioFileCache.deleteTemp(path)
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
    // доставки решения модели) не синтезировали один ответ дважды. Bounded — как остальные
    // дедуп-наборы (finalizedRunIds/seenServerMsgIds): не растёт бесконечно за сессию.
    private val spokenMsgIds = BoundedKeySet(maxSize = 128)

    // Дедуп ПО КОНТЕНТУ: один физический ответ приходит двумя путями (REST-final с runId
    // и WS-push с серверным messageId) — РАЗНЫЕ id, spokenMsgIds их не свяжет → двойной
    // синтез. Общий у обоих только текст. Bounded-LRU недавних озвученных текстов ловит
    // гонку push↔final, не мешая повторно озвучить тот же текст в другом тёрне позже.
    private val spokenContents = BoundedKeySet(maxSize = 16)

    /**
     * Тап по кнопке play на сообщении. Если озвучка ещё в кэше плеера — просто
     * play/pause. Если кэш стёрт (рестарт/OS почистил cacheDir/перезагрузка истории) —
     * РЕ-СИНТЕЗ on-device по тексту сообщения под тем же messageId, затем играем.
     */
    fun playMessageAudio(messageId: String) {
        logger.d(TAG, "playMessageAudio tap id=${messageId.take(8)} togonPlaying=${streamingTts.isPlaying()} cached=${audioPlaybackManager.isCached(messageId)}")
        // Живой догон сейчас озвучивает → кнопка = СТОП. Иначе playOrToggle запустил бы
        // плеер ПОВЕРХ догона (двойной звук) — догон был неуправляем кнопкой.
        if (streamingTts.isPlaying()) {
            logger.d(TAG, "playMessageAudio → стоп догона")
            streamingTts.cancel()
            return
        }
        if (audioPlaybackManager.isCached(messageId)) {
            logger.d(TAG, "playMessageAudio → playOrToggle (кэш есть)")
            audioPlaybackManager.playOrToggle(messageId)
            return
        }
        logger.d(TAG, "playMessageAudio → ре-синтез (кэша нет)")
        val msg = _messages.value.firstOrNull { it.id == messageId } ?: return
        if (msg.content.isBlank() || msg.isAudioLoading) return  // guard двойного тапа во время синтеза
        val originConv = _activeConversationId.value
        setAudioLoading(messageId, true)
        viewModelScope.launch {
            try {
                val path = synthesizeToPath(msg.content, messageId)
                // Не играем, если пользователь ушёл в другую беседу за время синтеза.
                if (path != null && _activeConversationId.value == originConv) {
                    audioPlaybackManager.play(messageId, path)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e(TAG, "replay synth failed: ${e.message}")
            } finally {
                setAudioLoading(messageId, false)
            }
        }
    }

    private fun setAudioLoading(messageId: String, loading: Boolean) {
        _messages.update { list -> list.map { if (it.id == messageId) it.copy(isAudioLoading = loading) else it } }
    }

    /**
     * Единый синтез ответа в проигрываемый ФАЙЛ-путь: on-device ([LocalTts.trySynthesize]
     * уже отдаёт путь) ИЛИ серверный fallback ([TtsClient.synthesize] → ByteArray, который
     * пишем на диск через [AudioFileCache.writeWav]). Возвращает путь к WAV или null.
     *
     * NB: в android-референсе оба пути возвращали File и склеивались `?:`; в commonMain
     * типы разъехались (String-путь vs ByteArray), поэтому мост — здесь.
     */
    private suspend fun synthesizeToPath(text: String, messageId: String): String? {
        ttsLocal.trySynthesize(text, messageId)?.let { return it }
        val bytes = ttsClient.synthesize(text, messageId) ?: return null
        return audioFileCache.writeWav(messageId, bytes)
    }

    private var speakJob: Job? = null

    private fun speakReply(messageId: String, text: String) {
        if (text.isBlank()) return
        if (!spokenMsgIds.add(messageId)) return
        // Гонка push↔final: тот же ответ под другим id уже озвучивается → не дублируем синтез.
        if (!spokenContents.add(text.trim())) return
        logger.i(TAG, "speakReply start id=${messageId.take(8)} len=${text.length}")
        // Отменяем незавершённый предыдущий синтез: при быстрых ответах подряд
        // (cron-проактив + живой) иначе параллельно синтезируются и накладываются.
        speakJob?.cancel()
        speakJob = viewModelScope.launch {
            val path = try {
                synthesizeToPath(text, messageId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e(TAG, "TTS failed: ${e.message}"); null
            } ?: run { logger.e(TAG, "speakReply: no audio (both null)"); return@launch }
            logger.i(TAG, "speakReply play id=${messageId.take(8)}")
            // Помечаем сообщение как голосовое → в пузыре появляется кнопка play/pause
            // (как в Telegram): можно переслушать ответ.
            _messages.update { msgs ->
                msgs.map { if (it.id == messageId) it.copy(hasAudio = true) else it }
            }
            audioPlaybackManager.play(messageId, path)
        }
    }

    /** Re-read the cached "available" version (instant, no network). */
    fun refreshUpdateState() {
        _updateReadyVersion.value = appUpdater.cachedAvailableVersion()
    }

    /**
     * Fast check on every return to the app: fetch version metadata only (~1s, no APK
     * download) so the banner appears IMMEDIATELY. Download is deferred to installUpdate().
     */
    fun checkForUpdate() {
        refreshInbox() // pick up items recorded while backgrounded
        viewModelScope.launch {
            val v = runCatching { appUpdater.checkAvailable() }.getOrDefault(0)
            _updateReadyVersion.value = if (v > 0) v else appUpdater.cachedAvailableVersion()
        }
    }

    private var installing = false

    /** Download + install the available update (in-app banner button). */
    fun installUpdate() {
        if (installing) return // guard double-tap → no parallel downloads/sessions
        installing = true
        _updateInstalling.value = true // instant banner feedback ("Скачиваю…")
        logger.i(TAG, "installUpdate(): starting download+install")
        _updateProgress.value = 0f
        viewModelScope.launch {
            try {
                appUpdater.downloadAndInstall(onProgress = { p -> _updateProgress.value = p })
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
