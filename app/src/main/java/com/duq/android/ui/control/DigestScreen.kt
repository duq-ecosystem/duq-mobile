package com.duq.android.ui.control

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.ArrowBackIosNew
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.duq.android.data.DigestInbox
import com.duq.android.ui.theme.DuqColors

/**
 * 📰 Дайджест — полноэкранный раздел (не bottom-sheet). Лента выпусков: карточка =
 * заголовок + время + полный текст (тап раскрывает, текст выделяется). Источник —
 * локальный [DigestInbox] на устройстве. Открывается из Пульта, из 📰-кнопки чата
 * и по тапу пуша (deep-link open_section=digest).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DigestScreen(onBack: () -> Unit, vm: DigestViewModel = hiltViewModel()) {
    val items by vm.items.collectAsState()
    LaunchedEffect(Unit) { vm.refresh() }
    var expandedId by remember { mutableStateOf<Long?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = DuqColors.background,
        topBar = {
            TopAppBar(
                title = { Text("📰 Дайджест", color = DuqColors.textPrimary, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBackIosNew, "Назад", tint = DuqColors.textPrimary,
                            modifier = Modifier.size(20.dp))
                    }
                },
                actions = {
                    if (items.isNotEmpty()) {
                        IconButton(onClick = { confirmClear = true }) {
                            Icon(Icons.Outlined.DeleteSweep, "Очистить", tint = DuqColors.accent)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DuqColors.background,
                    titleContentColor = DuqColors.textPrimary
                )
            )
        }
    ) { pad ->
        if (items.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(pad).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.AutoMirrored.Outlined.Article, null, tint = DuqColors.textDim,
                    modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(12.dp))
                Text("Пока нет выпусков", color = DuqColors.textSecondary, fontSize = 15.sp,
                    fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text("Дайджесты приходят по расписанию и появляются здесь.",
                    color = DuqColors.textDim, fontSize = 13.sp, textAlign = TextAlign.Center)
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(pad),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(items, key = { it.id }) { item ->
                    DigestCard(item, expandedId == item.id) {
                        expandedId = if (expandedId == item.id) null else item.id
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = DuqColors.surfaceElevated,
            title = { Text("Очистить дайджесты?", color = DuqColors.textPrimary) },
            text = { Text("Все выпуски будут удалены с устройства.", color = DuqColors.textSecondary) },
            confirmButton = {
                TextButton(onClick = { vm.clear(); confirmClear = false }) {
                    Text("Очистить", color = DuqColors.accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text("Отмена", color = DuqColors.textSecondary)
                }
            }
        )
    }
}

@Composable
private fun DigestCard(item: DigestInbox.Item, expanded: Boolean, onTap: () -> Unit) {
    ElevatedCard(
        Modifier.fillMaxWidth().clickable(onClick = onTap),
        colors = CardDefaults.elevatedCardColors(containerColor = DuqColors.surfaceVariant)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(item.title, color = DuqColors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(fmtTime(item.timestampMs), color = DuqColors.textDim, fontSize = 11.sp,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
            if (item.text.isNotBlank()) {
                if (expanded) {
                    // Полный текст рендерится целиком; прокручивает родительский LazyColumn
                    // (свой verticalScroll внутри item = бесконечная высота → краш).
                    SelectionContainer {
                        Text(item.text, color = DuqColors.textSecondary, fontSize = 14.sp, lineHeight = 21.sp)
                    }
                } else {
                    Text(item.text, color = DuqColors.textSecondary, fontSize = 14.sp, lineHeight = 21.sp, maxLines = 4)
                    Text("Читать полностью ▾", color = DuqColors.primary, fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}

private fun fmtTime(ms: Long): String =
    java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ms))
