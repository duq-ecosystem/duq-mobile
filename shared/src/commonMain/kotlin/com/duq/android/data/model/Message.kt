package com.duq.android.data.model

import com.duq.android.util.nowMillis
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

enum class MessageRole {
    USER, ASSISTANT;
    companion object {
        fun fromApiString(str: String): MessageRole = when (str.lowercase()) {
            "user" -> USER
            else -> ASSISTANT
        }
    }
    fun toApiString(): String = name.lowercase()
}

/**
 * Один tool-вызов агента в ответе — рендерится в пузыре сворачиваемым блоком «tool use».
 * Ключ [callId] = toolCallId; [done] → true на phase:"end".
 */
data class MessageStep(
    val callId: String,
    val label: String,
    val kind: String,
    val done: Boolean = false
)

@OptIn(ExperimentalUuidApi::class)
data class Message(
    val id: String = Uuid.random().toString(),
    val conversationId: String = "",
    val role: MessageRole,
    val content: String,
    val hasAudio: Boolean = false,
    // Идёт ре-синтез озвучки по тапу play (кэш стёрт) — кнопка показывает спиннер.
    val isAudioLoading: Boolean = false,
    val audioDurationMs: Int? = null,
    val waveform: List<Float>? = null,
    val isStreaming: Boolean = false,
    val steps: List<MessageStep> = emptyList(),
    val voicePhase: VoicePhase? = null,
    // Unix epoch millis — канонический порядок сообщений (kotlinx-datetime убран:
    // Clock.System не резолвился под Kotlin 2.3.20 на iOS klib).
    val createdAt: Long = nowMillis()
)

/**
 * Фаза голосового ввода для ИСХОДЯЩЕГО (user) пузыря: пока юзер говорит и идёт
 * on-device распознавание, пузырь стримит фазу, затем показывает финальный транскрипт.
 */
enum class VoicePhase { RECORDING, TRANSCRIBING }
