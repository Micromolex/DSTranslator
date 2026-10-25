package com.nya.dstranslate.speech

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SpokenLanguageIdentification
import com.k2fsa.sherpa.onnx.SpokenLanguageIdentificationConfig
import com.k2fsa.sherpa.onnx.SpokenLanguageIdentificationWhisperConfig
import com.k2fsa.sherpa.onnx.getFeatureConfig
import com.nya.dstranslate.data.Lang
import com.nya.dstranslate.data.StreamingModelStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import kotlin.math.sqrt

/**
 * Распознавание для вкладки «Авто».
 *
 * Задача движка — не потерять первые секунды. Поэтому запись начинается сразу,
 * ещё до того, как известен язык:
 *
 *  1. Микрофон пишет в кольцевой буфер в оперативной памяти (последние 20 секунд).
 *  2. Как только в буфере накопились первые ~1.8 с речи (порог по энергии,
 *     тишина и фоновый шум окно не запускают), отдельная корутина запускает
 *     LID (Whisper tiny, int8, 2 потока). Запись и главный поток не стоят.
 *     Короткое окно 0.8 с оказалось мало: Whisper принимал русскую фонетику
 *     за китайскую, и реплику декодировала не та модель. 1.8 с — компромисс
 *     между задержкой старта и надёжностью определения.
 *  3. LID сказал `ru` или `zh` — загружается нужная модель и получает буфер
 *     **с самого начала реплики**. Первые слова не теряются, даже если модель
 *     грузилась секунду-две. Обе модели — потоковые zipformer'ы
 *     ([OnlineRecognizer]): и русская, и китайская отдают промежуточный текст
 *     по мере речи и закрывают фразу по endpoint библиотеки.
 *  4. Пока реплика не закрыта, текст приходит в [onPartial] и показывается
 *     серой «субтитрной» плашкой. В DeepSeek уходит только final из [onFinal].
 *  5. На каждой новой реплике LID запускается снова. Если язык сменился,
 *     старая модель выгружается, новая получает буфер с начала реплики.
 *
 * Память: одновременно живут LID (~100 МБ) и ровно одна языковая модель
 * (~29 МБ русская / ~25 МБ китайская int8). При остановке записи и при смене
 * языка модели освобождаются.
 *
 * Моно-режимы («только RU» / «только ZH») используют этот же движок, но
 * с зафиксированным языком: [start] получает `lang`, LID не запускается вовсе,
 * нужная модель грузится сразу.
 */
class StreamingAutoEngine(private val ctx: Context) {

    // ------------------------------------------------------------- колбэки

    /** Промежуточный текст: показывать серым, НЕ переводить. */
    var onPartial: ((String) -> Unit)? = null

    /** Реплика закрыта endpoint'ом: только этот текст уходит в перевод. */
    var onFinal: ((String, Lang) -> Unit)? = null

    /** Язык текущей streaming-модели определён/сменился. */
    var onLanguage: ((Lang) -> Unit)? = null

    /** Промежуточный текст больше не актуален — стереть серую строку. */
    var onPartialCleared: (() -> Unit)? = null

    /** Речь была, но распознать не удалось (или фраза из одних пауз). */
    var onSilence: (() -> Unit)? = null

    var onError: ((String) -> Unit)? = null
    var onListeningChanged: ((Boolean) -> Unit)? = null

    /**
     * Нейтральное уведомление по ходу работы: например, что модель языка
     * не скачана и реплика пропущена (движок при этом продолжает слушать).
     */
    var onNotice: ((String) -> Unit)? = null

    /** Идёт загрузка/выгрузка модели (полезно для индикатора). */
    var onLoading: ((Boolean) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val store = StreamingModelStore(ctx)
    private val ring = AudioRing(SAMPLE_RATE * RING_SECONDS)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Модель и поток трогает только декодер; остановка ждёт этот замок. */
    private val modelLock = Any()

    /** Один прогон записи: своя модель, свой поток, свой буфер позиций. */
    private class RunState {
        /**
         * Потоковая модель sherpa-onnx: русская либо китайская zipformer.
         * Обе — OnlineRecognizer, поэтому ветка декодирования одна.
         */
        var recognizer: OnlineRecognizer? = null
        var stream: OnlineStream? = null

        var lang: Lang? = null
        var fed: Long = 0L

        /**
         * Абсолютный индекс сэмпла, с которого начинается ТЕКУЩИЙ сегмент.
         * По нему считается жёсткий лимит длительности: 10 секунд речи —
         * и сегмент закрывается принудительно, даже если человек не замолчал.
         */
        var segStart: Long = 0L

        var lastPartial: String = ""

        /** Индекс начала реплики, для которой LID уже запускали. */
        @Volatile var lastLidStart: Long = -1L
        @Volatile var lastLidAt: Long = 0L
        @Volatile var lidAttempts: Int = 0

        var lid: SpokenLanguageIdentification? = null
        val lidLock = Any()

        /** LID уже думает по окну этой реплики. */
        @Volatile var lidInFlight = false
    }

    @Volatile private var running = false
    @Volatile private var paused = false

    /** Поколение прогона: старые потоки не должны мешать новому запуску. */
    @Volatile private var runToken = 0

    /** Поколение аудиопотока: пауза/остановка не дают старому потоку ожить. */
    @Volatile private var audioToken = 0

    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var runState: RunState? = null

    private var audioThread: Thread? = null
    private var decodeThread: Thread? = null

    /** Ещё не применённое к модели решение LID: язык и начало реплики. */
    @Volatile private var switchRequest: Pair<Lang, Long>? = null

    /** После паузы (озвучка) текущую недописанную фразу начинаем заново. */
    @Volatile private var resetOnResume = false

    /**
     * Зафиксированный язык (моно-режимы) или null для обычного «Авто».
     * При не-null LID не нужен: нужная модель грузится сразу и не меняется.
     */
    @Volatile private var fixedLang: Lang? = null

    // ---------------------------------------------- речь в аудиопотоке (гейт)

    @Volatile private var utteranceStart = -1L
    @Volatile private var utteranceSpeechFrames = 0
    @Volatile private var lastSpeechEnd = -1L

    /**
     * Оценка уровня фонового шума (RMS), обновляется только на тихих кадрах.
     * По ней поднимается порог речи, чтобы в шумной комнате LID не запускался
     * от гула, а в тихой — не пропускал негромкую речь.
     */
    @Volatile private var noiseFloor = 0.002f
    fun isReady(): Boolean = store.isReady()

    /** Хватает ли моделей для режима: null — обычное «Авто», иначе один язык. */
    fun isReadyFor(lang: Lang?): Boolean = store.isReadyFor(lang)

    fun modelStore(): StreamingModelStore = store

    /**
     * Начать запись. [lang] = null — обычное «Авто» с LID; иначе моно-режим
     * «только RU» / «только ZH»: язык зафиксирован, LID не используется.
     * Модели грузятся лениво — запись не ждёт загрузки.
     */
    fun start(lang: Lang? = null) {
        if (running) return
        if (!store.isReadyFor(lang)) {
            fail(
                if (lang == null) {
                    "Модели потокового распознавания не скачаны"
                } else {
                    "Модель ${lang.label} не скачана"
                }
            )
            return
        }
        runToken++
        running = true
        paused = false
        fixedLang = lang
        // Для моно-режима сразу заказываем единственную нужную модель.
        // from = 0 — буфер кольца пуст, реплика начинается с самого старта.
        switchRequest = lang?.let { it to 0L }
        resetOnResume = false
        utteranceStart = -1L
        utteranceSpeechFrames = 0
        lastSpeechEnd = -1L
        // Каждый запуск начинает оценку фона заново: комната и микрофон
        // могли смениться, старая цифра только помешала бы.
        noiseFloor = 0.002f
        ring.reset()

        Log.i(
            TAG,
            "Старт распознавания: режим ${if (lang == null) "Авто (LID)" else "только ${lang.tag}"}, " +
                "RU=${store.ruDir.absolutePath}, ZH=${store.zhDir.absolutePath}"
        )

        val st = RunState()
        runState = st
        main.post { onListeningChanged?.invoke(true) }
        startDecode(st, runToken)
        startAudio(runToken)
    }

    /** Полная остановка: модели выгружаются из памяти. */
    fun stop() {
        val wasRunning = running
        running = false
        paused = false
        fixedLang = null
        runToken++
        audioToken++
        resetOnResume = false
        runCatching { recorder?.stop() }
        // LID может занимать сотни мегабайт и грузиться секундами — не держим
        // из-за этого главный поток. releaseLid дождётся compute под своим lock.
        val st = runState
        if (st != null) scope.launch { releaseLid(st) }
        if (wasRunning) main.post { onListeningChanged?.invoke(false) }
    }

    /** Пауза на время озвучки — модель и буфер остаются, микрофон выключается. */
    fun pause() {
        if (!running || paused) return
        paused = true
        audioToken++
        runCatching { recorder?.stop() }
        main.post { onListeningChanged?.invoke(false) }
    }

    fun resume() {
        if (!running || !paused) return
        paused = false
        // Пока звучала озвучка, микрофон не писал: склеивать обрывок с тем,
        // что было до паузы, нельзя — начинаем фразу заново.
        resetOnResume = true
        main.post { onListeningChanged?.invoke(true) }
        startAudio(runToken)
    }

    /** Полное освобождение — при закрытии ViewModel. */
    fun release() {
        running = false
        paused = false
        fixedLang = null
        runToken++
        audioToken++
        resetOnResume = false
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        val st = runState
        if (st != null) scope.launch { releaseLid(st) }
        audioThread = null
        decodeThread = null
        runState = null
    }

    // ------------------------------------------------------------------ аудио

    private fun startAudio(token: Int) {
        // Новое поколение: старый поток (если ещё выходит) больше не пишет.
        val myAudio = ++audioToken
        audioThread = Thread {
            var rec: AudioRecord? = null
            try {
                rec = createRecorder()
                if (rec == null) {
                    fail("Не удалось открыть микрофон")
                    return@Thread
                }
                recorder = rec
                rec.startRecording()
                val shortBuffer = ShortArray(WINDOW)
                val floatBuffer = FloatArray(WINDOW)
                while (running && !paused && token == runToken && myAudio == audioToken) {
                    val n = rec.read(shortBuffer, 0, shortBuffer.size)
                    if (n <= 0) continue
                    for (i in 0 until n) floatBuffer[i] = shortBuffer[i] / 32768f
                    val offset = ring.total()
                    ring.append(floatBuffer, n)
                    updateGate(offset, floatBuffer, n)
                }
            } catch (t: Throwable) {
                fail("Ошибка записи: ${t.message ?: t::class.java.simpleName}")
            } finally {
                // Останавливаем и освобождаем именно СВОЙ AudioRecord: за время
                // паузы мог стартовать новый поток со своим объектом.
                rec?.let {
                    runCatching { it.stop() }
                    runCatching { it.release() }
                }
                if (recorder === rec) recorder = null
                if (Thread.currentThread() === audioThread) audioThread = null
                if (!running || paused) main.post { onListeningChanged?.invoke(false) }
            }
        }.apply {
            name = "streaming-auto-audio"
            isDaemon = true
            start()
        }
    }

    /**
     * Простейший детектор речи по энергии: нужен только для того, чтобы LID
     * не бегал по тишине. Слова он не режет — границы сегментов дальше
     * определяет endpoint самой модели (у RU и ZH он настроен одинаково).
     */
    private fun updateGate(offset: Long, samples: FloatArray, count: Int) {
        var sum = 0.0
        for (i in 0 until count) {
            val v = samples[i].toDouble()
            sum += v * v
        }
        val rms = sqrt(sum / count).toFloat()

        // Порог речи = больший из абсолютного минимума и уровня шума с запасом:
        // так гул кондиционера не считается за начало реплики.
        val threshold = maxOf(GATE_MIN_RMS, noiseFloor * GATE_SPEECH_RATIO)
        if (rms < threshold) {
            // Тихий кадр — медленно подтягиваем оценку фона к его уровню.
            noiseFloor = (noiseFloor * 0.99f + rms * 0.01f).coerceIn(0.0005f, 0.02f)
            return
        }

        val chunkEnd = offset + count
        // Пауза длиннее GAP — это уже новая реплика, для неё нужен новый LID.
        if (utteranceStart < 0 || lastSpeechEnd < 0 ||
            offset - lastSpeechEnd > GATE_GAP_SAMPLES
        ) {
            utteranceStart = offset
            utteranceSpeechFrames = 0
        }
        utteranceSpeechFrames++
        lastSpeechEnd = chunkEnd
    }

    // ---------------------------------------------------------------- декодер

    private fun startDecode(st: RunState, token: Int) {
        // Не переиспользуем старый поток: после stop() он может ещё выходить,
        // а новый прогон обязан начать декодирование сразу.
        decodeThread = Thread {
            try {
                while (running && token == runToken) {
                    val worked = try {
                        synchronized(modelLock) {
                            if (!running || token != runToken) false else step(st)
                        }
                    } catch (e: OutOfMemoryError) {
                        fail("Не хватило памяти на модель распознавания")
                        false
                    } catch (t: Throwable) {
                        fail("Ошибка распознавания: ${t.message ?: t::class.java.simpleName}")
                        false
                    }
                    if (!worked) {
                        try {
                            Thread.sleep(15)
                        } catch (_: InterruptedException) {
                        }
                    }
                }
            } finally {
                synchronized(modelLock) { releaseRecognizer(st) }
                if (Thread.currentThread() === decodeThread) decodeThread = null
            }
        }.apply {
            name = "streaming-auto-decode"
            isDaemon = true
            start()
        }
    }

    /** Один шаг: применить LID, скормить новый кусок, отдать partial/final. */
    private fun step(st: RunState): Boolean {
        if (resetOnResume) {
            resetOnResume = false
            // Обе модели потоковые — у каждой есть состояние, его и сбрасываем.
            st.lang?.let { st.stream?.let { s -> st.recognizer?.reset(s) } }
            st.lastPartial = ""
            utteranceStart = -1L
            utteranceSpeechFrames = 0
            lastSpeechEnd = -1L
            // После паузы начинается новый сегмент: лимит 10 с отсчитываем
            // от того места, где запись продолжится.
            st.segStart = st.fed
            main.post { onPartialCleared?.invoke() }
        }
        maybeLaunchLid(st)

        val request = switchRequest
        if (request != null) {
            switchRequest = null
            if (st.lang != request.first) applyLanguage(st, request.first, request.second)
        }

        // Язык ещё не определён (LID думает) — модель не грузим, аудио копится
        // в кольцевом буфере и потом уйдёт модели с начала реплики.
        if (st.lang == null) return false

        val total = ring.total()
        if (st.fed >= total) return false
        val chunk = ring.read(st.fed, FEED_CHUNK)
        if (chunk.isEmpty()) return false

        val rec = st.recognizer ?: return false
        val stream = st.stream ?: return false

        stream.acceptWaveform(chunk, SAMPLE_RATE)
        st.fed += chunk.size
        while (rec.isReady(stream)) rec.decode(stream)

        val text = rec.getResult(stream).text.trim()
        if (text != st.lastPartial) {
            st.lastPartial = text
            main.post { onPartial?.invoke(text) }
        }

        if (rec.isEndpoint(stream) || st.fed - st.segStart >= MAX_SEGMENT_SAMPLES) {
            // Две причины закрыть сегмент:
            //  1) endpoint модели — пауза 1.2/2.4 с (обычный конец фразы);
            //  2) жёсткий лимит 10 с — человек говорит непрерывно с короткими
            //     паузами, и без отсечки экран заполнялся бы одной гигантской
            //     репликой, а перевод приходил бы слишком поздно.
            val finalText = text
            val lang = st.lang ?: Lang.RU
            rec.reset(stream)
            st.lastPartial = ""
            // Следующий сегмент начинается ровно с этого места: аудио между
            // отсечкой и стартом нового сегмента не теряется.
            st.segStart = st.fed
            if (finalText.isNotBlank()) {
                Log.i(TAG, "Final (${lang.tag}, zipformer): $finalText")
                main.post { onFinal?.invoke(finalText, lang) }
            } else {
                Log.i(TAG, "Final (${lang.tag}, zipformer): пусто — в перевод не отправляем")
                main.post { onSilence?.invoke() }
            }
            main.post { onPartialCleared?.invoke() }
            // Следующая реплика снова пройдёт LID: собеседник мог смениться.
            utteranceStart = -1L
            utteranceSpeechFrames = 0
            lastSpeechEnd = -1L
        }
        return true
    }

    // ------------------------------------------------------------------- LID

    private fun maybeLaunchLid(st: RunState) {
        // Моно-режим: язык зафиксирован чипом, LID и Whisper tiny не нужны.
        if (fixedLang != null) return
        if (st.lidInFlight) return
        val start = utteranceStart
        if (start < 0) return
        if (st.lastLidStart == start) return
        val total = ring.total()
        if (total - start < LID_WINDOW_SAMPLES) return
        if (utteranceSpeechFrames < LID_MIN_SPEECH_FRAMES) return
        val now = System.currentTimeMillis()
        if (now - st.lastLidAt < LID_RETRY_MS) return

        // Окно считаем по РЕЧИ, а не по времени с начала реплики: если человек
        // замолчал на секунду, хвостовая тишина не должна разбавлять фонетику.
        // Для короткой реплики (речи меньше окна) берём всё, что есть, —
        // иначе LID на ней не запустился бы вовсе.
        val speechEnd = lastSpeechEnd
        val speechSamples = if (speechEnd > start) (speechEnd - start).toInt() else 0
        val totalSamples = (total - start).toInt()
        val enoughSpeech = speechSamples >= LID_WINDOW_SAMPLES
        if (!enoughSpeech && totalSamples < SHORT_UTTERANCE_SAMPLES) return
        val available = if (enoughSpeech) speechSamples else totalSamples

        // Первая попытка — до ~1.8 с речи. Короткого окна 0.8 с не хватало:
        // Whisper tiny принимал русскую фонетику за китайскую, и реплику
        // декодировала китайская модель (иероглифы вместо букв). Если язык
        // всё же не разобран, следующие попытки берут до 2.6 с.
        val attemptNo = st.lidAttempts + 1
        val want = if (attemptNo <= 1) LID_WINDOW_SAMPLES else LID_MAX_SAMPLES
        val window = ring.read(start, minOf(available, want))
        if (window.size < LID_WINDOW_SAMPLES / 2) return

        st.lastLidStart = start
        st.lastLidAt = now
        st.lidAttempts++
        st.lidInFlight = true
        val attempt = st.lidAttempts
        val token = runToken
        scope.launch {
            try {
                val lang = detectLanguage(st, window)
                if (!running || token != runToken) return@launch
                // Пока LID думал, реплика могла закончиться. Результат по
                // старому окну применять нельзя: модель уже ушла вперёд,
                // а повторная подача старого аудио дала бы дубль перевода.
                if (start != utteranceStart) return@launch
                if (lang == null) {
                    Log.w(
                        TAG,
                        "LID detected: unknown (attempt $attempt, " +
                            "${window.size / SAMPLE_RATE.toFloat()} с) — окно расширяем"
                    )
                    if (st.lidAttempts >= LID_MAX_ATTEMPTS) {
                        if (st.lang == null) {
                            // LID так и не разобрал язык, а модели ещё нет.
                            // Не оставляем пользователя без распознавания:
                            // стартуем с русского; на следующей реплике LID
                            // попробует определить язык снова.
                            Log.w(TAG, "LID detected: unknown — фолбэк на RU")
                            switchRequest = Lang.RU to start
                        }
                        st.lidAttempts = 0
                        st.lastLidStart = start
                        return@launch
                    }
                    // Не разобрали язык — попробуем с более длинным окном.
                    st.lastLidStart = -1L
                    return@launch
                }
                Log.i(
                    TAG,
                    "LID detected: ${lang.tag} (attempt $attempt, " +
                        "${window.size / SAMPLE_RATE.toFloat()} с), текущая модель: " +
                        (st.lang?.tag ?: "ещё не выбрана")
                )
                st.lidAttempts = 0
                if (st.lang != lang) {
                    Log.i(TAG, "LID switch: ${st.lang?.tag ?: "none"} -> ${lang.tag}")
                    switchRequest = lang to start
                }
            } catch (e: OutOfMemoryError) {
                fail("Не хватило памяти на определение языка")
            } catch (t: Throwable) {
                fail("Ошибка определения языка: ${t.message ?: t::class.java.simpleName}")
            } finally {
                st.lidInFlight = false
            }
        }
    }

    private fun detectLanguage(st: RunState, samples: FloatArray): Lang? {
        val lid = ensureLid(st) ?: return null
        synchronized(st.lidLock) {
            if (!running) return null
            val stream = lid.createStream()
            try {
                stream.acceptWaveform(samples, SAMPLE_RATE)
                val code = lid.compute(stream).lowercase(Locale.ROOT)
                return when {
                    code.startsWith("ru") -> Lang.RU
                    code.startsWith("zh") -> Lang.ZH
                    else -> null
                }
            } finally {
                stream.release()
            }
        }
    }

    private fun ensureLid(st: RunState): SpokenLanguageIdentification? {
        synchronized(st.lidLock) {
            st.lid?.let { return it }
            if (!running) return null
            if (!store.isLidReady()) throw IllegalStateException("LID-модель не скачана")
            val lid = SpokenLanguageIdentification(
                null,
                SpokenLanguageIdentificationConfig(
                    whisper = SpokenLanguageIdentificationWhisperConfig(
                        encoder = File(store.lidDir, "tiny-encoder.int8.onnx").absolutePath,
                        decoder = File(store.lidDir, "tiny-decoder.int8.onnx").absolutePath,
                        tailPaddings = 0
                    ),
                    // 2 потока, а не 1: окно LID 1.8 с, и на нём распараллеливание
                    // заметно снижает задержку старта. Модель уже int8 — это
                    // главное ускорение.
                    numThreads = LID_THREADS,
                    debug = false,
                    provider = "cpu"
                )
            )
            st.lid = lid
            return lid
        }
    }

    private fun releaseLid(st: RunState?) {
        if (st == null) return
        synchronized(st.lidLock) {
            runCatching { st.lid?.release() }
            st.lid = null
        }
    }

    // ----------------------------------------------------------------- модели

    /** Вызывается только под [modelLock] из потока декодера. */
    private fun applyLanguage(st: RunState, lang: Lang, from: Long) {
        if (!running) return
        if (!store.isLangReady(lang)) {
            // Модели нет (например, русская не скачалась). Не роняем всё
            // «Авто»: сообщаем об этом и продолжаем слушать — китайский
            // язык остаётся рабочим.
            Log.w(TAG, "Using model: нет — модель ${lang.tag} не скачана")
            st.lang = null
            st.fed = ring.total()
            st.lastPartial = ""
            st.segStart = st.fed
            utteranceStart = -1L
            utteranceSpeechFrames = 0
            lastSpeechEnd = -1L
            main.post {
                onNotice?.invoke(
                    "Модель ${lang.label} не скачана — реплика на этом языке пропущена"
                )
                onPartialCleared?.invoke()
            }
            return
        }
        // Смена языка: старую модель, её поток и весь накопленный текст
        // выбрасываем ДО загрузки новой. Иначе обрывок партиала на прошлом
        // языке мог попасть в карточку рядом с иероглифами или кириллицей
        // новой модели.
        releaseRecognizer(st)
        resetDecodeState(st, from)
        main.post {
            onLoading?.invoke(true)
            onPartialCleared?.invoke()
        }
        try {
            if (!running) return
            // Обе языковые модели — потоковые zipformer'ы, поэтому ветка
            // загрузки одна: создать OnlineRecognizer и его поток.
            val recognizer = if (lang == Lang.RU) createRuRecognizer() else createZhRecognizer()
            st.recognizer = recognizer
            st.stream = recognizer.createStream("")
            Log.i(
                TAG,
                "Using model: zipformer ${lang.tag} (streaming) from " +
                    store.dirFor(lang).absolutePath
            )
            st.lang = lang
            // Главное: кормим накопленный буфер с самого начала реплики,
            // а не с момента, когда LID закончил думать.
            st.fed = from.coerceAtLeast(0L)
            // Лимит 10 с тоже отсчитываем от начала реплики, а не от загрузки
            // модели: иначе первые слова съедали бы часть лимита.
            st.segStart = st.fed
            st.lastPartial = ""
            main.post {
                onLanguage?.invoke(lang)
                onPartialCleared?.invoke()
            }
        } catch (e: OutOfMemoryError) {
            fail("Не хватило памяти на модель распознавания")
            running = false
        } catch (t: Throwable) {
            fail("Не удалось загрузить модель: ${t.message ?: t::class.java.simpleName}")
            running = false
        } finally {
            main.post { onLoading?.invoke(false) }
        }
    }

    /**
     * Полный сброс состояния распознавания при смене языка: новый язык
     * начинает с чистого листа и получает аудио ровно с начала реплики
     * ([from]). Старый поток/партиал уже освобождены [releaseRecognizer].
     *
     * Аудио в кольцевом буфере до [from] очищать не нужно: новый модели оно
     * не отдаётся, поэтому обрывки речи прошлого языка не склеиваются
     * с текстом новой. А старт сегмента обязательно сбрасывается — иначе
     * лимит 10 с считался бы от старой реплики.
     */
    private fun resetDecodeState(st: RunState, from: Long) {
        st.lastPartial = ""
        st.fed = from.coerceAtLeast(0L)
        st.segStart = st.fed
    }

    /**
     * Русская потоковая zipformer-модель (int8) из `models/streaming/ru`.
     *
     * Это настоящий OnlineRecognizer: он выдаёт частичный текст по мере
     * поступления аудио и сам решает, что фраза закончилась (endpoint).
     * Прежняя GigaAM была offline-моделью и партиалов не давала — русская
     * речь появлялась на экране только после паузы.
     *
     * `featureDim = 80` — стандартные мел-бины zipformer (у GigaAM было 64).
     * Перед вызовом JNI файлы проверяются и по наличию, и по точному размеру:
     * недокачанный файл не должен попасть в распознавание.
     */
    private fun createRuRecognizer(): OnlineRecognizer {
        val dir = store.ruDir
        val encoder = File(dir, "encoder.int8.onnx")
        val decoder = File(dir, "decoder.onnx")
        val joiner = File(dir, "joiner.int8.onnx")
        val tokens = File(dir, "tokens.txt")
        val expected = StreamingModelStore.RU_FILES
        val named = listOf(
            "encoder.int8.onnx" to encoder,
            "decoder.onnx" to decoder,
            "joiner.int8.onnx" to joiner,
            "tokens.txt" to tokens
        )
        val broken = named.filter { (name, file) ->
            val size = expected[name]?.bytes
            size == null || !file.isFile || file.length() != size
        }
        if (broken.isNotEmpty()) {
            throw IllegalStateException(
                "Модель zipformer RU повреждена: " + broken.joinToString { it.first } +
                    " — скачайте модели заново"
            )
        }
        return OnlineRecognizer(
            null,
            onlineConfig(encoder.absolutePath, decoder.absolutePath, joiner.absolutePath, tokens.absolutePath)
        )
    }

    /** Китайская потоковая zipformer-модель (без изменений с 1.6.10). */
    private fun createZhRecognizer(): OnlineRecognizer {
        val dir = store.zhDir
        val encoder = File(dir, "encoder-epoch-99-avg-1.int8.onnx").absolutePath
        val decoder = File(dir, "decoder-epoch-99-avg-1.int8.onnx").absolutePath
        val joiner = File(dir, "joiner-epoch-99-avg-1.int8.onnx").absolutePath
        return OnlineRecognizer(
            null,
            onlineConfig(encoder, decoder, joiner, File(dir, "tokens.txt").absolutePath)
        )
    }

    /**
     * Общий конфиг online-распознавания для обеих zipformer-моделей.
     * Отличаются только пути к файлам — правило endpoint и лимит сегмента
     * должны быть одинаковыми в обоих языках, иначе поведение фраз
     * в «Авто» и моно-режимах разъедется.
     */
    private fun onlineConfig(
        encoder: String,
        decoder: String,
        joiner: String,
        tokens: String
    ): OnlineRecognizerConfig = OnlineRecognizerConfig(
        featConfig = getFeatureConfig(SAMPLE_RATE, FEATURE_DIM),
        modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = encoder,
                decoder = decoder,
                joiner = joiner
            ),
            tokens = tokens,
            numThreads = threads(),
            debug = false,
            provider = "cpu"
        ),
        endpointConfig = EndpointConfig(
            // Пауза «конец фразы» — как в автодиалоге: вдох и заминка
            // внутри предложения фразу не рвут.
            rule1 = EndpointRule(false, 2.4f, 0f),
            rule2 = EndpointRule(true, 1.2f, 0f),
            // Страховка от бесконечного монолога: 10 секунд — жёсткий
            // предел сегмента. Дополнительно тот же лимит проверяется
            // в step() по счётчику сэмплов, поэтому отсечка не зависит
            // от версии sherpa-onnx и срабатывает гарантированно.
            rule3 = EndpointRule(false, 0f, MAX_SEGMENT_SECONDS)
        ),
        enableEndpoint = true,
        decodingMethod = "greedy_search",
        maxActivePaths = 4
    )

    /** Вызывается только под [modelLock]. */
    private fun releaseRecognizer(st: RunState?) {
        if (st == null) return
        runCatching { st.stream?.release() }
        st.stream = null
        runCatching { st.recognizer?.release() }
        st.recognizer = null
        st.lang = null
        st.lastPartial = ""
    }

    private fun threads(): Int =
        Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

    private fun createRecorder(): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (min <= 0) return null
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(min * 2, WINDOW * 8)
        )
        return if (rec.state == AudioRecord.STATE_INITIALIZED) {
            rec
        } else {
            runCatching { rec.release() }
            null
        }
    }

    private fun fail(message: String) {
        main.post { onError?.invoke(message) }
    }

    /** Кольцевой буфер в оперативной памяти: пишем сразу, читаем с любого места. */
    private class AudioRing(private val capacity: Int) {
        private val data = FloatArray(capacity)
        private var writePos = 0
        private var total = 0L

        @Synchronized
        fun reset() {
            writePos = 0
            total = 0L
        }

        @Synchronized
        fun total(): Long = total

        @Synchronized
        fun append(src: FloatArray, count: Int) {
            var copied = 0
            while (copied < count) {
                val pos = writePos
                val n = minOf(count - copied, capacity - pos)
                System.arraycopy(src, copied, data, pos, n)
                copied += n
                writePos = (pos + n) % capacity
                total += n
            }
        }

        /** Копия [maxCount] сэмплов начиная с абсолютного индекса [from]. */
        @Synchronized
        fun read(from: Long, maxCount: Int): FloatArray {
            val earliest = total - capacity
            val start = maxOf(from, earliest)
            val available = (total - start).toInt()
            if (available <= 0) return FloatArray(0)
            val n = minOf(available, maxCount)
            val out = FloatArray(n)
            var pos = (((writePos - (total - start)) % capacity + capacity) % capacity).toInt()
            var copied = 0
            while (copied < n) {
                val part = minOf(n - copied, capacity - pos)
                System.arraycopy(data, pos, out, copied, part)
                copied += part
                pos = (pos + part) % capacity
            }
            return out
        }
    }

    private companion object {
        /** Тег Logcat: `adb logcat -s StreamingASR`. */
        const val TAG = "StreamingASR"

        const val SAMPLE_RATE = 16000

        /** Мел-бины: 80 — стандарт для обеих zipformer-моделей (RU и ZH). */
        const val FEATURE_DIM = 80

        const val WINDOW = 512

        /** Сколько секунд аудио хранить в памяти. С запасом на загрузку модели. */
        const val RING_SECONDS = 20

        /** Сколько кормить модели за один шаг (100 мс). */
        const val FEED_CHUNK = SAMPLE_RATE / 10

        /**
         * Жёсткий лимит длительности одного сегмента распознавания.
         * 10 секунд: речь без длинных пауз принудительно режется на куски,
         * каждый кусок сразу уходит в перевод и в историю. Это защищает
         * экран от переполнения одной бесконечной карточкой.
         */
        const val MAX_SEGMENT_SECONDS = 10f

        /** Тот же лимит в сэмплах (10 с) — для проверки по счётчику в step(). */
        const val MAX_SEGMENT_SAMPLES = SAMPLE_RATE * 10L

        /**
         * Окно для LID — 1.8 с речи. Прежние 0.8 с оказались слишком коротки:
         * Whisper tiny на таком обрывке уверенно принимал русскую фонетику
         * за китайскую, и «Авто» грузил не ту модель (вместо кириллицы
         * появлялись иероглифы). 1.8 с — нижняя граница из 1.5–2 с, при
         * которой LID уже видит фонетику языка, но старт распознавания
         * остаётся быстрым.
         */
        const val LID_WINDOW_SAMPLES = (SAMPLE_RATE * 1.8f).toInt()

        /** Если LID не уверен — следующие попытки слушают до 2.6 с. */
        const val LID_MAX_SAMPLES = (SAMPLE_RATE * 2.6f).toInt()

        /**
         * Короткая реплика: речи меньше окна LID. После стольких секунд от её
         * начала (включая паузы) LID всё равно запускаем на том, что есть, —
         * иначе на «да»/«угу» распознавание вообще не стартовало бы.
         */
        const val SHORT_UTTERANCE_SAMPLES = (SAMPLE_RATE * 2.4f).toInt()

        /** Сколько раз пробовать LID на одной реплике, прежде чем взять RU. */
        const val LID_MAX_ATTEMPTS = 4

        /** Минимум речи в окне, чтобы LID вообще имел смысл (~0.32 с). */
        const val LID_MIN_SPEECH_FRAMES = 10

        /** Потоки LID: 2 (модель уже int8) — окно короткое, важна задержка. */
        const val LID_THREADS = 2

        /** Абсолютный минимум RMS, ниже которого кадр считается тишиной. */
        const val GATE_MIN_RMS = 0.010f

        /** Во сколько раз речь должна превышать оценку фона. */
        const val GATE_SPEECH_RATIO = 2.0f

        /** Пауза, после которой начинается новая реплика (для LID). */
        const val GATE_GAP_SAMPLES = (SAMPLE_RATE * 0.7f).toLong()

        /** Если LID не разобрал язык — не чаще, чем раз в Х мс. */
        const val LID_RETRY_MS = 400L
    }
}
