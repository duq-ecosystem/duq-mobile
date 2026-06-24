package com.duq.android.ui

/**
 * Визуальное состояние DUQ для индикаторов ([com.duq.android.ui.components.ArcReactor],
 * [com.duq.android.ui.components.DuqDuck], [com.duq.android.ui.components.VoiceWaveform]).
 *
 * Перенесён в commonMain из app-root `com.duq.android.DuqState` (был в android-модуле).
 * Сервисы озвучки/прослушки на платформе мапят свой lifecycle на эти состояния.
 */
enum class DuqState {
    IDLE,
    LISTENING,
    RECORDING,
    PROCESSING,
    PLAYING,
    ERROR
}
