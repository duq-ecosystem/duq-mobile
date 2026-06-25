package com.duq.android.service

import com.duq.android.error.DuqError
import com.duq.android.ui.DuqState
import kotlinx.coroutines.flow.StateFlow

/**
 * Контракт управления голосовым сервисом. Развязывает UI от конкретной
 * [DuqListenerService]: тестируемость, подмена реализации, инверсия зависимостей.
 *
 * Перенесён из app-модуля (`com.duq.android.service`) в shared/androidMain без изменений
 * логики: [DuqState] теперь в `com.duq.android.ui`, [DuqError] — в `com.duq.android.error`.
 */
interface VoiceServiceController {
    /** Текущее состояние голосовой обработки. */
    val state: StateFlow<DuqState>

    /** Текущая ошибка, если есть. */
    val error: StateFlow<DuqError?>

    /** Начать прослушивание. */
    fun startListening()

    /** Остановить прослушивание. */
    fun stopListening()

    /** Сбросить текущую ошибку. */
    fun clearError()
}
