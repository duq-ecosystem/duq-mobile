package com.duq.android.network.duq

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Ошибка вызова REST-API ядра DUQ. */
class DuqApiException(message: String) : Exception(message)

/** Ответ серверного STT (/stt, faster-whisper за nginx) — текст распознавания. */
@Serializable
data class SttResponse(val text: String? = null)

// ───────── Скиллы (md-промпты) — /duq/api/skills ─────────

@Serializable
data class SkillDto(
    val name: String,
    val description: String? = null,
    val content: String,
    val enabled: Boolean = true,
    @SerialName("allowed_tools") val allowedTools: List<String>? = null
)

@Serializable
data class SkillListDto(val skills: List<SkillDto>? = null, val total: Int? = null)

@Serializable
data class SkillCreateBody(val name: String, val content: String, val description: String? = null)

@Serializable
data class SkillUpdateBody(
    val content: String? = null,
    val description: String? = null,
    val enabled: Boolean? = null
)

// ───────── Крон-задачи — /duq/api/scheduler/tasks ─────────

@Serializable
data class CronTaskDto(
    @SerialName("task_id") val taskId: String,
    val name: String? = null,
    val cron: String? = null,
    val timezone: String? = null,
    @SerialName("next_run") val nextRun: String? = null,
    val skill: String? = null,
    val enabled: Boolean = true,
    @SerialName("agent_id") val agentId: String = "main"
)

@Serializable
data class CronTaskListDto(val tasks: List<CronTaskDto>? = null, val total: Int? = null)

@Serializable
data class CronCreateBody(
    val name: String,
    val cron: String,
    val skill: String,
    val timezone: String,
    @SerialName("agent_id") val agentId: String = "main",
    @SerialName("user_id") val userId: String? = null
)

@Serializable
data class CronEnabledBody(val enabled: Boolean)

@Serializable
data class CronPatchBody(
    val cron: String? = null,
    val skill: String? = null,
    val name: String? = null,
    @SerialName("agent_id") val agentId: String? = null
)

// ───────── Тулы / агенты ─────────

@Serializable
data class ToolCategory(val name: String = "", val tools: List<String> = emptyList())

@Serializable
data class ToolsResponse(
    val tools: List<String> = emptyList(),
    val categories: List<ToolCategory> = emptyList()
)

@Serializable
data class AgentCreateBody(
    val id: String,
    @SerialName("display_name") val displayName: String,
    val description: String,
    @SerialName("allowed_tools") val allowedTools: List<String>?
)
