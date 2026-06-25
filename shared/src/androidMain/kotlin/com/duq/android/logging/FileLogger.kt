package com.duq.android.logging

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Logger that mirrors every entry to BOTH logcat and a rotating file under the
 * app's external files dir, so the full WS/protocol exchange stays observable:
 *   - on release builds,
 *   - while the app runs in the background,
 *   - on OEM ROMs (MIUI/HyperOS) that throttle third-party logcat output.
 *
 * Pull the logs with:
 *   adb pull /sdcard/Android/data/com.duq.android/files/logs/
 *
 * Writes happen on a single background thread so logging never blocks callers
 * and never throws into the call site.
 */
class FileLogger(context: Context) : Logger {

    private val dir = File(context.getExternalFilesDir(null), "logs").apply { mkdirs() }
    private val logFile = File(dir, "duq.log")
    private val rotated = File(dir, "duq.log.1")
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "duq-file-logger").apply { isDaemon = true } }
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).apply {
        // Pin to Danny's timezone — the process default is UTC here, which made every
        // log line read +5h off from the wall clock.
        timeZone = java.util.TimeZone.getTimeZone(com.duq.android.config.AppConfig.LOG_TIMEZONE)
    }

    private fun write(level: Char, tag: String, message: String, t: Throwable?) {
        val now = Date()
        io.execute {
            try {
                // Format on the single IO thread: SimpleDateFormat is not thread-safe,
                // so formatting on the (multi-threaded) caller would corrupt the Calendar.
                val ts = fmt.format(now)
                if (logFile.length() > MAX_BYTES) {
                    rotated.delete()
                    logFile.renameTo(rotated)
                }
                val sb = StringBuilder()
                    .append(ts).append(' ').append(level).append('/').append(tag)
                    .append(": ").append(message).append('\n')
                if (t != null) sb.append(Log.getStackTraceString(t)).append('\n')
                logFile.appendText(sb.toString())
            } catch (_: Throwable) {
                // Logging must never crash the app.
            }
        }
    }

    override fun v(tag: String, message: String) { Log.v(tag, message); write('V', tag, message, null) }
    override fun d(tag: String, message: String) { Log.d(tag, message); write('D', tag, message, null) }
    override fun i(tag: String, message: String) { Log.i(tag, message); write('I', tag, message, null) }
    override fun w(tag: String, message: String) { Log.w(tag, message); write('W', tag, message, null) }
    override fun w(tag: String, message: String, throwable: Throwable) { Log.w(tag, message, throwable); write('W', tag, message, throwable) }
    override fun e(tag: String, message: String) { Log.e(tag, message); write('E', tag, message, null) }
    override fun e(tag: String, message: String, throwable: Throwable) { Log.e(tag, message, throwable); write('E', tag, message, throwable) }

    companion object {
        private const val MAX_BYTES = 4L * 1024 * 1024 // rotate at 4 MB, keep 1 previous file
    }
}
