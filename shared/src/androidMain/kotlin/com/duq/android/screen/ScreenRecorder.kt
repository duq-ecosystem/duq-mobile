package com.duq.android.screen

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Records a short screen clip via MediaProjection → MediaRecorder (MP4/H264) and
 * returns it base64-encoded for the screen.record phone command.
 *
 * Requires a prior user consent token (see [ScreenCaptureManager]) and — on
 * Android 14+ — an active `mediaProjection` foreground-service type, which the
 * caller raises via [onNeedProjectionForeground] before getMediaProjection().
 */
class ScreenRecorder(private val context: Context) {

    data class Clip(val base64: String, val durationMs: Long, val format: String = "mp4")

    suspend fun record(
        consent: ScreenCaptureManager.Consent,
        durationMs: Long,
        onNeedProjectionForeground: () -> Unit
    ): Clip = withContext(Dispatchers.IO) {
        onNeedProjectionForeground() // FGS type mediaProjection must be live first (A14+)
        val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = mpm.getMediaProjection(consent.resultCode, consent.data)
            ?: error("getMediaProjection returned null")

        val metrics = screenMetrics()
        // Cap resolution to keep the base64 payload sane.
        val scale = if (metrics.widthPixels > 720) 720f / metrics.widthPixels else 1f
        val w = (metrics.widthPixels * scale).toInt() / 2 * 2
        val h = (metrics.heightPixels * scale).toInt() / 2 * 2

        val outFile = File(context.cacheDir, "screen-record.mp4").apply { if (exists()) delete() }
        val recorder = buildRecorder(outFile, w, h, metrics.densityDpi)

        var virtualDisplay: VirtualDisplay? = null
        // A registered callback is mandatory on Android 14+.
        projection.registerCallback(object : MediaProjection.Callback() {}, Handler(Looper.getMainLooper()))
        try {
            recorder.prepare()
            virtualDisplay = projection.createVirtualDisplay(
                "duq-screen", w, h, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                recorder.surface, null, null
            )
            recorder.start()
            delay(durationMs)
            recorder.stop()
        } finally {
            runCatching {
                recorder.reset()
                recorder.release()
            }
            runCatching { virtualDisplay?.release() }
            runCatching { projection.stop() }
        }
        val bytes = outFile.readBytes()
        outFile.delete()
        Clip(Base64.encodeToString(bytes, Base64.NO_WRAP), durationMs)
    }

    @Suppress("UnusedParameter")
    private fun buildRecorder(out: File, w: Int, h: Int, dpi: Int): MediaRecorder {
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        r.setVideoSize(w, h)
        r.setVideoFrameRate(24)
        r.setVideoEncodingBitRate(3_000_000)
        r.setOutputFile(out.absolutePath)
        return r
    }

    @Suppress("DEPRECATION")
    private fun screenMetrics(): DisplayMetrics {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val m = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(m)
        return m
    }
}
