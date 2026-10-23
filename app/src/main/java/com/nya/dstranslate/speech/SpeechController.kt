package com.nya.dstranslate.speech

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Распознавание речи + озвучка.
 *
 * Ключевое правило: перед воспроизведением запись ПРИОСТАНАВЛИВАЕТСЯ,
 * после окончания озвучки — возобновляется. Микрофон никогда не пишет
 * собственный голос приложения, поэтому перевод не зацикливается.
 */
class SpeechController(private val ctx: Context) {

    var onPartial: ((String) -> Unit)? = null
    var onFinal: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onSilence: (() -> Unit)? = null
    var onListeningChanged: ((Boolean) -> Unit)? = null

    /** Определённый распознавателем язык (BCP-47), если сервис это поддерживает. */
    var onLanguageDetected: ((String) -> Unit)? = null

    /**
     * Речь началась, но за [PROBE_MS] не пришло ни одного частичного результата —
     * почти наверняка язык сеанса не тот. Позволяет переключиться ВНУТРИ фразы,
     * не дожидаясь её конца.
     */
    var onProbeFailed: (() -> Unit)? = null

    /** Идёт ли озвучка прямо сейчас — интерфейс показывает кнопку «стоп». */
    var onSpeakingChanged: ((Boolean) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var localeTag = "ru-RU"
    private var wantListening = false
    private var paused = false
    private var stopAfterFinal = false
    private var autoDetect = false

    /**
     * Длинная пауза «конец фразы» (режим автодиалога).
     *
     * По умолчанию распознаватель закрывает фразу после ~1 с тишины, и в живом
     * разговоре это рвёт предложение на куски: человек делает вдох — сеанс
     * закрывается, и следующее слово уходит в отдельную реплику. Для автодиалога
     * пауза увеличена настолько, чтобы вдох, «э-э» и короткая заминка внутри
     * предложения фразу не обрывали.
     */
    private var longPause = false
    private var restartScheduled = false
    private var switching = false
    private var sessionSeq = 0
    private var hadPartial = false
    private var partialsSupported = false
    private var lastForcedRestartAt = 0L
    private val lastPartial = StringBuilder()

    val isListening: Boolean get() = wantListening && !paused

    /** Язык, которым был запущен текущий (или последний) сеанс распознавания. */
    val currentLanguageTag: String get() = localeTag

    /** В последнем сеансе распознаватель услышал речь (но мог не распознать её). */
    var lastSessionHadSpeech: Boolean = false
        private set

    /** Устройство умеет сам определять и переключать язык распознавания. */
    val supportsAutoLanguage: Boolean get() = Build.VERSION.SDK_INT >= 34

    /** Ручное переопределение языка распознавания (null — автоопределение). */
    var forcedLanguageTag: String? = null
        private set

    fun setForcedLanguage(tag: String?) {
        if (tag == forcedLanguageTag) return
        forcedLanguageTag = tag
        if (wantListening && !paused) {
            main.post {
                switching = true
                restartScheduled = false
                runCatching { recognizer?.cancel() }
                main.postDelayed({
                    if (wantListening && !paused) beginSession() else switching = false
                }, 150)
            }
        }
    }

    fun isRecognitionAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(ctx)

    // ------------------------------------------------- заглушение «пиликанья»

    private val audio: AudioManager? =
        ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private var beepsMuted = false
    private var musicMuted = false

    /**
     * Системный «динь» включения/выключения микрофона идёт по STREAM_MUSIC /
     * STREAM_SYSTEM / STREAM_NOTIFICATION. Глушим их на время прослушивания,
     * чтобы в режиме автодиалога запись шла беззвучно и не прерывалась.
     */
    private fun muteBeeps() {
        val a = audio ?: return
        if (!beepsMuted) {
            beepsMuted = true
            runCatching {
                a.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_MUTE, 0)
                a.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_MUTE, 0)
            }
        }
        if (!musicMuted) {
            musicMuted = true
            runCatching { a.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0) }
        }
    }

    private fun unmuteMusic() {
        val a = audio ?: return
        if (!musicMuted) return
        musicMuted = false
        runCatching { a.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0) }
    }

    private fun unmuteAll() {
        val a = audio ?: return
        unmuteMusic()
        if (!beepsMuted) return
        beepsMuted = false
        runCatching {
            a.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_UNMUTE, 0)
            a.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_UNMUTE, 0)
        }
    }

    // ---------------------------------------------------------------- listening

    /**
     * @param stopAfterFinal true — режим «нажал и говори»: после первой распознанной
     *   фразы запись останавливается, и только потом запускается перевод и озвучка.
     * @param autoDetect true — режим автодиалога: ОДНА непрерывная запись, язык
     *   реплики определяется распознавателем на ходу (API 34+), микрофон не рвётся.
     * @param longPause true — не обрывать фразу на коротких паузах (см. [longPause]).
     */
    fun start(
        tag: String,
        stopAfterFinal: Boolean = false,
        autoDetect: Boolean = false,
        longPause: Boolean = false
    ) {
        localeTag = tag
        this.stopAfterFinal = stopAfterFinal
        this.autoDetect = autoDetect
        this.longPause = longPause
        wantListening = true
        paused = false
        restartScheduled = false
        lastPartial.setLength(0)
        main.post {
            ensureRecognizer()
            beginSession()
        }
    }

    /** Полная остановка. */
    fun stop() {
        wantListening = false
        paused = false
        main.post {
            restartScheduled = false
            runCatching { recognizer?.cancel() }
            onListeningChanged?.invoke(false)
            main.postDelayed({ unmuteAll() }, 400)
        }
    }

    /** Приостановить запись, запомнив, что она была активна. */
    fun pause() {
        if (!wantListening || paused) return
        paused = true
        main.post {
            restartScheduled = false
            runCatching { recognizer?.cancel() }
            onListeningChanged?.invoke(false)
        }
    }

    /** Возобновить запись после паузы. */
    fun resume() {
        if (!wantListening || !paused) return
        paused = false
        main.post { beginSession() }
    }

    /**
     * Пожелание на язык следующего сеанса распознавания.
     * Активный сеанс НЕ прерывается: смена применится, когда распознаватель
     * сам начнёт новый (после очередной фразы или тишины).
     */
    fun updateLanguage(tag: String) {
        localeTag = tag
    }

    /**
     * Немедленно перезапустить сеанс на другом языке, не дожидаясь конца фразы.
     * Используется, когда распознаватель услышал речь, но не смог её разобрать —
     * так хотя бы хвост фразы будет распознан на правильном языке.
     */
    fun restartNow(tag: String) {
        localeTag = tag
        if (!wantListening || paused) return
        // Не даём перезапускам идти сплошным потоком.
        val now = System.currentTimeMillis()
        if (now - lastForcedRestartAt < MIN_RESTART_GAP_MS) return
        lastForcedRestartAt = now

        switching = true
        restartScheduled = false
        val session = ++sessionSeq
        main.post {
            runCatching { recognizer?.cancel() }
            main.postDelayed({
                if (session == sessionSeq && wantListening && !paused) {
                    beginSession()
                } else {
                    switching = false
                }
            }, 120)
        }
    }

    fun release() {
        wantListening = false
        main.post {
            runCatching { recognizer?.destroy() }
            recognizer = null
        }
        runCatching { tts?.shutdown() }
        tts = null
        markSpeaking(false)
        unmuteAll()
    }

    private fun ensureRecognizer() {
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(ctx).apply {
                setRecognitionListener(listener)
            }
        }
    }

    private fun beginSession() {
        switching = false
        if (!wantListening || paused) return
        val rec = recognizer ?: return
        muteBeeps()
        lastPartial.setLength(0)
        lastSessionHadSpeech = false
        hadPartial = false
        sessionSeq++
        val multilingual = autoDetect && supportsAutoLanguage && forcedLanguageTag == null
        // Автодиалог не должен рвать предложение на паузах, остальные режимы
        // наоборот — должны закрывать фразу быстро.
        val completeSilence = if (longPause) SILENCE_LONG_MS else SILENCE_SHORT_MS
        val partialSilence = if (longPause) POSSIBLY_COMPLETE_LONG_MS else POSSIBLY_COMPLETE_SHORT_MS
        val minimumLength = if (longPause) MIN_LENGTH_LONG_MS else MIN_LENGTH_SHORT_MS
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)

            if (multilingual) {
                // EXTRA_LANGUAGE оставляем: без него качество распознавания падает.
                // Детект и переключение — как бонус, если распознаватель их умеет.
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, localeTag)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, localeTag)
                putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, true)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_MAX_SWITCHES, 100)
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_SWITCH_INITIAL_ACTIVE_DURATION_TIME_MILLIS,
                    8000L
                )
                putStringArrayListExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES,
                    arrayListOf(RU_TAG, ZH_TAG)
                )
                putStringArrayListExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES,
                    arrayListOf(RU_TAG, ZH_TAG)
                )
            } else {
                val tag = forcedLanguageTag ?: localeTag
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag)
            }
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                completeSilence
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                partialSilence
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                minimumLength
            )
        }
        runCatching { rec.startListening(intent) }
            .onFailure { onError?.invoke(it.message ?: "Ошибка запуска распознавания") }
        onListeningChanged?.invoke(true)
    }

    /** Язык, определённый распознавателем (API 34+). */
    private fun readDetectedLanguage(results: Bundle?): String? {
        if (results == null || Build.VERSION.SDK_INT < 34) return null
        val raw = runCatching { results.get(SpeechRecognizer.DETECTED_LANGUAGE) }.getOrNull() ?: return null
        return when (raw) {
            is Locale -> raw.toLanguageTag()
            is String -> raw
            else -> null
        }
    }

    private fun scheduleRestart(delayMs: Long) {
        if (!wantListening || paused || restartScheduled) return
        restartScheduled = true
        // В режиме автодиалога сеанс возобновляется без паузы — не теряем слова.
        val delay = if (autoDetect) 0L else delayMs
        main.postDelayed({
            restartScheduled = false
            if (wantListening && !paused) beginSession() else onListeningChanged?.invoke(false)
        }, delay)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {
            // Речь началась — именно этот признак отличает «говорили не на том
            // языке» от обычной тишины в разговоре.
            lastSessionHadSpeech = true
            // Зонд запускаем ТОЛЬКО если распознаватель уже доказал, что умеет
            // отдавать частичные результаты. Иначе он молчит по 2-3 секунды,
            // зонд решает «язык не тот» и перезапускает сеанс по кругу —
            // микрофон мигает, а распознавание не работает вообще.
            if (!autoDetect || !partialsSupported) return
            val session = sessionSeq
            main.postDelayed({
                if (session == sessionSeq && wantListening && !paused && !hadPartial) {
                    onProbeFailed?.invoke()
                }
            }, PROBE_MS)
        }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull().orEmpty()
            if (text.isNotBlank()) {
                hadPartial = true
                partialsSupported = true
                lastPartial.setLength(0)
                lastPartial.append(text)
                onPartial?.invoke(text)
            }
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull().orEmpty()
            val finalText = text.ifBlank { lastPartial.toString() }
            lastPartial.setLength(0)
            readDetectedLanguage(results)?.let { onLanguageDetected?.invoke(it) }
            if (finalText.isNotBlank()) onFinal?.invoke(finalText)
            if (stopAfterFinal) {
                // ГЛАВНОЕ: сначала глушим микрофон, потом уже перевод и озвучка.
                wantListening = false
                onListeningChanged?.invoke(false)
            } else {
                scheduleRestart(250)
            }
        }

        override fun onError(error: Int) {
            if (switching) return
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    val pending = lastPartial.toString()
                    lastPartial.setLength(0)
                    if (pending.isNotBlank()) {
                        onFinal?.invoke(pending)
                        if (stopAfterFinal) {
                            wantListening = false
                            onListeningChanged?.invoke(false)
                            return
                        }
                    } else {
                        onSilence?.invoke()
                        if (stopAfterFinal) {
                            wantListening = false
                            onListeningChanged?.invoke(false)
                            return
                        }
                    }
                    scheduleRestart(200)
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> scheduleRestart(600)
                SpeechRecognizer.ERROR_CLIENT -> scheduleRestart(400)
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    onError?.invoke("Нет разрешения на микрофон")
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                    onError?.invoke("Проблема с сетью распознавания")
                SpeechRecognizer.ERROR_AUDIO ->
                    onError?.invoke("Ошибка аудиоустройства")
                else -> onError?.invoke("Ошибка распознавания ($error)")
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // --------------------------------------------------------------------- TTS

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: Pair<String, String>? = null
    private var pausedBySpeech = false
    private var speakingNow = false

    /** Сообщить интерфейсу, что озвучка началась или закончилась. */
    private fun markSpeaking(value: Boolean) {
        if (speakingNow == value) return
        speakingNow = value
        main.post { onSpeakingChanged?.invoke(value) }
    }

    fun initTtsIfNeeded() {
        if (tts != null) return
        main.post {
            if (tts != null) return@post
            tts = TextToSpeech(ctx.applicationContext) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) {
                    tts?.setOnUtteranceProgressListener(progressListener)
                    tts?.language = Locale.SIMPLIFIED_CHINESE
                    flushSpeech()
                } else {
                    onError?.invoke("Синтез речи недоступен")
                    releaseSpeechPause()
                }
            }
        }
    }

    /** Озвучить текст. Запись на это время приостанавливается. */
    fun speak(text: String, langTag: String) {
        if (text.isBlank()) return
        main.post {
            if (wantListening && !paused) {
                pausedBySpeech = true
                pause()
            }
            pendingSpeech = text to langTag
            initTtsIfNeeded()
            flushSpeech()
        }
    }

    fun stopSpeaking() {
        main.post {
            pendingSpeech = null
            runCatching { tts?.stop() }
            markSpeaking(false)
            if (pausedBySpeech) {
                pausedBySpeech = false
                resume()
            }
        }
    }

    private fun flushSpeech() {
        if (!ttsReady) return
        val engine = tts ?: return
        val job = pendingSpeech ?: return
        pendingSpeech = null
        val locale = localeOf(job.second)
        engine.language = locale
        if (engine.isLanguageAvailable(locale) < TextToSpeech.LANG_AVAILABLE) {
            onError?.invoke("Голос для языка не установлен")
            releaseSpeechPause()
            return
        }
        // Озвучка идёт по STREAM_MUSIC, который заглушён ради тишины микрофона.
        unmuteMusic()
        engine.speak(job.first, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        markSpeaking(true)
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}
        override fun onDone(utteranceId: String?) {
            main.post { releaseSpeechPause() }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            main.post { releaseSpeechPause() }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            main.post { releaseSpeechPause() }
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            main.post { releaseSpeechPause() }
        }
    }

    /** Озвучка закончилась — возвращаем микрофон в работу. */
    private fun releaseSpeechPause() {
        if (pendingSpeech != null) {
            flushSpeech()
            return
        }
        markSpeaking(false)
        if (pausedBySpeech) {
            pausedBySpeech = false
            resume()
        }
        if (wantListening && !paused) muteBeeps()
    }

    private fun localeOf(tag: String): Locale =
        if (tag.startsWith("zh")) Locale.SIMPLIFIED_CHINESE else Locale.forLanguageTag(tag)

    private companion object {
        const val UTTERANCE_ID = "ds_translate"
        const val RU_TAG = "ru-RU"
        const val ZH_TAG = "zh-CN"

        /**
         * Пауза, после которой распознаватель считает фразу законченной,
         * в миллисекундах.
         *
         * Длинный вариант — для автодиалога: человек делает вдох, задумывается,
         * говорит «э-э» — фраза не должна на этом обрываться. 3.2 с — компромисс:
         * вдох и короткая заминка не режут предложение, а ожидание перевода
         * остаётся приемлемым (модель отвечает быстрее, чем копится пауза).
         * Короткий вариант — «нажал и говори»: там фраза заканчивается сразу,
         * как только человек замолчал.
         */
        const val SILENCE_LONG_MS = 3200L
        const val SILENCE_SHORT_MS = 900L

        /** «Скорее всего закончил» — промежуточный порог, мягкий намёк движку. */
        const val POSSIBLY_COMPLETE_LONG_MS = 2400L
        const val POSSIBLY_COMPLETE_SHORT_MS = 700L

        /** Минимальная длина речи, чтобы движок вообще начал сеанс. */
        const val MIN_LENGTH_LONG_MS = 800L
        const val MIN_LENGTH_SHORT_MS = 700L

        /**
         * Сколько ждать частичный результат, прежде чем счесть язык сеанса неверным.
         * Первый partial у большинства распознавателей приходит через 1-2 с,
         * поэтому значение заведомо больше — иначе получается цикл перезапусков.
         */
        const val PROBE_MS = 1800L

        /** Минимальный интервал между принудительными перезапусками сеанса. */
        const val MIN_RESTART_GAP_MS = 2500L
    }
}
