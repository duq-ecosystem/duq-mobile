package com.duq.android.util

import java.io.File

actual fun readFileBytes(path: String): ByteArray {
    val f = File(path)
    return if (f.exists() && f.length() > 0L) f.readBytes() else ByteArray(0)
}
