package com.duq.android.ui

import com.duq.android.logging.Logger
import platform.Foundation.NSBundle

/**
 * iOS-реализация [AppUpdateController] — деградация. In-app APK self-update на iOS
 * невозможен (нет PackageInstaller; .ipa обновляется через SideStore / App Store вне
 * приложения). Версия читается из Info.plist; проверки честно возвращают «нет
 * обновления»; установка — no-op с логом.
 */
class IosAppUpdateController(
    private val logger: Logger,
) : AppUpdateController {

    override val currentVersionName: String
        get() = infoString("CFBundleShortVersionString") ?: "?"

    override val currentVersionCode: Int
        get() = infoString("CFBundleVersion")?.toIntOrNull() ?: 0

    override fun cachedAvailableVersion(): Int = 0

    override suspend fun checkAvailable(): Int {
        // .ipa обновляется снаружи (SideStore/App Store) — in-app проверки нет.
        logger.d(TAG, "self-update не поддерживается на iOS — обновление через SideStore/App Store")
        return 0
    }

    override suspend fun downloadAndInstall(onProgress: (Float) -> Unit) {
        logger.d(TAG, "downloadAndInstall — no-op на iOS (обновление через SideStore/App Store)")
    }

    private fun infoString(key: String): String? =
        NSBundle.mainBundle.objectForInfoDictionaryKey(key) as? String

    private companion object {
        const val TAG = "AppUpdater"
    }
}
