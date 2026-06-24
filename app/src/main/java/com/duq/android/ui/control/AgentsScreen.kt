package com.duq.android.ui.control

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.duq.android.network.duq.AgentInfo
import com.duq.android.ui.theme.DuqColors

/**
 * Панель агентов (Пульт). Список агентов + создание (id/имя/описание/тулсет) +
 * удаление. Агент = тот же DUQ, свой тулсет + своя память/сессии. main — системный
 * (не удаляется/не редактируется здесь).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentsScreen(onBack: () -> Unit, vm: AgentsViewModel = hiltViewModel()) {
    val st by vm.state.collectAsState()
    LaunchedEffect(Unit) { vm.load() }
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<AgentInfo?>(null) }

    Scaffold(
        containerColor = DuqColors.background,
        topBar = {
            TopAppBar(
                title = { Text("Агенты", color = DuqColors.textPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, "Назад", tint = DuqColors.textPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DuqColors.background)
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { creating = true },
                containerColor = DuqColors.primary, contentColor = DuqColors.background
            ) { Icon(Icons.Outlined.Add, "Создать агента") }
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            st.error?.let {
                Text(it, color = DuqColors.accent, fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            Text(
                "Агент = тот же DUQ со своим набором тулов и отдельной памятью/историей.",
                color = DuqColors.textDim, fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(st.agents, key = { it.id }) { a ->
                    AgentItem(
                        a,
                        onEdit = { if (!a.isSystem) editing = a },
                        onDelete = { vm.deleteAgent(a.id) },
                    )
                }
            }
        }
    }

    if (creating || editing != null) AgentSheet(
        initial = editing,
        tools = st.tools,
        onDismiss = { creating = false; editing = null },
        onSave = { id, name, desc, tools ->
            vm.createAgent(id, name, desc, tools)   // POST = upsert (создание И редактирование)
            creating = false; editing = null
        }
    )
}

@Composable
private fun AgentItem(a: AgentInfo, onEdit: () -> Unit, onDelete: () -> Unit) {
    ElevatedCard(
        Modifier.fillMaxWidth().clickable(onClick = onEdit),
        colors = CardDefaults.elevatedCardColors(containerColor = DuqColors.surfaceVariant)
    ) {
        ListItem(
            headlineContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(a.displayName, color = DuqColors.textPrimary, fontWeight = FontWeight.Medium)
                    if (a.isSystem) {
                        Spacer(Modifier.width(6.dp))
                        Text("системный", color = DuqColors.textDim, fontSize = 11.sp)
                    }
                }
            },
            supportingContent = {
                Column {
                    Text("id: ${a.id}", color = DuqColors.textSecondary, fontSize = 12.sp)
                    val toolsLabel = a.allowedTools?.let { "тулы: ${it.joinToString(", ")}" }
                        ?: "тулы: все (по правам)"
                    Text(toolsLabel, color = DuqColors.textDim, fontSize = 11.sp)
                }
            },
            trailingContent = {
                if (!a.isSystem) {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Outlined.Delete, "Удалить", tint = DuqColors.accent)
                    }
                }
            },
            colors = ListItemDefaults.colors(containerColor = DuqColors.surfaceVariant)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentSheet(
    initial: AgentInfo? = null,
    tools: List<String>,
    onDismiss: () -> Unit,
    onSave: (String, String, String, List<String>) -> Unit
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val editMode = initial != null
    // key = initial?.id: при смене редактируемого агента (или create↔edit) форма
    // пересоздаёт state, иначе показывала бы значения от прошлого открытия.
    val key = initial?.id
    var id by remember(key) { mutableStateOf(initial?.id ?: "") }
    var name by remember(key) { mutableStateOf(initial?.displayName ?: "") }
    var desc by remember(key) { mutableStateOf(initial?.description ?: "") }
    // Предзаполняем тулсет агента при редактировании (галочки на его тулах).
    val picked = remember(key) { mutableStateListOf<String>().apply { initial?.allowedTools?.let { addAll(it) } } }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = DuqColors.surfaceElevated) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            // Прокручиваемая часть (поля + длинный список тулов). Кнопка вынесена ИЗ
            // скролла и зафиксирована снизу — иначе при большом тулсете она уезжала за
            // экран и до неё было не докрутить.
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    if (editMode) "Редактировать агента" else "Новый агент",
                    color = DuqColors.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold
                )
                // id — ключ агента, при редактировании не меняется (показываем как текст).
                if (editMode) {
                    Text("id: ${initial!!.id}", color = DuqColors.textSecondary, fontSize = 13.sp)
                } else {
                    AutoField(id, { id = it.lowercase().filter { c -> c.isLetterOrDigit() || c == '-' } }, "id (латиница, напр. recruiter)")
                }
                AutoField(name, { name = it }, "Имя агента")
                AutoField(desc, { desc = it }, "Описание (зачем агент)")

                Text(
                    if (picked.isEmpty()) "Тулсет: все тулы (по правам). Выбери, чтобы ОГРАНИЧИТЬ:"
                    else "Тулсет агента (${picked.size} выбрано):",
                    color = DuqColors.textSecondary, fontSize = 13.sp
                )
                FlowRowTools(tools, picked)
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { if (id.isNotBlank() && name.isNotBlank()) onSave(id, name, desc, picked.toList()) },
                enabled = id.isNotBlank() && name.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = DuqColors.primary, contentColor = DuqColors.background),
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (editMode) "Сохранить" else "Создать агента") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowTools(tools: List<String>, picked: androidx.compose.runtime.snapshots.SnapshotStateList<String>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        tools.forEach { t ->
            val on = t in picked
            FilterChip(
                selected = on,
                onClick = { if (on) picked.remove(t) else picked.add(t) },
                label = { Text(t, fontSize = 12.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = DuqColors.primary,
                    selectedLabelColor = DuqColors.background
                )
            )
        }
    }
}
