package com.duq.android.ui

/**
 * Обновление САМОГО приложения (in-app APK-апдейт через GitHub Releases).
 *
 * Интерфейс — общий код KMP (commonMain); реализация платформенная (androidMain:
 * AppUpdater + PackageInstaller + CoreUpdateNotifier; iosMain: деградация — App Store
 * сам обновляет, проверки возвращают «нет обновления», установка — no-op).
 *
 * Вынесен из референсного `ConversationViewModel`/`SectionViewModel`, где напрямую
 * использовались android-only `AppUpdater(context)` / `BuildConfig`. VM держит чистую
 * мультиплатформенную зависимость, а вся android-специфика (Context, PackageInstaller,
 * версии сборки) живёт в actual-реализации.
 */
interface AppUpdateController {

    /** Текущая версия приложения (read из сборки): человекочитаемое имя + числовой код. */
    val currentVersionName: String
    val currentVersionCode: Int

    /** Уже скачанная-но-не-установленная версия (0 = нет) — мгновенно, без сети. */
    fun cachedAvailableVersion(): Int

    /**
     * Быстрая проверка доступной версии (только version.json/Releases, без скачивания APK).
     * Возвращает код доступной версии (>0) или 0, если обновления нет/проверка не удалась.
     */
    suspend fun checkAvailable(): Int

    /**
     * Скачать + установить доступное обновление. [onProgress] — прогресс скачивания (0..1).
     * На iOS — no-op (App Store).
     */
    suspend fun downloadAndInstall(onProgress: (Float) -> Unit = {})
}
