package com.duq.android.di

import android.content.Context
import com.duq.android.data.SettingsRepository
import com.duq.android.logging.FileLogger
import com.duq.android.logging.Logger
import com.duq.android.ui.AndroidAppUpdateController
import com.duq.android.ui.AndroidAudioFileCache
import com.duq.android.ui.AndroidCoreUpdateNotifier
import com.duq.android.ui.AndroidNotificationInbox
import com.duq.android.ui.AppUpdateController
import com.duq.android.ui.AudioFileCache
import com.duq.android.ui.CoreUpdateNotifier
import com.duq.android.ui.NotificationInbox
import com.russhwolf.settings.SharedPreferencesSettings
import com.russhwolf.settings.Settings
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

actual val platformModule: Module = module {
    // Файловый логгер: пишет в logcat + ротируемый files/logs/duq.log (release/фон/MIUI,
    // где logcat третьих-сторон режется). Нужен Context — даёт androidContext().
    single<Logger> { FileLogger(androidContext()) }
    single<Settings> {
        val ctx: Context = androidContext()
        SharedPreferencesSettings(ctx.getSharedPreferences("duq_prefs", Context.MODE_PRIVATE))
    }
    single { SettingsRepository(get()) }

    // Платформенные UI-мосты (требуют Context — Koin даёт androidContext()).
    single<NotificationInbox> { AndroidNotificationInbox(androidContext()) }
    single<AudioFileCache> { AndroidAudioFileCache(androidContext(), get()) }
    single<AppUpdateController> { AndroidAppUpdateController(androidContext(), get()) }
    single<CoreUpdateNotifier> { AndroidCoreUpdateNotifier(androidContext(), get()) }

    // Геолокация (phone-control location.get + фоновые city-level апдейты).
    single<com.duq.android.location.LocationDataSource> {
        com.duq.android.location.FusedLocationDataSource(androidContext())
    }

    // WS bot→phone + presence + чат/reasoning-стрим. Полная нативная phone-control
    // реализация (CameraX snap, FusedLocation, MediaProjection screen.record, on-device
    // STT voice.activate, notify.show) — НЕ заглушка. Зависимости: LocationDataSource,
    // AudioRecorderInterface/LocalStt (audioModule), Logger (platformModule).
    single<com.duq.android.network.duq.PhoneCommandExecutor> {
        com.duq.android.network.duq.AndroidPhoneCommandExecutor(
            context = androidContext(),
            locationDataSource = get(),
            audioRecorder = get(),
            whisper = get(),
            logger = get(),
        )
    }
    single { com.duq.android.network.duq.DuqNodeClient(get(), get(), get(), get()) }

    // Голосовой флоу фонового сервиса (DuqListenerService): маппер ошибок + обработчик
    // голосовых команд (бип→запись→STT→отправка). Зависимости — из audioModule
    // (AudioRecorderInterface/AudioPlaybackManager/BeepPlayer) и common (DuqChatClient).
    // DuqNotificationManager НЕ здесь: он в androidApp (R/MainActivity) — биндится локальным
    // модулем приложения (androidApp/.../di/appAndroidModule).
    single<com.duq.android.service.ErrorMapper> { com.duq.android.service.DefaultErrorMapper() }
    single {
        com.duq.android.service.VoiceCommandProcessor(
            context = androidContext(),
            audioRecorder = get(),
            chatAudioPlaybackManager = get(),
            beepPlayer = get(),
            gatewayClient = get(),
            errorMapper = get(),
        )
    }
}
