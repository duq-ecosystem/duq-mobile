package com.duq.android.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.duq.android.logging.Logger
import com.duq.android.util.ReplyText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Android-реализация [StreamingTtsController]: НОРМАЛЬНЫЙ потоковый TTS-догон.
 * По мере стрима текста (дельт) синтезируем завершённые фразы on-device (sherpa-onnx →
 * PCM-сэмплы) и пишем их в ОДИН непрерывный [AudioTrack] по мере готовности. Звук льётся
 * сразу с первой фразы — без WAV-файлов на фразу, без очереди в ExoPlayer, без склейки.
 *
 * Только on-device (sherpa, единый формат). Если движок ещё не готов (модель не скачана) —
 * догон не стартует (ConversationViewModel.isReady-гейт), обычный speakReply озвучит и
 * скачает. PCM накапливаем → один WAV в кэш для мгновенного replay.
 *
 * VM зовёт start (начало голосового тёрна) → feed (на каждую дельту) → finish (финал),
 * либо cancel (abort/новый тёрн).
 */
class StreamingTts(
    private val ttsLocal: LocalTts,
    private val playback: AudioPlaybackManager,
    private val logger: Logger,
) : StreamingTtsController {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val segmenter = SentenceStreamer()

    @Volatile
    private var activeRunId: String? = null
    private var channel: Channel<String>? = null
    private var job: Job? = null

    // Текущий трек — поле, чтобы cancel() мог pause() и прервать блокирующий write мгновенно.
    @Volatile
    private var track: AudioTrack? = null

    // Захват/сброс пары (track, playingNow) — атомарно: cancel()+start() нового прогона не
    // ждёт завершения старого job, и его finally не должен стоптать поля нового прогона.
    private val trackLock = Any()

    // Идёт ли реальное проигрывание звука (AudioTrack играет/дренится). Отдельно от activeRunId:
    // activeRunId гаснет на finish(), а звук ещё доигрывается в консьюмере. Кнопка плеера по
    // этому флагу понимает, что догон озвучивает СЕЙЧАС → тап = СТОП (а не второе проигрывание).
    @Volatile
    private var playingNow = false

    override fun isStreaming(runId: String): Boolean = activeRunId == runId

    /** Озвучивает ли догон прямо сейчас (живой AudioTrack) — для кнопки-стоп в UI. */
    override fun isPlaying(): Boolean = playingNow

    /** Старт догона для голосового тёрна. Идемпотентно для того же runId. */
    override fun start(runId: String) {
        if (activeRunId == runId) return
        cancel()
        activeRunId = runId
        segmenter.reset()
        val ch = Channel<String>(Channel.UNLIMITED)
        channel = ch
        logger.d(TAG, "start догон runId=${runId.take(8)}")
        job = scope.launch {
            var sampleRate = 0
            var totalFrames = 0
            val replay = mutableListOf<ShortArray>()
            // ПРЕФЕТЧ (best practice): producer синтезирует фразы ВПЕРЁД в очередь (синтез ~1с
            // быстрее проигрывания фразы ~неск.сек → очередь набивается), а consumer льёт PCM в
            // AudioTrack без пауз. Раньше synth и write были в ОДНОМ цикле — следующую фразу
            // синтезировали ТОЛЬКО после доигрывания текущей → тишина-пауза между фразами
            // («голос прерывается»). Producer — child job (отменяется вместе с догоном).
            val pcmQueue = Channel<TtsSamples>(Channel.UNLIMITED)
            val producer = launch {
                try {
                    for (raw in ch) {
                        val text = ReplyText.clean(raw)
                        if (text.isBlank()) continue
                        val s = ttsLocal.synthesizeSamples(text)
                        if (s == null) { logger.d(TAG, "synth null (движок не готов) — skip"); continue }
                        logger.d(TAG, "synth '${text.take(28)}' samples=${s.pcm.size}")
                        pcmQueue.send(s)
                    }
                } finally { pcmQueue.close() }
            }
            // Трек принадлежит ЭТОМУ прогону: cancel() нового start() не ждёт завершения
            // старого job — finally старого не должен release'ить/обнулять трек нового
            // (гонка cancel→start), поэтому в finally чистим только СВОЙ myTrack.
            var myTrack: AudioTrack? = null
            try {
                for (s in pcmQueue) {
                    val t = myTrack ?: run {
                        sampleRate = s.sampleRate
                        newTrack(sampleRate).apply { play() }
                            .also { synchronized(trackLock) { myTrack = it; track = it; playingNow = true } }
                            .also { logger.d(TAG, "AudioTrack play sr=$sampleRate") }
                    }
                    // WRITE_BLOCKING явно; проверяем возврат — при ошибке (потеря focus/HAL)
                    // НЕ увеличиваем totalFrames (иначе drain зависнет), выходим.
                    val written = t.write(s.pcm, 0, s.pcm.size, AudioTrack.WRITE_BLOCKING)
                    if (written <= 0) { logger.e(TAG, "AudioTrack.write error=$written — стоп"); break }
                    totalFrames += written
                    replay.add(s.pcm)
                }
                // дать доиграть остаток буфера (write вернулся, но звук ещё проигрывается).
                // Ограничиваем ожидание длительностью аудио + 1с — защита от вечного цикла.
                myTrack?.let { t ->
                    val maxWaitMs = totalFrames.toLong() * 1000 / maxOf(1, sampleRate) + 1000
                    var waited = 0L
                    while (isActive && waited < maxWaitMs &&
                        t.playState == AudioTrack.PLAYSTATE_PLAYING &&
                        t.playbackHeadPosition < totalFrames
                    ) {
                        delay(50); waited += 50
                    }
                }
            } catch (e: CancellationException) {
                throw e // структурированная отмена + не пишем мусор-кэш отменённого тёрна
            } catch (e: Exception) {
                logger.e(TAG, "догон stream error: ${e.message}")
            } finally {
                producer.cancel()
                myTrack?.let { try { it.stop(); it.release() } catch (_: Exception) {} }
                // Сбрасываем общие поля, только если их ещё не перехватил новый прогон.
                synchronized(trackLock) {
                    if (track === myTrack) {
                        track = null
                        playingNow = false
                    }
                }
            }
            // единый WAV для replay (мгновенный, с длительностью) — НЕ выполнится при отмене (CE выше)
            if (replay.isNotEmpty() && sampleRate > 0) playback.cacheStreamedAudio(runId, replay, sampleRate)
            logger.d(TAG, "догон finish runId=${runId.take(8)} chunks=${replay.size} frames=$totalFrames")
        }
    }

    /** Скормить кумулятивный (СЫРОЙ) текст дельты: новые завершённые фразы → в синтез. */
    override fun feed(runId: String, cumulativeRaw: String) {
        if (activeRunId != runId) return
        val sents = segmenter.newSentences(cumulativeRaw)
        if (sents.isNotEmpty()) logger.d(TAG, "feed +${sents.size} фраз")
        sents.forEach { channel?.trySend(it) }
    }

    /** Финал: остаток текста → в синтез, закрыть канал (consumer доиграет и кэширует replay). */
    override fun finish(runId: String, fullTextRaw: String) {
        if (activeRunId != runId) return
        val tail = segmenter.flush(fullTextRaw)
        logger.d(TAG, "finish runId=${runId.take(8)} tail='${tail?.take(28)}'")
        tail?.let { channel?.trySend(it) }
        channel?.close()
        activeRunId = null
        channel = null
    }

    /** Прервать догон (новый тёрн/abort/error): pause трека прерывает блокирующий write
     *  немедленно (иначе corutine висит в write до ~1с), затем отмена job → finally
     *  остановит/освободит AudioTrack. */
    override fun cancel() {
        activeRunId = null
        channel = null
        track?.let { try { it.pause() } catch (_: Exception) {} }
        job?.cancel()
        job = null
    }

    private fun newTrack(sampleRate: Int): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufBytes = maxOf(minBuf, sampleRate * 4) // ~2с буфер (sampleRate×2сэмпла × 2 байта) — запас от джиттера, чтобы не было прерываний
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    companion object {
        private const val TAG = "StreamingTts"
    }
}
