package com.duq.android.network

import com.duq.android.config.AppConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.submitForm
import io.ktor.http.Parameters
import io.ktor.http.isSuccess

/**
 * Text-to-speech client for contextual voice replies (multiplatform, Ktor). POSTs reply
 * text to the server's Silero TTS endpoint and returns the WAV **bytes** to play.
 *
 * Returns the audio as a ByteArray rather than a File: commonMain has no portable
 * filesystem, so the caller/platform decides where (if anywhere) to persist it.
 * Edge-token (X-Auth-Token) rides the client's DefaultRequest (see DuqHttpClient).
 */
class TtsClient(private val http: HttpClient) {

    /**
     * Returns WAV bytes with [text] spoken, or null on a non-success / empty response.
     * [messageId] is kept in the signature for parity with on-device TTS and lets the
     * caller key a filename when it writes the bytes out.
     */
    @Suppress("UnusedParameter")
    suspend fun synthesize(text: String, messageId: String): ByteArray? {
        val resp = http.submitForm(
            url = AppConfig.TTS_URL,
            formParameters = Parameters.build { append("text", text) },
        )
        if (!resp.status.isSuccess()) return null
        val bytes = resp.body<ByteArray>()
        return bytes.takeIf { it.isNotEmpty() }
    }
}
