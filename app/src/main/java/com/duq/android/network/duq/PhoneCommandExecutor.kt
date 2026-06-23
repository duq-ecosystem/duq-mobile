package com.duq.android.network.duq

import android.content.Context
import com.duq.android.audio.AudioRecorderInterface
import com.duq.android.audio.WhisperLocal
import com.duq.android.camera.CameraCapture
import com.duq.android.config.AppConfig
import com.duq.android.location.LocationDataSource
import com.duq.android.logging.Logger
import com.duq.android.network.withDuqDns
import com.duq.android.network.withServerAuth
import com.duq.android.screen.ScreenCaptureManager
import com.duq.android.screen.ScreenRecorder
import com.duq.android.service.DuqListenerService
import com.duq.android.service.DuqNotificationManager
import dagger.hilt.android.qualifiers.ApplicationContext
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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Transport-agnostic executor of native phone-control commands (bot → phone).
 *
 * The command set (location / notify / voice / camera / screen) is the phone's
 * capability surface; it used to live in the legacy node client, tied to the old
 * node.invoke protocol. With DUQ on its own core the transport is the
 * bidirectional /duq/ws ([DuqNodeClient]), so the *how-to-do-it* is factored out
 * here and the WS client only deals with framing.
 */
@Singleton
class PhoneCommandExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val locationDataSource: LocationDataSource,
    private val notificationManager: DuqNotificationManager,
    private val audioRecorder: AudioRecorderInterface,
    private val whisper: WhisperLocal,
    private val logger: Logger
) {
    companion object {
        private const val TAG = "PhoneCmd"
        val SUPPORTED = setOf(
            "location.get", "notify.show", "voice.activate", "camera.snap", "screen.record"
        )
    }

    private val cameraCapture by lazy { CameraCapture(context) }
    private val screenRecorder by lazy { ScreenRecorder(context) }

    // STT for voice.activate. HTTP/1.1 (HTTP/2 stalls behind nginx for some bodies);
    // long read timeout because transcription can take a while.
    private val sttClient by lazy {
        OkHttpClient.Builder()
            .withDuqDns()
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .build()
    }

    /** Run a forwarded command and return its result payload. Throws on failure. */
    suspend fun execute(command: String, params: Map<*, *>): Map<String, Any?> = when (command) {
        "location.get" -> {
            val loc = locationDataSource.getLastLocation() ?: throw Exception("location unavailable")
            mapOf("lat" to loc.latitude, "lon" to loc.longitude,
                "accuracy" to loc.accuracy, "ts" to loc.time)
        }
        "notify.show" -> {
            val title = params["title"] as? String ?: "DUQ"
            val body = params["body"] as? String ?: ""
            val category = (params["category"] as? String ?: params["type"] as? String)
                ?.takeIf { it.isNotBlank() } ?: "message"
            notificationManager.showMessageNotification(body, title, category)
            mapOf("shown" to true)
        }
        "voice.activate" -> {
            val maxMs = (params["maxMs"] as? Double)?.toLong()?.coerceIn(2_000L, 30_000L) ?: 15_000L
            val file = File(context.cacheDir, "voice_activate.wav")
            logger.d(TAG, "voice.activate: recording (cap ${maxMs}ms)")
            val captured = try {
                withTimeoutOrNull(maxMs) { audioRecorder.record(file, useVad = true) } ?: run {
                    audioRecorder.stopRecording(); false
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                audioRecorder.stopRecording(); throw e
            }
            if (!captured || !file.exists() || file.length() <= 0L) throw Exception("no speech captured")
            val transcript = transcribe(file)
            logger.d(TAG, "voice.activate: transcript len=${transcript.length}")
            mapOf("transcript" to transcript)
        }
        "camera.snap" -> {
            val facingBack = (params["facing"] as? String) != "front"
            val snap = cameraCapture.snap(facingBack)
            mapOf("format" to snap.format, "base64" to snap.base64,
                "width" to snap.width, "height" to snap.height)
        }
        "screen.record" -> {
            val durationMs = (params["durationMs"] as? Double)?.toLong()?.coerceIn(1000L, 15000L) ?: 3000L
            val consent = ScreenCaptureManager.requestConsent(context)
                ?: throw Exception("screen capture consent denied")
            val clip = screenRecorder.record(consent, durationMs) {
                DuqListenerService.instance?.raiseMediaProjectionForeground()
            }
            mapOf("format" to clip.format, "base64" to clip.base64,
                "durationMs" to clip.durationMs, "hasAudio" to false)
        }
        else -> throw Exception("unsupported command: $command")
    }

    /** On-device whisper.cpp when enabled, else server /stt fallback. */
    private suspend fun transcribe(file: File): String =
        whisper.tryTranscribe(file) ?: transcribeOnServer(file)

    private suspend fun transcribeOnServer(file: File): String = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", "whisper-1")
            .addFormDataPart("language", AppConfig.STT_LANGUAGE)
            .addFormDataPart("file", file.name, file.asRequestBody("audio/wav".toMediaType()))
            .build()
        val req = Request.Builder().url(AppConfig.STT_URL).withServerAuth().post(body).build()
        sttClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("STT ${resp.code}")
            val text = JSONObject(resp.body?.string() ?: "{}").optString("text", "")
            if (text.isBlank()) throw Exception("empty transcript") else text
        }
    }
}
