package com.duq.android.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Разбор WAV-контейнера в float32 PCM. Чистая утилита формата — отдельно от STT-движка
 * (`WhisperLocal`), чтобы тот не смешивал распознавание с парсингом аудио (SRP).
 */
object WavDecoder {
    private const val WAV_HEADER_BYTES = 44

    /** WAV 16 kHz mono PCM16 → float32 [-1,1]. Пропускает 44-байтный заголовок. */
    fun decodePcm16Mono(file: File): FloatArray {
        val bytes = file.readBytes()
        if (bytes.size <= WAV_HEADER_BYTES) return FloatArray(0)
        val pcm = ByteBuffer
            .wrap(bytes, WAV_HEADER_BYTES, bytes.size - WAV_HEADER_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray((bytes.size - WAV_HEADER_BYTES) / 2)
        var i = 0
        while (pcm.remaining() >= 2) { out[i++] = pcm.short / 32768.0f }
        return out
    }
}
