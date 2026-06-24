package com.duq.android.ui.control

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.duq.android.ui.HUB_SECTIONS
import com.duq.android.ui.theme.DuqColors

/** Команда палитры: ярлык + куда вести (route внутреннего tab-nav или "settings"). */
data class PaletteCommand(val icon: androidx.compose.ui.graphics.vector.ImageVector, val label: String, val route: String)

private val STATIC_COMMANDS = listOf(
    PaletteCommand(Icons.Outlined.ChatBubbleOutline, "Чат", "tab_chat"),
    PaletteCommand(Icons.Outlined.GridView, "Пульт", "tab_hub"),
    PaletteCommand(Icons.Outlined.Settings, "Настройки", "settings"),
)

/** Все команды: вкладки + настройки + каждый раздел Пульта (переход к нему). */
private val ALL_COMMANDS: List<PaletteCommand> = STATIC_COMMANDS +
    HUB_SECTIONS.map { PaletteCommand(it.icon, it.title, "section/${it.key}") }

/**
 * Command palette (spec §3) — быстрый поиск-переход к любому разделу/вкладке.
 * Power-user ускоритель для одного юзера. [onNavigate] получает route (tab_*,
 * section/{key} или "settings"); вызывающий решает как навигировать.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandPalette(onNavigate: (String) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query) {
        if (query.isBlank()) ALL_COMMANDS
        else ALL_COMMANDS.filter { it.label.contains(query, ignoreCase = true) }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(DuqColors.surfaceElevated)
                .padding(12.dp)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Перейти к…", color = DuqColors.textMuted) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = DuqColors.textPrimary,
                    unfocusedTextColor = DuqColors.textPrimary,
                    focusedBorderColor = DuqColors.primary,
                    unfocusedBorderColor = DuqColors.glassBorder
                )
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(filtered) { cmd ->
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onNavigate(cmd.route); onDismiss() }
                            .padding(horizontal = 10.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(cmd.icon, contentDescription = cmd.label, tint = DuqColors.textSecondary,
                            modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(cmd.label, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = DuqColors.textPrimary)
                    }
                }
            }
        }
    }
}
