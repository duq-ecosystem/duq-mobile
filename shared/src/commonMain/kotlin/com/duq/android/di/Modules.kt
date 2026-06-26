package com.duq.android.di

import com.duq.android.network.CoreUpdateClient
import com.duq.android.network.TtsClient
import com.duq.android.network.createDuqHttpClient
import com.duq.android.network.duq.DuqChatClient
import com.duq.android.network.duq.DuqRestClient
import com.duq.android.ui.ConversationViewModel
import com.duq.android.ui.control.AgentsViewModel
import com.duq.android.ui.control.AutomationViewModel
import com.duq.android.ui.control.NotificationsViewModel
import com.duq.android.ui.control.SectionViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Koin DI (замена Hilt). Граф собран из:
 *  - [networkModule] — общий (commonMain): HttpClient + REST/чат/TTS/core-update клиенты.
 *  - [audioModule]   — expect/actual (audio-реализации к интерфейсам: android — нативные,
 *                       ios — деградации; Context/Logger приходят из платформенного графа).
 *  - [viewModelModule] — общий (commonMain): 5 экранных ViewModel (резолвятся koinViewModel()).
 *  - [platformModule] — expect/actual (Logger/Settings/SettingsRepository + UI-мосты;
 *                       на iOS дополнительно PhoneCommandExecutor + DuqNodeClient).
 *
 * ⚠ DuqNodeClient/PhoneCommandExecutor — НЕ в общем графе: Android-реализация
 * PhoneCommandExecutor живёт в app/ (референс, Hilt), в shared её нет, поэтому на Android
 * биндить нечем. На iOS они биндятся в iosMain (IosPhoneCommandExecutor — деградация).
 * Ни один из 5 ViewModel/экранов DuqNodeClient не использует, так что граф резолвится
 * полностью и приложение запускается.
 */
val networkModule = module {
    single { createDuqHttpClient(get()) }
    single { DuqRestClient(get(), get()) }
    // DuqChatClient(rest, stt: LocalStt, http, logger) — LocalStt из audioModule, logger из platformModule.
    single { DuqChatClient(get(), get(), get(), get()) }
    single { TtsClient(get()) }
    single { CoreUpdateClient(get()) }
}

/**
 * Экранные ViewModel (commonMain). Все 5 наследуют androidx.lifecycle.ViewModel и
 * резолвятся через koinViewModel() в Compose (koin-compose-viewmodel). Все параметры —
 * из графа (клиенты/audio-интерфейсы/Logger/Settings/UI-мосты), без примитивов.
 */
val viewModelModule = module {
    viewModel {
        ConversationViewModel(
            gatewayClient = get(),
            audioPlaybackManager = get(),
            audioRecorder = get(),
            ttsLocal = get(),
            ttsClient = get(),
            streamingTts = get(),
            sttLocal = get(),
            beepPlayer = get(),
            settings = get(),
            notificationInbox = get(),
            appUpdater = get(),
            audioFileCache = get(),
            logger = get(),
        )
    }
    viewModel { AgentsViewModel(rest = get(), logger = get()) }
    viewModel { AutomationViewModel(rest = get()) }
    viewModel {
        SectionViewModel(
            coreUpdate = get(),
            appUpdater = get(),
            coreUpdateNotifier = get(),
        )
    }
    viewModel { NotificationsViewModel(inbox = get(), appUpdater = get()) }
}

/** Audio-реализации (expect/actual): android — нативные, ios — деградации. */
expect val audioModule: Module

/** Платформенные синглтоны (Logger/Settings/SettingsRepository + UI-мосты). */
expect val platformModule: Module

/** Все модули shared — передаются в startKoin на старте (androidApp/iosApp). */
fun sharedModules(): List<Module> = listOf(
    networkModule,
    audioModule,
    viewModelModule,
    platformModule,
)
