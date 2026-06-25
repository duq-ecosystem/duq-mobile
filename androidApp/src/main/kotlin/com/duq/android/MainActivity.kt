package com.duq.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.duq.shared.App

/**
 * Хост Compose-Multiplatform UI. App() параметров не принимает, а MainScreen запрашивает
 * микрофон через колбэк с дефолтом (no-op) — поэтому разрешения запрашиваем тут напрямую
 * при старте: RECORD_AUDIO (push-to-talk / on-device STT) и POST_NOTIFICATIONS (Android 13+,
 * центр уведомлений и апдейт-баннеры).
 */
class MainActivity : ComponentActivity() {

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { /* результат не блокирует UI */ }

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
        setContent { App() }
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
            ) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isNotEmpty()) requestPermissions.launch(needed.toTypedArray())
    }
}
