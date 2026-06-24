package com.duq.android.di

import android.content.Context
import com.duq.android.data.SettingsRepository
import com.duq.android.logging.AndroidLogger
import com.duq.android.logging.Logger
import com.russhwolf.settings.SharedPreferencesSettings
import com.russhwolf.settings.Settings
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

actual val platformModule: Module = module {
    single<Logger> { AndroidLogger() }
    single<Settings> {
        val ctx: Context = androidContext()
        SharedPreferencesSettings(ctx.getSharedPreferences("duq_prefs", Context.MODE_PRIVATE))
    }
    single { SettingsRepository(get()) }
}
