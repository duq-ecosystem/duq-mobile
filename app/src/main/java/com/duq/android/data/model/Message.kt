package com.duq.android.data.model

import java.time.Instant

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
 * One tool call the agent ran while producing this reply (e.g. a web fetch, a
 * mail check). Rendered inline in the assistant bubble as a collapsible "tool
 * use" block, like Claude/ChatGPT — not as a transient line under the duck.
 *
 * Keyed by [callId] = the `toolCallId`, NOT the raw item id: the engine
 * emits up to two item events per call — a `kind:"tool"` one (the tool itself)
 * and, for exec/patch tools, a `kind:"command"`/`"patch"` one carrying the live
 * shell detail — both sharing one `toolCallId`. Grouping by callId collapses that
 * pair into a single step (the engine's own model), with the `"tool"` event
 * authoritative for the label. [done] flips true on its `phase:"end"`.
 */
data class MessageStep(
    val callId: String,
    val label: String,
    val kind: String,
    val done: Boolean = false
)

data class Message(
    val id: String = java.util.UUID.randomUUID().toString(),
    val conversationId: String = "",
    val role: MessageRole,
    val content: String,
    val hasAudio: Boolean = false,
    // Идёт ре-синтез озвучки по тапу play (кэш был стёрт) — кнопка показывает спиннер
    // и блокирует повторный тап (защита от двойного синтеза).
    val isAudioLoading: Boolean = false,
    val audioDurationMs: Int? = null,
    val waveform: List<Float>? = null,
    val isStreaming: Boolean = false,
    val steps: List<MessageStep> = emptyList(),
    val voicePhase: VoicePhase? = null,
    val createdAt: Instant = Instant.now()
)

/**
 * Фаза голосового ввода для ИСХОДЯЩЕГО (user) пузыря. Пока юзер говорит и идёт
 * on-device распознавание, пузырь стримит фазу (как tool-steps у ответа бота),
 * затем фаза гаснет и пузырь показывает финальный транскрипт.
 */
enum class VoicePhase { RECORDING, TRANSCRIBING }
