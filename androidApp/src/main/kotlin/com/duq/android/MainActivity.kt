package com.duq.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.duq.android.config.AppConfig
import com.duq.android.ui.DeepLinkState
import com.duq.android.ui.control.AppChrome
import com.duq.shared.App

/**
 * Хост Compose-Multiplatform UI. App() параметров не принимает, а MainScreen запрашивает
 * микрофон через колбэк с дефолтом (no-op) — поэтому разрешения запрашиваем тут напрямую
 * при старте: RECORD_AUDIO (push-to-talk / on-device STT) и POST_NOTIFICATIONS (Android 13+,
 * центр уведомлений и апдейт-баннеры).
 *
 * Также — единственный эмиттер deep-link из уведомлений: тап по пушу несёт intent-extras
 * (open_section / open_tab / open_notifications), которые надо доставить в навигацию CMP.
 * Приёмник — [DeepLinkState] (Channel) и [AppChrome], их собирает MainShell в DuqApp.
 */
class MainActivity : ComponentActivity() {

    private val requestPermissions =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { /* результат не блокирует UI */ }

    companion object {
        /**
         * Видимость UI. [com.duq.android.service.DuqListenerService] читает это, чтобы НЕ
         * слать системное уведомление о финальном ответе, когда чат и так на экране
         * (иначе дубль: пузырь в UI + push). Обновляется в onStart/onStop.
         */
        @Volatile
        var isInForeground: Boolean = false
            private set
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestStartupPermissions()
        // Кнопка «Войти через Telegram» (shared UI) дёргает этот хук — native SDK нужен
        // Activity-контекст, которого нет в commonMain. Переустанавливаем на каждый onCreate.
        AppChrome.startTelegramLogin = { org.telegram.login.TelegramLogin.startLogin(this) }
        routeDeepLink(intent)
        setContent { App() }
    }

    /**
     * Уведомление тапнуто, пока activity жива (launchMode=singleTask → onNewIntent вместо
     * нового onCreate) — доставляем свежий deep-link в навигацию.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        routeDeepLink(intent)
    }

    /**
     * Эмиссия deep-link из intent-extras в общий KMP-приёмник навигации:
     *  - open_section → раздел Пульта (напр. «version» по пушу обновления ядра/приложения);
     *  - open_tab → вкладка нижней навигации (обычный message-пуш → чат, иначе warm-тап
     *    оставит на прошлой панели);
     *  - open_notifications=digest → открыть шторку центра уведомлений на вкладке дайджестов.
     */
    private fun routeDeepLink(intent: Intent?) {
        intent ?: return
        intent.getStringExtra("open_section")?.let { DeepLinkState.sectionEvents.trySend(it) }
        intent.getStringExtra("open_tab")?.let { DeepLinkState.tabEvents.trySend(it) }
        if (intent.getStringExtra("open_notifications") == "digest") AppChrome.openShade(1)

        // URI deep link входа через Telegram: сервер редиректит браузер на
        // duq://auth/telegram?token=&user_id=&name=&role= → отдаём raw query в DuqApp-приёмник.
        val data = intent.data
        if (data != null &&
            data.scheme == AppConfig.TELEGRAM_LOGIN_DEEPLINK_SCHEME &&
            data.host == AppConfig.TELEGRAM_LOGIN_DEEPLINK_HOST
        ) {
            data.query?.let { DeepLinkState.telegramLoginEvents.trySend(it) }
        }

        // Native Telegram Login SDK: Telegram-приложение вернуло результат на App Link
        // app{client_id}-login.tg.dev/tglogin. Отдаём URI в SDK → получаем id_token → в приёмник.
        if (data != null) {
            android.util.Log.i("DuqTgLogin", "deep-link ${data.scheme}://${data.host}${data.path}")
        }
        if (data != null && data.host == AppConfig.TELEGRAM_NATIVE_REDIRECT_HOST) {
            android.util.Log.i("DuqTgLogin", "native redirect matched")
            org.telegram.login.TelegramLogin.handleLoginResponse(
                data,
                onSuccess = { loginData ->
                    val n = loginData.idToken.length
                    android.util.Log.i("DuqTgLogin", "SDK onSuccess idToken.len=$n")
                    DeepLinkState.telegramNativeLoginEvents.trySend(loginData.idToken)
                },
                onError = { error ->
                    android.util.Log.e("DuqTgLogin", "SDK onError: ${error.message}")
                },
            )
        }
    }

    override fun onStart() {
        super.onStart()
        isInForeground = true
    }

    override fun onStop() {
        super.onStop()
        isInForeground = false
    }

    private fun requestStartupPermissions() {
        val needed = buildList {
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.RECORD_AUDIO)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (needed.isNotEmpty()) requestPermissions.launch(needed.toTypedArray())
    }
}
