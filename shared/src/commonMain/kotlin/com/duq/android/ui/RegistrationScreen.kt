package com.duq.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.duq.android.data.SettingsRepository
import com.duq.android.network.duq.DuqRestClient
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Экран регистрации ПЕРВОГО входа (решение Дениса: никакой авто-регистрации). Юзер скачал app,
 * при первом запуске вводит СВОЁ ИМЯ и ОБЩИЙ ТОКЕН СИСТЕМЫ (edge-token семьи) → жмёт
 * «Зарегистрироваться». Токен сохраняется на устройстве (идёт в X-Auth-Token), имя → в ядро
 * (POST register method=app). ПЕРВЫЙ зарегистрированный = admin, последующие = public (роль в БД).
 * После успеха [onRegistered] ведёт в основное приложение. Интеграции (волт/gmail) — потом в панели.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegistrationScreen(
    onRegistered: () -> Unit,
    rest: DuqRestClient = koinInject(),
    repo: SettingsRepository = koinInject(),
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(repo.getUserName()) }
    var token by remember { mutableStateOf(repo.getServerToken()) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Scaffold(topBar = { TopAppBar(title = { Text("Регистрация в DUQ") }) }) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(24.dp).fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Первый вход. Введи своё имя и общий токен системы (его даёт владелец).",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Имя") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("Общий токен системы") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    busy = true
                    repo.saveServerToken(token.trim())
                    repo.saveUserName(name.trim())
                    scope.launch {
                        status = runCatching {
                            rest.ensureRegistered(name.trim().ifBlank { null })
                        }.fold(
                            onSuccess = { onRegistered(); "" },
                            onFailure = { busy = false; "Ошибка: ${it.message}" },
                        )
                    }
                },
                enabled = !busy && name.isNotBlank() && token.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (busy) "Регистрирую…" else "Зарегистрироваться") }

            if (status.isNotBlank()) {
                Text(status, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
