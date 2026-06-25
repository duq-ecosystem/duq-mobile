package com.duq.android.di

import com.duq.android.service.DuqNotificationManager
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Koin-модуль уровня приложения (androidApp). Здесь биндятся компоненты, завязанные на
 * app-ресурсы (R) и [com.duq.android.MainActivity], которые НЕЛЬЗЯ держать в shared
 * (shared не видит androidApp). Сейчас — [DuqNotificationManager] (FGS-уведомление +
 * deep-link пушей входящих ответов).
 */
val appAndroidModule: Module = module {
    single { DuqNotificationManager(androidContext()) }
}
