package com.duq.android.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.input.pointer.pointerInput
import com.duq.android.audio.PlaybackState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.Alignment
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import dev.chrisbanes.haze.hazeSource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.duq.android.R
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.duq.android.DuqState
import com.duq.android.audio.ChatAudioPlaybackManager
import com.duq.android.audio.PlaybackInfo
import com.duq.android.config.AppConfig
import com.duq.android.error.DuqError
import com.duq.android.service.DuqListenerService
import com.duq.android.service.VoiceServiceController
import com.duq.android.ui.components.DuqDuck
import com.duq.android.ui.components.MessagesList
import com.duq.android.ui.theme.DuqColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onNavigateToSettings: () -> Unit,
    onOpenPalette: () -> Unit = {},
    viewModel: ConversationViewModel = hiltViewModel(),
    audioPlaybackManager: ChatAudioPlaybackManager
) {
    val context = LocalContext.current
    var voiceController by remember { mutableStateOf<VoiceServiceController?>(null) }

    val voiceState by voiceController?.state?.collectAsState() ?: remember { mutableStateOf(DuqState.IDLE) }
    val serviceError by voiceController?.error?.collectAsState() ?: remember { mutableStateOf<DuqError?>(null) }
    val viewModelError by viewModel.error.collectAsState()
    val isTextProcessing by viewModel.isProcessing.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val voiceInput by viewModel.voiceInput.collectAsState()
    val updateReadyVersion by viewModel.updateReadyVersion.collectAsState()
    val updateInstalling by viewModel.updateInstalling.collectAsState()
    val updateProgress by viewModel.updateProgress.collectAsState()
    // Уведомления и дайджесты теперь в глобальной шторке (GlobalTopActions/NotificationsShade).
    val conversations by viewModel.conversations.collectAsState()
    val activeConversationId by viewModel.activeConversationId.collectAsState()
    val activeConversationTitle by viewModel.activeConversationTitle.collectAsState()
    // Which inbox row is expanded to full text (null = all collapsed).
    var expandedItemId by remember { mutableStateOf<Long?>(null) }

    // Audio playback state
    val audioPlaybackInfo by audioPlaybackManager.playbackInfo.collectAsState()

    // Combine push-to-talk, TTS playback, text processing, and wake-word states.
    val state = when {
        voiceInput == VoiceInputState.RECORDING -> DuqState.RECORDING
        voiceInput == VoiceInputState.TRANSCRIBING -> DuqState.PROCESSING
        audioPlaybackInfo.state == PlaybackState.PLAYING -> DuqState.PLAYING
        isTextProcessing -> DuqState.PROCESSING
        else -> voiceState
    }

    // Text input state
    var textInput by remember { mutableStateOf("") }
    val pttScope = rememberCoroutineScope()
    val hazeState = remember { dev.chrisbanes.haze.HazeState() }

    // Bottom sheet state for conversation picker
    var showConversationPicker by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()

    // Snackbar for error display
    val snackbarHostState = remember { SnackbarHostState() }

    // Combine errors - service error takes priority
    val currentError = serviceError ?: viewModelError

    // Show snackbar when error occurs
    LaunchedEffect(currentError) {
        currentError?.let { error ->
            snackbarHostState.showSnackbar(
                message = error.toDisplayMessage(),
                withDismissAction = true
            )
            // Clear both ViewModel and service errors to prevent re-showing
            viewModel.clearError()
            voiceController?.clearError()
        }
    }

    val serviceConnection = remember {
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                voiceController = (binder as? DuqListenerService.LocalBinder)?.getController()
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                voiceController = null
            }
        }
    }

    // Track lifecycle state
    val lifecycleOwner = LocalLifecycleOwner.current
    var isBound by remember { mutableStateOf(false) }
    var permissionsGranted by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Just mark permissions as granted - binding happens in lifecycle observer
        permissionsGranted = permissions.values.all { it }
    }

    // Request permissions on launch
    LaunchedEffect(Unit) {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    // Track service binding start time for timeout
    var bindingStartTime by remember { mutableStateOf(0L) }

    // Start foreground service and bind when permissions are granted
    LaunchedEffect(permissionsGranted) {
        if (permissionsGranted && !isBound) {
            bindingStartTime = System.currentTimeMillis()
            // Start as foreground service for background operation
            val serviceIntent = Intent(context, DuqListenerService::class.java).apply {
                action = DuqListenerService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            // Also bind to get service reference for UI updates
            context.bindService(
                Intent(context, DuqListenerService::class.java),
                serviceConnection,
                Context.BIND_AUTO_CREATE
            )
            isBound = true
        }
    }

    // Service binding timeout warning
    LaunchedEffect(isBound, voiceController) {
        if (isBound && voiceController == null && bindingStartTime > 0) {
            delay(AppConfig.SERVICE_BIND_TIMEOUT_MS)
            if (voiceController == null) {
                Log.w("MainScreen", "Service binding timeout after ${AppConfig.SERVICE_BIND_TIMEOUT_MS}ms")
            }
        }
    }

    // Handle lifecycle events
    // Wake word ("Hey Duq") is DISABLED for now — input is push-to-talk via the mic
    // button. The Porcupine path stays in the service but is not started here; it
    // will be re-enabled on a license-free openWakeWord model later.

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    // Rebind to get service reference for UI updates
                    if (permissionsGranted && !isBound) {
                        context.bindService(
                            Intent(context, DuqListenerService::class.java),
                            serviceConnection,
                            Context.BIND_AUTO_CREATE
                        )
                        isBound = true
                    }
                }
                Lifecycle.Event.ON_RESUME -> {
                    // Check for a newer build on every foreground (cold start only
                    // missed updates that landed while the app sat in the background).
                    viewModel.checkForUpdate()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    // Going to background — make sure no mic capture lingers.
                    voiceController?.stopListening()
                    viewModel.cancelVoiceInput()
                }
                Lifecycle.Event.ON_STOP -> {
                    // Only unbind, don't stop service - it keeps running for WebSocket
                    if (isBound) {
                        try { context.unbindService(serviceConnection) } catch (_: Exception) {}
                        isBound = false
                        voiceController = null
                    }
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (isBound) {
                try { context.unbindService(serviceConnection) } catch (_: Exception) {}
                // Don't stop service - let it manage its own lifecycle
            }
        }
    }


    // Переключатель диалогов — список бесед из /conversations + «Новый чат».
    // (Ядро одноагентное: переключаемся между беседами, а не агентами.)
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
                        // В списке — ТОЛЬКО саммари темы (как в ChatGPT). Дата (Сегодня/Вчера/
                        // «20 июня») живёт ТОЛЬКО в шапке-кнопке, в списке её нет. Пока тема не
                        // сгенерилась (новый/пустой чат) — нейтральный «Новый чат», не дата.
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
            // Силуэт тусклее в покое (меньше артефактов на краях пузырей), заметно ярче
            // при активности. Halo при этом НЕ глушится — он рисуется на полной яркости
            // внутри DuqDuck, так что цветные вспышки статуса видны всегда.
            val duckAlpha = if (state == DuqState.IDLE) 0.42f else 0.85f
            DuqDuck(
                state = state,
                silhouetteAlpha = duckAlpha,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(220.dp)
            )
            // Статус «что делает DUQ» теперь стримится В ИСХОДЯЩЕМ ПУЗЫРЕ (VoicePhaseBlock),
            // как tool-use у ответа бота — надпись за уткой убрана (была костыльной).
            // Утка остаётся живым индикатором через halo + орбиту по состоянию.
        }

        // Header row with conversation title and settings.
        // NB: статус-бар отступ даёт внешний Scaffold (оболочка bottom-nav) — здесь
        // statusBarsPadding НЕ нужен, иначе двойной отступ сверху.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Conversation title (clickable to open the conversation switcher)
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        viewModel.loadConversations()  // освежить список перед показом
                        showConversationPicker = true
                    }
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = activeConversationTitle,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = DuqColors.textPrimary,
                    maxLines = 1
                )
                Text(
                    text = " ▼",
                    fontSize = 12.sp,
                    color = DuqColors.textSecondary
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                // \ud83d\udd0d Command palette \u2014 \u0431\u044b\u0441\u0442\u0440\u044b\u0439 \u043f\u0435\u0440\u0435\u0445\u043e\u0434 \u043a \u043b\u044e\u0431\u043e\u043c\u0443 \u0440\u0430\u0437\u0434\u0435\u043b\u0443
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(DuqColors.surfaceVariant)
                        .clickable(onClick = onOpenPalette),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Outlined.Search,
                    contentDescription = "\u041f\u043e\u0438\u0441\u043a", tint = DuqColors.textSecondary, modifier = Modifier.size(22.dp)) }
                Spacer(modifier = Modifier.width(8.dp))
                // \ud83d\udd14 \u0443\u0432\u0435\u0434\u043e\u043c\u043b\u0435\u043d\u0438\u044f + \u2699\ufe0f \u043d\u0430\u0441\u0442\u0440\u043e\u0439\u043a\u0438 \u2014 \u0433\u043b\u043e\u0431\u0430\u043b\u044c\u043d\u044b\u0439 \u0431\u043b\u043e\u043a (\u043d\u0430 \u0432\u0441\u0435\u0445 \u044d\u043a\u0440\u0430\u043d\u0430\u0445).
                com.duq.android.ui.control.GlobalTopActions()
            }
        }

        // Main content - Arc Reactor + Messages + Text Input
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Статус-бар отступ даёт внешний Scaffold (оболочка bottom-nav).
                // Здесь — только высота шапки-overlay (иконки 44dp + паддинг), без
                // statusBarsPadding, иначе тройной отступ = чёрная зона сверху.
                .padding(top = 60.dp)
        ) {
            // In-app update banner — persists until installed, so a dismissed
            // system dialog/notification can always be re-triggered here.
            if (updateReadyVersion > 0) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(DuqColors.surfaceElevated)
                        // Don't re-trigger while a download is already running.
                        .clickable(enabled = !updateInstalling) { viewModel.installUpdate() }
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (updateInstalling) "⬆ Скачиваю v$updateReadyVersion…"
                                   else "⬆ Обновление v$updateReadyVersion готово",
                            fontSize = 13.sp,
                            color = DuqColors.textPrimary
                        )
                        Text(
                            text = if (updateInstalling) "${(updateProgress * 100).toInt()}%" else "УСТАНОВИТЬ",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = DuqColors.primary
                        )
                    }
                    // Прогресс-бар скачивания APK (0..1).
                    if (updateInstalling) {
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { updateProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = DuqColors.primary,
                            trackColor = DuqColors.surfaceVariant
                        )
                    }
                }
            }

            // (Утка переехала на задний фон Box; push-to-talk теперь на кнопке справа
            // от поля ввода: тап = отправить текст, удержание = запись голоса.)

            // Messages list - middle section (no loading spinner, uses optimistic updates)
            MessagesList(
                messages = messages,
                audioPlaybackInfo = audioPlaybackInfo,
                onAudioPlayPauseClick = { messageId ->
                    audioPlaybackManager.playOrToggle(messageId)
                },
                hazeState = hazeState,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
            )

            // Text input - bottom section
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = textInput,
                    onValueChange = { textInput = it },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            text = "Type a message...",
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
                            Log.d("MainScreen", "⌨️ Keyboard Send pressed, text='${textInput.take(20)}'")
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
                            .pointerInput(textInput, audioPlaybackInfo.state) {
                                awaitEachGesture {
                                    awaitFirstDown()
                                    // Удержание > 220мс запускает запись (через корутину, т.к.
                                    // в pointer-scope нет withTimeout); короткий тап — отправка.
                                    var recordingStarted = false
                                    val holdJob = pttScope.launch {
                                        kotlinx.coroutines.delay(220)
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

private fun formatInboxTime(ms: Long): String =
    java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(ms))

