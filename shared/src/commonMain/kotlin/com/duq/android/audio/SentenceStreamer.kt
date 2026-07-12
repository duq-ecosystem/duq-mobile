package com.duq.android.audio

/**
 * Выделяет ЗАВЕРШЁННЫЕ предложения из растущего кумулятивного текста стрима ответа —
 * для инкрементального TTS («догон» озвучки по фразам на лету, пока модель ещё пишет).
 *
 * Состояние — сколько символов уже отдано (`consumed`). На каждый кумулятив возвращает
 * ТОЛЬКО новые завершённые предложения; хвост (незавершённое последнее предложение)
 * отдаётся в [flush] на финале. Не thread-safe: звать из одного потока (consumer стрима).
 *
 * Чистый Kotlin stdlib — общий код KMP (commonMain), без платформенных зависимостей.
 */
class SentenceStreamer {
    private var consumed = 0

    /** Новые завершённые предложения из [cumulative] (с учётом уже отданного). */
    @Suppress("NestedBlockDepth")
    fun newSentences(cumulative: String): List<String> {
        if (cumulative.length <= consumed) return emptyList()
        val out = mutableListOf<String>()
        var start = consumed
        var i = consumed
        while (i < cumulative.length) {
            val c = cumulative[i]
            // Конец предложения: терминатор, за которым пробел/перевод строки/конец текста.
            // Перевод строки сам по себе тоже граница (пункты списка).
            val isEnder = c == '.' || c == '!' || c == '?' || c == '…' || c == '\n'
            if (isEnder) {
                val nextIsBoundary = i + 1 >= cumulative.length || cumulative[i + 1].isWhitespace()
                if (nextIsBoundary) {
                    val seg = cumulative.substring(start, i + 1).trim()
                    // MIN_SEGMENT режет крошечные фрагменты («1.», «т.д.») — они вольются
                    // в следующее предложение, а не озвучатся отдельным куском.
                    if (seg.length >= MIN_SEGMENT) {
                        out.add(seg)
                        start = i + 1
                    }
                }
            }
            i++
        }
        if (start > consumed) consumed = start
        return out
    }

    /** Финал: незавершённый остаток (последнее предложение без терминатора), или null. */
    fun flush(cumulative: String): String? {
        if (cumulative.length <= consumed) return null
        val tail = cumulative.substring(consumed).trim()
        consumed = cumulative.length
        return tail.ifEmpty { null }
    }

    /** Сброс перед новым ответом. */
    fun reset() {
        consumed = 0
    }

    companion object {
        private const val MIN_SEGMENT = 12
    }
}
