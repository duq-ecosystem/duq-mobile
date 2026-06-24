package com.duq.shared

import androidx.compose.runtime.Composable
import com.duq.android.ui.DuqApp
import com.duq.android.ui.theme.DuqTheme

/**
 * Корневой Composable DUQ — общий для Android и iOS (Compose Multiplatform).
 *
 * Тема DUQ (тёмная, glassmorphism) + реальный корень навигации [DuqApp]
 * (NavHost: Чат / Пульт / Версия). Зависимости (ViewModels, плеер, REST-клиенты)
 * приходят через Koin; платформенные actual-реализации интерфейсов — на фазе platform.
 */
@Composable
fun App() {
    DuqTheme {
        DuqApp()
    }
}
