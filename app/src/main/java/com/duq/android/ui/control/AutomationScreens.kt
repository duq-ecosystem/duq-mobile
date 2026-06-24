package com.duq.android.ui.control

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBackIosNew
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.duq.android.network.duq.CronTaskDto
import com.duq.android.network.duq.SkillDto
import com.duq.android.ui.theme.DuqColors

/* ════════════════════ ЭКРАН «СКИЛЛЫ» ════════════════════ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsScreen(onBack: () -> Unit, vm: AutomationViewModel = hiltViewModel()) {
    val st by vm.state.collectAsState()
    LaunchedEffect(Unit) { vm.load() }
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<SkillDto?>(null) }

    Scaffold(
        containerColor = DuqColors.background,
        topBar = { AutoTopBar("Скиллы", onBack) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editing = null; creating = true },
                containerColor = DuqColors.primary, contentColor = DuqColors.background,
                icon = { Icon(Icons.Outlined.Add, null) }, text = { Text("Скилл") }
            )
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when {
                st.loading && st.skills.isEmpty() -> Loading()
                st.skills.isEmpty() -> EmptyState(Icons.Outlined.AutoAwesome,
                    "Скиллов нет", "Скилл — это md-промпт, который агент выполняет своими тулами.")
                else -> LazyColumn(
                    Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    st.error?.let { item { ErrorRow(it) } }
                    items(st.skills, key = { it.name }) { s ->
                        SkillItem(s, onTap = { editing = s }, onDelete = { vm.deleteSkill(s.name) })
                    }
                }
            }
        }
    }
    if (creating || editing != null) SkillSheet(
        initial = editing,
        onDismiss = { creating = false; editing = null },
        onSave = { name, desc, content ->
            if (editing == null) vm.createSkill(name, content, desc)
            else vm.editSkill(editing!!.name, content, desc)
            creating = false; editing = null
        }
    )
}

@Composable
private fun SkillItem(s: SkillDto, onTap: () -> Unit, onDelete: () -> Unit) {
    ElevatedCard(
        Modifier.fillMaxWidth().clickable(onClick = onTap),
        colors = CardDefaults.elevatedCardColors(containerColor = DuqColors.surfaceVariant)
    ) {
        ListItem(
            headlineContent = { Text(s.name, color = DuqColors.textPrimary, fontWeight = FontWeight.Medium) },
            supportingContent = {
                Text(s.description?.takeIf { it.isNotBlank() } ?: s.content.take(80),
                    color = DuqColors.textSecondary, fontSize = 13.sp, maxLines = 2)
            },
            trailingContent = {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, "Удалить", tint = DuqColors.accent)
                }
            },
            colors = ListItemDefaults.colors(containerColor = DuqColors.surfaceVariant)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SkillSheet(initial: SkillDto?, onDismiss: () -> Unit, onSave: (String, String, String) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val editMode = initial != null
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var desc by remember { mutableStateOf(initial?.description ?: "") }
    var content by remember { mutableStateOf(initial?.content ?: "") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = DuqColors.surfaceElevated) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(if (editMode) "Скилл: ${initial!!.name}" else "Новый скилл",
                color = DuqColors.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            if (!editMode) AutoField(name, { name = it }, "Имя (kebab-case)")
            AutoField(desc, { desc = it }, "Описание (одна строка)")
            AutoField(content, { content = it }, "md-промпт: что сделать", minLines = 5)
            Button(
                onClick = { if (name.isNotBlank() && content.isNotBlank()) onSave(name, desc, content) },
                enabled = name.isNotBlank() && content.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = DuqColors.primary, contentColor = DuqColors.background),
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (editMode) "Сохранить" else "Создать скилл") }
        }
    }
}

/* ════════════════════ ЭКРАН «РАСПИСАНИЕ» (крон) ════════════════════ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(onBack: () -> Unit, vm: AutomationViewModel = hiltViewModel()) {
    val st by vm.state.collectAsState()
    LaunchedEffect(Unit) { vm.load() }
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CronTaskDto?>(null) }

    Scaffold(
        containerColor = DuqColors.background,
        topBar = { AutoTopBar("Задачи", onBack) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editing = null; creating = true },
                containerColor = DuqColors.primary, contentColor = DuqColors.background,
                icon = { Icon(Icons.Outlined.Add, null) }, text = { Text("Задача") }
            )
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when {
                st.loading && st.tasks.isEmpty() -> Loading()
                st.tasks.isEmpty() -> EmptyState(Icons.Outlined.Schedule,
                    "Задач нет", "Задача запускает скилл по расписанию (cron).")
                else -> LazyColumn(
                    Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    st.error?.let { item { ErrorRow(it) } }
                    items(st.tasks, key = { it.task_id }) { t ->
                        CronItem(t, onTap = { editing = t },
                            onToggle = { vm.toggleTask(t) }, onDelete = { vm.deleteTask(t.task_id) })
                    }
                }
            }
        }
    }
    if (creating || editing != null) CronSheet(
        initial = editing,
        skills = st.skills.map { it.name },
        agents = st.agents,
        onDismiss = { creating = false; editing = null },
        onSave = { n, cron, skill, agentId ->
            if (editing == null) vm.createTask(n, cron, skill, agentId)
            else vm.editTask(editing!!.task_id, n, cron, skill, agentId)
            creating = false; editing = null
        }
    )
}

@Composable
private fun CronItem(t: CronTaskDto, onTap: () -> Unit, onToggle: () -> Unit, onDelete: () -> Unit) {
    ElevatedCard(
        Modifier.fillMaxWidth().clickable(onClick = onTap),
        colors = CardDefaults.elevatedCardColors(containerColor = DuqColors.surfaceVariant)
    ) {
        ListItem(
            headlineContent = {
                Text(t.name ?: t.skill ?: "—", color = DuqColors.textPrimary, fontWeight = FontWeight.Medium)
            },
            supportingContent = {
                Column {
                    Text("${cronHuman(t.cron)}  ·  ${t.skill ?: "—"}", color = DuqColors.textSecondary, fontSize = 13.sp)
                    t.next_run?.let { Text("след.: ${prettyNext(it)}", color = DuqColors.textDim, fontSize = 11.sp) }
                }
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = t.enabled, onCheckedChange = { onToggle() })
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Outlined.Delete, "Удалить", tint = DuqColors.accent)
                    }
                }
            },
            colors = ListItemDefaults.colors(containerColor = DuqColors.surfaceVariant)
        )
    }
}

private val WEEKDAYS = listOf("Пн" to 1, "Вт" to 2, "Ср" to 3, "Чт" to 4, "Пт" to 5, "Сб" to 6, "Вс" to 0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CronSheet(
    initial: CronTaskDto?, skills: List<String>,
    agents: List<com.duq.android.network.duq.AgentInfo>,
    onDismiss: () -> Unit, onSave: (String, String, String, String) -> Unit
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val editMode = initial != null
    val parsed = remember(initial?.cron) { parseCron(initial?.cron) }  // (hour, minute, daysSet)
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var skill by remember { mutableStateOf(initial?.skill ?: skills.firstOrNull() ?: "") }
    var menu by remember { mutableStateOf(false) }
    // Агент-исполнитель крон-джобы (дефолт main). Выбор — dropdown ниже.
    var agentId by remember { mutableStateOf(initial?.agent_id ?: "main") }
    var agentMenu by remember { mutableStateOf(false) }
    val time = rememberTimePickerState(initialHour = parsed.first, initialMinute = parsed.second, is24Hour = true)
    val days = remember { mutableStateListOf<Int>().apply { addAll(parsed.third) } }

    val cron = buildCron(time.hour, time.minute, days.toSet())

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = DuqColors.surfaceElevated) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(if (editMode) "Редактировать задачу" else "Новая задача",
                color = DuqColors.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.Start))
            AutoField(name, { name = it }, "Название задачи")

            Text("Во сколько запускать", color = DuqColors.textSecondary, fontSize = 13.sp,
                modifier = Modifier.align(Alignment.Start))
            TimeInput(state = time, colors = TimePickerDefaults.colors(
                timeSelectorSelectedContainerColor = DuqColors.primary,
                timeSelectorSelectedContentColor = DuqColors.background,
                timeSelectorUnselectedContainerColor = DuqColors.surfaceVariant,
                timeSelectorUnselectedContentColor = DuqColors.textPrimary
            ))

            Text("В какие дни (ничего = каждый день)", color = DuqColors.textSecondary, fontSize = 13.sp,
                modifier = Modifier.align(Alignment.Start))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                WEEKDAYS.forEach { (label, num) ->
                    val on = num in days
                    FilterChip(
                        selected = on,
                        onClick = { if (on) days.remove(num) else days.add(num) },
                        label = { Text(label, fontSize = 12.sp) },
                        modifier = Modifier.weight(1f),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = DuqColors.primary,
                            selectedLabelColor = DuqColors.background
                        )
                    )
                }
            }
            Text("🕒 " + cronHuman(cron), color = DuqColors.textDim, fontSize = 12.sp,
                modifier = Modifier.align(Alignment.Start))

            ExposedDropdownMenuBox(expanded = menu, onExpandedChange = { menu = it }) {
                OutlinedTextField(
                    value = skill, onValueChange = {}, readOnly = true,
                    label = { Text("Скилл") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menu) },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                    colors = autoFieldColors()
                )
                ExposedDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (skills.isEmpty())
                        DropdownMenuItem(text = { Text("сначала создай скилл") }, onClick = { menu = false })
                    skills.forEach { s ->
                        DropdownMenuItem(text = { Text(s) }, onClick = { skill = s; menu = false })
                    }
                }
            }

            // Выбор агента-исполнителя (его тулсет/память). Дефолт — main.
            if (agents.isNotEmpty()) {
                val agentLabel = agents.firstOrNull { it.id == agentId }?.displayName ?: agentId
                ExposedDropdownMenuBox(expanded = agentMenu, onExpandedChange = { agentMenu = it }) {
                    OutlinedTextField(
                        value = agentLabel, onValueChange = {}, readOnly = true,
                        label = { Text("Агент-исполнитель") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = agentMenu) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        colors = autoFieldColors()
                    )
                    ExposedDropdownMenu(expanded = agentMenu, onDismissRequest = { agentMenu = false }) {
                        agents.forEach { a ->
                            DropdownMenuItem(
                                text = { Text(a.displayName) },
                                onClick = { agentId = a.id; agentMenu = false }
                            )
                        }
                    }
                }
            }
            Button(
                onClick = { if (name.isNotBlank() && skill.isNotBlank()) onSave(name, cron, skill, agentId) },
                enabled = name.isNotBlank() && skill.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = DuqColors.primary, contentColor = DuqColors.background),
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (editMode) "Сохранить" else "Создать задачу") }
        }
    }
}

/** cron "M H * * D[,D]" → (hour, minute, daysSet). Дефолт 9:00 каждый день. */
private fun parseCron(cron: String?): Triple<Int, Int, Set<Int>> {
    val p = cron?.trim()?.split(Regex("\\s+")) ?: emptyList()
    if (p.size == 5) {
        val mi = p[0].toIntOrNull(); val hh = p[1].toIntOrNull()
        if (mi != null && hh != null) {
            val days = if (p[4] == "*") emptySet()
            else p[4].split(",").mapNotNull { it.toIntOrNull() }.toSet()
            return Triple(hh, mi, days)
        }
    }
    return Triple(9, 0, emptySet())
}

/** (hour, minute, days) → cron "M H * * D". Пусто дней → каждый день (*). */
private fun buildCron(hour: Int, minute: Int, days: Set<Int>): String {
    val dow = if (days.isEmpty()) "*" else days.sorted().joinToString(",")
    return "$minute $hour * * $dow"
}

/* ════════════════════ общие компоненты ════════════════════ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutoTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title, color = DuqColors.textPrimary) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.Outlined.ArrowBackIosNew, "Назад", tint = DuqColors.textPrimary, modifier = Modifier.size(20.dp))
            }
        },
        actions = { GlobalTopActions() },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = DuqColors.background)
    )
}

@Composable
private fun Loading() = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    CircularProgressIndicator(color = DuqColors.primary)
}

@Composable
private fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, hint: String) =
    Column(
        Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, null, tint = DuqColors.textDim, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, color = DuqColors.textSecondary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        Text(hint, color = DuqColors.textDim, fontSize = 13.sp,
            modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }

@Composable
private fun ErrorRow(msg: String) =
    Text("⚠️ $msg", color = DuqColors.accent, fontSize = 13.sp, modifier = Modifier.padding(8.dp))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun autoFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = DuqColors.textPrimary, unfocusedTextColor = DuqColors.textPrimary,
    focusedBorderColor = DuqColors.primary, unfocusedBorderColor = DuqColors.textDim,
    focusedLabelColor = DuqColors.primary, unfocusedLabelColor = DuqColors.textDim,
    cursorColor = DuqColors.primary
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AutoField(value: String, onChange: (String) -> Unit, label: String, minLines: Int = 1) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(label) }, minLines = minLines,
        modifier = Modifier.fillMaxWidth(), colors = autoFieldColors()
    )
}

/** "0 9 * * *" → "каждый день в 09:00"; иначе сырой cron. */
private val DOW_RU = mapOf(0 to "Вс", 1 to "Пн", 2 to "Вт", 3 to "Ср", 4 to "Чт", 5 to "Пт", 6 to "Сб", 7 to "Вс")

private fun cronHuman(cron: String?): String {
    if (cron.isNullOrBlank()) return "—"
    val p = cron.trim().split(Regex("\\s+"))
    if (p.size == 5) {
        val (m, h, dom, mon, dow) = p
        val mi = m.toIntOrNull(); val hh = h.toIntOrNull()
        if (mi != null && hh != null && dom == "*" && mon == "*") {
            val time = "%02d:%02d".format(hh, mi)
            if (dow == "*") return "каждый день в $time"
            val names = dow.split(",").mapNotNull { it.toIntOrNull()?.let(DOW_RU::get) }
            return if (names.isNotEmpty()) "${names.joinToString(", ")} в $time" else cron
        }
    }
    return cron
}

/** "2026-06-23T01:00:00" → "23.06 01:00". */
private fun prettyNext(iso: String): String = runCatching {
    val d = iso.substringBefore("T"); val t = iso.substringAfter("T").take(5)
    val (y, mo, da) = d.split("-")
    "$da.$mo $t"
}.getOrDefault(iso)
