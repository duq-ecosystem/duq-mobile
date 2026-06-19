package com.duq.android.network.openclaw

import android.content.Context
import com.duq.android.auth.DeviceIdentityManager
import com.duq.android.camera.CameraCapture
import com.duq.android.config.AppConfig
import com.duq.android.data.SettingsRepository
import com.duq.android.location.LocationDataSource
import com.duq.android.logging.Logger
import com.duq.android.screen.ScreenCaptureManager
import com.duq.android.screen.ScreenRecorder
import com.duq.android.service.DuqListenerService
import com.duq.android.service.DuqNotificationManager
import dagger.hilt.android.qualifiers.ApplicationContext
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow

/**
 * OpenClaw **node** host — the "bot → phone" direction.
 *
 * The phone connects a SECOND websocket as `role: "node"` (separate Ed25519
 * identity / device record from the operator/chat session), declares the
 * capabilities + commands it can execute, and then services `node.invoke`
 * requests the gateway forwards to it, replying with `node.invoke.result`.
 *
 * This is the native OpenClaw mechanism (camera.snap / screen.record /
 * location.get / custom commands) — it replaces the `__duq_cmd`-in-chat hack.
 * The operator session ([OpenClawGatewayClient]) stays as-is for chat.
 */
@Singleton
class OpenClawNodeClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val locationDataSource: LocationDataSource,
    private val notificationManager: DuqNotificationManager,
    private val audioRecorder: com.duq.android.audio.AudioRecorderInterface,
    private val whisper: com.duq.android.audio.WhisperLocal,
    private val logger: Logger
) {
    private val cameraCapture by lazy { CameraCapture(context) }
    private val screenRecorder by lazy { ScreenRecorder(context) }

    // STT client for voice.activate. HTTP/1.1 (HTTP/2 stalls behind nginx for some
    // bodies); long read timeout because Whisper transcription can take a while.
    private val sttClient by lazy {
        OkHttpClient.Builder()
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(90, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    companion object {
        private const val TAG = "OcNode"
        private const val CLIENT_ID = "openclaw-android"
        private const val CLIENT_MODE = "node"
        private const val ROLE = "node"
        private const val PLATFORM = "android"
        private const val DEVICE_FAMILY = "mobile"
        // What this phone can do. Native + custom commands the engine may invoke.
        private val CAPS = listOf("location", "notify", "voice", "camera", "screen")
        private val COMMANDS = listOf("location.get", "notify.show", "voice.activate", "camera.snap", "screen.record")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson: Gson = GsonBuilder().create()
    // SAME identity as the operator session: the setup-code bootstrap profile is
    // roles=[node,operator] and binds to ONE publicKey, and a device record can
    // carry both roles. A separate keypair would be rejected by the bootstrap.
    private val identity = DeviceIdentityManager(settings, keyName = "operator")

    @Volatile private var webSocket: WebSocket? = null
    private val isManualStop = AtomicBoolean(false)
    private val isConnecting = AtomicBoolean(false)
    private val reconnectAttempt = AtomicInteger(0)

    private val frameChannel = Channel<String>(Channel.UNLIMITED)
    private val consumerStarted = AtomicBoolean(false)

    @Volatile private var currentAuth: Map<String, String?> = emptyMap()

    private val _state = MutableStateFlow(GatewayConnectionState.DISCONNECTED)
    val state: StateFlow<GatewayConnectionState> = _state

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(AppConfig.CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    }

    fun start() {
        isManualStop.set(false)
        if (_state.value == GatewayConnectionState.CONNECTED) return
        ensureConsumer()
        scope.launch { connect() }
    }

    fun stop() {
        isManualStop.set(true)
        webSocket?.close(1000, "stopped")
        webSocket = null
        _state.value = GatewayConnectionState.DISCONNECTED
    }

    private fun ensureConsumer() {
        if (!consumerStarted.compareAndSet(false, true)) return
        scope.launch {
            for (text in frameChannel) {
                try {
                    consumeFrame(text)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.e(TAG, "consumeFrame: ${e.message}")
                }
            }
        }
    }

    private fun connect() {
        if (!isConnecting.compareAndSet(false, true)) return
        val url = settings.getGatewayUrl()
        val nodeToken = settings.getNodeToken()
        val bootstrap = settings.getBootstrapToken()
        // Device tokens are role-bound (operator token ≠ node token). Once we have a
        // node token, reconnect with it. Otherwise authenticate with the BOOTSTRAP
        // on the SAME identity the operator already redeemed it on: the bound
        // setup-profile (roles=[node,operator]) triggers silent node pairing and the
        // gateway issues a node token in hello-ok auth.deviceTokens[].
        currentAuth = when {
            nodeToken.isNotBlank() -> mapOf("deviceToken" to nodeToken)
            bootstrap.isNotBlank() -> mapOf("bootstrapToken" to bootstrap)
            else -> emptyMap()
        }
        _state.value = GatewayConnectionState.CONNECTING
        logger.d(TAG, "Node connecting to $url (nodeToken=${nodeToken.isNotBlank()} bootstrap=${bootstrap.isNotBlank()})")
        val request = Request.Builder().url(url).build()
        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                logger.d(TAG, "Node WS open, waiting for challenge...")
            }
            override fun onMessage(ws: WebSocket, text: String) { frameChannel.trySend(text) }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                logger.w(TAG, "Node WS closed code=$code reason=$reason")
                isConnecting.set(false)
                _state.value = GatewayConnectionState.DISCONNECTED
                if (!isManualStop.get()) scheduleReconnect()
            }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                logger.e(TAG, "Node WS failure: ${t.message}")
                isConnecting.set(false)
                _state.value = GatewayConnectionState.ERROR
                if (!isManualStop.get()) scheduleReconnect()
            }
        })
    }

    private fun buildDeviceField(token: String, nonce: String): Map<String, Any> {
        val deviceId = identity.getDeviceId()
        val signedAt = System.currentTimeMillis()
        // v3|deviceId|clientId|clientMode|role|scopes|signedAtMs|token|nonce|platform|deviceFamily
        val payload = listOf("v3", deviceId, CLIENT_ID, CLIENT_MODE, ROLE, "",
            signedAt.toString(), token, nonce, PLATFORM, DEVICE_FAMILY).joinToString("|")
        return mapOf(
            "id" to deviceId,
            "publicKey" to identity.getPublicKeyBase64Url(),
            "signature" to identity.sign(payload),
            "signedAt" to signedAt,
            "nonce" to nonce
        )
    }

    private fun buildConnectFrame(auth: Map<String, String?>, nonce: String): String {
        val token = auth["bootstrapToken"] ?: auth["deviceToken"] ?: ""
        val params = mapOf(
            "minProtocol" to 3,
            "maxProtocol" to 4,
            "role" to ROLE,
            "scopes" to emptyList<String>(),
            "caps" to CAPS,
            "commands" to COMMANDS,
            "permissions" to emptyMap<String, Any>(),
            "client" to mapOf(
                "id" to CLIENT_ID, "displayName" to "DUQ Android",
                "version" to "1.0.0", "platform" to PLATFORM,
                "deviceFamily" to DEVICE_FAMILY, "mode" to CLIENT_MODE
            ),
            "device" to buildDeviceField(token, nonce),
            "auth" to auth.filterValues { !it.isNullOrEmpty() }
        )
        return gson.toJson(mapOf(
            "type" to "req", "id" to UUID.randomUUID().toString(),
            "method" to "connect", "params" to params
        ))
    }

    private suspend fun consumeFrame(text: String) {
        val raw = gson.fromJson<Map<String, Any?>>(text, object : TypeToken<Map<String, Any?>>() {}.type)
        val event = raw["event"] as? String
        when (event) {
            "tick" -> {}
            "health" -> {}
            // Presence frames are large + frequent (every gateway/backend churn) and
            // carry nothing the node acts on — skip the raw disk dump (battery).
            "presence" -> {}
            else -> logger.d(TAG, "← ${redact(text).take(600)}")
        }
        if (raw["type"] == "event" && event == "connect.challenge") {
            val nonce = (raw["payload"] as? Map<*, *>)?.get("nonce") as? String ?: return
            logger.d(TAG, "Node challenge nonce=${nonce.take(8)}, sending node connect")
            webSocket?.send(buildConnectFrame(currentAuth, nonce))
            return
        }
        handleMessage(raw)
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun handleMessage(raw: Map<String, Any?>) {
        when (raw["type"] as? String) {
            "res" -> {
                val ok = raw["ok"] as? Boolean ?: false
                if (ok) {
                    val payload = raw["payload"] as? Map<String, Any?>
                    if (payload?.get("type") == "hello-ok") onConnected(payload)
                } else {
                    val details = (raw["error"] as? Map<*, *>)?.get("details") as? Map<*, *>
                    val code = details?.get("code") as? String
                    logger.e(TAG, "✗ node res FAILED code=$code reason=${details?.get("reason")}")
                    if (code == "PAIRING_REQUIRED") {
                        _state.value = GatewayConnectionState.PAIRING
                        logger.w(TAG, "Node pairing required — approve with: openclaw nodes approve <reqId> (will retry)")
                        // node connect is retryable; reconnect loop polls until approved
                    }
                }
            }
            "event" -> {
                val name = (raw["event"] ?: raw["name"]) as? String ?: return
                if (name == "node.invoke.request" || name.startsWith("node.invoke")) {
                    handleInvoke(raw["payload"] as? Map<String, Any?> ?: emptyMap())
                }
            }
        }
    }

    private suspend fun onConnected(helloOk: Map<*, *>) {
        reconnectAttempt.set(0)
        isConnecting.set(false)
        _state.value = GatewayConnectionState.CONNECTED
        val auth = helloOk["auth"] as? Map<*, *>
        // Prefer the node-role token from the bootstrap handoff array; fall back to
        // the single deviceToken on a normal node reconnect.
        val nodeToken = (auth?.get("deviceTokens") as? List<*>)
            ?.mapNotNull { it as? Map<*, *> }
            ?.firstOrNull { (it["role"] as? String) == "node" }
            ?.get("deviceToken") as? String
            ?: auth?.get("deviceToken") as? String
        logger.d(TAG, "Node connected — role=${auth?.get("role")} tokenIssued=${!nodeToken.isNullOrBlank()}")
        if (!nodeToken.isNullOrBlank() && nodeToken != settings.getNodeToken()) {
            settings.saveNodeToken(nodeToken)
            logger.d(TAG, "node token persisted")
        }
    }

    /** Execute a forwarded command and reply with node.invoke.result. */
    private suspend fun handleInvoke(payload: Map<String, Any?>) {
        val command = payload["command"] as? String ?: return
        // The gateway keys the invoke by "id" (echoed back in node.invoke.result).
        val invokeId = (payload["id"] ?: payload["requestId"] ?: payload["invokeId"]) as? String ?: ""
        val nodeId = payload["nodeId"] as? String ?: identity.getDeviceId()
        // params arrive either as a "params" object or a "paramsJSON" string.
        val params = (payload["params"] as? Map<*, *>)
            ?: (payload["paramsJSON"] as? String)?.let {
                runCatching { gson.fromJson<Map<String, Any?>>(it, object : TypeToken<Map<String, Any?>>() {}.type) }.getOrNull()
            }
            ?: emptyMap<String, Any?>()
        logger.d(TAG, "node.invoke command=$command id=${invokeId.take(8)}")
        try {
            val result = execute(command, params)
            sendResult(nodeId, invokeId, result, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(TAG, "command $command failed: ${e.message}")
            sendResult(nodeId, invokeId, null, e.message ?: "command failed")
        }
    }

    private suspend fun execute(command: String, params: Map<*, *>): Map<String, Any?> = when (command) {
        "location.get" -> {
            val loc = locationDataSource.getLastLocation() ?: throw Exception("location unavailable")
            mapOf("lat" to loc.latitude, "lon" to loc.longitude,
                "accuracy" to loc.accuracy, "ts" to loc.time)
        }
        "notify.show" -> {
            val title = params["title"] as? String ?: "DUQ"
            val body = params["body"] as? String ?: ""
            // Optional category routes the item into a dedicated inbox section
            // (e.g. "digest" → the 📰 Дайджест feed). Defaults to a normal message.
            val category = (params["category"] as? String ?: params["type"] as? String)
                ?.takeIf { it.isNotBlank() } ?: "message"
            notificationManager.showMessageNotification(body, title, category)
            mapOf("shown" to true)
        }
        "voice.activate" -> {
            // Hands-free capture: record the user's speech (VAD auto-stops on
            // end-of-speech), transcribe via STT, return the text to the bot. A hard
            // cap stops a stuck recording if the user never speaks.
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

    /** Transcribes a WAV: on-device whisper.cpp when enabled, else server /stt fallback. */
    private suspend fun transcribe(file: File): String {
        if (AppConfig.STT_ON_DEVICE) {
            try {
                if (!whisper.isModelReady()) whisper.ensureModel()
                if (whisper.isModelReady()) {
                    val text = whisper.transcribeWav(file)
                    if (text.isNotBlank()) return text
                    logger.w(TAG, "on-device STT empty, falling back to server")
                }
            } catch (e: Exception) {
                logger.w(TAG, "on-device STT failed (${e.message}), falling back to server")
            }
        }
        return transcribeOnServer(file)
    }

    /** Uploads a WAV to the server STT endpoint and returns the transcript text. */
    private suspend fun transcribeOnServer(file: File): String = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", "whisper-1")
            .addFormDataPart("language", "ru")
            .addFormDataPart("file", file.name, file.asRequestBody("audio/wav".toMediaType()))
            .build()
        val req = Request.Builder().url(AppConfig.STT_URL).post(body).build()
        sttClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("STT ${resp.code}")
            val text = JSONObject(resp.body?.string() ?: "{}").optString("text", "")
            if (text.isBlank()) throw Exception("empty transcript") else text
        }
    }

    private fun sendResult(nodeId: String, invokeId: String, payload: Map<String, Any?>?, error: String?) {
        val ws = webSocket ?: return
        // Schema requires "id" (the invoke id) and "ok"; not "requestId".
        val params = mutableMapOf<String, Any?>("id" to invokeId, "nodeId" to nodeId, "ok" to (error == null))
        if (error != null) params["error"] = mapOf("message" to error)
        else params["payload"] = (payload ?: emptyMap<String, Any?>())
        val frame = gson.toJson(mapOf(
            "type" to "req", "id" to UUID.randomUUID().toString(),
            "method" to "node.invoke.result", "params" to params
        ))
        val enqueued = ws.send(frame)
        logger.d(TAG, "→ node.invoke.result id=${invokeId.take(8)} error=${error != null} enqueued=$enqueued")
    }

    private fun scheduleReconnect() {
        scope.launch {
            val attempt = reconnectAttempt.getAndIncrement()
            val delay = (AppConfig.INITIAL_RETRY_DELAY_MS * 2.0.pow(attempt)).toLong()
                .coerceAtMost(AppConfig.MAX_RETRY_DELAY_MS)
            delay(delay)
            if (!isManualStop.get()) connect()
        }
    }

    private fun redact(s: String): String =
        s.replace(Regex("(\"(?:deviceToken|token|bootstrapToken|signature|publicKey)\"\\s*:\\s*\")[^\"]+(\")"), "$1***$2")
}
