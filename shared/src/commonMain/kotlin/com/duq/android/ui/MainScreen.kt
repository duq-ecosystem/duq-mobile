package com.duq.android.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.duq.android.audio.AudioPlaybackManager
import com.duq.android.audio.PlaybackState
import com.duq.android.ui.components.DuqDuck
import com.duq.android.ui.components.MessagesList
import com.duq.android.ui.control.GlobalTopActions
import com.duq.android.ui.control.ProfileAvatar
import com.duq.android.ui.theme.DuqColors
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

/**
 * Главный экран чата DUQ (Compose Multiplatform). Чистый UI поверх [ConversationViewModel]:
 * сообщения, мульти-агенты/диалоги, push-to-talk, watermark-утка с frosted-glass (haze).
 *
 * Android-специфика вынесена из общего кода:
 * - привязка к foreground-сервису/voiceController убрана — состояние выводится из флоу VM
 *   (push-to-talk идёт напрямую через VM.startVoiceInput/stopVoiceInput);
 * - запрос разрешения на микрофон — колбэк [onRequestMicPermission] (хост-платформа
 *   подставляет реальный permission-launcher; на iOS — системный запрос/no-op);
 * - lifecycle-хуки (проверка апдейта на ON_RESUME, остановка аудио на ON_STOP) —
 *   мультиплатформенный LifecycleEventObserver.
 *
 * [audioPlaybackManager] и [viewModel] приходят через Koin; глобальный чат-плеер
 * инициализируется хостом (его жизненный цикл = процесс), здесь только используется.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onNavigateToSettings: () -> Unit,
    onOpenPalette: () -> Unit = {},
    onRequestMicPermission: () -> Unit = {},
    viewModel: ConversationViewModel = koinViewModel(),
    audioPlaybackManager: AudioPlaybackManager = org.koin.compose.koinInject()
) {
    val viewModelError by viewModel.error.collectAsState()
    val isTextProcessing by viewModel.isProcessing.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val voiceInput by viewModel.voiceInput.collectAsState()
    val conversations by viewModel.conversations.collectAsState()
    val activeConversationId by viewModel.activeConversationId.collectAsState()
    val activeConversationTitle by viewModel.activeConversationTitle.collectAsState()
    val agents by viewModel.agents.collectAsState()
    val activeAgentId by viewModel.activeAgentId.collectAsState()
    val models by viewModel.models.collectAsState()
    val activeModelId by viewModel.activeModelId.collectAsState()

    // Audio playback state
    val audioPlaybackInfo by audioPlaybackManager.playbackInfo.collectAsState()

    // Combine push-to-talk, TTS playback, and text processing states into one indicator.
    val state = when {
        voiceInput == VoiceInputState.RECORDING -> DuqState.RECORDING
        voiceInput == VoiceInputState.TRANSCRIBING -> DuqState.PROCESSING
        audioPlaybackInfo.state == PlaybackState.PLAYING -> DuqState.PLAYING
        isTextProcessing -> DuqState.PROCESSING
        else -> DuqState.IDLE
    }

    // Text input state
    var textInput by remember { mutableStateOf("") }
    val pttScope = rememberCoroutineScope()
    val hazeState = remember { HazeState() }

    // Bottom sheet state for conversation picker
    var showConversationPicker by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()

    // Snackbar for error display
    val snackbarHostState = remember { SnackbarHostState() }

    // Show snackbar when error occurs
    LaunchedEffect(viewModelError) {
        viewModelError?.let { error ->
            snackbarHostState.showSnackbar(
                message = error.toDisplayMessage(),
                withDismissAction = true
            )
            viewModel.clearError()
        }
    }

    // Запрос разрешения на микрофон на входе (реальный launcher подставляет хост).
    LaunchedEffect(Unit) { onRequestMicPermission() }

    // Lifecycle: на возврат в приложение — быстрый чек апдейта; на уход в фон — стоп
    // ВСЕГО аудио (догон + replay) и отмена записи. Мультиплатформенный observer.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.checkForUpdate()
                Lifecycle.Event.ON_PAUSE -> viewModel.cancelVoiceInput()
                Lifecycle.Event.ON_STOP -> viewModel.stopAllAudio()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Переключатель диалогов — выбор агента (мульти-агенты, каждый со своим
    // чатом/памятью/тулсетом) + список бесед из /conversations + «Новый чат».
    if (showConversationPicker) {
        ModalBottomSheet(
            onDismissRequest = { showConversationPicker = false },
            sheetState = sheetState,
            containerColor = DuqColors.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 32.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Диалоги",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = DuqColors.textPrimary
                    )
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(DuqColors.primary.copy(alpha = 0.15f))
                            .clickable {
                                viewModel.newConversation()
                                showConversationPicker = false
                            }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "＋ Новый чат", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = DuqColors.primary)
                    }
                }
                // Мульти-агенты: выбор агента. Каждый агент = свой чат/память/тулсет
                // (ядро изолирует по agent_id). Смена агента стартует его сессию.
                if (agents.size > 1) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(bottom = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        agents.forEach { agent ->
                            val sel = agent.id == activeAgentId
                            Text(
                                text = agent.displayName,
                                fontSize = 14.sp,
                                fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                                color = if (sel) DuqColors.primary else DuqColors.textSecondary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(
                                        if (sel) DuqColors.primary.copy(alpha = 0.15f)
                                        else DuqColors.surfaceElevated
                                    )
                                    .clickable {
                                        viewModel.switchAgent(agent.id)
                                        showConversationPicker = false
                                    }
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
                // Задача 16: пикер модели (аналог агентов). «Авто» = автоцепь ядра (без override),
                // остальные — ручной выбор primary из сконфигурированной цепи. Скрыт если цепь пуста.
                if (models.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(bottom = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        @Composable
                        fun modelChip(id: String, label: String) {
                            val sel = id == activeModelId
                            Text(
                                text = label,
                                fontSize = 13.sp,
                                fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                                color = if (sel) DuqColors.primary else DuqColors.textSecondary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(
                                        if (sel) DuqColors.primary.copy(alpha = 0.15f)
                                        else DuqColors.surfaceElevated
                                    )
                                    .clickable { viewModel.switchModel(id) }
                                    .padding(horizontal = 14.dp, vertical = 7.dp)
                            )
                        }
                        modelChip("", "Авто")
                        models.forEach { m -> modelChip(m.id, m.model.substringAfterLast('/')) }
                    }
                }
                if (conversations.isEmpty()) {
                    Text(
                        text = "Пока нет сохранённых диалогов",
                        fontSize = 14.sp,
                        color = DuqColors.textSecondary,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
                conversations.forEach { conv ->
                    val selected = conv.id == activeConversationId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (selected) DuqColors.primary.copy(alpha = 0.15f) else DuqColors.surfaceElevated)
                            .clickable {
                                viewModel.selectConversation(conv.id)
                                showConversationPicker = false
                            }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "💬", fontSize = 20.sp)
                        Spacer(modifier = Modifier.width(14.dp))
                        // В списке — ТОЛЬКО саммари темы (как в ChatGPT). Дата живёт в шапке-кнопке.
                        // Пока тема не сгенерилась — нейтральный «Новый чат».
                        Text(
                            text = conv.summary ?: "Новый чат",
                            fontSize = 16.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = if (selected) DuqColors.primary else DuqColors.textPrimary,
                            maxLines = 2,
                            modifier = Modifier.weight(1f)
                        )
                        if (selected) Text(text = "✓", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = DuqColors.primary)
                    }
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DuqColors.background)
    ) {
        // Утка — на ЗАДНЕМ ФОНЕ чата (watermark). hazeSource: пузыри сообщений
        // (hazeEffect) размывают её под собой настоящим frosted-glass. Сама утка
        // чёткая в промежутках, анимируется по state.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(hazeState)
        ) {
            // Силуэт тусклее в покое, ярче при активности. Halo не глушится (он на полной
            // яркости внутри DuqDuck), так что цветные вспышки статуса видны всегда.
            val duckAlpha = if (state == DuqState.IDLE) 0.42f else 0.85f
            DuqDuck(
                state = state,
                silhouetteAlpha = duckAlpha,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(220.dp)
            )
        }

        // Header row with conversation title and settings.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Слева — профиль (best-practice: аккаунт вверху слева) + заголовок беседы.
            // Переключатель сессий уехал ВНИЗ к полю ввода (иконка в thumb-зоне).
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProfileAvatar()
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = activeConversationTitle,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = DuqColors.textPrimary,
                    maxLines = 1
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                // 🔍 Command palette — быстрый переход к любому разделу.
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(DuqColors.surfaceVariant)
                        .clickable(onClick = onOpenPalette),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.Search, contentDescription = "Поиск",
                        tint = DuqColors.textSecondary, modifier = Modifier.size(22.dp))
                }
                Spacer(modifier = Modifier.width(8.dp))
                // 🔔 уведомления + ⚙️ настройки — глобальный блок (на всех экранах).
                GlobalTopActions()
            }
        }

        // Main content — Duck watermark (behind) + Messages + Text Input
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Высота шапки-overlay (иконки 44dp + паддинг). Статус-бар отступ даёт хост.
                .padding(top = 60.dp)
        ) {
            // Messages list — middle section (optimistic updates, no loading spinner).
            MessagesList(
                messages = messages,
                audioPlaybackInfo = audioPlaybackInfo,
                onAudioPlayPauseClick = { messageId ->
                    // Через VM: ре-синтез on-device если кэш озвучки стёрт (replay из истории).
                    viewModel.playMessageAudio(messageId)
                },
                hazeState = hazeState,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
            )

            // Text input — bottom section
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Сессии (история бесед) — в нижней thumb-зоне, слева от поля. Открывает
                // переключатель бесед (раньше висел в шапке как «заголовок ▼»).
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(DuqColors.surfaceVariant)
                        .clickable {
                            viewModel.loadConversations()
                            viewModel.loadAgents()
                            showConversationPicker = true
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.History, contentDescription = "Сессии",
                        tint = DuqColors.textSecondary, modifier = Modifier.size(22.dp))
                }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedTextField(
                    value = textInput,
                    onValueChange = { textInput = it },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            text = "Напишите сообщение…",
                            color = DuqColors.textMuted
                        )
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = DuqColors.textPrimary,
                        unfocusedTextColor = DuqColors.textPrimary,
                        focusedBorderColor = DuqColors.primary,
                        unfocusedBorderColor = DuqColors.surfaceElevated,
                        focusedContainerColor = DuqColors.surface,
                        unfocusedContainerColor = DuqColors.surface,
                        cursorColor = DuqColors.primary
                    ),
                    shape = RoundedCornerShape(24.dp),
                    singleLine = false,
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (textInput.isNotBlank()) {
                                viewModel.sendTextMessage(textInput)
                                textInput = ""
                            }
                        }
                    )
                )

                Spacer(modifier = Modifier.width(8.dp))

                // Комбо-кнопка: ТАП = отправить текст; УДЕРЖАНИЕ = запись голоса
                // (push-to-talk). Тап во время TTS — стоп воспроизведения.
                val hasText = textInput.isNotBlank()
                val recording = voiceInput == VoiceInputState.RECORDING
                val transcribing = voiceInput == VoiceInputState.TRANSCRIBING

                // Живые анимации кнопки: пульс при записи + сама кнопка слегка «дышит».
                val btnAnim = rememberInfiniteTransition(label = "recBtn")
                val btnPulse by btnAnim.animateFloat(
                    initialValue = 0f, targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        tween(if (recording) 650 else 900, easing = FastOutSlowInEasing),
                        RepeatMode.Reverse
                    ),
                    label = "btnPulse"
                )
                val btnScale = if (recording) 1f + 0.10f * btnPulse else 1f
                val btnColor = when {
                    recording -> DuqColors.error
                    transcribing -> Color(0xFF7C4DFF)   // фиолетовый — совпадает с halo «распознаю»
                    hasText -> DuqColors.primary
                    else -> DuqColors.surfaceElevated
                }

                Box(
                    modifier = Modifier.size(64.dp),   // запас под пульс-кольцо
                    contentAlignment = Alignment.Center
                ) {
                    // Расходящееся пульсирующее кольцо — видно, что идёт запись.
                    if (recording) {
                        Box(Modifier
                            .size(48.dp)
                            .drawBehind {
                                val rr = size.minDimension / 2f * (1f + 0.55f * btnPulse)
                                drawCircle(
                                    color = DuqColors.error.copy(alpha = 0.45f * (1f - btnPulse)),
                                    radius = rr
                                )
                            }
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .scale(btnScale)
                            .clip(CircleShape)
                            .background(btnColor)
                            // pointerInput(Unit): textInput/audioPlaybackInfo — snapshot-state,
                            // читаются актуальными внутри жеста. Ключи (textInput/state) тут
                            // РЕСТАРТОВАЛИ жест посреди удержания (смена state плеера во время
                            // hold) → startVoiceInput без stopVoiceInput — «застрявшая» запись.
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    awaitFirstDown()
                                    // Удержание > 220мс запускает запись (через корутину, т.к.
                                    // в pointer-scope нет withTimeout); короткий тап — отправка.
                                    var recordingStarted = false
                                    val holdJob = pttScope.launch {
                                        delay(220)
                                        recordingStarted = true
                                        viewModel.startVoiceInput()
                                    }
                                    waitForUpOrCancellation()
                                    holdJob.cancel()
                                    if (recordingStarted) {
                                        viewModel.stopVoiceInput()  // отпустили → стоп + отправка транскрипта
                                    } else if (audioPlaybackInfo.state == PlaybackState.PLAYING) {
                                        audioPlaybackManager.stop()
                                    } else if (textInput.isNotBlank()) {
                                        viewModel.sendTextMessage(textInput); textInput = ""
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (transcribing) {
                            // Идёт распознавание (on-device whisper) — крутящийся индикатор.
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = when {
                                    recording -> Icons.Outlined.Stop
                                    hasText -> Icons.AutoMirrored.Outlined.Send
                                    else -> Icons.Outlined.Mic
                                },
                                contentDescription = if (hasText) "Отправить" else "Запись (удерживай)",
                                tint = if (hasText || recording) Color.Black else DuqColors.textSecondary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
        }

        // Snackbar for error messages
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 100.dp)
        ) { data ->
            Snackbar(
                snackbarData = data,
                containerColor = DuqColors.error,
                contentColor = Color.White
            )
        }
    }
}
