package com.duq.android.di

import com.duq.android.data.SettingsRepository
import com.duq.android.logging.IosLogger
import com.duq.android.logging.Logger
import com.duq.android.network.duq.DuqNodeClient
import com.duq.android.network.duq.IosPhoneCommandExecutor
import com.duq.android.network.duq.PhoneCommandExecutor
import com.duq.android.ui.AppUpdateController
import com.duq.android.ui.AudioFileCache
import com.duq.android.ui.CoreUpdateNotifier
import com.duq.android.ui.IosAppUpdateController
import com.duq.android.ui.IosAudioFileCache
import com.duq.android.ui.IosCoreUpdateNotifier
import com.duq.android.ui.IosNotificationInbox
import com.duq.android.ui.NotificationInbox
import com.duq.android.config.AppSecrets
import com.russhwolf.settings.NSUserDefaultsSettings
import com.russhwolf.settings.Settings
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import platform.Foundation.NSBundle
import org.koin.dsl.module
import platform.Foundation.NSUserDefaults

actual val platformModule: Module = module {
    single<Logger> { IosLogger() }
    single<Settings> { NSUserDefaultsSettings(NSUserDefaults.standardUserDefaults) }
    single { SettingsRepository(get()) }

    // Платформенные UI-мосты (iOS-деградации: in-memory inbox, no-op self-update).
    single<NotificationInbox> { IosNotificationInbox(get()) }
    single<AudioFileCache> { IosAudioFileCache(get()) }
    single<AppUpdateController> { IosAppUpdateController(get()) }
    single<CoreUpdateNotifier> { IosCoreUpdateNotifier(get()) }

    // Phone-control (bot → телефон) — iOS-only в графе: IosPhoneCommandExecutor —
    // деградация (на Android реализация живёт в app/, в shared её нет → там не биндим).
    // DuqNodeClient(executor, chatClient, http, logger): chatClient/http/logger — из графа.
    single<PhoneCommandExecutor> { IosPhoneCommandExecutor(get()) }
    single { DuqNodeClient(get(), get(), get(), get(), get()) }
}

/**
 * iOS-точка инициализации Koin. Swift-сторона (iOSApp) вызывает её на старте до показа UI.
 * Edge-токен (`SERVER_TOKEN`) читается из Info.plist (ключ `DuqServerToken`) — CI подставляет
 * туда секрет при сборке .ipa (аналог Android BuildConfig.SERVER_TOKEN). Без него запросы к
 * серверу уходят без `X-Auth-Token` → edge-периметр 401 → fail2ban. githubReleaseToken на iOS
 * не нужен (self-update идёт через SideStore/App Store, не AppUpdater).
 */
fun initKoinIos() {
    val token = NSBundle.mainBundle.objectForInfoDictionaryKey("DuqServerToken") as? String
    // Плейсхолдер (если CI не подставил секрет) — игнорируем, токен остаётся пустым.
    AppSecrets.serverToken = token?.takeIf { it.isNotBlank() && it != "__SERVER_TOKEN__" } ?: ""
    val koinApp = startKoin { modules(sharedModules()) }
    koinApp.koin.get<com.duq.android.network.duq.DuqNodeClient>().start()
}
