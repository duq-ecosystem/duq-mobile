package com.duq.android.network

import android.content.Context
import com.duq.android.config.AppConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Text-to-speech client for contextual voice replies. POSTs reply text to the
 * server's Silero TTS endpoint and returns a WAV file to play.
 *
 * HTTP/1.1 is pinned + a hard callTimeout is set: OkHttp's HTTP/2 stream can
 * stall indefinitely on larger audio bodies behind nginx (same issue fixed in
 * the APK self-updater), and the per-read timeout never fires.
 */
@Singleton
class TtsClient @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val client = OkHttpClient.Builder()
        .withDuqDns()
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectTimeout(10, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()

    /**
     * Returns a WAV file with [text] spoken, keyed by [messageId] for a unique
     * filename (avoids clobbering audio still being played). Old TTS files are
     * purged first so synthesized speech doesn't accumulate in the cache.
     */
    suspend fun synthesize(text: String, messageId: String): File? = withContext(Dispatchers.IO) {
        context.cacheDir.listFiles { f -> f.name.startsWith(TTS_PREFIX) }?.forEach { it.delete() }
        val body = FormBody.Builder().add("text", text).build()
        val request = Request.Builder().url(AppConfig.TTS_URL).withServerAuth().post(body).build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext null
            val bytes = resp.body?.bytes() ?: return@withContext null
            if (bytes.isEmpty()) return@withContext null
            val out = File(context.cacheDir, "$TTS_PREFIX${messageId.take(24)}.wav")
            out.outputStream().use { it.write(bytes) }
            out
        }
    }

    private companion object { const val TTS_PREFIX = "tts_reply_" }
}
