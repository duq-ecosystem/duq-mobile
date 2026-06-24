package com.duq.android.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.duq.android.data.model.Message
import com.duq.android.data.model.MessageRole
import com.duq.android.data.model.MessageStep
import com.duq.android.data.model.VoicePhase
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontStyle
import com.duq.android.ui.theme.DuqColors
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Premium Message bubble with Glassmorphism 3.0 design.
 *
 * Features:
 * - Animated slide-in entrance
 * - Glass-like translucent backgrounds with subtle glow
 * - Gradient borders with shimmer effect
 * - Streaming text support for AI responses
 * - Audio playback controls for voice messages
 *
 * NB: frosted-glass blur (haze) убран на этой фазе — `dev.chrisbanes.haze` ещё не
 * подключён в shared. Пузырь рисует обычный градиентный фон (ветка else исходника).
 * Блюр вернётся на фазе экранов вместе с haze-зависимостью.
 */
@Composable
fun MessageBubble(
    message: Message,
    modifier: Modifier = Modifier,
    isStreaming: Boolean = false,
    audioPlaybackState: AudioPlaybackState = AudioPlaybackState.IDLE,
    audioProgress: Float = 0f,
    audioDurationMs: Int? = null,   // живая длительность из PlaybackInfo (msg.audioDurationMs не заполняется)
    onAudioPlayPauseClick: () -> Unit = {}
) {
    val isUser = message.role == MessageRole.USER
    val alignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart

    // Animation state
    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { isVisible = true }

    // Glow animation for streaming
    val infiniteTransition = rememberInfiniteTransition(label = "bubbleGlow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAlpha"
    )

    // Premium color schemes
    val (backgroundBrush, borderBrush, glowColor) = if (isUser) {
        Triple(
            Brush.linearGradient(
                colors = listOf(
                    DuqColors.primary.copy(alpha = 0.2f),
                    DuqColors.primary.copy(alpha = 0.08f),
                    DuqColors.primaryDim.copy(alpha = 0.05f)
                ),
                start = Offset(0f, 0f),
                end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
            ),
            Brush.linearGradient(
                colors = listOf(
                    DuqColors.primary.copy(alpha = 0.5f),
                    DuqColors.primary.copy(alpha = 0.2f),
                    DuqColors.primaryDim.copy(alpha = 0.1f)
                )
            ),
            DuqColors.primary
        )
    } else {
        Triple(
            Brush.linearGradient(
                colors = listOf(
                    DuqColors.surfaceElevated,
                    DuqColors.surface.copy(alpha = 0.8f),
                    DuqColors.surfaceVariant.copy(alpha = 0.5f)
                ),
                start = Offset(0f, 0f),
                end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
            ),
            Brush.linearGradient(
                colors = listOf(
                    DuqColors.glassBorder.copy(alpha = 0.3f),
                    DuqColors.glassBorder.copy(alpha = 0.1f),
                    Color.Transparent
                )
            ),
            if (isStreaming) DuqColors.accent else DuqColors.primary
        )
    }

    val bubbleShape = RoundedCornerShape(
        topStart = 20.dp,
        topEnd = 20.dp,
        bottomStart = if (isUser) 20.dp else 4.dp,
        bottomEnd = if (isUser) 4.dp else 20.dp
    )

    AnimatedVisibility(
        visible = isVisible,
        enter = slideInHorizontally(
            initialOffsetX = { if (isUser) it else -it },
            animationSpec = spring(dampingRatio = 0.8f, stiffness = 300f)
        ) + fadeIn(animationSpec = tween(300))
    ) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp, horizontal = 12.dp),
            contentAlignment = alignment
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    // Soft glow that FOLLOWS the bubble shape (Duq bubbles / while
                    // streaming). A radial circle behind a rounded-rect bubble spills
                    // past the corners as a halo — a shape-matched shadow tint glows
                    // the edge cleanly with no spill.
                    .then(
                        if (isStreaming || !isUser)
                            Modifier.shadow(
                                elevation = if (isStreaming) 14.dp else 5.dp,
                                shape = bubbleShape,
                                ambientColor = glowColor.copy(alpha = if (isStreaming) glowAlpha else 0.18f),
                                spotColor = glowColor.copy(alpha = if (isStreaming) glowAlpha else 0.18f),
                                clip = false
                            )
                        else Modifier
                    )
                    .clip(bubbleShape)
                    .background(backgroundBrush)
                    .border(
                        width = 1.dp,
                        brush = borderBrush,
                        shape = bubbleShape
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                // Sender label for Duq with icon
                if (!isUser) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 6.dp)
                    ) {
                        // Mini duck icon indicator
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    brush = Brush.radialGradient(
                                        colors = listOf(
                                            DuqColors.primary,
                                            DuqColors.primaryDim
                                        )
                                    ),
                                    shape = RoundedCornerShape(50)
                                )
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Duq",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = DuqColors.primary,
                            letterSpacing = 1.sp
                        )
                        if (isStreaming) {
                            Spacer(modifier = Modifier.width(8.dp))
                            // Typing indicator dots
                            TypingIndicator()
                        }
                    }
                }

                // Inline collapsible "tool use" block (like Claude) — the steps the
                // agent ran for this reply. Collapsed by default; live label while running.
                if (!isUser && message.steps.isNotEmpty()) {
                    ToolStepsBlock(steps = message.steps)
                    Spacer(modifier = Modifier.height(if (message.content.isNotEmpty() || isStreaming) 8.dp else 0.dp))
                }

                // Message content
                if (isUser && message.voicePhase != null) {
                    // Исходящий голосовой пузырь стримит фазу ввода (запись → распознавание),
                    // как блок tool-use у ответа бота — без надписи за уткой.
                    VoicePhaseBlock(phase = message.voicePhase)
                } else if (!isUser && (isStreaming || message.content.isNotEmpty())) {
                    StreamingText(
                        text = message.content,
                        isStreaming = isStreaming,
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        color = DuqColors.textPrimary
                    )
                } else {
                    Text(
                        text = message.content,
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        color = DuqColors.textPrimary,
                        fontWeight = FontWeight.Normal
                    )
                }

                // Audio playback controls for voice messages
                if (message.hasAudio) {
                    Spacer(modifier = Modifier.height(8.dp))
                    AudioMessageControls(
                        state = audioPlaybackState,
                        durationMs = audioDurationMs ?: message.audioDurationMs,
                        progress = audioProgress,
                        onPlayPauseClick = onAudioPlayPauseClick
                    )
                }

                // Timestamp (createdAt — Unix epoch millis; форматируем HH:mm в системной TZ)
                Text(
                    text = formatTime(message.createdAt),
                    fontSize = 10.sp,
                    color = DuqColors.textMuted,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .align(Alignment.End)
                )
            }
        }
    }
}

/** Unix epoch millis → "HH:mm" в системной таймзоне (мультиплатформенно, kotlinx-datetime). */
private fun formatTime(epochMillis: Long): String {
    val ldt = Instant.fromEpochMilliseconds(epochMillis)
        .toLocalDateTime(TimeZone.currentSystemDefault())
    val h = ldt.hour.toString().padStart(2, '0')
    val m = ldt.minute.toString().padStart(2, '0')
    return "$h:$m"
}

/**
 * Collapsible "tool use" block shown inside an assistant bubble — mirrors how
 * Claude/ChatGPT surface tool calls: a compact header (live step label while the
 * agent is working, or "Использовал инструменты · N" once done) that expands to
 * the full step list. Collapsed by default.
 */
@Composable
private fun ToolStepsBlock(steps: List<MessageStep>) {
    var expanded by remember { mutableStateOf(false) }
    val running = steps.any { !it.done }
    val chevronRotation by animateFloatAsState(if (expanded) 90f else 0f, label = "chevron")

    // Header summary: while running show the latest active step so the user sees
    // live activity (this replaces the old under-the-duck line); once done, a count.
    val summary = if (running) {
        steps.lastOrNull { !it.done }?.label ?: steps.last().label
    } else {
        "Использовал инструменты · ${steps.size}"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(DuqColors.surfaceVariant.copy(alpha = 0.35f))
            .border(1.dp, DuqColors.glassBorder.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
            .clickable { expanded = !expanded }
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (running) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                    color = DuqColors.accent
                )
            } else {
                Text(text = "✓", fontSize = 12.sp, color = DuqColors.primary)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = summary,
                fontSize = 12.sp,
                color = DuqColors.textSecondary,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            // Chevron ">" rotates to "v" when expanded.
            Text(
                text = "›",
                fontSize = 15.sp,
                color = DuqColors.textMuted,
                modifier = Modifier.rotate(chevronRotation)
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(top = 6.dp)) {
                steps.forEach { step ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 2.dp)
                    ) {
                        if (step.done) {
                            Text(text = "✓", fontSize = 11.sp, color = DuqColors.primary.copy(alpha = 0.7f))
                        } else {
                            CircularProgressIndicator(
                                modifier = Modifier.size(10.dp),
                                strokeWidth = 1.5.dp,
                                color = DuqColors.accent
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = step.label,
                            fontSize = 12.sp,
                            color = DuqColors.textSecondary,
                            maxLines = 2
                        )
                    }
                }
            }
        }
    }
}

/**
 * Animated typing indicator (three bouncing dots)
 */
@Composable
private fun TypingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "typing")

    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(3) { index ->
            val delay = index * 150
            val offsetY by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = -4f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes {
                        durationMillis = 600
                        0f at 0
                        -4f at 150
                        0f at 300
                        0f at 600
                    },
                    repeatMode = RepeatMode.Restart,
                    initialStartOffset = StartOffset(delay)
                ),
                label = "dot$index"
            )

            Box(
                modifier = Modifier
                    .offset(y = offsetY.dp)
                    .size(4.dp)
                    .background(
                        color = DuqColors.accent,
                        shape = RoundedCornerShape(50)
                    )
            )
        }
    }
}

/**
 * Live-блок фазы голосового ввода внутри ИСХОДЯЩЕГО пузыря — аналог tool-use блока
 * у ответа бота, но для своей реплики: «Слушаю…» с пульсирующей красной точкой
 * (идёт запись) → «Распознаю речь…» со спиннером (on-device whisper). Когда фаза
 * заканчивается, ViewModel заменяет её финальным транскриптом — блок исчезает.
 */
@Composable
private fun VoicePhaseBlock(phase: VoicePhase) {
    val label = when (phase) {
        VoicePhase.RECORDING -> "Слушаю…"
        VoicePhase.TRANSCRIBING -> "Распознаю речь…"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (phase) {
            VoicePhase.RECORDING -> {
                val t = rememberInfiniteTransition(label = "recDot")
                val a by t.animateFloat(
                    initialValue = 0.3f, targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        tween(600, easing = FastOutSlowInEasing), RepeatMode.Reverse
                    ),
                    label = "recDotAlpha"
                )
                Box(
                    Modifier
                        .size(11.dp)
                        .alpha(a)
                        .clip(CircleShape)
                        .background(DuqColors.error)
                )
            }
            VoicePhase.TRANSCRIBING -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(15.dp),
                    color = Color(0xFF7C4DFF),
                    strokeWidth = 2.dp
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            color = DuqColors.textPrimary,
            fontSize = 15.sp,
            fontStyle = FontStyle.Italic
        )
    }
}
