package com.duq.android.screen

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Coordinates the one-time MediaProjection user consent between a node command
 * (running in the service/coroutine) and the transparent [ScreenConsentActivity].
 *
 * MediaProjection cannot be obtained without an explicit per-session user grant
 * (Android security) — there is no headless path.
 *
 * Launching an activity from a background service is blocked on Android 10+/MIUI.
 * We therefore raise a high-importance notification with a **full-screen intent**
 * (the path the OS allows for background→foreground UI, as used by alarms/calls),
 * and fall back to a direct startActivity when the app is already foreground.
 */
object ScreenCaptureManager {

    data class Consent(val resultCode: Int, val data: Intent)

    private const val CHANNEL_ID = "duq_screen_consent"
    private const val NOTIF_ID = 9100

    @Volatile private var pending: CompletableDeferred<Consent?>? = null

    suspend fun requestConsent(context: Context, timeoutMs: Long = 60_000L): Consent? {
        val deferred = CompletableDeferred<Consent?>()
        pending = deferred
        val activityIntent = Intent(context, ScreenConsentActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val pi = PendingIntent.getActivity(
            context,
            0,
            activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        ensureChannel(context)
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("DUQ screen capture")
            .setContentText("Tap to allow screen capture")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(pi, true)
            .setAutoCancel(true)
            .build()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, notif)
        // Best-effort direct launch too (works when already foreground).
        runCatching { context.startActivity(activityIntent) }
        return withTimeoutOrNull(timeoutMs) { deferred.await() }.also {
            pending = null
            nm.cancel(NOTIF_ID)
        }
    }

    fun deliverConsent(consent: Consent?) {
        pending?.complete(consent)
        pending = null
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "DUQ Screen Capture", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "Screen capture consent prompt" }
            )
        }
    }
}
