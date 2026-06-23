package com.duq.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.duq.android.ui.theme.DuqColors

/**
 * Пульт — hub-and-spoke вход в разделы конфигурации ядра DUQ.
 * Фаза 1: каркас-плитки с навигацией. Живые метрики и detail-экраны разделов
 * приходят в следующих фазах (см. spec §6). Плитка «Голос» уже ведёт в текущий
 * SettingsScreen (миграция настроек туда — позже).
 */
data class HubSection(val key: String, val icon: ImageVector, val title: String)

/** Разделы пульта — пока только «Движок» (обновление ядра
 *  через /core-update). Прежние gateway-RPC разделы убраны — у ядра DUQ нет RPC. */
val HUB_SECTIONS = listOf(
    HubSection("skills", Icons.Outlined.AutoAwesome, "Скиллы"),
    HubSection("schedule", Icons.Outlined.Schedule, "Расписание"),
    HubSection("engine", Icons.Outlined.Tune, "Движок"),
)

@Composable
fun HubScreen(onOpenSection: (String) -> Unit, onOpenPalette: () -> Unit = {}) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 16.dp, start = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "ПУЛЬТ",
                fontSize = 13.sp,
                fontWeight = FontWeight.Light,
                color = DuqColors.textDim,
                letterSpacing = 3.sp
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Search, contentDescription = "Поиск", tint = DuqColors.textSecondary,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onOpenPalette).padding(8.dp).size(22.dp))
                com.duq.android.ui.control.GlobalTopActions()
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(HUB_SECTIONS, key = { it.key }) { section ->
                HubTile(section, onClick = { onOpenSection(section.key) })
            }
        }
    }
}

@Composable
private fun HubTile(section: HubSection, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(DuqColors.surfaceVariant)
            .border(1.dp, DuqColors.glassBorder, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Icon(section.icon, contentDescription = section.title,
            tint = DuqColors.textSecondary, modifier = Modifier.size(26.dp))
        Text(
            text = section.title,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = DuqColors.textPrimary
        )
    }
}
