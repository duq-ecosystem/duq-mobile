package com.duq.android.data

import com.duq.android.network.duqJson
import com.russhwolf.settings.Settings
import com.russhwolf.settings.get
import com.russhwolf.settings.set
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/** Аккаунт, сохранённый на устройстве (мультиаккаунт): под кем уже заходили. */
@Serializable
data class Account(val userId: String, val name: String, val role: String = "")

/**
 * KMP-хранилище настроек на multiplatform-settings (com.russhwolf:multiplatform-settings),
 * заменяет Android DataStore/EncryptedSharedPreferences из app/.
 *
 * Settings инжектится в конструктор (фабрика подключается на фазе DI):
 *   - androidMain: SharedPreferencesSettings (поверх EncryptedSharedPreferences/SharedPreferences)
 *   - iosMain:     NSUserDefaultsSettings
 *
 * Шифрование (EncryptedSharedPreferences из референса) — забота платформенной фабрики
 * Settings, а не этого класса: репозиторий работает с любым Settings-бэкендом одинаково.
 *
 * Reactive-поля (porcupineApiKey/wakeWordSensitivity/silenceTimeoutMs) сохранены 1:1 как
 * в референсе — backed by MutableStateFlow, инициализируется значением из Settings,
 * синхронные геттеры читают .value. Это идентичная семантика, без android-only DataStore.
 */
class SettingsRepository(private val settings: Settings) {

    companion object {
        private const val KEY_PORCUPINE_API_KEY = "porcupine_api_key"
        private const val KEY_WAKE_WORD_SENSITIVITY = "wake_word_sensitivity"
        private const val KEY_SILENCE_TIMEOUT_MS = "silence_timeout_ms"
        private const val KEY_LAST_REPORTED_LOCATION = "last_reported_location"
        private const val KEY_ACCOUNTS = "duq_accounts" // мультиаккаунт: список сохранённых
        private const val KEY_ACTIVE_USER = "duq_active_user" // активный user_id
        private const val KEY_SERVER_TOKEN = "duq_server_token"

        const val DEFAULT_WAKE_WORD_SENSITIVITY = 0.9f
        const val DEFAULT_SILENCE_TIMEOUT_MS = 2000L
    }

    // Авторизация устройства — build-time edge-token (BuildConfig.SERVER_TOKEN), не
    // per-device пейринг. Поля device/node/bootstrap/gateway (Ed25519/legacy) удалены.

    // ───────── Мультиаккаунт (вход по имени + общий токен; устройство помнит всех) ─────────
    // user_id (UUID) активного аккаунта шлётся в каждом запросе → ядро различает членов семьи
    // поверх общего edge-токена. Список аккаунтов хранится на устройстве, «Выход» = переключение
    // (не стирает), плюс «войти под другим» и удаление аккаунта с устройства.

    private fun loadAccounts(): MutableList<Account> =
        runCatching { duqJson.decodeFromString<List<Account>>(settings[KEY_ACCOUNTS, "[]"]) }
            .getOrElse { emptyList() }.toMutableList()

    private fun storeAccounts(list: List<Account>) {
        settings[KEY_ACCOUNTS] = duqJson.encodeToString(list)
    }

    /** Все сохранённые на устройстве аккаунты (под кем заходили). */
    fun getAccounts(): List<Account> = loadAccounts()

    /** Активный user_id (пусто = ни одного аккаунта, показываем экран входа). */
    fun getUserId(): String = settings[KEY_ACTIVE_USER, ""]

    /** Имя активного аккаунта. */
    fun getUserName(): String =
        loadAccounts().firstOrNull { it.userId == getUserId() }?.name ?: ""

    /** Роль активного аккаунта (admin/public). */
    fun getUserRole(): String =
        loadAccounts().firstOrNull { it.userId == getUserId() }?.role ?: ""

    /** Вход/регистрация: добавить (или обновить) аккаунт и сделать его активным. */
    fun upsertActiveAccount(userId: String, name: String, role: String) {
        val list = loadAccounts()
        val i = list.indexOfFirst { it.userId == userId }
        if (i >= 0) list[i] = Account(userId, name, role) else list.add(Account(userId, name, role))
        storeAccounts(list)
        settings[KEY_ACTIVE_USER] = userId
    }

    /** Переключить активный аккаунт на уже сохранённый (без повторного входа/токена). */
    fun setActiveUser(userId: String) {
        if (loadAccounts().any { it.userId == userId }) settings[KEY_ACTIVE_USER] = userId
    }

    /** Убрать аккаунт с устройства (реальное удаление из списка). */
    fun removeAccount(userId: String) {
        val list = loadAccounts().filterNot { it.userId == userId }
        storeAccounts(list)
        if (getUserId() == userId) settings[KEY_ACTIVE_USER] = list.firstOrNull()?.userId ?: ""
    }

    /** Переименование активного аккаунта (после updateProfile на ядре). */
    fun renameActive(name: String) {
        val uid = getUserId()
        val list = loadAccounts()
        val i = list.indexOfFirst { it.userId == uid }
        if (i >= 0) {
            list[i] = list[i].copy(name = name)
            storeAccounts(list)
        }
    }

    // Общий токен системы (edge-token семьи), вводится юзером на экране регистрации при первом
    // входе — НЕ зашит в билд. Идёт в X-Auth-Token на всех запросах (см. DuqHttpClient).
    fun getServerToken(): String = settings[KEY_SERVER_TOKEN, ""]
    fun saveServerToken(token: String) { settings[KEY_SERVER_TOKEN] = token }

    /** Last lat/lng reported to DUQ — used to suppress duplicate reports across restarts. */
    fun getLastReportedLocation(): Pair<Double, Double>? {
        val s = settings[KEY_LAST_REPORTED_LOCATION, ""]
        if (s.isBlank()) return null
        val parts = s.split(",")
        val lat = parts.getOrNull(0)?.toDoubleOrNull() ?: return null
        val lng = parts.getOrNull(1)?.toDoubleOrNull() ?: return null
        return lat to lng
    }

    fun saveLastReportedLocation(lat: Double, lng: Double) {
        settings[KEY_LAST_REPORTED_LOCATION] = "$lat,$lng"
    }

    // Porcupine — reactive flow backed by MutableStateFlow
    private val _porcupineApiKey = MutableStateFlow(settings[KEY_PORCUPINE_API_KEY, ""])
    val porcupineApiKey: Flow<String> = _porcupineApiKey
    fun getPorcupineApiKey(): String = _porcupineApiKey.value
    fun savePorcupineApiKey(key: String) {
        settings[KEY_PORCUPINE_API_KEY] = key
        _porcupineApiKey.value = key
    }

    // Wake word sensitivity — reactive
    private val _wakeWordSensitivity =
        MutableStateFlow(settings[KEY_WAKE_WORD_SENSITIVITY, DEFAULT_WAKE_WORD_SENSITIVITY])
    val wakeWordSensitivity: Flow<Float> = _wakeWordSensitivity
    fun getWakeWordSensitivitySync(): Float = _wakeWordSensitivity.value
    fun saveWakeWordSensitivity(v: Float) {
        val clamped = v.coerceIn(0.5f, 1.0f)
        settings[KEY_WAKE_WORD_SENSITIVITY] = clamped
        _wakeWordSensitivity.value = clamped
    }

    // Silence timeout — reactive
    private val _silenceTimeoutMs =
        MutableStateFlow(settings[KEY_SILENCE_TIMEOUT_MS, DEFAULT_SILENCE_TIMEOUT_MS])
    val silenceTimeoutMs: Flow<Long> = _silenceTimeoutMs
    fun getSilenceTimeoutMsSync(): Long = _silenceTimeoutMs.value
    fun saveSilenceTimeoutMs(v: Long) {
        val clamped = v.coerceIn(1000L, 4000L)
        settings[KEY_SILENCE_TIMEOUT_MS] = clamped
        _silenceTimeoutMs.value = clamped
    }
}
