package com.duq.android

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.duq.android.data.SettingsRepository
import com.duq.android.ui.DeepLinkState
import com.duq.android.ui.DuqApp
import com.duq.android.ui.theme.DuqAndroidTheme
import com.duq.android.update.AppUpdater
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // After permission result (granted or not) — run update check
            runUpdateCheck()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra("porcupine_key")?.let { settingsRepository.savePorcupineApiKey(it) }
        // Deep-link из уведомления: открыть раздел Пульта (напр. «engine» по пушу обновления).
        intent.getStringExtra("open_section")?.let { DeepLinkState.sectionEvents.trySend(it) }
        // Обычный message-пуш → вкладка чата (иначе warm-тап оставит на прошлой панели).
        intent.getStringExtra("open_tab")?.let { DeepLinkState.tabEvents.trySend(it) }
        if (intent.getStringExtra("open_notifications") == "digest")
            com.duq.android.ui.control.AppChrome.openShade(1)
        enableEdgeToEdge()
        setContent {
            DuqAndroidTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    DuqApp()
                }
            }
        }

        // Request POST_NOTIFICATIONS on Android 13+ (API 33), then run update check
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                runUpdateCheck()
            }
        } else {
            runUpdateCheck()
        }
    }

    private fun runUpdateCheck() {
        val nm = getSystemService(NotificationManager::class.java)
        lifecycleScope.launch(Dispatchers.IO) {
            AppUpdater(applicationContext, nm.areNotificationsEnabled()).checkAvailable()
            // Проверка обновления ЯДРА DUQ → пуш «Обновление ядра» с deep-link в «Движок».
            com.duq.android.update.CoreUpdateNotifier.check(applicationContext)
        }
    }

    // Уведомление тапнуто, пока activity жива (singleTop) — обновляем deep-link.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra("open_section")?.let { DeepLinkState.sectionEvents.trySend(it) }
        intent.getStringExtra("open_tab")?.let { DeepLinkState.tabEvents.trySend(it) }
        if (intent.getStringExtra("open_notifications") == "digest")
            com.duq.android.ui.control.AppChrome.openShade(1)
    }

    override fun onResume() {
        super.onResume()
        isInForeground = true
    }

    override fun onPause() {
        super.onPause()
        isInForeground = false
    }

    companion object {
        @Volatile var isInForeground = false
    }
}
