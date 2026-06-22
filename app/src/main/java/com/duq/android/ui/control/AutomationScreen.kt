package com.duq.android.ui.control

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBackIosNew
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.duq.android.network.duq.CronTaskDto
import com.duq.android.network.duq.SkillDto
import com.duq.android.ui.theme.DuqColors

/**
 * «Автоматизация»: скиллы (md-промпты) + крон-задачи (расписание → скилл).
 * Смотреть / создавать / удалять / вкл-выкл руками. Бэкенд — REST ядра.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationScreen(onBack: () -> Unit, vm: AutomationViewModel = hiltViewModel()) {
    val st by vm.state.collectAsState()
    LaunchedEffect(Unit) { vm.load() }

    var showSkillDialog by remember { mutableStateOf(false) }
    var showTaskDialog by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = DuqColors.background,
        topBar = {
            TopAppBar(
                title = { Text("Автоматизация", color = DuqColors.textPrimary) },
                navigationIcon = {
                    Icon(Icons.Outlined.ArrowBackIosNew, "Назад", tint = DuqColors.textPrimary,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onBack)
                            .padding(horizontal = 16.dp, vertical = 8.dp).size(20.dp))
                },
                actions = {
                    Icon(Icons.Outlined.Refresh, "Обновить", tint = DuqColors.textSecondary,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { vm.load() }
                            .padding(12.dp).size(20.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DuqColors.background)
            )
        }
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            st.error?.let { item { Text("⚠️ $it", color = DuqColors.accent, fontSize = 13.sp, modifier = Modifier.padding(8.dp)) } }

            item { SectionHeader("🧩 Скиллы", st.busy) { showSkillDialog = true } }
            if (st.skills.isEmpty()) item { EmptyRow("Скиллов нет — создай первый") }
            items(st.skills, key = { "s_" + it.name }) { s ->
                SkillCard(s, expanded == "s_" + s.name,
                    onTap = { expanded = if (expanded == "s_" + s.name) null else "s_" + s.name },
                    onToggle = { vm.toggleSkill(s) }, onDelete = { vm.deleteSkill(s.name) })
            }

            item { Spacer(Modifier.height(6.dp)) }
            item { SectionHeader("⏰ Крон-задачи", st.busy) { showTaskDialog = true } }
            if (st.tasks.isEmpty()) item { EmptyRow("Задач нет — создай первую") }
            items(st.tasks, key = { "t_" + it.task_id }) { t ->
                CronCard(t) { vm.deleteTask(t.task_id) }
            }
        }
    }

    if (showSkillDialog) SkillDialog(
        onDismiss = { showSkillDialog = false },
        onCreate = { n, d, c -> vm.createSkill(n, c, d); showSkillDialog = false }
    )
    if (showTaskDialog) TaskDialog(
        skills = st.skills.map { it.name },
        onDismiss = { showTaskDialog = false },
        onCreate = { n, cron, skill -> vm.createTask(n, cron, skill); showTaskDialog = false }
    )
}

@Composable
private fun SectionHeader(title: String, busy: Boolean, onAdd: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp, start = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = DuqColors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = DuqColors.primary, strokeWidth = 2.dp)
        else Icon(Icons.Outlined.Add, "Добавить", tint = DuqColors.primary,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onAdd).padding(6.dp).size(24.dp))
    }
}

@Composable
private fun EmptyRow(text: String) =
    Text(text, color = DuqColors.textDim, fontSize = 13.sp, modifier = Modifier.padding(8.dp))

@Composable
private fun SkillCard(s: SkillDto, expanded: Boolean, onTap: () -> Unit, onToggle: () -> Unit, onDelete: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(DuqColors.surfaceVariant).clickable(onClick = onTap).padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.name, color = DuqColors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                s.description?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = DuqColors.textSecondary, fontSize = 12.sp)
                }
            }
            Switch(checked = s.enabled, onCheckedChange = { onToggle() })
            Icon(Icons.Outlined.Delete, "Удалить", tint = DuqColors.accent,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onDelete).padding(6.dp).size(20.dp))
        }
        if (expanded) Text(s.content, color = DuqColors.textSecondary, fontSize = 12.sp,
            modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun CronCard(t: CronTaskDto, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(DuqColors.surfaceVariant).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(t.name ?: t.skill ?: "—", color = DuqColors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text("cron: ${t.cron ?: "—"}  ·  скилл: ${t.skill ?: "—"}", color = DuqColors.textSecondary, fontSize = 12.sp)
            t.next_run?.let { Text("next: $it", color = DuqColors.textDim, fontSize = 11.sp) }
        }
        Icon(Icons.Outlined.Delete, "Удалить", tint = DuqColors.accent,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onDelete).padding(6.dp).size(20.dp))
    }
}

@Composable
private fun SkillDialog(onDismiss: () -> Unit, onCreate: (String, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DuqColors.surfaceElevated,
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank() && content.isNotBlank()) onCreate(name, desc, content) }) {
                Text("Создать", color = DuqColors.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = DuqColors.textSecondary) } },
        title = { Text("Новый скилл", color = DuqColors.textPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(name, { name = it }, "имя (kebab-case)")
                Field(desc, { desc = it }, "описание (одна строка)")
                Field(content, { content = it }, "md-промпт: что сделать", minLines = 4)
            }
        }
    )
}

@Composable
private fun TaskDialog(skills: List<String>, onDismiss: () -> Unit, onCreate: (String, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var cron by remember { mutableStateOf("0 9 * * *") }
    var skill by remember { mutableStateOf(skills.firstOrNull() ?: "") }
    var menu by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DuqColors.surfaceElevated,
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank() && cron.isNotBlank() && skill.isNotBlank()) onCreate(name, cron, skill) }) {
                Text("Создать", color = DuqColors.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = DuqColors.textSecondary) } },
        title = { Text("Новая крон-задача", color = DuqColors.textPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(name, { name = it }, "имя задачи")
                Field(cron, { cron = it }, "cron (мин час дн мес дн.нед)")
                Box {
                    OutlinedButton(onClick = { menu = true }) {
                        Text(if (skill.isBlank()) "выбрать скилл" else "скилл: $skill", color = DuqColors.textPrimary)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (skills.isEmpty()) DropdownMenuItem(text = { Text("сначала создай скилл") }, onClick = { menu = false })
                        skills.forEach { s ->
                            DropdownMenuItem(text = { Text(s) }, onClick = { skill = s; menu = false })
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, hint: String, minLines: Int = 1) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        placeholder = { Text(hint, color = DuqColors.textDim, fontSize = 13.sp) },
        minLines = minLines, modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = DuqColors.textPrimary, unfocusedTextColor = DuqColors.textPrimary,
            focusedBorderColor = DuqColors.primary, unfocusedBorderColor = DuqColors.textDim
        )
    )
}
