package com.duq.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.duq.android.data.Account
import com.duq.android.data.SettingsRepository
import com.duq.android.network.duq.DuqRestClient
import com.duq.android.network.duq.FamilyMember
import com.duq.android.network.duq.IntegrationsResponse
import com.duq.android.ui.theme.DuqColors
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Профиль/аккаунт (мультиаккаунт). Показывает, под кем ты вошёл; даёт переключаться между
 * сохранёнными на устройстве аккаунтами (вход по имени+токену), войти под другим, удалить
 * аккаунт с устройства. Для admin — список ВСЕХ зареганых членов семьи. Плюс редактирование
 * имени и карточки интеграций. См. Multi-User-Architecture.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onBack: () -> Unit,
    onSwitched: () -> Unit,        // переключили активный аккаунт → DuqApp перезагрузит как нового
    onAddAccount: () -> Unit,      // «войти под другим» → экран входа
    rest: DuqRestClient = koinInject(),
    repo: SettingsRepository = koinInject(),
) {
    val scope = rememberCoroutineScope()
    val activeId = remember { repo.getUserId() }
    val savedName = remember { repo.getUserName() }
    var name by remember { mutableStateOf(savedName) }
    var info by remember { mutableStateOf(IntegrationsResponse()) }
    var accounts by remember { mutableStateOf(repo.getAccounts()) }
    var members by remember { mutableStateOf<List<FamilyMember>>(emptyList()) }
    var status by remember { mutableStateOf("") }

    suspend fun reload() {
        runCatching { info = rest.integrations() }
        runCatching { members = rest.familyMembers() }   // пусто если не admin
        accounts = repo.getAccounts()
    }
    LaunchedEffect(Unit) { reload() }

    val role = info.role.ifBlank { repo.getUserRole() }
    val isAdmin = role == "admin" || role == "root"

    Scaffold(
        containerColor = DuqColors.background,
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
            modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // ───────── Шапка: вы вошли как ─────────
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Avatar(name, 80.dp, 34.sp)
                Text("Вы вошли как", style = MaterialTheme.typography.bodySmall, color = DuqColors.textDim)
                Text(name.ifBlank { "Без имени" }, style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold)
                RoleBadge(role)
            }

            // ───────── Имя (Сохранить только при изменении) ─────────
            SectionCard {
                Text("Имя", style = MaterialTheme.typography.titleSmall, color = DuqColors.textSecondary)
                OutlinedTextField(name, { name = it }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                AnimatedVisibility(name.trim().isNotBlank() && name.trim() != savedName) {
                    Button(
                        onClick = {
                            scope.launch {
                                status = runCatching { rest.updateProfile(name.trim()); reload(); "Сохранено" }
                                    .getOrElse { "Ошибка: ${it.message}" }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Сохранить") }
                }
                if (status.isNotBlank()) {
                    Text(status, style = MaterialTheme.typography.bodySmall, color = DuqColors.textDim)
                }
            }

            // ───────── Аккаунты на устройстве (переключение) ─────────
            Text("Аккаунты на устройстве", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 4.dp))
            accounts.forEach { acc ->
                AccountRow(
                    acc = acc,
                    active = acc.userId == activeId,
                    onSwitch = { repo.setActiveUser(acc.userId); onSwitched() },
                    onRemove = { repo.removeAccount(acc.userId); accounts = repo.getAccounts() },
                )
            }
            OutlinedButton(onClick = onAddAccount, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.PersonAdd, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Войти под другим")
            }

            // ───────── Все пользователи (только admin) ─────────
            if (isAdmin && members.isNotEmpty()) {
                Text("Все пользователи (админ)", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 4.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    members.forEach { m -> MemberRow(m) }
                }
            }

            // ───────── Интеграции ─────────
            Text("Интеграции", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 4.dp))
            ObsidianCard(connected = info.integrations.obsidian, rest = rest,
                onLinked = { scope.launch { reload() } })
            GoogleCard(connected = info.integrations.google, rest = rest)

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun Avatar(name: String, size: androidx.compose.ui.unit.Dp, fontSize: androidx.compose.ui.unit.TextUnit) {
    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(DuqColors.primary),
        contentAlignment = Alignment.Center,
    ) {
        Text(name.trim().firstOrNull()?.uppercase() ?: "?", fontSize = fontSize,
            fontWeight = FontWeight.Bold, color = Color.Black)
    }
}

@Composable
private fun RoleBadge(role: String) {
    val (label, color) = when (role) {
        "admin", "root" -> "Администратор" to DuqColors.primary
        "" -> "—" to DuqColors.textDim
        else -> "Пользователь" to DuqColors.textSecondary
    }
    Box(
        modifier = Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.15f))
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = color,
            fontWeight = FontWeight.Medium)
    }
}

/** Строка сохранённого аккаунта: тап = переключиться; активный отмечен; корзина = убрать с устройства. */
@Composable
private fun AccountRow(acc: Account, active: Boolean, onSwitch: () -> Unit, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(DuqColors.surfaceVariant)
            .then(if (active) Modifier.border(1.dp, DuqColors.primary, RoundedCornerShape(16.dp)) else Modifier)
            .clickable(enabled = !active, onClick = onSwitch)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(acc.name, 36.dp, 15.sp)
        Column(Modifier.weight(1f)) {
            Text(acc.name.ifBlank { "Без имени" }, style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium)
            Text(if (acc.role == "admin" || acc.role == "root") "Администратор" else "Пользователь",
                style = MaterialTheme.typography.bodySmall, color = DuqColors.textDim)
        }
        if (active) {
            Icon(Icons.Filled.CheckCircle, "активен", tint = DuqColors.primary, modifier = Modifier.size(20.dp))
        } else {
            IconButton(onClick = onRemove) {
                Icon(Icons.Outlined.Delete, "Убрать", tint = DuqColors.textDim, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun MemberRow(m: FamilyMember) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(DuqColors.surfaceVariant).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(m.name, 32.dp, 13.sp)
        Text(m.name.ifBlank { "Без имени" }, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f))
        Text(if (m.role == "admin" || m.role == "root") "админ" else "юзер",
            style = MaterialTheme.typography.labelMedium, color = DuqColors.textDim)
    }
}

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(DuqColors.surfaceVariant).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/** Google: статус + «Подключить» → открывает OAuth-вход в браузере (per-user). */
@Composable
private fun GoogleCard(connected: Boolean, rest: DuqRestClient) {
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var err by remember { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(DuqColors.surfaceVariant).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(Icons.Outlined.Mail, "Google", tint = DuqColors.textSecondary, modifier = Modifier.size(26.dp))
            Column(Modifier.weight(1f)) {
                Text("Google", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                Text("Почта и календарь", style = MaterialTheme.typography.bodySmall, color = DuqColors.textDim)
            }
            StatusChip(connected)
        }
        if (!connected) {
            Text(
                "Создавать ничего в Google не нужно. Нажми «Войти через Google» — откроется " +
                    "браузер. Выбери свой Google-аккаунт и нажми «Разрешить»: DUQ получит доступ " +
                    "к твоей почте, календарю и задачам (доступ только у тебя, токены личные). " +
                    "Если Google покажет «приложение не проверено» — нажми «Дополнительно» → " +
                    "«Перейти», это нормально. Потом вернись в DUQ и снова открой профиль — " +
                    "статус станет «подключено». Дальше просто пользуйся — повторно входить не надо.",
                style = MaterialTheme.typography.bodySmall, color = DuqColors.textDim,
            )
            Button(
                onClick = {
                    scope.launch {
                        err = runCatching { uriHandler.openUri(rest.googleAuthUrl()); "" }
                            .getOrElse { "Ошибка: ${it.message}" }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Войти через Google") }
            if (err.isNotBlank()) {
                Text(err, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun StatusChip(connected: Boolean) {
    val (txt, col, icn) = if (connected) Triple("подключено", DuqColors.primary, Icons.Filled.CheckCircle)
    else Triple("не подключено", DuqColors.textDim, Icons.Outlined.CloudOff)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icn, null, tint = col, modifier = Modifier.size(16.dp))
        Text(txt, style = MaterialTheme.typography.labelMedium, color = col)
    }
}

@Composable
private fun ObsidianCard(connected: Boolean, onLinked: () -> Unit, rest: DuqRestClient) {
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var salt by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(DuqColors.surfaceVariant).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(Icons.Outlined.Folder, "Obsidian", tint = DuqColors.textSecondary, modifier = Modifier.size(26.dp))
            Column(Modifier.weight(1f)) {
                Text("Obsidian-волт", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                Text("Свой E2EE-волт через vault-sync", style = MaterialTheme.typography.bodySmall, color = DuqColors.textDim)
            }
            StatusChip(connected)
        }
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.align(Alignment.End)) {
            Text(if (expanded) "Скрыть" else if (connected) "Изменить" else "Подключить")
        }
        AnimatedVisibility(expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "URL и MCP-токен — от твоего vault-sync. Passphrase и Salt — E2EE-ключ волта " +
                        "из настроек плагина Obsidian VaultSync (тот же, чем шифруется синк).",
                    style = MaterialTheme.typography.bodySmall, color = DuqColors.textDim,
                )
                OutlinedTextField(url, { url = it }, label = { Text("URL волта (vault-sync MCP)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(token, { token = it }, label = { Text("MCP-токен") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(pass, { pass = it }, label = { Text("Passphrase (из плагина VaultSync)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(salt, { salt = it }, label = { Text("Salt (из плагина VaultSync)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(
                    onClick = {
                        scope.launch {
                            err = runCatching {
                                rest.linkObsidian(url.trim(), pass, salt.trim(), token.trim().ifBlank { null })
                                expanded = false; onLinked(); ""
                            }.getOrElse { "Ошибка: ${it.message}" }
                        }
                    },
                    enabled = url.isNotBlank() && pass.isNotBlank() && salt.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Привязать волт") }
                if (err.isNotBlank()) {
                    Text(err, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Start)
                }
            }
        }
    }
}
