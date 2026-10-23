package com.nya.dstranslate.speech

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
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
 * Потоковое распознавание для вкладки «Авто».
 *
 * Задача движка — не потерять первые секунды. Поэтому запись начинается сразу,
 * ещё до того, как известен язык:
 *
 *  1. Микрофон пишет в кольцевой буфер в оперативной памяти (последние 20 секунд).
 *  2. Как только в буфере накопились первые ~1.6 с речи, отдельная корутина
 *     запускает LID (Whisper tiny). Запись и главный поток при этом не стоят.
 *  3. LID сказал `ru` или `zh` — загружается нужная streaming-модель
 *     (zipformer int8), и ей скармливается буфер **с самого начала реплики**.
 *     Первые слова не теряются, даже если модель грузилась две секунды.
 *  4. Дальше модель получает аудио с микрофона в реальном времени. Промежуточный
 *     текст отдаётся наружу (в интерфейсе он серый), перевод — только когда
 *     сработал endpoint и пришёл final.
 *  5. На каждой новой реплике LID запускается снова. Если язык сменился,
 *     старая модель выгружается, новая получает тот же буфер с начала реплики.
 *
 * Память: одновременно живут LID (~100 МБ) и ровно одна streaming-модель
 * (~25-29 МБ). При остановке записи и при смене языка модели освобождаются.
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
        var recognizer: OnlineRecognizer? = null
        var stream: OnlineStream? = null
        var lang: Lang? = null
        var fed: Long = 0L
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

    // ---------------------------------------------- речь в аудиопотоке (гейт)

    @Volatile private var utteranceStart = -1L
    @Volatile private var utteranceSpeechFrames = 0
    private var lastSpeechEnd = -1L
    fun isReady(): Boolean = store.isReady()

    fun modelStore(): StreamingModelStore = store

    /** Начать запись. Модели грузятся лениво, после LID — запись не ждёт. */
    fun start() {
        if (running) return
        if (!store.isReady()) {
            fail("Модели потокового распознавания не скачаны")
            return
        }
        runToken++
        running = true
        paused = false
        switchRequest = null
        resetOnResume = false
        utteranceStart = -1L
        utteranceSpeechFrames = 0
        lastSpeechEnd = -1L
        ring.reset()

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
     * не бегал по тишине. Слова он не режет — endpoint остаётся за моделью.
     */
    private fun updateGate(offset: Long, samples: FloatArray, count: Int) {
        var sum = 0.0
        for (i in 0 until count) {
            val v = samples[i].toDouble()
            sum += v * v
        }
        val rms = sqrt(sum / count).toFloat()
        if (rms < GATE_RMS) return

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
            st.lang?.let { st.stream?.let { s -> st.recognizer?.reset(s) } }
            st.lastPartial = ""
            utteranceStart = -1L
            utteranceSpeechFrames = 0
            lastSpeechEnd = -1L
            main.post { onPartialCleared?.invoke() }
        }
        maybeLaunchLid(st)

        val request = switchRequest
        if (request != null) {
            switchRequest = null
            if (st.lang != request.first) applyLanguage(st, request.first, request.second)
        }

        val rec = st.recognizer ?: return false
        val stream = st.stream ?: return false

        val total = ring.total()
        if (st.fed >= total) return false
        val chunk = ring.read(st.fed, FEED_CHUNK)
        if (chunk.isEmpty()) return false

        stream.acceptWaveform(chunk, SAMPLE_RATE)
        st.fed += chunk.size
        while (rec.isReady(stream)) rec.decode(stream)

        val text = rec.getResult(stream).text.trim()
        if (text != st.lastPartial) {
            st.lastPartial = text
            main.post { onPartial?.invoke(text) }
        }

        if (rec.isEndpoint(stream)) {
            val finalText = text
            val lang = st.lang ?: Lang.RU
            rec.reset(stream)
            st.lastPartial = ""
            if (finalText.isNotBlank()) {
                main.post { onFinal?.invoke(finalText, lang) }
            } else {
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
        if (st.lidInFlight) return
        val start = utteranceStart
        if (start < 0) return
        if (st.lastLidStart == start) return
        val total = ring.total()
        if (total - start < LID_WINDOW_SAMPLES) return
        if (utteranceSpeechFrames < LID_MIN_SPEECH_FRAMES) return
        val now = System.currentTimeMillis()
        if (now - st.lastLidAt < LID_RETRY_MS) return

        // Первая попытка — первые ~1.6 с. Если LID не разобрал, следующие
        // попытки берут больше речи (до 4 с), а не то же самое окно.
        val want = minOf((total - start).toInt(), LID_MAX_SAMPLES)
        val window = ring.read(start, want)
        if (window.size < LID_WINDOW_SAMPLES / 2) return

        st.lastLidStart = start
        st.lastLidAt = now
        st.lidAttempts++
        st.lidInFlight = true
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
                    if (st.lidAttempts >= LID_MAX_ATTEMPTS) {
                        if (st.lang == null) {
                            // LID так и не разобрал язык, а модели ещё нет.
                            // Не оставляем пользователя без распознавания:
                            // стартуем с русского; на следующей реплике LID
                            // попробует определить язык снова.
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
                st.lidAttempts = 0
                if (st.lang != lang) switchRequest = lang to start
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
                    numThreads = 1,
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
        releaseRecognizer(st)
        main.post { onLoading?.invoke(true) }
        try {
            if (!running) return
            if (!store.isLangReady(lang)) {
                throw IllegalStateException("Модель ${lang.label} не скачана")
            }
            val recognizer = createRecognizer(lang)
            st.recognizer = recognizer
            st.stream = recognizer.createStream("")
            st.lang = lang
            // Главное: кормим накопленный буфер с самого начала реплики,
            // а не с момента, когда LID закончил думать.
            st.fed = from.coerceAtLeast(0L)
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

    private fun createRecognizer(lang: Lang): OnlineRecognizer {
        val dir = store.dirFor(lang)
        val encoder: String
        val decoder: String
        val joiner: String
        if (lang == Lang.RU) {
            encoder = File(dir, "encoder.int8.onnx").absolutePath
            decoder = File(dir, "decoder.onnx").absolutePath
            joiner = File(dir, "joiner.int8.onnx").absolutePath
        } else {
            encoder = File(dir, "encoder-epoch-99-avg-1.int8.onnx").absolutePath
            decoder = File(dir, "decoder-epoch-99-avg-1.int8.onnx").absolutePath
            joiner = File(dir, "joiner-epoch-99-avg-1.int8.onnx").absolutePath
        }
        val config = OnlineRecognizerConfig(
            featConfig = getFeatureConfig(SAMPLE_RATE, FEATURE_DIM),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = encoder,
                    decoder = decoder,
                    joiner = joiner
                ),
                tokens = File(dir, "tokens.txt").absolutePath,
                numThreads = threads(),
                debug = false,
                provider = "cpu"
            ),
            endpointConfig = EndpointConfig(
                // Пауза «конец фразы» — как в автодиалоге: вдох и заминка
                // внутри предложения фразу не рвут.
                rule1 = EndpointRule(false, 2.4f, 0f),
                rule2 = EndpointRule(true, 1.2f, 0f),
                // Страховка от бесконечного монолога.
                rule3 = EndpointRule(false, 20f, 0f)
            ),
            enableEndpoint = true,
            decodingMethod = "greedy_search",
            maxActivePaths = 4
        )
        return OnlineRecognizer(null, config)
    }

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
        const val SAMPLE_RATE = 16000
        const val FEATURE_DIM = 80
        const val WINDOW = 512

        /** Сколько секунд аудио хранить в памяти. С запасом на загрузку модели. */
        const val RING_SECONDS = 20

        /** Сколько кормить модели за один шаг (100 мс). */
        const val FEED_CHUNK = SAMPLE_RATE / 10

        /** Окно для LID: первые ~1.6 секунды реплики. */
        const val LID_WINDOW_SAMPLES = (SAMPLE_RATE * 1.6f).toInt()

        /** Если LID не уверен — следующие попытки слушают до 4 секунд. */
        const val LID_MAX_SAMPLES = SAMPLE_RATE * 4

        /** Сколько раз пробовать LID на одной реплике, прежде чем взять RU. */
        const val LID_MAX_ATTEMPTS = 5

        /** Минимум речи в окне, чтобы LID вообще имел смысл (~0.4 с). */
        const val LID_MIN_SPEECH_FRAMES = 12

        /** Неориентированный порог энергии: тишина и шум LID не запускают. */
        const val GATE_RMS = 0.010f

        /** Пауза, после которой начинается новая реплика (для LID). */
        const val GATE_GAP_SAMPLES = (SAMPLE_RATE * 0.7f).toLong()

        /** Если LID не разобрал язык — не чаще, чем раз в Х мс. */
        const val LID_RETRY_MS = 600L
    }
}
