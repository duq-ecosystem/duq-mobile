package com.duq.android.util

/**
 * Reads a local file into memory. Platform-backed (Android: File; iOS: NSData) —
 * commonMain has no portable filesystem, so the byte read is delegated like
 * [nowMillis]. Used by the server STT fallback (multipart upload of a recorded WAV)
 * and any commonMain code that needs raw file content.
 *
 * Returns an empty array if the file is missing/unreadable (caller treats that as
 * "nothing to upload").
 */
expect fun readFileBytes(path: String): ByteArray

/** The trailing path segment (filename) — for multipart form-data part names. */
fun fileNameOf(path: String): String =
    path.substringAfterLast('/').substringAfterLast('\\').ifBlank { "audio.wav" }
