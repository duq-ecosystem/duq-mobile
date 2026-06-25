package com.duq.android.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.duq.android.MainActivity
import com.duq.android.error.DuqError
import com.duq.android.network.duq.DuqChatClient
import com.duq.android.network.duq.DuqNodeClient
import com.duq.android.ui.DuqState
import com.duq.android.util.ReplyText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import java.util.concurrent.ConcurrentHashMap

/**
 * Постоянный foreground-сервис — держит WebSocket-присутствие и чат-клиент ЖИВЫМИ в фоне,
 * чтобы ядро достучалось до телефона (presence) и ответы приходили, когда приложение
 * свёрнуто/в кармане. Без FGS WS жил только пока процесс жив (стартовал из
 * DuqApplication.onCreate) — Android при сворачивании мог убить процесс → presence терялся.
 *
 * Перенесён из app-модуля в androidApp. Изменения под KMP:
 *  - Hilt (`@AndroidEntryPoint`/`@Inject`) → Koin (`by inject()` из koin-android).
 *  - Wake-word (Porcupine) ВЫРЕЗАН осознанно (его нет в KMP, `DuqError.WakeWordError`
 *    удалён) → [startListening]/[stopListening] оставлены контрактом [VoiceServiceController],
 *    но микрофон в фоне НЕ открывают: голосовой капчур инициирует только UI (push-to-talk).
 *  - [DuqState] теперь `com.duq.android.ui`, [ReplyText] — `com.duq.android.util`.
 *  - foreground вызывается явно из [DuqApplication] action=ACTION_START (см. onStartCommand).
 *
 * Без микрофона в фоне. Входящие ответы DUQ → системное уведомление, когда app в фоне.
 */
class DuqListenerService : Service(), VoiceServiceController {

    companion object {
        private const val TAG = "DuqListenerService"
        const val ACTION_START = "com.duq.android.START"
        const val ACTION_STOP = "com.duq.android.STOP"
        // Live instance — node screen.record может поднять mediaProjection-тип FGS на этом
        // же запущенном сервисе до getMediaProjection() (A14+). В KMP основной путь
        // screen.record — отдельный MediaProjectionForegroundService; метод оставлен для
        // совместимости и как fallback.
        @Volatile var instance: DuqListenerService? = null
            private set
    }

    /** Добавить mediaProjection-тип FGS (Android 14+ требует его до старта захвата). */
    fun raiseMediaProjectionForeground() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val n = notificationManager.createServiceNotification()
        // location/camera типы — только при наличии runtime-разрешения (иначе
        // SecurityException роняет сервис, см. startForegroundServiceWithNotification).
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        if (hasPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ||
            hasPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        if (hasPermission(android.Manifest.permission.CAMERA)) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }
        runCatching { startForeground(DuqNotificationManager.SERVICE_NOTIFICATION_ID, n, type) }
            .onFailure { Log.e(TAG, "raiseMediaProjectionForeground: ${it.message}") }
    }

    // Koin-инъекции (koin-android by inject()) — синглтоны из общего графа.
    private val notificationManager: DuqNotificationManager by inject()
    private val voiceCommandProcessor: VoiceCommandProcessor by inject()
    // ЧАТ — клиент ядра DUQ (REST + поллинг + reasoning-стрим через DuqNodeClient).
    private val gatewayClient: DuqChatClient by inject()
    // phone-control — node-сессия bot→phone поверх двунаправленного /duq/ws (presence).
    private val nodeClient: DuqNodeClient by inject()

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Накопитель стриминг-текста по runId для фоновых уведомлений.
    private val messageBuffers = ConcurrentHashMap<String, StringBuilder>()

    private val _state = MutableStateFlow(DuqState.IDLE)
    override val state: StateFlow<DuqState> = _state

    private val _error = MutableStateFlow<DuqError?>(null)
    override val error: StateFlow<DuqError?> = _error

    inner class LocalBinder : Binder() {
        fun getController(): VoiceServiceController = this@DuqListenerService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "SERVICE CREATED — lean WS mode")
        voiceCommandProcessor.initializePlayer()
        gatewayClient.start()      // чат: телефон → ядро DUQ (REST + поллинг)
        nodeClient.start()         // phone-control: ядро DUQ → телефон (двунаправленный /duq/ws)
        collectIncomingMessages()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
            ACTION_START -> startForegroundServiceWithNotification()
        }
        return START_STICKY
    }

    private fun startForegroundServiceWithNotification() {
        val notification = notificationManager.createServiceNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // dataSync (WS) + location (reports) + camera (node camera.snap). Camera type
            // нужен для фонового доступа к камере на Android 11+. mediaProjection
            // добавляется динамически в момент screen.record (нельзя объявить без активного
            // projection-токена).
            // ⚠️ targetSDK34: startForeground с type=location/camera БЕЗ соответствующего
            // runtime-разрешения кидает SecurityException и роняет ВЕСЬ сервис (так app
            // крашился после `pm clear` — разрешения сброшены). Поэтому тип добавляем только
            // когда право реально выдано; иначе стартуем как dataSync и не падаем (location/
            // camera просто не работают, пока юзер не выдаст разрешение в UI).
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (hasPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ||
                    hasPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)) {
                    type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                }
                if (hasPermission(android.Manifest.permission.CAMERA)) {
                    type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                }
            }
            startForeground(DuqNotificationManager.SERVICE_NOTIFICATION_ID, notification, type)
        } else {
            startForeground(DuqNotificationManager.SERVICE_NOTIFICATION_ID, notification)
        }
    }

    private fun hasPermission(permission: String): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(this, permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Чат-события — буферим текст, показываем уведомление, когда app в фоне. */
    private fun collectIncomingMessages() {
        serviceScope.launch {
            gatewayClient.chatEvents.collect { event ->
                when (event.state) {
                    "delta" -> {
                        val text = event.deltaText ?: return@collect
                        messageBuffers.getOrPut(event.runId) { StringBuilder() }.append(text)
                    }
                    "final" -> {
                        // Предпочитаем авторитетный кумулятивный текст сервера (как в UI);
                        // фолбэк — локально накопленный delta-буфер.
                        val raw = event.fullText ?: messageBuffers[event.runId]?.toString()
                        messageBuffers.remove(event.runId)
                        val text = raw?.let { ReplyText.clean(it) } ?: return@collect
                        if (text.isNotBlank() && !MainActivity.isInForeground) {
                            notificationManager.showMessageNotification(text)
                        }
                    }
                    "error", "aborted" -> messageBuffers.remove(event.runId)
                }
            }
        }
    }

    // VoiceServiceController — wake-word вырезан (Porcupine). Микрофон в фоне НЕ
    // открывается: голосовой капчур инициирует только UI (push-to-talk). Методы оставлены
    // контрактом интерфейса и держат состояние в IDLE.

    override fun startListening() {
        // Wake-word отключён навсегда (Porcupine исчерпал free-tier; openWakeWord — план).
        // НИКАКОЙ код не открывает микрофон в фоне → privacy-индикатор не загорается.
        _state.value = DuqState.IDLE
    }

    override fun stopListening() {
        _state.value = DuqState.IDLE
    }

    override fun clearError() {
        _error.value = null
        _state.value = DuqState.IDLE
    }

    override fun onDestroy() {
        Log.d(TAG, "SERVICE DESTROYING")
        voiceCommandProcessor.stopRecording()
        voiceCommandProcessor.releasePlayer()
        gatewayClient.stop()
        nodeClient.stop()
        serviceScope.cancel()
        instance = null
        super.onDestroy()
    }
}
