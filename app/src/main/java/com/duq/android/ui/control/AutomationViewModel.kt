package com.duq.android.ui.control

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.duq.android.network.duq.CronTaskDto
import com.duq.android.network.duq.DuqRestClient
import com.duq.android.network.duq.SkillDto
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Экран «Автоматизация»: управление СКИЛЛАМИ (md-промпты) и КРОН-задачами
 * (расписание → скилл). Бьёт в REST ядра /duq/api/skills и /duq/api/scheduler/tasks
 * через [DuqRestClient]. Создание/удаление/вкл-выкл — здесь, руками.
 */
@HiltViewModel
class AutomationViewModel @Inject constructor(
    private val rest: DuqRestClient
) : ViewModel() {

    data class UiState(
        val skills: List<SkillDto> = emptyList(),
        val tasks: List<CronTaskDto> = emptyList(),
        val loading: Boolean = false,
        val busy: Boolean = false,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val tz: String = runCatching { java.util.TimeZone.getDefault().id }.getOrDefault("UTC")

    fun load() = viewModelScope.launch {
        _state.update { it.copy(loading = true, error = null) }
        runCatching {
            val skills = rest.listSkills()
            val tasks = rest.listCronTasks()
            skills to tasks
        }.onSuccess { (skills, tasks) ->
            _state.update { it.copy(skills = skills, tasks = tasks, loading = false) }
        }.onFailure { e ->
            _state.update { it.copy(loading = false, error = e.message ?: "Ошибка загрузки") }
        }
    }

    private fun mutate(block: suspend () -> Unit) = viewModelScope.launch {
        _state.update { it.copy(busy = true, error = null) }
        runCatching { block() }
            .onFailure { e -> _state.update { it.copy(error = e.message ?: "Ошибка") } }
        _state.update { it.copy(busy = false) }
        load()
    }

    fun createSkill(name: String, content: String, description: String?) =
        mutate { rest.createSkill(name.trim(), content.trim(), description?.trim()?.ifBlank { null }) }

    fun toggleSkill(s: SkillDto) = mutate { rest.updateSkill(s.name, enabled = !s.enabled) }

    fun deleteSkill(name: String) = mutate { rest.deleteSkill(name) }

    fun createTask(name: String, cron: String, skill: String) =
        mutate { rest.createCronTask(name.trim(), cron.trim(), skill, tz) }

    fun deleteTask(taskId: String) = mutate { rest.deleteCronTask(taskId) }
}
