package com.duq.android.network.duq

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.duq.android.audio.AudioRecorderInterface
import com.duq.android.audio.LocalStt
import com.duq.android.camera.CameraCapture
import com.duq.android.config.AppConfig
import com.duq.android.location.LocationDataSource
import com.duq.android.logging.Logger
import com.duq.android.network.DohDns
import com.duq.android.screen.MediaProjectionForegroundService
import com.duq.android.screen.ScreenCaptureManager
import com.duq.android.screen.ScreenRecorder
import com.duq.android.ui.AndroidNotificationInbox
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Android phone-control executor (bot → phone) — full native capability surface.
 *
 * The core forwards native commands over the bidirectional /duq/ws socket
 * ({type:"phone.command", command, params}); [DuqNodeClient] frames them and
 * delegates the *how-to-do-it* here. The command set (location / notify / voice /
 * camera / screen) is the phone's capability surface, gated by [PhoneCommandExecutor.SUPPORTED].
 *
 * Migrated from the legacy `app/` Hilt implementation into KMP androidMain: Hilt
 * @Inject → Koin constructor injection, the AudioRecorderInterface contract takes a
 * String path (commonMain has no java.io.File), and the notification/inbox bridge is
 * the shared [AndroidNotificationInbox] instead of the old DuqNotificationManager.
 */
class AndroidPhoneCommandExecutor(
    private val context: Context,
    private val locationDataSource: LocationDataSource,
    private val audioRecorder: AudioRecorderInterface,
    private val whisper: LocalStt,
    private val logger: Logger,
) : PhoneCommandExecutor {

    private companion object {
        const val TAG = "PhoneCmdAndroid"
        const val MESSAGE_CHANNEL_ID = "duq_messages"
    }

    private val cameraCapture by lazy { CameraCapture(context) }
    private val screenRecorder by lazy { ScreenRecorder(context) }

    // STT for voice.activate. HTTP/1.1 (HTTP/2 stalls behind nginx for some bodies);
    // long read timeout because transcription can take a while. DoH-resolver so the
    // domain resolves on networks where the system DNS fails (same as the Ktor engine).
    private val sttClient by lazy {
        OkHttpClient.Builder()
            .dns(DohDns)
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .build()
    }

    /** Run a forwarded command and return its result payload. Throws on failure. */
    @Suppress("CyclomaticComplexMethod")
    override suspend fun execute(command: String, params: Map<*, *>): Map<String, Any?> = when (command) {
        "location.get" -> {
            val loc = locationDataSource.getLastLocation() ?: error("location unavailable")
            mapOf(
                "lat" to loc.latitude,
                "lon" to loc.longitude,
                "accuracy" to loc.accuracy,
                "ts" to loc.time
            )
        }
        "notify.show" -> {
            val title = params["title"] as? String ?: "DUQ"
            val body = params["body"] as? String ?: ""
            val category = (params["category"] as? String ?: params["type"] as? String)
                ?.takeIf { it.isNotBlank() } ?: "message"
            showMessageNotification(body, title, category)
            mapOf("shown" to true)
        }
        "voice.activate" -> {
            val maxMs = (params["maxMs"] as? Double)?.toLong()?.coerceIn(2_000L, 30_000L) ?: 15_000L
            val file = File(context.cacheDir, "voice_activate.wav")
            logger.d(TAG, "voice.activate: recording (cap ${maxMs}ms)")
            val captured = try {
                withTimeoutOrNull(maxMs) { audioRecorder.record(file.absolutePath, useVad = true) } ?: run {
                    audioRecorder.stopRecording()
                    false
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                audioRecorder.stopRecording()
                throw e
            }
            if (!captured || !file.exists() || file.length() <= 0L) error("no speech captured")
            val transcript = transcribe(file)
            logger.d(TAG, "voice.activate: transcript len=${transcript.length}")
            mapOf("transcript" to transcript)
        }
        "camera.snap" -> {
            val facingBack = (params["facing"] as? String) != "front"
            val snap = cameraCapture.snap(facingBack)
            mapOf(
                "format" to snap.format,
                "base64" to snap.base64,
                "width" to snap.width,
                "height" to snap.height
            )
        }
        "screen.record" -> {
            val durationMs = (params["durationMs"] as? Double)?.toLong()?.coerceIn(1000L, 15000L) ?: 3000L
            val consent = ScreenCaptureManager.requestConsent(context)
                ?: error("screen capture consent denied")
            // Bring up a dedicated mediaProjection FGS for the lifetime of the clip
            // (A14+ requires the type to be live before getMediaProjection()).
            MediaProjectionForegroundService.start(context)
            try {
                val clip = screenRecorder.record(consent, durationMs) {
                    MediaProjectionForegroundService.instance?.raiseProjectionForeground()
                }
                mapOf(
                    "format" to clip.format,
                    "base64" to clip.base64,
                    "durationMs" to clip.durationMs,
                    "hasAudio" to false
                )
            } finally {
                MediaProjectionForegroundService.stop(context)
            }
        }
        else -> throw IllegalArgumentException("unsupported command: $command")
    }

    /**
     * notify.show — surface a system notification AND drop the item into the shared
     * notification inbox (🔔), the same single source of truth the UI renders.
     */
    private fun showMessageNotification(body: String, title: String, category: String) {
        val now = System.currentTimeMillis()
        AndroidNotificationInbox.record(context, title, body, category, now)
        ensureMessageChannel()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notif = NotificationCompat.Builder(context, MESSAGE_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body.take(200))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .build()
        nm.notify(now.toInt(), notif)
    }

    private fun ensureMessageChannel() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(MESSAGE_CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(MESSAGE_CHANNEL_ID, "DUQ", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "DUQ messages" }
            )
        }
    }

    /** On-device whisper.cpp when enabled, else server /stt fallback. */
    private suspend fun transcribe(file: File): String =
        whisper.tryTranscribe(file.absolutePath) ?: transcribeOnServer(file)

    private suspend fun transcribeOnServer(file: File): String = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", "whisper-1")
            .addFormDataPart("language", AppConfig.STT_LANGUAGE)
            .addFormDataPart("file", file.name, file.asRequestBody("audio/wav".toMediaType()))
            .build()
        val req = Request.Builder()
            .url(AppConfig.STT_URL)
            .header(AppConfig.SERVER_TOKEN_HEADER, AppConfig.SERVER_TOKEN)
            .post(body)
            .build()
        sttClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("STT ${resp.code}")
            val text = JSONObject(resp.body?.string() ?: "{}").optString("text", "")
            if (text.isBlank()) error("empty transcript") else text
        }
    }
}
