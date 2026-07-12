package com.duq.android.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.app.NotificationCompat
import com.duq.android.config.AppConfig
import com.duq.android.logging.Logger
import com.duq.android.network.DohDns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Android-реализация [AppUpdateController] — in-app APK self-update (sideload-канал,
 * app не в Play Store): проверка GitHub Releases → скачивание подписанного APK →
 * системный PackageInstaller (OS показывает диалог «Установить»). Перенесена из
 * референсного `app/.../update/AppUpdater.kt`, Hilt убран — зависимости (Context,
 * Logger, токены) через конструктор (Koin предоставит Context через androidContext()).
 *
 * Финальная установка подтверждается пользователем (Android запрещает тихую установку
 * без root / device-owner). Install — современный PackageInstaller Session API (не
 * deprecated ACTION_INSTALL_PACKAGE): реальный статус-колбэк в [InstallResultReceiver],
 * который поднимает диалог подтверждения из foreground и background.
 *
 * ВАЖНО: androidApp ОБЯЗАН задекларировать [InstallResultReceiver] в своём
 * AndroidManifest.xml (exported=false, action com.duq.android.INSTALL_STATUS).
 */
class AndroidAppUpdateController(
    private val context: Context,
    private val logger: Logger,
    private val notificationsEnabled: Boolean = true,
) : AppUpdateController {

    override val currentVersionName: String
        get() = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        }.getOrDefault("?")

    override val currentVersionCode: Int
        get() = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                info.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                info.versionCode
            }
        }.getOrDefault(0)

    // HTTP/1.1 принудительно: OkHttp HTTP/2-стрим может зависнуть на больших телах за
    // некоторыми nginx (flow-control окно не открывается), per-read timeout не срабатывает —
    // загрузка висит вечно. readTimeout (60s) ловит реальный столл; callTimeout 600s —
    // потолок на всю загрузку (51MB на медленной сети ≥85 КБ/с).
    private val client = OkHttpClient.Builder()
        .dns(DohDns)
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(600, TimeUnit.SECONDS)
        .build()

    private val nm = context.getSystemService(NotificationManager::class.java)
    private val updatePrefs = context.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE)

    override fun cachedAvailableVersion(): Int =
        updatePrefs.getInt(KEY_AVAILABLE_VERSION, 0).takeIf { it > currentVersionCode } ?: 0

    /**
     * Быстрая проверка (version из tag релиза, ~1s, БЕЗ скачивания APK). Записывает
     * доступную версию для in-app баннера и поднимает уведомление сразу. Возвращает
     * код новой версии (>0) или 0.
     */
    override suspend fun checkAvailable(): Int = withContext(Dispatchers.IO) {
        try {
            val remoteCode = fetchRemoteVersionCode() ?: run {
                logger.w(TAG, "releases/latest вернул null")
                return@withContext 0
            }
            logger.i(TAG, "Remote versionCode=$remoteCode, local=$currentVersionCode")
            if (remoteCode <= currentVersionCode) {
                // Обновление уже установлено/снято — сбрасываем и «доступную», и «уведомлённую»
                // версии, чтобы БУДУЩАЯ новая версия снова подняла уведомление.
                updatePrefs.edit().remove(KEY_AVAILABLE_VERSION).remove(KEY_NOTIFIED_VERSION).apply()
                nm.cancel(NOTIFY_ID)
                return@withContext 0
            }
            updatePrefs.edit().putInt(KEY_AVAILABLE_VERSION, remoteCode).apply()
            // Уведомление (шторка + запись в ленту) — ОДИН раз на версию. checkAvailable() зовётся
            // на КАЖДЫЙ ON_RESUME; без этой отсечки повторные выводы приложения на передний план
            // плодили дубли в ленте (AndroidNotificationInbox.record дедупа по контенту не имеет).
            if (notificationsEnabled && updatePrefs.getInt(KEY_NOTIFIED_VERSION, 0) != remoteCode) {
                showAvailableNotification(remoteCode)
                updatePrefs.edit().putInt(KEY_NOTIFIED_VERSION, remoteCode).apply()
                logger.i(TAG, "Доступно обновление: $remoteCode (баннер + уведомление, первый раз)")
            } else {
                logger.i(TAG, "Доступно обновление: $remoteCode (баннер; уведомление уже показано)")
            }
            remoteCode
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(TAG, "checkAvailable failed: ${e.message}", e)
            0
        }
    }

    /** Скачать APK и начать установку. Всегда тянет свежий APK (stale-кэш → INSTALL_PARSE_FAILED). */
    override suspend fun downloadAndInstall(onProgress: (Float) -> Unit) {
        withContext(Dispatchers.IO) {
            try {
                val v = updatePrefs.getInt(KEY_AVAILABLE_VERSION, 0)
                if (v <= currentVersionCode) return@withContext
                if (notificationsEnabled) showDownloadingNotification(v)
                val apkFile = downloadApk(onProgress) ?: run {
                    nm.cancel(NOTIFY_ID)
                    return@withContext
                }
                nm.cancel(NOTIFY_ID)
                installApk(apkFile, v)
            } catch (e: CancellationException) {
                nm.cancel(NOTIFY_ID)
                throw e
            } catch (e: Exception) {
                logger.e(TAG, "downloadAndInstall failed: ${e.message}", e)
                nm.cancel(NOTIFY_ID)
            }
        }
    }

    /** GitHub release JSON (приватный репо → нужен read-only токен). */
    private fun fetchLatestReleaseJson(): JSONObject? {
        val token = AppConfig.UPDATE_GITHUB_TOKEN
        if (token.isBlank()) {
            logger.w(TAG, "GH_RELEASE_TOKEN пуст — self-update отключён")
            return null
        }
        val request = Request.Builder()
            .url(AppConfig.UPDATE_LATEST_RELEASE_URL)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("Cache-Control", "no-cache")
            .build()
        return client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                logger.w(TAG, "releases/latest HTTP ${resp.code}")
                return null
            }
            resp.body?.string()?.let { JSONObject(it) }
        }
    }

    private fun fetchRemoteVersionCode(): Int? {
        val rel = fetchLatestReleaseJson() ?: return null
        // CI тегает каждый релиз "build-<versionCode>" (совпадает с versionCode APK).
        val tag = rel.optString("tag_name", "")
        return Regex("build-(\\d+)").find(tag)?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 }
    }

    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth", "ReturnCount", "LoopWithTooManyJumpStatements")
    private fun downloadApk(onProgress: (Float) -> Unit): File? {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val apkFile = File(dir, APK_FILENAME)
        val tmp = File(dir, "$APK_FILENAME.tmp")
        // Приватные ассеты требуют auth + Accept: octet-stream и скачивание по asset URL
        // (browser_download_url 404 без сессии). Atomic .tmp → rename: прерванная загрузка
        // не оставит частичный файл, который завалит установку.
        val token = AppConfig.UPDATE_GITHUB_TOKEN
        if (token.isBlank()) {
            logger.w(TAG, "GH_RELEASE_TOKEN пуст — нечем качать")
            return null
        }
        val rel = fetchLatestReleaseJson() ?: return null
        val assets = rel.optJSONArray("assets") ?: return null
        var assetUrl: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            if (a.optString("name") == APK_ASSET_NAME) {
                assetUrl = a.optString("url")
                break
            }
        }
        if (assetUrl.isNullOrBlank()) {
            logger.w(TAG, "$APK_ASSET_NAME не найден в релизе")
            return null
        }
        val request = Request.Builder()
            .url(assetUrl)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/octet-stream")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                tmp.delete()
                return null
            }
            val body = resp.body ?: run {
                tmp.delete()
                return null
            }
            val total = body.contentLength()
            body.byteStream().use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(16 * 1024)
                    var sum = 0L
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        out.write(buf, 0, read)
                        sum += read
                        if (total > 0) onProgress((sum.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }
        if (tmp.length() <= 0) {
            tmp.delete()
            return null
        }
        apkFile.delete()
        if (!tmp.renameTo(apkFile)) {
            tmp.delete()
            return null
        }
        logger.i(TAG, "APK скачан: ${apkFile.length() / 1024 / 1024}MB")
        return apkFile
    }

    /**
     * Стримит APK в PackageInstaller-сессию и коммитит. Система шлёт
     * STATUS_PENDING_USER_ACTION в [InstallResultReceiver], который поднимает диалог
     * подтверждения. Работает из foreground и background.
     */
    @Suppress("NestedBlockDepth")
    private fun installApk(apkFile: File, version: Int) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        ).apply { setAppPackageName(context.packageName) }

        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                apkFile.inputStream().use { input ->
                    session.openWrite("duq-update", 0, apkFile.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                val statusIntent = Intent(context, InstallResultReceiver::class.java).apply {
                    action = InstallResultReceiver.ACTION_INSTALL_STATUS
                    setPackage(context.packageName)
                    putExtra(InstallResultReceiver.EXTRA_VERSION, version)
                }
                val pi = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    statusIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentMutableFlag()
                )
                session.commit(pi.intentSender)
            }
        } catch (e: Exception) {
            // Иначе висячая сессия копится к OEM-лимиту сессий.
            runCatching { installer.abandonSession(sessionId) }
            throw e
        }
        logger.i(TAG, "Install-сессия закоммичена для v$version (id=$sessionId)")
    }

    private fun pendingIntentMutableFlag(): Int =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }

    private fun ensureChannel() {
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "DUQ Updates", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "App update notifications" }
            )
        }
    }

    private fun showAvailableNotification(version: Int) {
        ensureChannel()
        val open = Intent().apply {
            setClassName(context.packageName, "$LAUNCH_ACTIVITY")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("open_section", "version")
        }
        val pi = PendingIntent.getActivity(
            context,
            1,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or
                (
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                        PendingIntent.FLAG_IMMUTABLE
                    } else {
                        0
                    }
                    )
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Доступно обновление DUQ v$version")
            .setContentText("Открой приложение и нажми «Установить»")
            .setContentIntent(pi)
            .setAutoCancel(false)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        nm.notify(NOTIFY_ID, n)
        AndroidNotificationInbox.record(
            context,
            "Доступно обновление v$version",
            "Открой приложение и нажми «Установить»",
            "update",
            System.currentTimeMillis()
        )
        logger.i(TAG, "Уведомление о доступном обновлении v$version показано")
    }

    private fun showDownloadingNotification(version: Int) {
        ensureChannel()
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("DUQ Update")
            .setContentText("Скачивание v$version...")
            .setProgress(0, 0, true)
            .setOngoing(true)
            .build()
        nm.notify(NOTIFY_ID, n)
    }

    private companion object {
        const val TAG = "AppUpdater"
        const val APK_ASSET_NAME = "app-release.apk"
        const val APK_FILENAME = "duq-update.apk"
        const val CHANNEL_ID = "duq_update_channel"
        const val NOTIFY_ID = 9001
        const val UPDATE_PREFS = "duq_update"
        const val KEY_AVAILABLE_VERSION = "available_version"

        // Версия, для которой уведомление (шторка+лента) уже показано — чтобы не дублировать
        // на каждом ON_RESUME. Сбрасывается, когда обновление установлено (remoteCode<=local).
        const val KEY_NOTIFIED_VERSION = "notified_version"

        // Точка входа androidApp (тап по пушу обновления → MainActivity, раздел «Версия»).
        const val LAUNCH_ACTIVITY = "com.duq.android.MainActivity"
    }
}
