package com.duq.android.ui

import com.duq.android.data.model.Message
import com.duq.android.data.model.MessageRole
import com.duq.android.data.model.MessageStep

/**
 * Чистые редьюсеры привязки tool/command-шагов агента к его ответу. Шаги и дельты
 * чата приходят одним упорядоченным WS-стримом с общим runId → шаг принадлежит ответу
 * с совпадающим id сообщения. Логика Android-free и юнит-тестируемая; ConversationViewModel
 * просто применяет их к `_messages`.
 */
object ChatStepReducer {

    /**
     * Upsert одного tool-вызова в сообщение с id == [runId]. Если его ещё нет (шаг
     * может прийти до первой дельты, создавшей пузырь) — создаём пустой streaming-плейсхолдер,
     * чтобы поздняя дельта нашла его по id, а не вставила дубль. Матч по [callId] (toolCallId):
     * событие `tool` и парное `command`/`patch` схлопываются в ОДИН шаг; `tool`-лейбл
     * авторитетен и не перезаписывается `command`/`patch`-деталью.
     */
    fun upsertStep(
        messages: List<Message>,
        runId: String,
        callId: String,
        label: String,
        kind: String,
        done: Boolean
    ): List<Message> {
        val base = if (messages.any { it.id == runId }) {
            messages
        } else {
            messages + Message(id = runId, role = MessageRole.ASSISTANT, content = "", isStreaming = true)
        }

        return base.map { m ->
            if (m.id != runId) return@map m
            val steps = m.steps.toMutableList()
            val idx = steps.indexOfFirst { it.callId == callId }
            if (idx < 0) {
                steps.add(MessageStep(callId = callId, label = label, kind = kind, done = done))
            } else {
                val cur = steps[idx]
                val keepToolLabel = cur.kind == "tool" && kind != "tool"
                steps[idx] = cur.copy(
                    label = if (keepToolLabel) cur.label else label,
                    kind = if (keepToolLabel) cur.kind else kind,
                    done = done
                )
            }
            m.copy(steps = steps)
        }
    }

    /** Помечает все шаги сообщения [runId] завершёнными — на финале/аборте ответа,
     *  чтобы шаг без пришедшего `phase:"end"` не завис со спиннером. */
    fun markAllStepsDone(messages: List<Message>, runId: String): List<Message> =
        messages.map { m ->
            if (m.id == runId && m.steps.any { !it.done }) {
                m.copy(steps = m.steps.map { it.copy(done = true) })
            } else {
                m
            }
        }
}
