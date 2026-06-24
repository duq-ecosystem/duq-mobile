package com.duq.android.ui.control

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.duq.android.network.duq.AgentInfo
import com.duq.android.network.duq.DuqRestClient
import com.duq.android.network.duq.ToolCategory
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Панель агентов в Пульте: создавать/удалять агентов, назначать им тулсет.
 * Агент = тот же DUQ, свой набор тулов + своя зона памяти/сессий (по agent_id).
 * Бьёт в REST ядра /duq/api/agents (CRUD) и /duq/api/tools (список тулов).
 */
@HiltViewModel
class AgentsViewModel @Inject constructor(
    private val rest: DuqRestClient
) : ViewModel() {

    data class UiState(
        val agents: List<AgentInfo> = emptyList(),
        val toolCategories: List<ToolCategory> = emptyList(),
        val loading: Boolean = false,
        val busy: Boolean = false,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun load() = viewModelScope.launch {
        _state.update { it.copy(loading = true, error = null) }
        runCatching {
            val agents = rest.listAgents()
            val cats = runCatching { rest.listToolCategories() }
                .onFailure { Log.w("AgentsViewModel", "listToolCategories failed: ${it.message}") }
                .getOrDefault(emptyList())
            agents to cats
        }.onSuccess { (agents, cats) ->
            _state.update { it.copy(agents = agents, toolCategories = cats, loading = false) }
        }.onFailure { e ->
            _state.update { it.copy(loading = false, error = e.message ?: "Ошибка загрузки") }
        }
    }

    private fun mutate(block: suspend () -> Unit) = viewModelScope.launch {
        _state.update { it.copy(busy = true, error = null) }
        val ok = runCatching { block() }
            .onFailure { e -> _state.update { it.copy(error = e.message ?: "Ошибка") } }
            .isSuccess
        _state.update { it.copy(busy = false) }
        // load() только при успехе: иначе он сразу обнулит error (error=null в начале load),
        // и пользователь увидит лишь вспышку ошибки. При ошибке список и так не изменился.
        if (ok) load()
    }

    /** allowedTools пустой/null → агент без доп. сужения (все тулы по правам юзера). */
    fun createAgent(id: String, displayName: String, description: String, allowedTools: List<String>) =
        mutate {
            rest.createAgent(
                id.trim(), displayName.trim(), description.trim(),
                allowedTools.ifEmpty { null }
            )
        }

    fun deleteAgent(id: String) = mutate { rest.deleteAgent(id) }
}
