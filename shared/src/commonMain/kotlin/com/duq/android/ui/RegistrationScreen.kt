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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.duq.android.config.AppConfig
import com.duq.android.data.SettingsRepository
import com.duq.android.network.duq.DuqNodeClient
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
    onBack: (() -> Unit)? = null, // не-null = «войти под другим» поверх профиля (показать назад)
    rest: DuqRestClient = koinInject(),
    repo: SettingsRepository = koinInject(),
    nodeClient: DuqNodeClient = koinInject(),
) {
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var name by remember { mutableStateOf("") }
    var token by remember { mutableStateOf(repo.getServerToken()) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Вход в DUQ") },
            navigationIcon = {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                }
            },
        )
    }) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(24.dp).fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Введи своё имя и общий токен системы (его даёт владелец). Есть такой член семьи — " +
                    "войдёшь в него, нет — заведётся новый.",
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
                    scope.launch {
                        status = runCatching {
                            rest.login(name.trim())
                        }.fold(
                            onSuccess = {
                                nodeClient.reconnect()
                                onRegistered()
                                ""
                            },
                            onFailure = {
                                busy = false
                                "Ошибка: ${it.message}"
                            },
                        )
                    }
                },
                enabled = !busy && name.isNotBlank() && token.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (busy) "Вхожу…" else "Войти") }

            // Альтернативный путь входа — через официальный Telegram Login Widget. Открывает
            // страницу входа в браузере; после авторизации сервер вернёт в приложение по
            // deep link (duq://auth/telegram) с per-user токеном — вход завершит DuqApp-приёмник.
            Text("или", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(
                onClick = { uriHandler.openUri(AppConfig.TELEGRAM_LOGIN_URL) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Войти через Telegram") }

            if (status.isNotBlank()) {
                Text(
                    status,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
