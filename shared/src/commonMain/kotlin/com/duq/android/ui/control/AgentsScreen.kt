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
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.ui.state.ToggleableState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.duq.android.network.duq.AgentInfo
import com.duq.android.network.duq.ToolCategory
import com.duq.android.ui.theme.DuqColors
import org.koin.compose.viewmodel.koinViewModel

/**
 * Панель агентов (Пульт). Список агентов + создание (id/имя/описание/тулсет) +
 * удаление. Агент = тот же DUQ, свой тулсет + своя память/сессии. main — системный
 * (не удаляется/не редактируется здесь).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentsScreen(onBack: () -> Unit, vm: AgentsViewModel = koinViewModel()) {
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
        toolCategories = st.toolCategories,
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Карандаш — явный сигнал, что по тапу карточка редактируется.
                        IconButton(onClick = onEdit) {
                            Icon(Icons.Outlined.Edit, "Редактировать", tint = DuqColors.textSecondary)
                        }
                        IconButton(onClick = onDelete) {
                            Icon(Icons.Outlined.Delete, "Удалить", tint = DuqColors.accent)
                        }
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
    toolCategories: List<ToolCategory>,
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
                CategorizedTools(toolCategories, picked)
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

/**
 * Тулсет по категориям: секции свёрнуты по умолчанию (тулов много — плоский список
 * нечитаем). У каждой категории — чекбокс вкл/выкл ВСЕЙ категории (трёхпозиционный:
 * все/часть/никого) + разворот в конкретные тулы (чипы). Picked — плоский список имён
 * (контракт onSave не меняется: ядру уходит список тулов, не категорий).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategorizedTools(
    categories: List<ToolCategory>,
    picked: androidx.compose.runtime.snapshots.SnapshotStateList<String>
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        categories.forEach { cat ->
            var expanded by remember(cat.name) { mutableStateOf(false) }
            val selectedInCat = cat.tools.count { it in picked }
            val allOn = selectedInCat == cat.tools.size && cat.tools.isNotEmpty()
            val someOn = selectedInCat > 0 && !allOn

            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TriStateCheckbox(
                    state = when {
                        allOn -> ToggleableState.On
                        someOn -> ToggleableState.Indeterminate
                        else -> ToggleableState.Off
                    },
                    onClick = {
                        // вкл всю категорию если не все выбраны, иначе выкл всю
                        if (allOn) cat.tools.forEach { picked.remove(it) }
                        else cat.tools.forEach { if (it !in picked) picked.add(it) }
                    },
                    colors = CheckboxDefaults.colors(checkedColor = DuqColors.primary)
                )
                Text(
                    "${cat.name}  ($selectedInCat/${cat.tools.size})",
                    color = DuqColors.textPrimary, fontSize = 13.sp,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "Свернуть" else "Развернуть",
                    tint = DuqColors.textSecondary
                )
            }
            if (expanded) {
                FlowRow(
                    Modifier.fillMaxWidth().padding(start = 12.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    cat.tools.forEach { t ->
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
        }
    }
}
