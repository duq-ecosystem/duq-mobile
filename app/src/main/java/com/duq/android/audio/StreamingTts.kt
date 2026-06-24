package com.duq.android.audio

import com.duq.android.logging.Logger
import com.duq.android.network.TtsClient
import com.duq.android.util.ReplyText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Инкрементальный TTS («догон» озвучки): синтезирует ответ ПО ФРАЗАМ по мере прихода
 * стрим-дельт текста и проигрывает сегменты по порядку — озвучка стартует с первого
 * готового предложения, не дожидаясь всего текста ответа.
 *
 * SRP: ConversationViewModel зовёт [start] (начало голосового тёрна) → [feed] (на каждую
 * дельту) → [finish] (финал), либо [cancel] (abort/новый тёрн). Синтез строго
 * последовательный (single-consumer Channel) → порядок сегментов сохраняется. Сегментер
 * работает на СЫРОМ кумулятиве (стабильный растущий префикс), markdown чистится у каждой
 * выделенной фразы перед синтезом. Backend синтеза — on-device [TtsLocal] с fallback на
 * серверный [TtsClient] (тот же путь, что и разовый speakReply).
 */
@Singleton
class StreamingTts @Inject constructor(
    private val ttsLocal: TtsLocal,
    private val ttsClient: TtsClient,
    private val playback: ChatAudioPlaybackManager,
    private val logger: Logger,
) {
    // scope на Main: ВСЕ поля (channel/job/segIdx/activeRunId) и сегментер трогаются только
    // из Main (VM-вызовы + consumer на Main) → без гонок. Сам синтез тяжёлый уходит в IO
    // внутри ttsLocal.trySynthesize / ttsClient.synthesize (suspend с withContext(IO)),
    // поэтому Main не блокируется (consumer на нём только ждёт).
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val segmenter = SentenceStreamer()

    private var activeRunId: String? = null
    private var channel: Channel<String>? = null
    private var job: Job? = null
    private var segIdx = 0

    /** Идёт ли догон для данного тёрна (VM решает: финалить инкрементально или speakReply). */
    fun isStreaming(runId: String): Boolean = activeRunId == runId

    /** Старт догона для голосового тёрна. Идемпотентно для того же runId. */
    fun start(runId: String) {
        if (activeRunId == runId) return
        cancel()
        activeRunId = runId
        segmenter.reset()
        segIdx = 0
        val ch = Channel<String>(Channel.UNLIMITED)
        channel = ch
        job = scope.launch {
            for (rawSentence in ch) {
                val text = ReplyText.clean(rawSentence)
                if (text.isBlank()) continue
                val id = "${runId}_seg${segIdx++}"
                val file = try {
                    ttsLocal.trySynthesize(text, id) ?: ttsClient.synthesize(text, id)
                } catch (e: Exception) {
                    logger.e(TAG, "segment synth failed: ${e.message}"); null
                }
                if (file != null) playback.enqueueSegment(runId, file)
            }
            playback.finishStream(runId)
        }
    }

    /** Скормить кумулятивный (СЫРОЙ) текст дельты: новые завершённые фразы → в синтез. */
    fun feed(runId: String, cumulativeRaw: String) {
        if (activeRunId != runId) return
        segmenter.newSentences(cumulativeRaw).forEach { channel?.trySend(it) }
    }

    /** Финал: остаток текста → в синтез, закрыть канал (consumer доиграет и finishStream). */
    fun finish(runId: String, fullTextRaw: String) {
        if (activeRunId != runId) return
        segmenter.flush(fullTextRaw)?.let { channel?.trySend(it) }
        channel?.close()
        activeRunId = null
        channel = null
    }

    /** Прервать догон (новый тёрн/abort/error): стоп синтеза и озвучки.
     *  Канал НЕ закрываем — отменяем job (consumer прервётся CancellationException,
     *  finishStream НЕ вызовется, в отличие от штатного [finish]). */
    fun cancel() {
        activeRunId = null
        channel = null
        job?.cancel()
        job = null
        playback.cancelStream()
    }

    /** Освободить scope (вызывать из того же места, что и ChatAudioPlaybackManager.release). */
    fun release() {
        scope.cancel()
        playback.cancelStream()
    }

    companion object {
        private const val TAG = "StreamingTts"
    }
}
