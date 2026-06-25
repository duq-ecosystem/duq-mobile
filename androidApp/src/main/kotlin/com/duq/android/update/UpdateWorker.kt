package com.duq.android.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.duq.android.logging.FileLogger
import com.duq.android.network.CoreUpdateClient
import com.duq.android.ui.AppUpdateController
import com.duq.android.ui.CoreUpdateNotifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext
import java.util.concurrent.TimeUnit

/**
 * Periodic background self-update check — so the app updates itself without the
 * user having to open it first. Runs every [INTERVAL_HOURS] hours when network
 * is available.
 *
 * Зависимости тянутся из Koin (CoroutineWorker создаётся WorkManager'ом, не Koin):
 *   - [AppUpdateController.checkAvailable] — ТОЛЬКО детект новой версии APK + уведомление
 *     (без 33-MB скачивания на таймере/батарее; реализация AndroidAppUpdateController
 *     внутри AppUpdater поднимает локальный пуш о доступном обновлении);
 *   - заодно проверяем обновление ЯДРА DUQ ([CoreUpdateClient.status] → [CoreUpdateNotifier.notifyResult],
 *     дедуп по result.ts внутри реализации) → пуш с deep-link в «Движок».
 */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val flog = FileLogger(applicationContext)
        flog.i("AppUpdater", "UpdateWorker.doWork() started")
        try {
            val koin = GlobalContext.get()
            // Background: only detect + notify (no full APK download on a timer/battery).
            koin.get<AppUpdateController>().checkAvailable()
            // Заодно проверяем обновление ЯДРА DUQ → пуш с deep-link в «Движок».
            val status = koin.get<CoreUpdateClient>().status()
            if (status != null) koin.get<CoreUpdateNotifier>().notifyResult(status)
            flog.i("AppUpdater", "UpdateWorker.doWork() finished")
            Result.success()
        } catch (e: Exception) {
            flog.e("AppUpdater", "UpdateWorker failed: ${e.message}", e)
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "duq-self-update"
        private const val INTERVAL_HOURS = 6L

        /** Schedules the recurring check; KEEP so re-launches don't reset the timer. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }
    }
}
