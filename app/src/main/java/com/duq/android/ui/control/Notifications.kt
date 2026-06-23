package com.duq.android.ui.control

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.duq.android.data.NotificationInbox
import com.duq.android.ui.theme.DuqColors
import com.duq.android.update.AppUpdater
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Глобальный chrome: шторка уведомлений (на всех экранах) + вход в Настройки. */
object AppChrome {
    var showNotifications by mutableStateOf(false)
    var notificationsTab by mutableStateOf(0) // 0 = Уведомления, 1 = Дайджесты
    var openSettings: () -> Unit = {}

    fun openShade(tab: Int = 0) { notificationsTab = tab; showNotifications = true }
}

@HiltViewModel
class NotificationsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val inbox: NotificationInbox
) : ViewModel() {
    val items = inbox.items
    val unread = inbox.unread
    fun refresh() = inbox.refresh()
    fun markOpened() = inbox.markOpened()
    fun clear() = inbox.clear()

    @Volatile private var installing = false
    fun installUpdate() {
        if (installing) return
        installing = true
        viewModelScope.launch(Dispatchers.IO) {
            try { AppUpdater(context).downloadAndInstall() } finally { installing = false }
        }
    }
}

/** Правый блок верхней панели — общий для ВСЕХ экранов: 🔔(бейдж) + ⚙️. */
@Composable
fun GlobalTopActions(vm: NotificationsViewModel = hiltViewModel()) {
    val unread by vm.unread.collectAsState()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier.size(44.dp).clip(CircleShape)
                .clickable { vm.refresh(); AppChrome.openShade(0) },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Outlined.NotificationsNone, "Уведомления",
                tint = DuqColors.textSecondary, modifier = Modifier.size(23.dp))
            if (unread > 0) {
                Box(
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(16.dp)
                        .clip(CircleShape).background(DuqColors.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Text(if (unread > 9) "9+" else unread.toString(),
                        fontSize = 9.sp, color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        }
        Box(
            modifier = Modifier.size(44.dp).clip(CircleShape)
                .clickable { AppChrome.openSettings() },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Outlined.Settings, "Настройки",
                tint = DuqColors.textSecondary, modifier = Modifier.size(22.dp))
        }
    }
}

/**
 * Центр уведомлений — единая шторка на всех экранах. Два раздела: «Уведомления» и
 * «Дайджесты», переключение тапом по табу И свайпом (HorizontalPager). Дайджесты
 * живут здесь же (отдельного экрана нет) — раскрываются на полный текст по тапу.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsShade(vm: NotificationsViewModel = hiltViewModel()) {
    if (!AppChrome.showNotifications) return
    val all by vm.items.collectAsState()

    val notifs = all.filter { it.type != "digest" }
    val digests = all.filter { it.type == "digest" }
    val pager = rememberPagerState(initialPage = AppChrome.notificationsTab, pageCount = { 2 })
    val scope = rememberCoroutineScope()
    val tabs = listOf("Уведомления", "Дайджесты")

    LaunchedEffect(Unit) { vm.refresh(); vm.markOpened() }
    // Открыли по пушу дайджеста (или сменили запрошенный таб) → форсим нужный таб.
    // rememberPagerState(initialPage) не реагирует на смену значения после создания,
    // поэтому двигаем пейджер явно — и при первом открытии, и если шторка уже открыта.
    LaunchedEffect(AppChrome.notificationsTab) { pager.scrollToPage(AppChrome.notificationsTab) }

    ModalBottomSheet(
        onDismissRequest = { AppChrome.showNotifications = false },
        containerColor = DuqColors.surface
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            TabRow(
                selectedTabIndex = pager.currentPage,
                containerColor = DuqColors.surface,
                contentColor = DuqColors.primary
            ) {
                tabs.forEachIndexed { i, title ->
                    Tab(
                        selected = pager.currentPage == i,
                        onClick = { scope.launch { pager.animateScrollToPage(i) } },
                        text = {
                            Text(title, fontSize = 14.sp,
                                fontWeight = if (pager.currentPage == i) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (pager.currentPage == i) DuqColors.textPrimary else DuqColors.textSecondary)
                        }
                    )
                }
            }
            HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth()) { page ->
                if (page == 0) NotifList(notifs, vm) else DigestList(digests)
            }
        }
    }
}

@Composable
private fun NotifList(items: List<NotificationInbox.Item>, vm: NotificationsViewModel) {
    if (items.isEmpty()) { EmptyShade("Пока нет уведомлений"); return }
    var expandedId by remember { mutableStateOf<Long?>(null) }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp).padding(horizontal = 16.dp)) {
        items(items, key = { it.id }) { item ->
            val icon = when (item.type) { "update" -> "⬆"; "message" -> "💬"; else -> "🔔" }
            val expanded = expandedId == item.id
            Column(
                Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(10.dp))
                    .clickable {
                        if (item.type == "update") {
                            // Апдейт приложения → ведём в раздел «Версия» (там и установка).
                            com.duq.android.ui.DeepLinkState.pendingSection = "version"
                            AppChrome.showNotifications = false
                        } else expandedId = if (expanded) null else item.id
                    }.padding(8.dp)
            ) {
                Text("$icon  ${item.title}", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = DuqColors.textPrimary)
                if (item.text.isNotBlank()) {
                    Text(item.text, fontSize = 13.sp, color = DuqColors.textSecondary,
                        maxLines = if (expanded) Int.MAX_VALUE else 3, modifier = Modifier.padding(top = 2.dp))
                }
                Text(fmtNotifTime(item.timestampMs), fontSize = 11.sp, color = DuqColors.textDim, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

@Composable
private fun DigestList(items: List<NotificationInbox.Item>) {
    if (items.isEmpty()) { EmptyShade("Пока нет выпусков"); return }
    var expandedId by remember { mutableStateOf<Long?>(null) }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp).padding(horizontal = 16.dp)) {
        items(items, key = { it.id }) { item ->
            val expanded = expandedId == item.id
            Column(
                Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(10.dp))
                    .background(DuqColors.surfaceVariant)
                    .clickable { expandedId = if (expanded) null else item.id }.padding(12.dp)
            ) {
                Text("📰  ${item.title}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = DuqColors.textPrimary)
                Text(fmtNotifTime(item.timestampMs), fontSize = 11.sp, color = DuqColors.textDim,
                    modifier = Modifier.padding(top = 2.dp, bottom = 6.dp))
                if (item.text.isNotBlank()) {
                    if (expanded) {
                        SelectionContainer {
                            Text(item.text, fontSize = 14.sp, color = DuqColors.textSecondary, lineHeight = 21.sp)
                        }
                    } else {
                        Text(item.text, fontSize = 14.sp, color = DuqColors.textSecondary, lineHeight = 21.sp, maxLines = 4)
                        Text("Читать полностью ▾", fontSize = 12.sp, color = DuqColors.primary, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyShade(text: String) {
    Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 14.sp, color = DuqColors.textSecondary)
    }
}

private fun fmtNotifTime(ms: Long): String =
    java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ms))
