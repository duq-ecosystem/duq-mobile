package com.duq.android.di

import com.duq.android.data.SettingsRepository
import com.duq.android.logging.IosLogger
import com.duq.android.logging.Logger
import com.duq.android.ui.AppUpdateController
import com.duq.android.ui.AudioFileCache
import com.duq.android.ui.CoreUpdateNotifier
import com.duq.android.ui.IosAppUpdateController
import com.duq.android.ui.IosAudioFileCache
import com.duq.android.ui.IosCoreUpdateNotifier
import com.duq.android.ui.IosNotificationInbox
import com.duq.android.ui.NotificationInbox
import com.russhwolf.settings.NSUserDefaultsSettings
import com.russhwolf.settings.Settings
import org.koin.core.module.Module
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
}
