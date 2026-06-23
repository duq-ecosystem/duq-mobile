package com.duq.android.ui.control

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBackIosNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.duq.android.network.CoreUpdateClient
import com.duq.android.ui.theme.DuqColors

/**
 * Раздел Пульта. Остаётся единственный раздел — «Движок»:
 * версия ядра + обновление через бэкенд-ручку /core-update (HTTP). Прежние
 * gateway-RPC разделы убраны (у ядра DUQ нет gateway-RPC). Полная админка ядра —
 * отдельная фича под будущий core admin-REST.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SectionScreen(sectionKey: String, onBack: () -> Unit, vm: SectionViewModel = hiltViewModel()) {
    when (sectionKey) {
        "skills" -> SkillsScreen(onBack)
        "schedule" -> ScheduleScreen(onBack)
        else -> VersionScreen(vm, onBack)   // "version" (+ legacy "engine")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VersionScreen(vm: SectionViewModel, onBack: () -> Unit) {
    val core by vm.core.collectAsState()
    val app by vm.app.collectAsState()
    LaunchedEffect(Unit) { vm.loadCore(); vm.loadApp() }
    // Пока апдейт ядра идёт — авто-опрос статуса (живой прогресс + детект завершения).
    val running = (core as? SectionViewModel.CoreState.Data)?.status?.running == true
    LaunchedEffect(running) {
        while (running) {
            kotlinx.coroutines.delay(8000)
            vm.loadCore()
        }
    }
    Scaffold(
        containerColor = DuqColors.background,
        topBar = {
            TopAppBar(
                title = { Text("Версия", color = DuqColors.textPrimary) },
                navigationIcon = {
                    Icon(Icons.Outlined.ArrowBackIosNew, contentDescription = "Назад",
                        tint = DuqColors.textPrimary,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onBack)
                            .padding(horizontal = 16.dp, vertical = 8.dp).size(20.dp))
                },
                actions = {
                    Icon(Icons.Outlined.Refresh, contentDescription = "Обновить",
                        tint = DuqColors.primary,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp))
                            .clickable { vm.loadCore(); vm.loadApp() }.padding(horizontal = 14.dp, vertical = 8.dp).size(22.dp))
                    GlobalTopActions()
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DuqColors.background),
                windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            item { AppCard(app, vm) }
            item {
                when (val s = core) {
                    is SectionViewModel.CoreState.Loading ->
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = DuqColors.primary, modifier = Modifier.size(22.dp))
                        }
                    is SectionViewModel.CoreState.Error ->
                        Text("⚠️ ядро: ${s.message}", color = DuqColors.textMuted, fontSize = 12.sp,
                            modifier = Modifier.padding(8.dp))
                    is SectionViewModel.CoreState.Data -> EngineCard(s.status, vm)
                }
            }
        }
    }
}

@Composable private fun AppCard(st: SectionViewModel.AppState, vm: SectionViewModel) = Card {
    Text("📱 Приложение", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = DuqColors.textPrimary)
    Text("текущая: ${st.currentName}", fontSize = 12.sp, color = DuqColors.textSecondary, modifier = Modifier.padding(top = 4.dp))
    if (st.updateAvailable)
        Text("доступна: v${st.remoteCode}", fontSize = 12.sp, color = DuqColors.primary, modifier = Modifier.padding(top = 2.dp))
    Spacer(Modifier.height(10.dp))
    when {
        st.installing -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(color = DuqColors.primary, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("Скачиваю… ${(st.progress * 100).toInt()}%", fontSize = 13.sp, color = DuqColors.textSecondary)
        }
        st.updateAvailable -> ActionChip("Обновить приложение", DuqColors.primary) { vm.installApp() }
        else -> Text("✓ Установлена последняя версия", fontSize = 13.sp, color = DuqColors.success)
    }
}

@Composable private fun EngineCard(st: CoreUpdateClient.Status, vm: SectionViewModel) = Card {
    var showConfirm by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    Text("⚙️ Ядро DUQ", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = DuqColors.textPrimary)
    // Версии (git-ревизии ядра) показываем только в покое; во время апдейта — «идёт».
    if (!st.running) {
        Text("текущая: ${st.current ?: "?"}", fontSize = 12.sp, color = DuqColors.textSecondary,
            modifier = Modifier.padding(top = 4.dp))
        if (!st.latest.isNullOrBlank() && st.latest != st.current)
            Text("доступна: ${st.latest}", fontSize = 12.sp, color = DuqColors.primary, modifier = Modifier.padding(top = 2.dp))
        if (st.updateAvailable)
            Text("⚡ Доступно обновление", fontSize = 12.sp, color = DuqColors.success, modifier = Modifier.padding(top = 2.dp))
    }
    Spacer(Modifier.height(10.dp))
    if (st.running) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(color = DuqColors.primary, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("Обновляется до ${st.latest ?: "новой версии"}… (~8-10 мин)", fontSize = 13.sp, color = DuqColors.textSecondary)
        }
        if (st.log.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                st.log.trim().lines().takeLast(6).joinToString("\n"),
                fontSize = 10.sp,
                color = DuqColors.textMuted,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            )
        }
    } else if (st.updateAvailable) {
        ActionChip("Обновить ядро", DuqColors.primary) { showConfirm = true }
    } else {
        Text("✓ Установлена последняя версия", fontSize = 13.sp, color = DuqColors.success)
    }
    msg?.let { Text(it, fontSize = 12.sp, color = DuqColors.textSecondary, modifier = Modifier.padding(top = 8.dp)) }

    if (showConfirm) AlertDialog(
        onDismissRequest = { showConfirm = false },
        title = { Text("Обновить ядро?") },
        text = { Text("Ядро обновится до новой версии. Бот перезапустится и будет недоступен несколько минут. Продолжить?") },
        confirmButton = {
            TextButton(onClick = {
                showConfirm = false
                vm.runCore { res -> msg = when (res) {
                    CoreUpdateClient.RunResult.STARTED -> "Обновление запущено…"
                    CoreUpdateClient.RunResult.ALREADY_RUNNING -> "Обновление уже идёт"
                    CoreUpdateClient.RunResult.FAILED -> "Не удалось запустить (бэкенд недоступен?)"
                } }
            }) { Text("Обновить", color = DuqColors.primary) }
        },
        dismissButton = { TextButton(onClick = { showConfirm = false }) { Text("Отмена") } },
        containerColor = DuqColors.surfaceVariant
    )
}

// ---- shared UI ----
@Composable private fun Card(content: @Composable ColumnScope.() -> Unit) = Column(
    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(DuqColors.surfaceVariant)
        .border(1.dp, DuqColors.glassBorder, RoundedCornerShape(12.dp)).padding(14.dp), content = content)

@Composable private fun ActionChip(label: String, color: Color, onClick: () -> Unit) = Text(
    label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = color,
    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.12f))
        .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp))
