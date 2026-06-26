package com.duq.android.ui

import androidx.savedstate.read
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.duq.android.audio.AudioPlaybackManager
import com.duq.android.ui.control.AppChrome
import com.duq.android.ui.control.CommandPalette
import com.duq.android.ui.control.NotificationsShade
import com.duq.android.ui.control.SectionScreen
import com.duq.android.ui.theme.DuqColors
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import org.koin.compose.koinInject

sealed class Screen(val route: String) {
    object Shell : Screen("shell")       // bottom-nav оболочка (Чат/Пульт)
    object Settings : Screen("settings")
    object Profile : Screen("profile")   // панель пользователя (мультиюзер: имя + интеграции)
}

/** Вкладки нижней навигации. */
private data class Tab(val route: String, val icon: ImageVector, val label: String)
private val TABS = listOf(
    Tab("tab_hub", Icons.Outlined.GridView, "Пульт"),
    Tab("tab_chat", Icons.Outlined.ChatBubbleOutline, "Чат"),
)
// Стартуем с чата (основной экран), несмотря на порядок вкладок в баре.
private const val START_TAB = "tab_chat"

/**
 * Deep-link из уведомления — ОДНОРАЗОВЫЕ события навигации (не state). Тап по пушу
 * эмитит в Channel, MainShell собирает и навигирует. Channel (а не nullable state):
 * переживает cold-start — событие, отправленное до входа MainShell в композицию,
 * буферизуется и доставляется коллектору при подписке; не теряет повторные тапы и
 * не дедупит по значению. Хост-платформа (Activity/штора) эмитит, MainShell потребляет.
 */
object DeepLinkState {
    // Раздел Пульта (напр. "version" по пушу обновления ядра/приложения).
    val sectionEvents = Channel<String>(Channel.UNLIMITED)
    // Вкладка нижней навигации (напр. "tab_chat" по обычному message-пушу).
    val tabEvents = Channel<String>(Channel.UNLIMITED)
}

/**
 * Корень DUQ (Compose Multiplatform): NavHost оболочка (Чат/Пульт) + Настройки поверх.
 * Зависимости (плеер чата) — через Koin. Android-EntryPoint/Hilt убраны при переносе.
 */
@Composable
fun DuqApp(
    audioPlaybackManager: AudioPlaybackManager = koinInject(),
    restClient: com.duq.android.network.duq.DuqRestClient = koinInject(),
) {
    val navController = rememberNavController()

    // Глобальный чат-плеер (@Singleton в DI) живёт процесс — initialize один раз, БЕЗ
    // release() на disposal (release необратим — навигация/рекомпозиция убила бы аудио).
    LaunchedEffect(Unit) { audioPlaybackManager.initialize() }

    // Мультиюзер: регистрация устройства при старте приложения (надёжно — НЕ в ленивом
    // DuqChatClient, который создаётся лишь при открытии чата). Идемпотентно: user_id уже
    // есть → no-op. Так член семьи заводится сразу при первом запуске.
    LaunchedEffect(Unit) { runCatching { restClient.ensureRegistered() } }

    // Глобальная ⚙️ (из верхней панели любого экрана) ведёт в Настройки. SideEffect, а не
    // LaunchedEffect(Unit): пере-публикует лямбду на КАЖДОЙ рекомпозиции, поэтому после
    // конфиг-изменения, когда rememberNavController даёт новый инстанс, в
    // AppChrome.openSettings не остаётся ссылка на старый (detached) navController.
    SideEffect {
        AppChrome.openSettings = { navController.navigate(Screen.Settings.route) }
    }

    NavHost(navController = navController, startDestination = Screen.Shell.route) {
        composable(Screen.Shell.route) {
            MainShell(
                audioPlaybackManager = audioPlaybackManager,
                onNavigateToSettings = { navController.navigate(Screen.Settings.route) }
            )
        }
        composable(Screen.Settings.route) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenProfile = { navController.navigate(Screen.Profile.route) },
            )
        }
        composable(Screen.Profile.route) {
            ProfileScreen(onBack = { navController.popBackStack() })
        }
    }

    // Шторка центра уведомлений — глобальный оверлей поверх любого экрана.
    NotificationsShade()
}

/**
 * Оболочка с нижней навигацией. Внутренний NavHost держит вкладки и сохраняет их
 * состояние (чат не пересоздаётся при переключении). Settings и detail-экраны
 * разделов открываются поверх через внешний navController (полноэкранно).
 */
@Composable
private fun MainShell(
    audioPlaybackManager: AudioPlaybackManager,
    onNavigateToSettings: () -> Unit
) {
    val tabNav = rememberNavController()
    val backStack by tabNav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route ?: START_TAB
    var showPalette by remember { mutableStateOf(false) }
    // Сброс фокуса поля чата + закрытие клавиатуры при смене вкладки — иначе IME и
    // поле ввода чата «зависают» поверх Пульта (фокус держит их при навигации).
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(current) {
        if (current != "tab_chat") {
            keyboardController?.hide()
            focusManager.clearFocus(force = true)
        }
    }

    // Deep-link из уведомления → раздел Пульта (тап по пушу «обновление ядра» → «Версия»).
    LaunchedEffect(Unit) {
        DeepLinkState.sectionEvents.receiveAsFlow().collect { key ->
            tabNav.navigate("section/$key")
        }
    }

    // Deep-link на вкладку (тап по обычному message-пушу → чат). Ведём на саму вкладку,
    // СБРАСЫВАЯ деталь-секцию, открытую поверх неё. БЕЗ restoreState — он восстановил бы
    // сохранённую секцию вместо чистого чата.
    LaunchedEffect(Unit) {
        DeepLinkState.tabEvents.receiveAsFlow().collect { route ->
            keyboardController?.hide()
            focusManager.clearFocus(force = true)
            tabNav.navigate(route) {
                popUpTo(tabNav.graph.findStartDestination().id) { inclusive = false }
                launchSingleTop = true
            }
        }
    }

    if (showPalette) {
        CommandPalette(
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
                // Прозрачный фон — бар плавно сливается с чатом, без резкого блока.
                containerColor = Color.Transparent,
                tonalElevation = 0.dp,
                // navigationBarsPadding поднимает бар НАД зоной жестов; height сжимает бар.
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
                            // Тап вкладки ведёт на её корень, СБРАСЫВАЯ деталь-секцию,
                            // открытую поверх. БЕЗ restoreState. Состояние чата сохраняет сам
                            // NavHost (tab_chat — стартовый, всегда в стеке).
                            tabNav.navigate(tab.route) {
                                popUpTo(tabNav.graph.findStartDestination().id) { inclusive = false }
                                launchSingleTop = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label,
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
                val key = entry.arguments?.read { getStringOrNull("key") } ?: ""
                SectionScreen(
                    sectionKey = key,
                    onBack = { tabNav.popBackStack() }
                )
            }
        }
    }
}
