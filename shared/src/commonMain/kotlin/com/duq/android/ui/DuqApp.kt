package com.duq.android.ui

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
import androidx.savedstate.read
import com.duq.android.audio.AudioPlaybackManager
import com.duq.android.ui.control.AppChrome
import com.duq.android.ui.control.CommandPalette
import com.duq.android.ui.control.NotificationsShade
import com.duq.android.ui.control.SectionScreen
import com.duq.android.ui.theme.DuqColors
import io.ktor.http.decodeURLQueryComponent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import org.koin.compose.koinInject

sealed class Screen(val route: String) {
    object Shell : Screen("shell") // bottom-nav оболочка (Чат/Пульт)
    object Settings : Screen("settings")
    object Profile : Screen("profile") // профиль/аккаунты (переключение, admin-список)
    object Login : Screen("login") // «войти под другим» поверх профиля
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

    // Вход через Telegram Login Widget: raw query из deep-link callback
    // (token/user_id/name/role). Эмитит Activity (duq://auth/telegram), DuqApp потребляет.
    val telegramLoginEvents = Channel<String>(Channel.UNLIMITED)

    // Native Telegram Login SDK: id_token из handleLoginResponse. Эмитит Activity
    // (app{client_id}-login.tg.dev/tglogin), DuqApp шлёт на /api/auth/telegram/native.
    val telegramNativeLoginEvents = Channel<String>(Channel.UNLIMITED)
}

/** Разобрать raw query "a=1&b=2" в map с URL-декодом значений (Telegram-callback deep link). */
internal fun parseQuery(raw: String): Map<String, String> =
    raw.split("&").mapNotNull { pair ->
        val i = pair.indexOf('=')
        if (i <= 0) {
            null
        } else {
            pair.substring(0, i) to pair.substring(i + 1).decodeURLQueryComponent(plusIsSpace = true)
        }
    }.toMap()

/**
 * Корень DUQ (Compose Multiplatform): NavHost оболочка (Чат/Пульт) + Настройки поверх.
 * Зависимости (плеер чата) — через Koin. Android-EntryPoint/Hilt убраны при переносе.
 */
@Composable
fun DuqApp(
    audioPlaybackManager: AudioPlaybackManager = koinInject(),
    settings: com.duq.android.data.SettingsRepository = koinInject(),
    nodeClient: com.duq.android.network.duq.DuqNodeClient = koinInject(),
    rest: com.duq.android.network.duq.DuqRestClient = koinInject(),
) {
    val navController = rememberNavController()

    // Глобальный чат-плеер (@Singleton в DI) живёт процесс — initialize один раз, БЕЗ
    // release() на disposal (release необратим — навигация/рекомпозиция убила бы аудио).
    LaunchedEffect(Unit) { audioPlaybackManager.initialize() }

    // Мультиаккаунт. Гейт: нет активного аккаунта → экран входа (имя + общий токен). Вход/
    // переключение меняет activeUser → ниже всё (включая чат-VM) пересоздаётся по key(activeUser),
    // чтобы данные были нового юзера. Никакой авто-регистрации.
    var activeUser by remember { mutableStateOf(settings.getUserId()) }

    // Вход через Telegram (deep-link callback duq://auth/telegram?token=&user_id=&name=&role=).
    // Приёмник ВЫШЕ гейта — иначе на экране входа (return ниже) он бы не подписался и вход бы
    // не завершился. Сохраняем аккаунт, реконнектим WS под новый user_id, снимаем гейт.
    LaunchedEffect(Unit) {
        DeepLinkState.telegramLoginEvents.receiveAsFlow().collect { rawQuery ->
            val params = parseQuery(rawQuery)
            val uid = params["user_id"].orEmpty()
            if (uid.isNotBlank()) {
                settings.applyTelegramLogin(
                    userId = uid,
                    name = params["name"].orEmpty(),
                    role = params["role"].orEmpty(),
                    userToken = params["token"].orEmpty(),
                )
                nodeClient.reconnect()
                activeUser = uid
                // Если вход шёл с Login-роута («войти под другим»), смены activeUser мало —
                // NavHost остаётся на форме. Возвращаем на чат (no-op, если стек уже там).
                navController.popBackStack(Screen.Shell.route, inclusive = false)
            }
        }
    }

    // Native Telegram Login (SDK вернул id_token через App Link). Шлём ядру → сессия. Тоже
    // выше гейта, чтобы завершить вход с экрана RegistrationScreen.
    LaunchedEffect(Unit) {
        DeepLinkState.telegramNativeLoginEvents.receiveAsFlow().collect { idToken ->
            println("DuqTgLogin: got idToken (len=${idToken.length}), POST /native")
            runCatching { rest.nativeTelegramLogin(idToken) }.onSuccess { uid ->
                println("DuqTgLogin: native login OK uid=$uid")
                nodeClient.reconnect()
                activeUser = uid
                // Вход с Login-роута («войти под другим») — вернуть на чат (иначе форма висит).
                navController.popBackStack(Screen.Shell.route, inclusive = false)
            }.onFailure { e ->
                println("DuqTgLogin: native login FAILED: ${e.message}")
            }
        }
    }

    if (activeUser.isBlank()) {
        RegistrationScreen(onRegistered = { activeUser = settings.getUserId() })
        return
    }

    // Глобальная ⚙️ (из верхней панели любого экрана) ведёт в Настройки. SideEffect, а не
    // LaunchedEffect(Unit): пере-публикует лямбду на КАЖДОЙ рекомпозиции, поэтому после
    // конфиг-изменения, когда rememberNavController даёт новый инстанс, в
    // AppChrome.openSettings не остаётся ссылка на старый (detached) navController.
    SideEffect {
        AppChrome.openSettings = { navController.navigate(Screen.Settings.route) }
        AppChrome.openProfile = { navController.navigate(Screen.Profile.route) }
    }

    NavHost(navController = navController, startDestination = Screen.Shell.route) {
        composable(Screen.Shell.route) {
            // key(activeUser): смена аккаунта пересоздаёт оболочку и чат-VM → данные нового юзера.
            key(activeUser) {
                MainShell(
                    audioPlaybackManager = audioPlaybackManager,
                    onNavigateToSettings = { navController.navigate(Screen.Settings.route) }
                )
            }
        }
        composable(Screen.Settings.route) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
            )
        }
        composable(Screen.Profile.route) {
            ProfileScreen(
                onBack = { navController.popBackStack() },
                onSwitched = {
                    activeUser = settings.getUserId()
                    navController.popBackStack(Screen.Shell.route, inclusive = false)
                },
                onAddAccount = { navController.navigate(Screen.Login.route) },
            )
        }
        composable(Screen.Login.route) {
            RegistrationScreen(
                onRegistered = {
                    activeUser = settings.getUserId()
                    navController.popBackStack(Screen.Shell.route, inclusive = false)
                },
                onBack = { navController.popBackStack() },
            )
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
                if (route == "settings") {
                    onNavigateToSettings()
                } else {
                    tabNav.navigate(route) { launchSingleTop = true }
                }
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
                        icon = {
                            Icon(
                                tab.icon,
                                contentDescription = tab.label,
                                modifier = Modifier.size(22.dp)
                            )
                        },
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
