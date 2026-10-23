package com.nya.dstranslate.speech

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.k2fsa.sherpa.onnx.getFeatureConfig
import com.nya.dstranslate.data.AsrModelStore
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Локальное распознавание речи на устройстве: Qwen3-ASR-0.6B (int8) через
 * sherpa-onnx + Silero VAD.
 *
 * Почему отдельный движок: системный `SpeechRecognizer` на этом планшете
 * путает русский и китайский, а переключать язык он умеет только на границе
 * сеанса (и часто вообще не умеет). Qwen3-ASR определяет язык сам и внутри
 * одной фразы, поэтому понимает смешанную речь.
 *
 * Схема: микрофон 16 кГц → Silero VAD режет речь на фразы по паузам →
 * каждая фраза декодируется offline-моделью → текст уходит в перевод.
 * Партиалов нет намеренно: автогрессивный декодер на каждый пере-декод
 * жёг бы батарею и дёргал текст.
 */
class LocalAsrEngine(private val ctx: Context) {

    var onFinal: ((String) -> Unit)? = null
    var onSilence: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onListeningChanged: ((Boolean) -> Unit)? = null

    /** Идёт декодирование уже сказанной фразы — интерфейс показывает «распознаю». */
    var onDecodingChanged: ((Boolean) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val store = AsrModelStore(ctx)

    /** Готовые VAD-сегменты ждут декодера. Маленькая очередь — не копим минуты речи. */
    private val segments = ArrayBlockingQueue<FloatArray>(4)

    @Volatile private var running = false
    @Volatile private var paused = false
    @Volatile private var stopAfterFinal = false

    private var recognizer: OfflineRecognizer? = null
    private var vad: Vad? = null
    private var recorder: AudioRecord? = null

    private var audioThread: Thread? = null
    private var decodeThread: Thread? = null

    /** Пауза, после которой VAD считает фразу законченной. */
    private var silenceSeconds = 1.0f

    fun isReady(): Boolean = store.isReady()

    fun modelStore(): AsrModelStore = store

    fun start(longPause: Boolean, stopAfterFirst: Boolean) {
        if (running) return
        stopAfterFinal = stopAfterFirst
        silenceSeconds = if (longPause) 1.0f else 0.5f
        running = true
        paused = false
        segments.clear()
        main.post { onListeningChanged?.invoke(true) }
        startDecoder()
        startAudio()
    }

    fun stop() {
        val wasRunning = running
        running = false
        paused = false
        runCatching { recorder?.stop() }
        if (wasRunning) main.post { onListeningChanged?.invoke(false) }
    }

    /** Временная пауза (например, пока приложение говорит само). Движок остаётся в памяти. */
    fun pause() {
        if (!running || paused) return
        paused = true
        runCatching { recorder?.stop() }
        main.post { onListeningChanged?.invoke(false) }
    }

    fun resume() {
        if (!running || !paused) return
        paused = false
        segments.clear()
        main.post { onListeningChanged?.invoke(true) }
        startAudio()
    }

    fun release() {
        running = false
        paused = false
        runCatching { recorder?.stop() }
        recorder = null
        audioThread?.interrupt()
        decodeThread?.interrupt()
        audioThread = null
        decodeThread = null
        runCatching { recognizer?.release() }
        runCatching { vad?.release() }
        recognizer = null
        vad = null
    }

    // ------------------------------------------------------------------ потоки

    private fun startDecoder() {
        if (decodeThread?.isAlive == true) return
        decodeThread = Thread {
            while (running) {
                val samples = try {
                    segments.poll(200, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    null
                } ?: continue
                decodeSegment(samples)
            }
        }.apply {
            name = "local-asr-decode"
            isDaemon = true
            start()
        }
    }

    private fun startAudio() {
        if (audioThread?.isAlive == true) return
        audioThread = Thread {
            try {
                ensureLoaded()
                val rec = createRecorder()
                if (rec == null) {
                    fail("Не удалось открыть микрофон")
                    return@Thread
                }
                recorder = rec
                rec.startRecording()
                val shortBuffer = ShortArray(WINDOW)
                val floatBuffer = FloatArray(WINDOW)
                while (running && !paused) {
                    val n = rec.read(shortBuffer, 0, shortBuffer.size)
                    if (n <= 0) continue
                    for (i in 0 until n) floatBuffer[i] = shortBuffer[i] / 32768f
                    val samples = if (n == WINDOW) floatBuffer.copyOf() else floatBuffer.copyOf(n)
                    val v = vad ?: break
                    v.acceptWaveform(samples)
                    while (!v.empty()) {
                        val segment = v.front()
                        v.pop()
                        if (segment.samples.size >= MIN_SEGMENT_SAMPLES) {
                            // Если декодер не успевает — выбрасываем старый сегмент,
                            // иначе перевод отстаёт от разговора на минуты.
                            segments.offer(segment.samples)
                        }
                    }
                }
            } catch (t: Throwable) {
                fail("Ошибка записи: ${t.message ?: t::class.java.simpleName}")
            } finally {
                runCatching { recorder?.stop() }
                runCatching { recorder?.release() }
                recorder = null
                if (!running || paused) main.post { onListeningChanged?.invoke(false) }
            }
        }.apply {
            name = "local-asr-audio"
            isDaemon = true
            start()
        }
    }

    private fun decodeSegment(samples: FloatArray) {
        val rec = recognizer ?: return
        main.post { onDecodingChanged?.invoke(true) }
        try {
            val stream = rec.createStream()
            try {
                stream.acceptWaveform(samples, SAMPLE_RATE)
                rec.decode(stream)
                val text = rec.getResult(stream).text.trim()
                if (text.isNotEmpty()) {
                    main.post { onFinal?.invoke(text) }
                    if (stopAfterFinal) {
                        stop()
                        return
                    }
                } else {
                    main.post { onSilence?.invoke() }
                }
            } finally {
                stream.release()
            }
        } catch (t: Throwable) {
            fail("Ошибка распознавания: ${t.message ?: t::class.java.simpleName}")
        } finally {
            main.post { onDecodingChanged?.invoke(false) }
        }
    }

    // ------------------------------------------------------------------ модель

    private fun ensureLoaded() {
        if (recognizer != null && vad != null) return
        if (!store.isReady()) {
            throw IllegalStateException("Модель распознавания не скачана")
        }
        val dir = store.dir
        try {
            vad = Vad(
                ctx.assets,
                VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = "silero_vad.onnx",
                        threshold = 0.5f,
                        minSilenceDuration = silenceSeconds,
                        minSpeechDuration = 0.3f,
                        windowSize = WINDOW,
                        maxSpeechDuration = 15f
                    ),
                    sampleRate = SAMPLE_RATE,
                    numThreads = 1,
                    provider = "cpu"
                )
            )
            recognizer = OfflineRecognizer(
                null,
                OfflineRecognizerConfig(
                    featConfig = getFeatureConfig(SAMPLE_RATE, FEATURE_DIM),
                    modelConfig = OfflineModelConfig(
                        qwen3Asr = OfflineQwen3AsrModelConfig(
                            convFrontend = File(dir, "conv_frontend.onnx").absolutePath,
                            encoder = File(dir, "encoder.int8.onnx").absolutePath,
                            decoder = File(dir, "decoder.int8.onnx").absolutePath,
                            // Внимание: tokenizer — это КАТАЛОГ, а не файл.
                            tokenizer = store.tokenizerPath(),
                            maxTotalLen = 512,
                            // Дефолт 128 обрезает длинную фразу — для реплик из
                            // нескольких предложений нужно больше.
                            maxNewTokens = 512
                        ),
                        tokens = "",
                        numThreads = threads(),
                        provider = "cpu"
                    )
                )
            )
        } catch (e: OutOfMemoryError) {
            throw IllegalStateException(
                "Не хватило памяти на модель распознавания (~1 ГБ). " +
                    "Освободите память или верните системный движок."
            )
        }
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

    private companion object {
        const val SAMPLE_RATE = 16000
        const val FEATURE_DIM = 80
        const val WINDOW = 512
        const val MIN_SEGMENT_SAMPLES = SAMPLE_RATE / 2 // короче 0.5 с не распознаём
    }
}
