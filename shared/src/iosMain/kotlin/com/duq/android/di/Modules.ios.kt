package com.duq.android.di

import com.duq.android.data.SettingsRepository
import com.duq.android.logging.IosLogger
import com.duq.android.logging.Logger
import com.russhwolf.settings.NSUserDefaultsSettings
import com.russhwolf.settings.Settings
import org.koin.core.module.Module
import org.koin.dsl.module
import platform.Foundation.NSUserDefaults

actual val platformModule: Module = module {
    single<Logger> { IosLogger() }
    single<Settings> { NSUserDefaultsSettings(NSUserDefaults.standardUserDefaults) }
    single { SettingsRepository(get()) }
}
