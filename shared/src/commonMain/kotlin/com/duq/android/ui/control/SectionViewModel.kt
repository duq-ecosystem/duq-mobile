package com.duq.android.ui.control

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.duq.android.network.CoreUpdateClient
import com.duq.android.ui.AppUpdateController
import com.duq.android.ui.CoreUpdateNotifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Пульт ядра DUQ — только карточка «Движок».
 *
 * Прежние gateway-RPC разделы (agents/models/cron/skills/tools/channels/memory/
 * usage/voice/nodes) убраны: у ядра DUQ нет gateway-RPC.
 * Полноценная админка ядра — отдельная фича под будущий core admin-REST. Остаётся
 * «Движок»: версия ядра + обновление через бэкенд-ручку /core-update (HTTP, не WS).
 *
 * Зависимости — через конструктор (Koin предоставит). Hilt/@Inject убраны при переносе
 * в commonMain. Android-only `AppUpdater(context)` / `BuildConfig` / `CoreUpdateNotifier`
 * заменены мультиплатформенными интерфейсами [AppUpdateController]/[CoreUpdateNotifier].
 */
class SectionViewModel(
    private val coreUpdate: CoreUpdateClient,
    private val appUpdater: AppUpdateController,
    private val coreUpdateNotifier: CoreUpdateNotifier,
) : ViewModel() {

    sealed class CoreState {
        object Loading : CoreState()
        data class Error(val message: String) : CoreState()
        data class Data(val status: CoreUpdateClient.Status) : CoreState()
    }

    private val _core = MutableStateFlow<CoreState>(CoreState.Loading)
    val core: StateFlow<CoreState> = _core.asStateFlow()

    /** Подтянуть текущую/доступную ревизию ядра + флаг «идёт обновление». */
    fun loadCore() {
        // ⛔ Loading показываем ТОЛЬКО при первой загрузке. Авто-поллинг (каждые 8с во время
        // апдейта) НЕ должен сбрасывать в Loading — иначе экран мигает/«перезагружается».
        if (_core.value !is CoreState.Data) _core.value = CoreState.Loading
        viewModelScope.launch {
            val s = coreUpdate.status()
            if (s != null) {
                // апдейт только что завершился? бэкенд написал self-check → уведомить юзера
                // (дедуп по result.ts): «добро пожаловать / ошибка».
                coreUpdateNotifier.notifyResult(s)
                _core.value = CoreState.Data(s)
            } else if (_core.value !is CoreState.Data) {
                _core.value = CoreState.Error("Бэкенд обновления недоступен")
            }
        }
    }

    /** Запустить обновление ядра через ручку (детач на сервере), затем обновить статус. */
    fun runCore(onResult: (CoreUpdateClient.RunResult) -> Unit = {}) {
        viewModelScope.launch {
            val res = coreUpdate.run()
            loadCore()
            onResult(res)
        }
    }

    // ── Обновление ПРИЛОЖЕНИЯ (APK через AppUpdateController) — рядом с обновлением ядра ──
    data class AppState(
        val currentName: String,
        val currentCode: Int,
        val remoteCode: Int = 0, // >0 и > current ⇒ доступно обновление
        val installing: Boolean = false,
        val progress: Float = 0f,
    ) { val updateAvailable get() = remoteCode > currentCode }

    private val _app = MutableStateFlow(
        AppState(
            currentName = appUpdater.currentVersionName,
            currentCode = appUpdater.currentVersionCode,
        )
    )
    val app: StateFlow<AppState> = _app.asStateFlow()

    /** Проверить доступную версию приложения (GitHub Releases через AppUpdateController). */
    fun loadApp() {
        viewModelScope.launch {
            val remote = runCatching { appUpdater.checkAvailable() }.getOrDefault(0)
            _app.value = _app.value.copy(remoteCode = remote)
        }
    }

    private var appInstalling = false

    /** Скачать и установить APK (PackageInstaller подтвердит). */
    fun installApp() {
        if (appInstalling) return
        appInstalling = true
        _app.value = _app.value.copy(installing = true, progress = 0f)
        viewModelScope.launch {
            try {
                appUpdater.downloadAndInstall(onProgress = { p ->
                    _app.value = _app.value.copy(progress = p)
                })
            } finally {
                appInstalling = false
                _app.value = _app.value.copy(installing = false, progress = 0f)
            }
        }
    }
}
