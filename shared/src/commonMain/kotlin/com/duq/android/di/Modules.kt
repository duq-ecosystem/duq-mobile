package com.duq.android.di

import com.duq.android.network.createDuqHttpClient
import com.duq.android.network.duq.DuqRestClient
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Koin DI (замена Hilt). [networkModule] — общий (commonMain), [platformModule] —
 * expect/actual (Logger, Settings, SettingsRepository — платформенные реализации).
 * Граф наполняется по мере переноса модулей (audio/location/VM добавляются на своих фазах).
 */
val networkModule = module {
    single { createDuqHttpClient() }
    single { DuqRestClient(get()) }
}

/** Платформенные синглтоны (Logger/Settings/SettingsRepository). */
expect val platformModule: Module

/** Все модули shared — передаются в startKoin на старте (androidApp/iosApp). */
fun sharedModules(): List<Module> = listOf(networkModule, platformModule)
