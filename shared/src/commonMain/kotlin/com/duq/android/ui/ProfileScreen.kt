package com.duq.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.duq.android.data.SettingsRepository
import com.duq.android.network.duq.DuqRestClient
import com.duq.android.network.duq.IntegrationsStatus
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Панель пользователя (мультиюзер). Член семьи задаёт имя (регистрируется на ядре под общим
 * edge-токеном → персональный user_id) и видит/ставит свои интеграции (Obsidian-волт, Google).
 * Токен общий на семью, личность — по user_id. См. Multi-User-Architecture.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onBack: () -> Unit,
    rest: DuqRestClient = koinInject(),
    repo: SettingsRepository = koinInject(),
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(repo.getUserName()) }
    var userId by remember { mutableStateOf(repo.getUserId()) }
    var integrations by remember { mutableStateOf(IntegrationsStatus()) }
    var status by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        runCatching { integrations = rest.integrations().integrations }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Профиль") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Кто ты", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Имя") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    repo.saveUserName(name.trim())
                    scope.launch {
                        status = runCatching {
                            userId = rest.ensureRegistered(name.trim().ifBlank { null })
                            integrations = rest.integrations().integrations
                            "Сохранено"
                        }.getOrElse { "Ошибка: ${it.message}" }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (userId.isBlank()) "Зарегистрироваться" else "Сохранить") }

            if (userId.isNotBlank()) {
                Text("ID: $userId", style = MaterialTheme.typography.bodySmall)
            }
            if (status.isNotBlank()) {
                Text(status, style = MaterialTheme.typography.bodySmall)
            }

            HorizontalDivider()
            Text("Интеграции", style = MaterialTheme.typography.titleMedium)
            IntegrationRow("Obsidian-волт", integrations.obsidian)
            IntegrationRow("Google (почта/календарь)", integrations.google)

            // Форма привязки своего E2EE-волта: ключи/настройки вводятся здесь и сохраняются
            // в ядре per-user (vault-тулы юзера потом резолвят его волт по этим данным).
            var vaultUrl by remember { mutableStateOf("") }
            var vaultPass by remember { mutableStateOf("") }
            var vaultSalt by remember { mutableStateOf("") }
            var vaultToken by remember { mutableStateOf("") }
            Text("Подключить Obsidian-волт", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(vaultUrl, { vaultUrl = it }, label = { Text("URL волта (vault-sync MCP)") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(vaultToken, { vaultToken = it }, label = { Text("MCP-токен (опц.)") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(vaultPass, { vaultPass = it }, label = { Text("Passphrase (E2EE)") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(vaultSalt, { vaultSalt = it }, label = { Text("Salt (base64)") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(
                onClick = {
                    scope.launch {
                        status = runCatching {
                            rest.linkObsidian(
                                vaultUrl = vaultUrl.trim(),
                                passphrase = vaultPass,
                                saltB64 = vaultSalt.trim(),
                                mcpToken = vaultToken.trim().ifBlank { null },
                            )
                            integrations = rest.integrations().integrations
                            "Волт подключён"
                        }.getOrElse { "Ошибка волта: ${it.message}" }
                    }
                },
                enabled = vaultUrl.isNotBlank() && vaultPass.isNotBlank() && vaultSalt.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Привязать волт") }
        }
    }
}

@Composable
private fun IntegrationRow(title: String, connected: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title)
        Text(
            if (connected) "подключено" else "не подключено",
            color = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}
