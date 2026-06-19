package com.duq.android.network.openclaw

import com.duq.android.BuildConfig
import com.duq.android.auth.DeviceIdentityManager
import com.duq.android.config.AppConfig
import com.duq.android.data.SettingsRepository
import com.duq.android.logging.Logger
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.pow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OpenClawGatewayClient @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val deviceIdentity: DeviceIdentityManager,
    private val whisper: com.duq.android.audio.WhisperLocal,
    private val logger: Logger
) {
    companion object {
        private const val TAG = "OcGateway"
        private const val CLIENT_ID = "openclaw-android"
        private const val CLIENT_MODE = "ui"
        private const val PLATFORM = "android"
        private const val DEVICE_FAMILY = "mobile"
        private const val OPERATOR_ROLE = "operator"
        private val OPERATOR_SCOPES = listOf("operator.read", "operator.write")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson: Gson = GsonBuilder().create()

    // Written from connect()/stop() on different threads, read from request()/consumer.
    @Volatile private var webSocket: WebSocket? = null
    private val isManualStop = AtomicBoolean(false)
    private val isConnecting = AtomicBoolean(false)
    private val reconnectAttempt = AtomicInteger(0)

    // Which agent the chat is currently bound to. Default "main" (the operator chat).
    // The user can switch (agent picker) to view/talk to other agents (strain, digest…).
    //
    // chatKey() uses the CANONICAL session key "agent:<id>:main" — the exact form the
    // gateway stores sessions under and BROADCASTS chat/agent frames with. We speak it
    // everywhere (send, subscribe, history AND the inbound filter) so the keys match
    // on both directions. The server normalizes any raw inbound key (loadSessionEntry →
    // canonicalKey), so sending the canonical form is always accepted.
    //
    // History: the client used to send bare "main". The server accepted it (normalized),
    // but broadcast frames came back as "agent:main:main" and the inbound filter
    // (sk != "main") silently dropped them → the assistant's reply never rendered and
    // the bubble spun forever (fixed 2026-06-19). Both chat AND agent-step frames are
    // matched against chatKey() EXACTLY (not a prefix), so sub-sessions like
    // "agent:main:main:active-memory:<id>" never leak their tool-steps into the chat.
    @Volatile var activeAgentId: String = "main"
        private set
    private fun chatKey() = "agent:$activeAgentId:main"

    // OkHttp delivers onMessage serially on its reader thread, but we must NOT fan
    // out to scope.launch{} per frame — that hands frames to Dispatchers.IO's thread
    // pool and reorders them, so chat deltas/finals arrive scrambled (incomplete
    // final + orphan streaming bubbles). Instead push raw frames into an unbounded
    // ordered channel and process them in ONE consumer coroutine, preserving order.
    private val frameChannel = Channel<String>(Channel.UNLIMITED)
    private val consumerStarted = AtomicBoolean(false)

    private fun ensureFrameConsumer() {
        if (!consumerStarted.compareAndSet(false, true)) return
        scope.launch {
            for (text in frameChannel) {
                try {
                    consumeFrame(text)
                } catch (e: CancellationException) {
                    throw e  // never swallow cancellation — lets the consumer stop cleanly
                } catch (e: Exception) {
                    logger.e(TAG, "consumeFrame: ${e.message}")
                }
            }
        }
    }

    /** Single ordered handler for every inbound frame on the persistent connection. */
    private suspend fun consumeFrame(text: String) {
        val raw = gson.fromJson<Map<String, Any?>>(text, object : TypeToken<Map<String, Any?>>() {}.type)
        val event = raw["event"] as? String
        // Periodic noise (health/tick) floods the pullable log and buries the chat
        // exchange. Log it compactly; log everything else in full (redacted).
        when (event) {
            "tick" -> { /* heartbeat — skip logging entirely */ }
            "health" -> logger.d(TAG, "← health ok")
            // Agent tool/command steps are high-volume (one cron digest run = hundreds
            // of frames) and are already surfaced compactly as OcAgentStep below. Dumping
            // each raw 600-char frame to disk was a real background CPU/IO battery drain —
            // skip the raw dump; handleMessage still processes them into steps.
            "agent" -> { /* logged compactly as OcAgentStep in handleMessage */ }
            "presence" -> { /* large + frequent, nothing actionable — skip raw dump */ }
            "cron" -> {
                val p = raw["payload"] as? Map<*, *>
                logger.d(TAG, "← cron ${p?.get("action")} ${(p?.get("job") as? Map<*, *>)?.get("name")}")
            }
            // Полный дамп каждого фрейма на диск — заметный фоновый IO/батарея.
            // В release пишем компактную трассу (тип + event/id), в debug — полный фрейм.
            else -> if (BuildConfig.DEBUG) logger.d(TAG, "← frame ${redactSecrets(text).take(600)}")
                    else logger.d(TAG, "← ${raw["type"]} ${event ?: (raw["id"] as? String)?.take(8) ?: ""}")
        }
        if (raw["type"] == "event" && event == "connect.challenge") {
            val nonce = (raw["payload"] as? Map<*, *>)?.get("nonce") as? String ?: return
            logger.d(TAG, "Got challenge nonce=${nonce.take(8)}, sending connect")
            webSocket?.send(buildConnectFrame(currentConnectAuth, nonce))
            return
        }
        handleMessage(raw)
    }

    // Auth used by the latest connect() — read by the frame consumer when the
    // server's connect.challenge arrives.
    @Volatile private var currentConnectAuth: Map<String, String?> = emptyMap()

    private val pending = ConcurrentHashMap<String, CompletableDeferred<Map<String, Any?>>>()

    private val _connectionState = MutableStateFlow(GatewayConnectionState.DISCONNECTED)
    val connectionState: StateFlow<GatewayConnectionState> = _connectionState

    private val _chatEvents = MutableSharedFlow<OcChatEvent>(extraBufferCapacity = 128)
    val chatEvents: SharedFlow<OcChatEvent> = _chatEvents

    // Agent tool/command steps (multi-step progress visibility).
    private val _agentSteps = MutableSharedFlow<OcAgentStep>(extraBufferCapacity = 128)
    val agentSteps: SharedFlow<OcAgentStep> = _agentSteps

    // For pairing flow
    private val _pairResolved = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 1) // requestId, decision
    val pairResolved: SharedFlow<Pair<String, String>> = _pairResolved

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(AppConfig.CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    }

    fun start() {
        if (isManualStop.get()) isManualStop.set(false)
        if (_connectionState.value == GatewayConnectionState.CONNECTED) return
        scope.launch { connect() }
    }

    fun stop() {
        isManualStop.set(true)
        webSocket?.close(1000, "stopped")
        webSocket = null
        _connectionState.value = GatewayConnectionState.DISCONNECTED
    }

    private fun buildDeviceField(token: String, challengeNonce: String): Map<String, Any> {
        val deviceId = deviceIdentity.getDeviceId()  // SHA256(pubkey).hex — derived, not stored
        // device.nonce MUST equal the server's challenge nonce (anti-replay)
        val signedAt = System.currentTimeMillis()
        // operator role + read/write scopes: chat (chat.send / sessions.messages.subscribe) is an
        // operator surface — a "node" connection is rejected ("unauthorized role: node").
        val scopes = OPERATOR_SCOPES.joinToString(",")
        val role = OPERATOR_ROLE
        // v3|deviceId|clientId|clientMode|role|scopes|signedAtMs|token|nonce|platform|deviceFamily
        val payload = listOf("v3", deviceId, CLIENT_ID, CLIENT_MODE, role, scopes,
            signedAt.toString(), token, challengeNonce, PLATFORM, DEVICE_FAMILY).joinToString("|")
        val signature = deviceIdentity.sign(payload)
        val publicKey = deviceIdentity.getPublicKeyBase64Url()
        return mapOf("id" to deviceId, "publicKey" to publicKey,
            "signature" to signature, "signedAt" to signedAt, "nonce" to challengeNonce)
    }

    private fun buildConnectFrame(auth: Map<String, String?>, challengeNonce: String): String {
        val token = auth["bootstrapToken"] ?: auth["deviceToken"] ?: ""
        val authMap = auth.filterValues { !it.isNullOrEmpty() }
        // Gateway expects req-frame: {type:"req", method:"connect", params:{...}}
        val params = mapOf(
            // Gateway protocol is v4 (server pinned 2026.6.x); declare the real
            // range we speak instead of 1..99. Server rejects ranges excluding its
            // current protocol, and 1..99 was dishonest negotiation.
            "minProtocol" to 3,
            "maxProtocol" to 4,
            "role" to OPERATOR_ROLE,        // chat is an operator surface, not node
            "scopes" to OPERATOR_SCOPES,    // must match the signed payload scopes
            "client" to mapOf(
                "id" to CLIENT_ID, "displayName" to "DUQ Android",
                "version" to "1.0.0", "platform" to PLATFORM,
                "deviceFamily" to DEVICE_FAMILY, "mode" to CLIENT_MODE
            ),
            "device" to buildDeviceField(token, challengeNonce),
            "auth" to authMap
        )
        return gson.toJson(mapOf(
            "type" to "req",
            "id" to UUID.randomUUID().toString(),
            "method" to "connect",
            "params" to params
        ))
    }

    private suspend fun connect() {
        if (!isConnecting.compareAndSet(false, true)) return

        val url = settingsRepository.getGatewayUrl()
        val deviceToken = settingsRepository.getDeviceToken()

        _connectionState.value = GatewayConnectionState.CONNECTING
        logger.d(TAG, "Connecting to $url (paired=${deviceToken.isNotBlank()})")

        val request = Request.Builder().url(url).build()
        val connectAuth = if (deviceToken.isNotBlank()) mapOf("deviceToken" to deviceToken) else mapOf()
        currentConnectAuth = connectAuth
        ensureFrameConsumer()
        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                // Wait for connect.challenge — don't send connect frame yet
                logger.d(TAG, "WS open, waiting for challenge...")
            }

            override fun onMessage(ws: WebSocket, text: String) {
                // Enqueue in arrival order; a single consumer preserves WS ordering.
                // trySend never blocks the reader thread (channel is UNLIMITED).
                frameChannel.trySend(text)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                logger.w(TAG, "WS closed code=$code reason=$reason")
                isConnecting.set(false)
                _connectionState.value = GatewayConnectionState.DISCONNECTED
                failPending("closed")
                if (!isManualStop.get()) scheduleReconnect()
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                logger.e(TAG, "WS failure: ${t.message}")
                isConnecting.set(false)
                _connectionState.value = GatewayConnectionState.ERROR
                failPending(t.message ?: "failure")
                if (!isManualStop.get()) scheduleReconnect()
            }
        })
    }

    /** Initiates device pairing with a bootstrap token from QR scan */
    suspend fun startPairing(gatewayUrl: String, bootstrapToken: String): Boolean {
        settingsRepository.saveGatewayUrl(gatewayUrl)
        settingsRepository.saveBootstrapToken(bootstrapToken)  // reused for node pairing
        isManualStop.set(false)
        _connectionState.value = GatewayConnectionState.PAIRING

        val request = Request.Builder().url(gatewayUrl).build()
        var pairingWs: WebSocket? = null
        val result = CompletableDeferred<Boolean>()
        // When PAIRING_REQUIRED, we intentionally close this socket and hand off
        // to the poll loop — onClosed must NOT then complete the result as false
        // (that caused a spurious "Failed" flash while pairing actually continued).
        // AtomicBoolean for cross-thread visibility: the flag is set on the IO
        // coroutine but read on OkHttp's onClosed callback thread.
        val handedOffToRetry = AtomicBoolean(false)

        pairingWs = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                // Wait for connect.challenge before sending connect frame
                logger.d(TAG, "Pairing WS open, waiting for challenge...")
            }

            override fun onMessage(ws: WebSocket, text: String) {
                scope.launch {
                    try {
                        val raw = gson.fromJson<Map<String, Any?>>(text, object : TypeToken<Map<String, Any?>>() {}.type)
                        // Handle challenge first
                        if (raw["type"] == "event" && raw["event"] == "connect.challenge") {
                            val nonce = (raw["payload"] as? Map<*, *>)?.get("nonce") as? String ?: return@launch
                            logger.d(TAG, "Pairing got challenge, sending connect with bootstrapToken")
                            ws.send(buildConnectFrame(mapOf("bootstrapToken" to bootstrapToken), nonce))
                            return@launch
                        }
                        when (raw["type"] as? String) {
                            "res" -> {
                                val ok = raw["ok"] as? Boolean ?: false
                                if (ok) {
                                    // res.payload = hello-ok with auth.deviceToken
                                    val payload = raw["payload"] as? Map<*, *>
                                    val auth = payload?.get("auth") as? Map<*, *>
                                    val token = auth?.get("deviceToken") as? String
                                    if (!token.isNullOrBlank()) {
                                        settingsRepository.saveDeviceToken(token)
                                        pairingWs?.close(1000, "paired")
                                        result.complete(true)
                                    } else {
                                        pairingWs?.close(1000, "no-token")
                                        result.complete(false)
                                    }
                                } else {
                                    val details = (raw["error"] as? Map<*, *>)?.get("details") as? Map<*, *>
                                    val code = details?.get("code") as? String
                                    if (code == "PAIRING_REQUIRED") {
                                        // Device registered as pending — close and retry with same token.
                                        // Mark hand-off so onClosed doesn't complete result=false.
                                        logger.d(TAG, "Pairing pending, will retry with same bootstrapToken")
                                        handedOffToRetry.set(true)
                                        pairingWs?.close(1000, "pending")
                                        // Retry loop: poll until approved (max 5 min)
                                        delay(5000)
                                        val retryResult = retryPairingBootstrap(gatewayUrl, bootstrapToken)
                                        result.complete(retryResult)
                                    } else {
                                        logger.e(TAG, "Pairing failed: $code")
                                        pairingWs?.close(1000, "failed")
                                        result.complete(false)
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) { logger.e(TAG, "Pairing message error: ${e.message}") }
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                result.completeExceptionally(t)
            }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                // Don't fail the result if we deliberately closed to hand off to
                // the retry/poll loop — that loop owns the final result.
                if (!handedOffToRetry.get() && !result.isCompleted) result.complete(false)
            }
        })

        return try {
            withTimeout(300_000L) { result.await() } // 5 min timeout for approval
        } catch (e: Exception) {
            pairingWs?.close(1000, "timeout")
            false
        }
    }

    /** Poll with same bootstrapToken until approved (max 60 attempts × 5s = 5min) */
    private suspend fun retryPairingBootstrap(gatewayUrl: String, bootstrapToken: String): Boolean {
        repeat(60) { attempt ->
            delay(5000)
            val retryResult = CompletableDeferred<Boolean?>()
            val req = Request.Builder().url(gatewayUrl).build()
            httpClient.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {}
                override fun onMessage(ws: WebSocket, text: String) {
                    scope.launch {
                        try {
                            val raw = gson.fromJson<Map<String, Any?>>(text, object : TypeToken<Map<String, Any?>>() {}.type)
                            if (raw["type"] == "event" && raw["event"] == "connect.challenge") {
                                val nonce = (raw["payload"] as? Map<*, *>)?.get("nonce") as? String ?: return@launch
                                ws.send(buildConnectFrame(mapOf("bootstrapToken" to bootstrapToken), nonce))
                                return@launch
                            }
                            if (raw["type"] == "res") {
                                val ok = raw["ok"] as? Boolean ?: false
                                if (ok) {
                                    // res.payload = hello-ok with auth.deviceToken
                                    val payload = raw["payload"] as? Map<*, *>
                                    val auth = payload?.get("auth") as? Map<*, *>
                                    val token = auth?.get("deviceToken") as? String
                                    if (!token.isNullOrBlank()) settingsRepository.saveDeviceToken(token)
                                    retryResult.complete(!token.isNullOrBlank())
                                } else {
                                    val code = ((raw["error"] as? Map<*,*>)?.get("details") as? Map<*,*>)?.get("code") as? String
                                    retryResult.complete(if (code == "PAIRING_REQUIRED") null else false)
                                }
                                ws.close(1000, "done")
                            }
                        } catch (e: Exception) { retryResult.complete(null) }
                    }
                }
                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) { retryResult.complete(null) }
                override fun onClosed(ws: WebSocket, code: Int, reason: String) { if (!retryResult.isCompleted) retryResult.complete(null) }
            })
            val r = withTimeoutOrNull(10_000L) { retryResult.await() }
            if (r == true) return true
            if (r == false) return false
            logger.d(TAG, "Pairing attempt $attempt: still pending")
        }
        return false
    }

    private suspend fun onConnectSuccess(raw: Map<*, *>) {
        reconnectAttempt.set(0)
        isConnecting.set(false)
        _connectionState.value = GatewayConnectionState.CONNECTED
        val auth = raw["auth"] as? Map<*, *>
        // Prefer a bounded operator token from auth.deviceTokens[] — the bootstrap
        // handoff returns a node primary token plus an operator token in that array.
        // Fall back to the single auth.deviceToken (normal device-pair reconnect).
        val operatorToken = (auth?.get("deviceTokens") as? List<*>)
            ?.mapNotNull { it as? Map<*, *> }
            ?.firstOrNull { (it["role"] as? String) == "operator" }
            ?.get("deviceToken") as? String
        val newToken = operatorToken ?: auth?.get("deviceToken") as? String
        // The bootstrap handoff also issues a NODE token in the same array. Capture
        // it so the node session (bot→phone) can authenticate without a separate
        // bootstrap (device tokens are role-bound: operator token ≠ node token).
        val nodeToken = (auth?.get("deviceTokens") as? List<*>)
            ?.mapNotNull { it as? Map<*, *> }
            ?.firstOrNull { (it["role"] as? String) == "node" }
            ?.get("deviceToken") as? String
        logger.d(TAG, "Connected successfully — role=${auth?.get("role")} scopes=${auth?.get("scopes")} " +
            "tokenIssued=${!newToken.isNullOrBlank()} fromDeviceTokens=${operatorToken != null} nodeToken=${!nodeToken.isNullOrBlank()}")
        if (!newToken.isNullOrBlank() && newToken != settingsRepository.getDeviceToken()) {
            settingsRepository.saveDeviceToken(newToken)
            logger.d(TAG, "device token persisted")
        }
        if (!nodeToken.isNullOrBlank() && nodeToken != settingsRepository.getNodeToken()) {
            settingsRepository.saveNodeToken(nodeToken)
            logger.d(TAG, "node token persisted (from bootstrap handoff)")
        }
        // Must NOT await here: onConnectSuccess runs inside the single frame
        // consumer, and request() awaits the subscribe `res` — which only that
        // same consumer can deliver. Awaiting inline would deadlock. Fire it on
        // the shared scope so the consumer stays free to process the response.
        scope.launch { request("sessions.messages.subscribe", mapOf("key" to chatKey())) }
    }

    /**
     * Switch the chat to another agent (e.g. "strain", "digest") or back to "main".
     * Unsubscribes the old session, subscribes the new one. The caller (ViewModel)
     * reloads chat.history for the new agent. No-op if already on that agent.
     */
    fun switchAgent(agentId: String) {
        if (agentId == activeAgentId) return
        val old = chatKey()
        activeAgentId = agentId
        val new = chatKey()
        logger.d(TAG, "switchAgent $old -> $new")
        scope.launch {
            runCatching { request("sessions.messages.unsubscribe", mapOf("key" to old)) }
            request("sessions.messages.subscribe", mapOf("key" to new))
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun handleMessage(raw: Map<String, Any?>) {
        try {
            when (raw["type"] as? String) {
                "hello-ok" -> {
                    // Legacy hello-ok (kept for compatibility)
                    onConnectSuccess(raw)
                }
                "res" -> {
                    val id = raw["id"] as? String ?: return
                    val ok = raw["ok"] as? Boolean ?: false
                    if (ok) {
                        // Connect success: payload contains hello-ok with auth
                        val payload = raw["payload"] as? Map<String, Any?>
                        if (payload?.get("type") == "hello-ok") {
                            onConnectSuccess(payload)
                        } else {
                            logger.d(TAG, "✓ res id=${id.take(8)} ok")
                            pending.remove(id)?.complete(payload ?: emptyMap())
                        }
                    } else {
                        // Surface the gateway error instead of swallowing it. The spec
                        // carries error.details.{code,reason,recommendedNextStep}.
                        val error = raw["error"] as? Map<*, *>
                        val details = error?.get("details") as? Map<*, *>
                        val code = details?.get("code") as? String
                        val reason = details?.get("reason") as? String
                        val nextStep = details?.get("recommendedNextStep") as? String
                        val message = error?.get("message") as? String
                        logger.e(TAG, "✗ res id=${id.take(8)} FAILED code=$code reason=$reason next=$nextStep msg=$message")
                        val deferred = pending.remove(id)
                        if (deferred != null) {
                            deferred.complete(emptyMap())
                        } else {
                            // Orphan failure = the connect handshake itself was rejected
                            // (its id is not tracked in `pending`). Previously this was
                            // dropped silently, leaving a blind reconnect loop. Surface it.
                            logger.e(TAG, "connect rejected (code=$code) — entering ERROR state")
                            _connectionState.value = GatewayConnectionState.ERROR
                        }
                    }
                }
                "event" -> {
                    // Gateway names events in the "event" field (value e.g. "chat",
                    // not "chat."). Reading "name"/"chat." dropped every reply.
                    val name = (raw["event"] ?: raw["name"]) as? String ?: return
                    val p = raw["payload"] as? Map<*, *> ?: return
                    when {
                        name == "chat" || name.startsWith("chat.") -> {
                            // Gateway broadcasts ALL sessions to operators. The phone's
                            // chat IS the "main" session — ignore other sessions (digest
                            // cron, strain, etc.) so their activity doesn't leak into chat.
                            val sk = p["sessionKey"] as? String ?: ""
                            if (sk != chatKey()) { logger.d(TAG, "skip chat for session=$sk"); return }
                            val event = OcChatEvent(
                                runId = p["runId"] as? String ?: return,
                                sessionKey = sk,
                                seq = (p["seq"] as? Double)?.toInt() ?: 0,
                                state = p["state"] as? String ?: return,
                                deltaText = p["deltaText"] as? String,
                                fullText = extractMessageText(p["message"]),
                                errorMessage = p["errorMessage"] as? String,
                                stopReason = p["stopReason"] as? String
                            )
                            logger.d(TAG, "chat event runId=${event.runId.take(8)} state=${event.state} " +
                                "seq=${event.seq} deltaLen=${event.deltaText?.length ?: 0} " +
                                "fullLen=${event.fullText?.length ?: 0}")
                            _chatEvents.emit(event)
                        }
                        name == "agent" -> {
                            // Tool-steps belong in the chat ONLY for the exact chat session
                            // (same key as the text frames). Match chatKey() precisely — NOT
                            // a prefix — otherwise SUB-sessions leak their steps:
                            //  - active-memory's blocking recall sub-agent runs under
                            //    "agent:main:main:active-memory:<id>" — its chat text is
                            //    correctly skipped, but with a prefix match its memory_search
                            //    steps slipped in and created a phantom "Использовал инструменты"
                            //    bubble that never got a final → spun forever (fixed 2026-06-19).
                            //  - other agents (digest cron "agent:digest:cron:…", strain) also
                            //    broadcast to operators; exact match drops them too.
                            val sk = p["sessionKey"] as? String ?: ""
                            if (sk != chatKey()) { logger.d(TAG, "skip agent step for session=$sk"); return }
                            // Multi-step progress: tool calls / shell commands the
                            // agent runs mid-reply. Only "item" frames describe steps.
                            if (p["stream"] as? String == "item") {
                                val data = p["data"] as? Map<*, *>
                                val kind = data?.get("kind") as? String
                                if (data != null && (kind == "tool" || kind == "command")) {
                                    _agentSteps.emit(
                                        OcAgentStep(
                                            runId = p["runId"] as? String ?: "",
                                            itemId = data["itemId"] as? String ?: "",
                                            kind = kind,
                                            title = (data["title"] as? String).orEmpty(),
                                            status = (data["status"] as? String).orEmpty(),
                                            phase = (data["phase"] as? String).orEmpty()
                                        )
                                    )
                                }
                            }
                        }
                        name == "device.pair.resolved" -> {
                            val requestId = p["requestId"] as? String ?: ""
                            val decision = p["decision"] as? String ?: ""
                            _pairResolved.emit(requestId to decision)
                        }
                    }
                }
                "tick" -> { /* heartbeat */ }
            }
        } catch (e: Exception) { logger.e(TAG, "handleMessage: ${e.message}") }
    }

    /**
     * Pulls the cumulative assistant text out of a chat payload's `message`.
     * content is either a plain String (user echoes) or a list of
     * {type:"text", text:"…"} blocks (assistant). Returns null when absent so
     * the caller can fall back to delta accumulation.
     */
    private fun extractMessageText(message: Any?): String? {
        val content = (message as? Map<*, *>)?.get("content") ?: return null
        return when (content) {
            is String -> content
            is List<*> -> content
                .mapNotNull { (it as? Map<*, *>)?.takeIf { m -> m["type"] == "text" }?.get("text") as? String }
                .joinToString("")
                .ifEmpty { null }
            else -> null
        }
    }

    suspend fun sendMessage(text: String) {
        // Gateway method is "chat.send" with param "sessionKey" (not "sessions.send"/"key").
        request("chat.send", mapOf("sessionKey" to chatKey(), "message" to text,
            "idempotencyKey" to UUID.randomUUID().toString()))
    }

    /**
     * Background/system messages (e.g. location updates) ALWAYS go to the MAIN agent,
     * never to whichever agent the UI is currently viewing. They carry global user
     * context (USER.md / memory) owned by `main`. Routing them via chatKey() would
     * misdeliver to "agent:digest:main" etc. if the user switched agents in the UI —
     * the same wrong-channel class of bug as the global heartbeat. Hardcode the
     * canonical main session key, independent of activeAgentId.
     */
    suspend fun sendMessageToMainAgent(text: String) {
        request("chat.send", mapOf("sessionKey" to "agent:main:main", "message" to text,
            "idempotencyKey" to UUID.randomUUID().toString()))
    }

    /**
     * Reports a location update so DUQ (the `main` agent) updates the user context.
     * Runs a REAL background agent turn (gateway method "agent", scope operator.write)
     * in a dedicated session `location-bg` — NOT the main chat (так не светится в чате)
     * and NOT the heartbeat (который silent-by-default и контекст не трогает). A real
     * turn ALWAYS processes the input, so DUQ reliably resolves the city and writes
     * USER.md «Локация» (USER.md/persona/skills are shared across the main agent's
     * sessions). No channel delivery — context-update only.
     */
    suspend fun reportLocationToMainAgent(text: String) {
        request("agent", mapOf(
            "message" to text,
            "agentId" to "main",
            "sessionKey" to "location-bg",
            // gateway "agent" метод требует idempotencyKey (как chat.send) — без него
            // запрос отклоняется: "invalid agent params: must have required property 'idempotencyKey'".
            "idempotencyKey" to UUID.randomUUID().toString()
        ))
    }

    /**
     * Server-side chat history for the "main" session. The gateway is the single
     * source of truth for the transcript (shared across devices), so the client
     * restores it on startup instead of keeping its own local copy. `chat.history`
     * is already display-normalized server-side.
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun fetchHistory(limit: Int = 100): List<OcHistoryMsg> {
        val payload = request("chat.history", mapOf("sessionKey" to chatKey(), "limit" to limit))
        val rows = payload["messages"] as? List<*> ?: run {
            logger.w(TAG, "chat.history: no 'messages' in payload keys=${payload.keys}")
            return emptyList()
        }
        val out = rows.mapNotNull { row ->
            val m = row as? Map<*, *> ?: return@mapNotNull null
            val role = m["role"] as? String ?: return@mapNotNull null
            // Rows mirror the chat-frame `message` shape: text is in `content`
            // (a String or a list of {type:"text",text}) — directly on the row or
            // nested under `message`. Reuse the same extractor; fall back to plain text.
            val text = extractMessageText(m["message"] ?: m)
                ?: (m["text"] as? String)
                ?: return@mapNotNull null
            if (text.isBlank()) null else OcHistoryMsg(role, text)
        }
        logger.d(TAG, "chat.history: parsed ${out.size}/${rows.size} rows")
        return out
    }

    suspend fun transcribeAudio(file: File): String {
        // On-device whisper.cpp when enabled; on any failure fall back to server /stt.
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
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", "whisper-1")
            .addFormDataPart("language", "ru")
            .addFormDataPart("file", file.name, file.asRequestBody("audio/wav".toMediaType()))
            .build()
        val req = Request.Builder().url(AppConfig.STT_URL).post(body).build()
        // Reuse the shared client's connection pool + dispatcher; only override the
        // read timeout for this call. Building a fresh OkHttpClient per request
        // leaked a thread pool + connection pool on every voice command.
        val sttClient = httpClient.newBuilder()
            .readTimeout(AppConfig.READ_TIMEOUT_S, TimeUnit.SECONDS).build()
        return withContext(Dispatchers.IO) {
            val response = sttClient.newCall(req).execute()
            if (!response.isSuccessful) throw Exception("STT ${response.code}")
            val result = gson.fromJson<Map<String, Any?>>(response.body?.string() ?: "{}",
                object : TypeToken<Map<String, Any?>>() {}.type)
            result["text"] as? String ?: throw Exception("No text in STT response")
        }
    }

    /**
     * Public WS-RPC entry point for the control panel (Пульт). Calls any gateway
     * method and returns its `result` payload as a map (empty on failure). Thin
     * wrapper over [request] so section view-models can hit `agents.list`,
     * `cron.list`, `channels.status`, `node.list`, etc. without re-implementing
     * the connect/retry/correlation plumbing.
     */
    suspend fun rpc(method: String, params: Any? = null): Map<String, Any?> = request(method, params)

    private suspend fun request(method: String, params: Any? = null): Map<String, Any?> {
        // Ensure a live connection first. Previously this silently returned when the
        // socket had churned/dropped, so chat.send vanished with no error or log.
        if (webSocket == null || _connectionState.value != GatewayConnectionState.CONNECTED) {
            logger.w(TAG, "req $method: not connected (state=${_connectionState.value}) — reconnecting")
            start()
            val ready = withTimeoutOrNull(10_000L) {
                connectionState.first { it == GatewayConnectionState.CONNECTED }
            }
            if (ready == null) {
                logger.e(TAG, "req $method: still not connected after wait — dropped")
                return emptyMap()
            }
        }
        val ws = webSocket ?: return emptyMap()
        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<Map<String, Any?>>()
        pending[id] = deferred
        val frame = gson.toJson(mapOf("type" to "req", "id" to id, "method" to method, "params" to params))
        // OkHttp WebSocket.send() returns false when the socket is closing/closed
        // (the silent-drop cause). Force a reconnect and resend once on false.
        var enqueued = ws.send(frame)
        logger.d(TAG, "→ req $method id=${id.take(8)} enqueued=$enqueued")
        if (!enqueued) {
            pending.remove(id)
            logger.w(TAG, "req $method: send rejected (socket dead) — reconnecting and resending")
            _connectionState.value = GatewayConnectionState.DISCONNECTED
            start()
            val ready = withTimeoutOrNull(10_000L) {
                connectionState.first { it == GatewayConnectionState.CONNECTED }
            } ?: run { logger.e(TAG, "req $method: resend gave up"); return emptyMap() }
            val ws2 = webSocket ?: return emptyMap()
            pending[id] = deferred
            enqueued = ws2.send(frame)
            logger.d(TAG, "→ req $method (resend) enqueued=$enqueued")
            if (!enqueued) { pending.remove(id); return emptyMap() }
        }
        return try {
            deferred.await()
        } catch (e: CancellationException) {
            throw e  // real coroutine cancellation must propagate
        } catch (e: Exception) {
            // deferred.completeExceptionally(...) from failPending() on socket close —
            // surface it instead of letting it escape to the caller's scope.
            logger.w(TAG, "req $method: deferred failed (${e.message})"); pending.remove(id); emptyMap()
        }
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

    /** Masks token/key/signature values so secrets never land in the pullable log file. */
    private fun redactSecrets(s: String): String =
        s.replace(
            Regex("(\"(?:deviceToken|token|bootstrapToken|signature|publicKey)\"\\s*:\\s*\")[^\"]+(\")"),
            "$1***$2"
        )

    private fun failPending(reason: String) {
        pending.keys().toList().forEach { id -> pending.remove(id)?.completeExceptionally(Exception(reason)) }
    }
}
