package com.duq.android.ui

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.duq.android.audio.ChatAudioPlaybackManager
import com.duq.android.data.SettingsRepository
import com.duq.android.ui.theme.DuqColors
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

sealed class Screen(val route: String) {
    object Shell : Screen("shell")       // bottom-nav оболочка (Чат/Пульт)
    object Settings : Screen("settings")
}

/** Вкладки нижней навигации. «Лента» убрана (была gateway-RPC). */
private data class Tab(val route: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val label: String)
private val TABS = listOf(
    Tab("tab_hub", Icons.Outlined.GridView, "Пульт"),
    Tab("tab_chat", Icons.Outlined.ChatBubbleOutline, "Чат"),
)
// Стартуем с чата (основной экран), несмотря на порядок вкладок в баре.
private const val START_TAB = "tab_chat"

/**
 * Deep-link из уведомления — ОДНОРАЗОВЫЕ события навигации (не state). Тап по пушу
 * эмитит в Channel, MainShell собирает и навигирует. Channel (а не nullable
 * mutableStateOf): переживает cold-start — событие, отправленное из onCreate ДО входа
 * MainShell в композицию, буферизуется и доставляется коллектору при подписке; не теряет
 * повторные тапы и не дедупит по значению (старый паттерн со state терял второй тап тем же
 * значением до сброса в null). MainActivity/шторка эмитят, MainShell потребляет.
 */
object DeepLinkState {
    // Раздел Пульта (напр. "version" по пушу обновления ядра/приложения).
    val sectionEvents = Channel<String>(Channel.UNLIMITED)
    // Вкладка нижней навигации (напр. "tab_chat" по обычному message-пушу и сервисному
    // «Connected») — иначе warm-тап оставил бы юзера на прошлой панели, а сообщение в чате.
    val tabEvents = Channel<String>(Channel.UNLIMITED)
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface DuqAppEntryPoint {
    fun chatAudioPlaybackManager(): ChatAudioPlaybackManager
    fun settingsRepository(): SettingsRepository
}

@Composable
fun DuqApp() {
    val navController = rememberNavController()
    val context = LocalContext.current

    val entryPoint = remember { EntryPointAccessors.fromApplication(context, DuqAppEntryPoint::class.java) }
    val audioPlaybackManager = remember { entryPoint.chatAudioPlaybackManager() }

    // The playback manager is an app-scoped @Singleton; its ExoPlayer lives for the
    // process. Do NOT release() it on composable disposal — release() flips an
    // irreversible isReleased flag, so any navigation/recomposition that re-enters
    // DuqApp would leave audio permanently dead. The process teardown frees ExoPlayer.
    LaunchedEffect(Unit) { audioPlaybackManager.initialize() }

    // Глобальная ⚙️ (из верхней панели любого экрана) ведёт в Настройки. SideEffect, а не
    // LaunchedEffect(Unit): пере-публикует лямбду на КАЖДОЙ рекомпозиции, поэтому после
    // конфиг-изменения (ротация/тема), когда rememberNavController даёт новый инстанс,
    // в AppChrome.openSettings не остаётся ссылка на старый (detached) navController.
    SideEffect {
        com.duq.android.ui.control.AppChrome.openSettings = { navController.navigate(Screen.Settings.route) }
    }

    // Ядро DUQ авторизуется build-time edge-токеном (BuildConfig.SERVER_TOKEN) —
    // устройство НЕ пейрится (Ed25519/bootstrap-пейринг удалены).
    // Всегда стартуем с оболочки (чат).
    val startDestination = Screen.Shell.route

    NavHost(navController = navController, startDestination = startDestination) {
        composable(Screen.Shell.route) {
            MainShell(
                audioPlaybackManager = audioPlaybackManager,
                onNavigateToSettings = { navController.navigate(Screen.Settings.route) }
            )
        }
        composable(Screen.Settings.route) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }

    // Шторка центра уведомлений — глобальный оверлей поверх любого экрана.
    com.duq.android.ui.control.NotificationsShade()
}

/**
 * Оболочка с нижней навигацией. Внутренний NavHost держит вкладки и сохраняет их
 * состояние (чат не пересоздаётся при переключении). Settings и detail-экраны
 * разделов открываются поверх через внешний navController (полноэкранно).
 */
@Composable
private fun MainShell(
    audioPlaybackManager: ChatAudioPlaybackManager,
    onNavigateToSettings: () -> Unit
) {
    val tabNav = rememberNavController()
    val backStack by tabNav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route ?: START_TAB
    var showPalette by remember { mutableStateOf(false) }
    // Сброс фокуса поля чата + закрытие клавиатуры при смене вкладки — иначе IME и
    // поле ввода чата «зависают» поверх Пульта (фокус держит их при навигации).
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    // Любой уход с вкладки чата (клик/жест/deep-link) гасит клавиатуру и фокус, чтобы
    // поле ввода чата не висело поверх других экранов.
    LaunchedEffect(current) {
        if (current != "tab_chat") {
            keyboardController?.hide()
            focusManager.clearFocus(force = true)
        }
    }

    // Deep-link из уведомления → раздел Пульта (тап по пушу «обновление ядра» → «Версия»).
    // Коллектор на Unit-ключе: живёт пока MainShell в композиции, дренит буфер событий
    // (в т.ч. отправленных до композиции на cold-start) и каждый следующий тап.
    LaunchedEffect(Unit) {
        DeepLinkState.sectionEvents.receiveAsFlow().collect { key ->
            tabNav.navigate("section/$key")
        }
    }

    // Deep-link на вкладку (тап по обычному message-пушу → чат). Без этого warm-тап
    // оставлял юзера на прошлой панели (Пульт/раздел), а сообщение — в чате.
    LaunchedEffect(Unit) {
        DeepLinkState.tabEvents.receiveAsFlow().collect { route ->
            keyboardController?.hide()
            focusManager.clearFocus(force = true)
            tabNav.navigate(route) {
                popUpTo(tabNav.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    if (showPalette) {
        com.duq.android.ui.control.CommandPalette(
            onNavigate = { route ->
                if (route == "settings") onNavigateToSettings()
                else tabNav.navigate(route) { launchSingleTop = true }
            },
            onDismiss = { showPalette = false }
        )
    }

    Scaffold(
        containerColor = DuqColors.background,
        bottomBar = {
            NavigationBar(
                // Прозрачный фон — бар плавно сливается с чатом, без резкого блока
                // (Danny: «края аккуратнее, плавный переход»).
                containerColor = Color.Transparent,
                tonalElevation = 0.dp,
                // navigationBarsPadding поднимает бар НАД зоной жестов HyperOS (иначе
                // тап по вкладке перехватывается как home-swipe); height сжимает сам бар.
                modifier = Modifier.navigationBarsPadding().height(56.dp)
            ) {
                TABS.forEach { tab ->
                    NavigationBarItem(
                        selected = current == tab.route,
                        onClick = {
                            // Убираем фокус/клавиатуру ДО навигации, чтобы поле ввода
                            // чата не повисло поверх Пульта.
                            keyboardController?.hide()
                            focusManager.clearFocus(force = true)
                            tabNav.navigate(tab.route) {
                                popUpTo(tabNav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { androidx.compose.material3.Icon(tab.icon, contentDescription = tab.label,
                            modifier = Modifier.size(22.dp)) },
                        label = { Text(tab.label, fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = DuqColors.primary,
                            selectedTextColor = DuqColors.primary,
                            unselectedIconColor = DuqColors.textMuted,
                            unselectedTextColor = DuqColors.textMuted,
                            indicatorColor = Color.Transparent
                        )
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = tabNav,
            startDestination = START_TAB,
            modifier = Modifier.padding(padding)
        ) {
            composable("tab_chat") {
                MainScreen(
                    onNavigateToSettings = onNavigateToSettings,
                    onOpenPalette = { showPalette = true },
                    audioPlaybackManager = audioPlaybackManager
                )
            }
            composable("tab_hub") {
                HubScreen(
                    onOpenSection = { key -> tabNav.navigate("section/$key") },
                    onOpenPalette = { showPalette = true }
                )
            }
            composable("section/{key}") { entry ->
                val key = entry.arguments?.getString("key") ?: ""
                com.duq.android.ui.control.SectionScreen(
                    sectionKey = key,
                    onBack = { tabNav.popBackStack() }
                )
            }
        }
    }
}
