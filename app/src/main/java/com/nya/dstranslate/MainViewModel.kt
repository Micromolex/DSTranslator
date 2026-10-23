package com.nya.dstranslate

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nya.dstranslate.data.ChatTurn
import com.nya.dstranslate.data.DeepSeekClient
import com.nya.dstranslate.data.HistoryEntry
import com.nya.dstranslate.data.HistoryStore
import com.nya.dstranslate.data.Lang
import com.nya.dstranslate.data.LangDetect
import com.nya.dstranslate.data.OcrEngine
import com.nya.dstranslate.data.Prefs
import com.nya.dstranslate.data.Prompts
import com.nya.dstranslate.data.TextSplit
import com.nya.dstranslate.data.UpdateChecker
import com.nya.dstranslate.data.UpdateInfo
import com.nya.dstranslate.speech.SpeechController
import com.nya.dstranslate.ui.UiLang
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Реплика в режиме автодиалога по ролям. */
class Utterance(val id: Long, initialSide: Lang) {
    /** Роль (язык говорящего) — уточняется, когда распознавание завершено. */
    var side by mutableStateOf(initialSide)
    var source by mutableStateOf("")
    var target by mutableStateOf("")
    var isFinal by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    @Volatile
    var generation: Int = 0

    var job: Job? = null

    val targetLang: Lang get() = side.other()
}

/**
 * Состояние одной половины экрана в режиме «Два экрана».
 *
 * Владелец этой стороны ГОВОРИТ на [lang] и ЧИТАЕТ перевод на [lang] —
 * то есть перевод чужой фразы показывается здесь, а не на стороне говорящего.
 */
class SideState(val lang: Lang) {
    /** Речь, распознаваемая прямо сейчас (говорит владелец этой стороны). */
    var live by mutableStateOf("")

    /** Последняя распознанная фраза владельца этой стороны. */
    var spoken by mutableStateOf("")

    /** Перевод чужой фразы НА язык этой стороны — то, что читает владелец. */
    var incoming by mutableStateOf("")

    /** Оригинал чужой фразы (на другом языке), для справки. */
    var incomingSource by mutableStateOf("")

    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    @Volatile
    var generation: Int = 0

    var job: Job? = null

    val targetLang: Lang get() = lang.other()
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = Prefs(app)
    private val client = DeepSeekClient()
    private val historyStore = HistoryStore(app)
    private val ocr = OcrEngine()
    val speech = SpeechController(app)

    // ------------------------------------------------------------- настройки

    /** Модель зафиксирована — выбора в интерфейсе нет. */
    val model: String = MODEL_ID

    var uiLang by mutableStateOf(prefs.uiLang)
        private set
    var autoSpeak by mutableStateOf(prefs.autoSpeak)
        private set
    var useContext by mutableStateOf(prefs.useContext)
        private set

    /** Нажатие на заголовок в шапке: переворачивает название и меняет язык интерфейса. */
    fun toggleUiLang() {
        uiLang = if (uiLang == UiLang.RU) UiLang.ZH else UiLang.RU
        prefs.uiLang = uiLang
    }

    fun updateAutoSpeak(value: Boolean) {
        autoSpeak = value
        prefs.autoSpeak = value
    }

    fun updateUseContext(value: Boolean) {
        useContext = value
        prefs.useContext = value
    }

    fun initTts() = speech.initTtsIfNeeded()

    // --------------------------------------------------------- режим «Текст»

    var fromLang by mutableStateOf(Lang.RU)
        private set
    var textInput by mutableStateOf("")
        private set
    var textOutput by mutableStateOf("")
        private set
    var textBusy by mutableStateOf(false)
        private set
    var textError by mutableStateOf<String?>(null)
        private set

    private var textJob: Job? = null
    private var textGeneration = 0

    /** Последний полностью переведённый ввод и его результат — для дозаписи. */
    private var lastTranslatedInput = ""
    private var lastTranslatedOutput = ""

    /**
     * Ввод текста с автоматической сменой направления: набрали иероглифы —
     * перевод пошёл с китайского, набрали кириллицу — с русского.
     */
    fun onTextInputChange(value: String) {
        textInput = value
        val detected = LangDetect.detect(value)
        if (detected != null && detected != fromLang) {
            fromLang = detected
            resetTranslation()
        }
    }

    /** Сброс всего перевода: старый результат больше не соответствует вводу. */
    private fun resetTranslation() {
        textOutput = ""
        textError = null
        lastTranslatedInput = ""
        lastTranslatedOutput = ""
    }

    /** Текущее направление перевода определяем по введённому тексту. */
    fun refreshDirection() {
        LangDetect.detect(textInput)?.let { if (it != fromLang) fromLang = it }
    }

    // ------------------------------------------------------- история переводов

    val history = mutableStateListOf<HistoryEntry>()
    private var historyId = 0L

    init {
        history.addAll(historyStore.load())
        historyId = (history.maxOfOrNull { it.id } ?: 0L) + 1
        speech.onSpeakingChanged = { isSpeaking = it }
    }

    /** Идёт ли озвучка — кнопки показывают «стоп» вместо «play». */
    var isSpeaking by mutableStateOf(false)
        private set

    fun stopSpeaking() = speech.stopSpeaking()

    /**
     * Кнопка воспроизведения работает как переключатель: повторное нажатие
     * останавливает уже начатую озвучку.
     */
    fun toggleSpeak(text: String, langTag: String) {
        if (isSpeaking) {
            speech.stopSpeaking()
            return
        }
        if (text.isBlank()) return
        speech.initTtsIfNeeded()
        speech.speak(text, langTag)
    }

    fun removeHistory(id: Long) {
        history.removeAll { it.id == id }
        historyStore.save(history)
    }

    fun clearHistory() {
        history.clear()
        historyStore.save(history)
    }

    private fun addHistory(from: Lang, source: String, target: String) {
        if (source.isBlank() || target.isBlank()) return
        history.add(
            0,
            HistoryEntry(
                id = historyId++,
                from = from,
                source = source.trim(),
                target = target.trim(),
                at = System.currentTimeMillis()
            )
        )
        while (history.size > 200) history.removeAt(history.size - 1)
        historyStore.save(history)
    }

    fun swapLanguages() {
        fromLang = fromLang.other()
        val oldInput = textInput
        textInput = textOutput
        textOutput = oldInput
        textError = null
        // Направление поменялось — прошлая пара «ввод → перевод» больше не годится.
        lastTranslatedInput = ""
        lastTranslatedOutput = ""
    }

    fun clearText() {
        textInput = ""
        resetTranslation()
    }

    /** Взять готовый перевод из истории: переводим заново, без режима дозаписи. */
    fun reuseHistory(entry: HistoryEntry) {
        lastTranslatedInput = ""
        lastTranslatedOutput = ""
        onTextInputChange(entry.source)
        translateText()
    }

    /**
     * Перевод введённого текста.
     *
     * Два важных отличия от наивной версии:
     *  1. Старый перевод НЕ стирается, пока не пойдёт новый: экран не пустеет,
     *     и не мигает «пусто → готово» на каждой кнопке.
     *  2. Если ввод не изменился, а ДОПОЛНИЛСЯ (старый текст — его начало),
     *     в модель уходит только хвост, а готовая часть сохраняется. Это и
     *     быстрее, и дешевле: не переводим одно и то же по десять раз.
     *     Хвост берётся только если предыдущий ввод кончился концом предложения,
     *     иначе он был бы обрывком («Привет, как» + «дела?») — такой случай
     *     переводится целиком.
     */
    fun translateText() {
        val text = textInput.trim()
        if (text.isEmpty()) return
        textJob?.cancel()
        speech.stopSpeaking()
        textBusy = true
        textError = null

        val prevInput = lastTranslatedInput.trim()
        val prevOutput = lastTranslatedOutput
        val appended = prevInput.isNotEmpty() && prevOutput.isNotBlank() &&
            text.length > prevInput.length && text.startsWith(prevInput)
        val tail = if (appended) text.substring(prevInput.length).trim() else ""
        if (appended && tail.isEmpty()) {
            // Дописали только пробелы — переводить нечего, оставляем как было.
            textOutput = prevOutput
            textBusy = false
            return
        }
        // Дозапись засчитываем, только если предыдущее предложение было закончено.
        val incremental = appended && TextSplit.endsWithTerminator(prevInput)
        val chunk = if (incremental) tail else text
        val context = if (incremental) {
            listOf(
                ChatTurn(
                    TextSplit.lastSentences(prevInput, 1),
                    TextSplit.lastSentences(prevOutput, 1)
                )
            )
        } else {
            emptyList()
        }

        val generation = ++textGeneration
        textJob = viewModelScope.launch {
            val streamed = StringBuilder()
            try {
                client.translateStream(
                    model = model,
                    systemPrompt = Prompts.system(fromLang, fromLang.other()),
                    userText = chunk,
                    history = context
                ).collect { piece ->
                    if (generation != textGeneration) return@collect
                    streamed.append(piece)
                    // Старый перевод виден, пока не пришёл первый кусок нового.
                    textOutput = if (incremental) {
                        joinTranslation(prevOutput, streamed.toString(), fromLang.other())
                    } else {
                        streamed.toString()
                    }
                }
                if (generation != textGeneration) return@launch
                val fresh = streamed.toString().trim()
                if (fresh.isNotBlank()) {
                    commitTranslation(text, textOutput.trim(), incremental, prevInput)
                    // Озвучиваем только новый фрагмент: старое уже слышали.
                    if (autoSpeak) speech.speak(fresh, fromLang.other().speech)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation != textGeneration) return@launch
                textError = e.message ?: "Ошибка перевода"
                // Не оставляем обрубок: возвращаем целый прошлый перевод.
                if (incremental) textOutput = prevOutput
            } finally {
                // Устаревший перевод не должен гасить индикатор нового.
                if (generation == textGeneration) textBusy = false
            }
        }
    }

    /** Склейка старого перевода с новым куском (в китайском пробел не нужен). */
    private fun joinTranslation(head: String, tail: String, target: Lang): String {
        val h = head.trimEnd()
        val t = tail.trimStart()
        return when {
            h.isEmpty() -> t
            t.isEmpty() -> h
            target == Lang.ZH -> h + t
            else -> "$h $t"
        }
    }

    /**
     * Запоминаем, что именно переведено. При дозаписи обновляем последнюю
     * запись истории, а не создаём новую на каждое дописанное предложение.
     */
    private fun commitTranslation(
        fullSource: String,
        fullTarget: String,
        incremental: Boolean,
        prevInput: String
    ) {
        lastTranslatedInput = fullSource
        lastTranslatedOutput = fullTarget
        val sameEntry = incremental && history.isNotEmpty() &&
            history[0].source.trim() == prevInput
        if (sameEntry) {
            history[0] = history[0].copy(source = fullSource, target = fullTarget)
            historyStore.save(history)
        } else {
            addHistory(fromLang, fullSource, fullTarget)
        }
    }

    // ------------------------------------------- голосовой ввод в режиме «Текст»

    /** Идёт запись с микрофона в поле ввода. */
    var voiceInputActive by mutableStateOf(false)
        private set

    var voiceError by mutableStateOf<String?>(null)
        private set

    /**
     * Наговорить текст в поле ввода. Распознанное дописывается к уже введённому
     * и после финальной фразы сразу уходит в перевод — как будто текст набрали.
     */
    fun startVoiceInput() {
        if (voiceInputActive) {
            stopVoiceInput()
            return
        }
        if (!speech.isRecognitionAvailable()) {
            voiceError = "На устройстве нет сервиса распознавания речи"
            return
        }
        voiceError = null
        speech.stopSpeaking()
        val base = textInput.trimEnd()
        voiceInputActive = true
        speech.onPartial = { live ->
            if (live.isNotBlank()) {
                textInput = mergeSpoken(base, live)
                // Речь на другом языке сразу меняет направление перевода.
                LangDetect.detect(live)?.let { if (it != fromLang) fromLang = it }
            }
        }
        speech.onFinal = { recognized ->
            voiceInputActive = false
            speech.stop()
            val merged = mergeSpoken(base, recognized.trim())
            if (merged.isNotBlank()) {
                onTextInputChange(merged)
                translateText()
            }
        }
        speech.onSilence = {
            if (voiceInputActive) {
                voiceInputActive = false
                speech.stop()
            }
        }
        speech.onError = { message ->
            if (voiceInputActive) {
                voiceInputActive = false
                speech.stop()
                voiceError = message
            }
        }
        speech.start(fromLang.speech, stopAfterFinal = true)
    }

    fun stopVoiceInput() {
        if (!voiceInputActive) return
        voiceInputActive = false
        speech.stop()
    }

    /** Дописывает распознанное к уже набранному тексту с учётом пробела. */
    private fun mergeSpoken(base: String, spoken: String): String {
        val tail = spoken.trim()
        if (tail.isEmpty()) return base
        if (base.isEmpty()) return tail
        return if (base.last().isLetterOrDigit()) "$base $tail" else "$base$tail"
    }

    // -------------------------------------------------- режим «Два экрана»

    val ruSide = SideState(Lang.RU)
    val zhSide = SideState(Lang.ZH)

    var splitActive by mutableStateOf<Lang?>(null)
        private set
    var splitError by mutableStateOf<String?>(null)
        private set

    fun sideState(lang: Lang): SideState = if (lang == Lang.RU) ruSide else zhSide

    /**
     * Говорит владелец половины [lang]. Распознанная речь остаётся у него,
     * а перевод уезжает на ВТОРУЮ половину — её читает собеседник.
     */
    fun startSplit(lang: Lang) {
        if (!speech.isRecognitionAvailable()) {
            splitError = "На устройстве нет сервиса распознавания речи"
            return
        }
        splitError = null
        val speaker = sideState(lang)
        speaker.job?.cancel()
        speaker.live = ""
        speaker.spoken = ""
        speaker.error = null
        splitActive = lang

        speech.onPartial = { text ->
            speaker.live = text
            translateToListener(speaker, text, immediate = false)
        }
        speech.onFinal = { text ->
            speaker.live = ""
            speaker.spoken = text
            splitActive = null
            translateToListener(speaker, text, immediate = true)
        }
        speech.start(lang.speech, stopAfterFinal = true)
    }

    fun stopSplit() {
        splitActive = null
        speech.stop()
    }

    /** Озвучить перевод, который читает эта сторона. */
    fun speakSideIncoming(lang: Lang) {
        val st = sideState(lang)
        if (st.incoming.isNotBlank()) speech.speak(st.incoming, st.lang.speech)
    }

    /** Озвучить оригинал чужой фразы. */
    fun speakSideIncomingSource(lang: Lang) {
        val st = sideState(lang)
        if (st.incomingSource.isNotBlank()) speech.speak(st.incomingSource, st.targetLang.speech)
    }

    /** Озвучить собственную распознанную фразу. */
    fun speakSideOwn(lang: Lang) {
        val st = sideState(lang)
        val text = st.live.ifBlank { st.spoken }
        if (text.isNotBlank()) speech.speak(text, st.lang.speech)
    }

    private fun translateToListener(speaker: SideState, text: String, immediate: Boolean) {
        val listener = sideState(speaker.targetLang)
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        listener.job?.cancel()
        val generation = ++listener.generation
        listener.job = viewModelScope.launch {
            if (!immediate) delay(300)
            listener.busy = true
            listener.error = null
            listener.incomingSource = trimmed
            listener.incoming = ""
            try {
                client.translateStream(
                    model = model,
                    systemPrompt = Prompts.system(speaker.lang, listener.lang),
                    userText = trimmed
                ).collect { chunk ->
                    if (listener.generation == generation) listener.incoming += chunk
                }
                if (listener.generation == generation && immediate && autoSpeak) {
                    speech.speak(listener.incoming, listener.lang.speech)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (listener.generation == generation) listener.error = e.message ?: "Ошибка перевода"
            } finally {
                if (listener.generation == generation) listener.busy = false
            }
        }
    }

    // --------------------------------- режим «Автодиалог по ролям» (2 языка)

    val autoItems = mutableStateListOf<Utterance>()
    var autoRunning by mutableStateOf(false)
        private set
    var autoNextLang by mutableStateOf(Lang.RU)
        private set
    var autoError by mutableStateOf<String?>(null)
        private set

    /** Короткая подсказка о том, что происходит с языком распознавания. */
    var autoNotice by mutableStateOf<String?>(null)
        private set

    /** Язык, который вернул сам распознаватель (API 34+). */
    var autoDetectedLang by mutableStateOf<Lang?>(null)
        private set

    /** Ручное переопределение языка распознавания: null — автоопределение. */
    var autoOverride by mutableStateOf<Lang?>(null)
        private set

    fun setAutoLanguage(lang: Lang?) {
        autoOverride = lang
        speech.setForcedLanguage(lang?.speech)
    }

    private var autoCurrent: Utterance? = null
    private var nextId = 0L
    private var lastFinalText = ""
    private var lastFinalAt = 0L
    private var lastSessionWasSilent = false

    /** Распознаватель хоть раз сам сообщил язык — значит, умеет. */
    private var detectionSeen = false

    /**
     * ОДНА непрерывная запись, микрофон не выключается между фразами.
     *
     * Язык реплики определяется двумя способами:
     *  1. Если распознаватель умеет автоопределение (Android 14+ и не все
     *     вендорские сборки) — берём его ответ.
     *  2. Иначе работает чередование: сеанс идёт на текущем языке, и если
     *     распознаватель УСЛЫШАЛ речь, но текст вышел на другом языке
     *     (или не вышел вовсе) — значит, заговорил второй собеседник,
     *     и следующий сеанс идёт на другом языке. Пока речь распознаётся
     *     правильно, язык не меняется, поэтому один человек может говорить
     *     сколько угодно фраз подряд.
     */
    fun startAuto() {
        if (!speech.isRecognitionAvailable()) {
            autoError = "На устройстве нет сервиса распознавания речи"
            return
        }
        autoError = null
        autoRunning = true
        autoCurrent = null
        lastFinalText = ""
        autoDetectedLang = null
        detectionSeen = false
        lastSessionWasSilent = false
        autoNextLang = autoOverride ?: Lang.RU

        speech.onLanguageDetected = { tag ->
            detectionSeen = true
            val t = tag.lowercase()
            autoDetectedLang = when {
                t.startsWith("zh") -> Lang.ZH
                t.startsWith("ru") -> Lang.RU
                else -> null
            }
        }
        speech.onPartial = { text ->
            if (LangDetect.hasLetters(text)) {
                val u = ensureAutoItem(text)
                u.source = text
                translateAuto(u, text, immediate = false)
            }
        }
        speech.onFinal = { raw ->
            val text = raw.trim()
            val now = System.currentTimeMillis()
            val duplicate = text == lastFinalText && now - lastFinalAt < 4000
            if (!duplicate) {
                val sessionLang = langOfTag(speech.currentLanguageTag)
                val forced = autoOverride
                val detected = LangDetect.detect(text)
                when {
                    // Мусор («.», «…») — переводить нечего.
                    !LangDetect.hasLetters(text) -> discardAuto()

                    // Ручной режим «только RU» / «только ZH» — берём как есть.
                    forced != null -> acceptAuto(text, forced)

                    // Распознаватель сам сказал, на каком языке была речь.
                    detectionSeen -> {
                        val side = autoDetectedLang ?: detected ?: sessionLang
                        autoDetectedLang = null
                        acceptAuto(text, side)
                    }

                    // Текст пришёл на языке сеанса — всё хорошо, язык не трогаем.
                    LangDetect.matches(text, sessionLang) -> acceptAuto(text, sessionLang)

                    // Распознаватель вернул текст на ДРУГОМ языке — фраза не теряется:
                    // принимаем её и переводим, а язык сеансов переключаем.
                    detected != null && LangDetect.matches(text, detected) -> {
                        acceptAuto(text, detected)
                        autoNextLang = detected
                        speech.updateLanguage(detected.speech)
                    }

                    else -> discardAuto()
                }
            }
        }

        speech.onProbeFailed = {
            // Речь идёт, но за 0.7 с ни одного частичного результата —
            // язык не тот. Перезапускаем сеанс немедленно, внутри фразы,
            // чтобы поймать хотя бы её продолжение.
            if (autoRunning && autoOverride == null && !detectionSeen) {
                autoNextLang = autoNextLang.other()
                speech.restartNow(autoNextLang.speech)
            }
        }

        speech.onSilence = {
            if (autoRunning && autoOverride == null && !detectionSeen) {
                if (speech.lastSessionHadSpeech) {
                    // Речь была, но распознать не удалось — почти наверняка другой язык.
                    discardAuto()
                    switchAutoLanguage()
                } else if (!lastSessionWasSilent) {
                    // Тишина после реплики: скорее всего сейчас заговорит второй.
                    // Переключаемся один раз, чтобы не «дребезжать» на паузах.
                    lastSessionWasSilent = true
                    switchAutoLanguage()
                }
            }
        }
        speech.start(
            (autoOverride ?: autoNextLang).speech,
            stopAfterFinal = false,
            autoDetect = true,
            longPause = true
        )
    }

    private fun langOfTag(tag: String): Lang =
        if (tag.lowercase().startsWith("zh")) Lang.ZH else Lang.RU

    private fun switchAutoLanguage() {
        autoNextLang = autoNextLang.other()
        speech.updateLanguage(autoNextLang.speech)
    }

    /** Реплика принята: карточка создаётся или дозаполняется и уходит на перевод. */
    private fun acceptAuto(text: String, side: Lang) {
        val u = ensureAutoItem(text, side)
        u.side = side
        u.source = text
        u.isFinal = true
        autoCurrent = null
        translateAuto(u, text, immediate = true)
        lastFinalText = text
        lastFinalAt = System.currentTimeMillis()
        lastSessionWasSilent = false
        autoNotice = null
    }

    /** Реплика не распозналась на нужном языке — убираем черновик карточки. */
    private fun discardAuto() {
        val u = autoCurrent ?: return
        u.job?.cancel()
        autoItems.remove(u)
        autoCurrent = null
    }

    fun stopAuto() {
        autoRunning = false
        autoCurrent = null
        autoDetectedLang = null
        speech.stop()
    }

    fun clearAuto() {
        autoItems.forEach { it.job?.cancel() }
        autoItems.clear()
        autoCurrent = null
        autoError = null
        autoNotice = null
        speech.stopSpeaking()
    }

    /** Ручное воспроизведение фразы: запись на это время приостанавливается. */
    fun playAuto(u: Utterance) {
        if (u.target.isNotBlank()) speech.speak(u.target, u.targetLang.speech)
    }

    fun playAutoSource(u: Utterance) {
        if (u.source.isNotBlank()) speech.speak(u.source, u.side.speech)
    }

    private fun ensureAutoItem(text: String, side: Lang? = null): Utterance {
        autoCurrent?.let { return it }
        val s = side ?: LangDetect.detect(text) ?: autoNextLang
        val u = Utterance(nextId++, s)
        autoItems.add(u)
        autoCurrent = u
        return u
    }

    private fun translateAuto(u: Utterance, text: String, immediate: Boolean) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        u.job?.cancel()
        val generation = ++u.generation
        u.job = viewModelScope.launch {
            if (!immediate) delay(350)
            u.busy = true
            u.error = null
            u.target = ""
            val buffer = StringBuilder()
            var garbage = false
            var translit = false
            try {
                client.translateStream(
                    model = model,
                    systemPrompt = Prompts.system(u.side, u.targetLang, strict = true),
                    userText = trimmed,
                    history = if (useContext) autoContextFor(u) else emptyList()
                ).collect { chunk ->
                    if (garbage || u.generation != generation) return@collect
                    buffer.append(chunk)
                    val shown = buffer.toString().trimStart()

                    if (shown.startsWith(Prompts.SKIP_SENTINEL)) {
                        garbage = true
                        return@collect
                    }
                    if (shown.startsWith(Prompts.KEEP_SENTINEL)) {
                        // Распознаватель выдал транслитерацию, но модель её поняла —
                        // фразу сохраняем, а распознавание переводим на другой язык.
                        translit = true
                        val rest = shown.removePrefix(Prompts.KEEP_SENTINEL).trimStart()
                        u.target = rest
                        return@collect
                    }
                    if (isMarkerPrefix(shown)) return@collect
                    u.target = shown
                }
                when {
                    garbage && u.generation == generation -> onAutoGarbage(u)
                    translit && u.generation == generation -> onAutoTranslit(u)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (u.generation == generation) u.error = e.message ?: "Ошибка перевода"
            } finally {
                if (u.generation == generation) u.busy = false
            }
        }
    }

    /** Текст может оказаться началом служебного маркера — подождём ещё кусочек. */
    private fun isMarkerPrefix(shown: String): Boolean {
        if (shown.isEmpty()) return true
        if (shown.length > Prompts.SKIP_SENTINEL.length) return false
        return Prompts.SKIP_SENTINEL.startsWith(shown) || Prompts.KEEP_SENTINEL.startsWith(shown)
    }

    /**
     * Модель сказала, что фраза не на языке сеанса. Убираем мусорную карточку
     * и немедленно переводим распознавание на второй язык — следующая реплика
     * того же собеседника попадёт уже в правильный сеанс.
     */
    private fun onAutoGarbage(u: Utterance) {
        if (u === autoCurrent) autoCurrent = null
        u.job?.cancel()
        autoItems.remove(u)
        autoNotice = "Фраза не на ${u.side.label} — переключаю распознавание"
        switchRecognitionTo(u.side.other())
    }

    /** Фраза была транслитерацией, но перевод получился — сохраняем и меняем язык. */
    private fun onAutoTranslit(u: Utterance) {
        val realSide = u.side.other()
        u.side = realSide
        autoNotice = "Похоже, говорили на ${realSide.label} — переключаю распознавание"
        switchRecognitionTo(realSide)
    }

    private fun switchRecognitionTo(lang: Lang) {
        if (autoOverride != null) return
        autoNextLang = lang
        lastSessionWasSilent = false
        speech.restartNow(lang.speech)
    }

    private fun autoContextFor(current: Utterance): List<ChatTurn> {
        val idx = autoItems.indexOf(current)
        if (idx <= 0) return emptyList()
        return autoItems.subList(0, idx)
            .filter { it.isFinal && it.target.isNotBlank() }
            .map { ChatTurn(it.source, it.target) }
    }

    // -------------------------------------------------- проверка обновлений

    val currentVersionName: String = BuildConfig.VERSION_NAME
    val currentVersionCode: Int = BuildConfig.VERSION_CODE

    var updateUrl by mutableStateOf(prefs.updateUrl)
        private set
    var autoCheckUpdates by mutableStateOf(prefs.autoCheckUpdates)
        private set

    var updateChecking by mutableStateOf(false)
        private set
    var updateAvailable by mutableStateOf<UpdateInfo?>(null)
        private set

    /** Версия с сервера после удачной проверки; null — ещё не проверяли. */
    var latestVersion by mutableStateOf<String?>(null)
        private set
    var updateStatus by mutableStateOf<String?>(null)
        private set
    var updateError by mutableStateOf<String?>(null)
        private set
    var updateProgress by mutableStateOf<Int?>(null)
        private set
    var updateDialogVisible by mutableStateOf(false)
        private set

    fun changeUpdateUrl(value: String) {
        updateUrl = value.trim()
        prefs.updateUrl = value
    }

    fun changeAutoCheckUpdates(value: Boolean) {
        autoCheckUpdates = value
        prefs.autoCheckUpdates = value
    }

    fun dismissUpdateDialog() {
        updateDialogVisible = false
    }

    /** Проверка версии. manual = true — кнопкой (с отчётом об ошибках). */
    fun checkUpdates(manual: Boolean) {
        if (updateChecking) return
        updateChecking = true
        updateError = null
        if (manual) updateStatus = "Проверяю…"
        viewModelScope.launch {
            try {
                val info = UpdateChecker.fetch(updateUrl)
                latestVersion = info.versionName
                if (info.versionCode > BuildConfig.VERSION_CODE) {
                    updateAvailable = info
                    updateStatus = "Доступна версия ${info.versionName}"
                    updateDialogVisible = true
                } else {
                    updateAvailable = null
                    updateStatus = if (manual) "Установлена последняя версия" else null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateStatus = null
                updateError = if (manual) "Не удалось проверить: ${e.message}" else null
            } finally {
                updateChecking = false
            }
        }
    }

    /** Скачивает APK и открывает системный установщик. */
    fun downloadAndInstallUpdate() {
        val info = updateAvailable ?: return
        if (updateProgress != null) return
        updateProgress = 0
        updateError = null
        viewModelScope.launch {
            try {
                val dir = File(getApplication<Application>().getExternalFilesDir(null), "updates")
                val target = File(dir, "update-${info.versionCode}.apk")
                if (target.exists()) target.delete()
                UpdateChecker.download(info.apkUrl, target) { percent ->
                    withContext(Dispatchers.Main) { updateProgress = percent }
                }
                updateProgress = null
                installApk(target)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateProgress = null
                updateError = "Не удалось скачать: ${e.message}"
            }
        }
    }

    private fun installApk(file: File) {
        val app = getApplication<Application>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !app.packageManager.canRequestPackageInstalls()
        ) {
            updateError = "Разрешите установку из этого приложения и нажмите ещё раз"
            runCatching {
                app.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${app.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return
        }
        runCatching {
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
            app.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            updateDialogVisible = false
            updateStatus = "Загрузка завершена — подтвердите установку"
        }.onFailure {
            updateError = "Не удалось открыть установщик: ${it.message}"
        }
    }

    // -------------------------------------- картинка (внутри вкладки «Текст»)

    var imageUri by mutableStateOf<Uri?>(null)
        private set
    var imageBusy by mutableStateOf(false)
        private set
    var imageNoText by mutableStateOf(false)
        private set
    var imageError by mutableStateOf<String?>(null)
        private set

    private var imageJob: Job? = null

    /** Куда камера положит снимок: файл в приватной папке приложения. */
    fun createCameraUri(): Uri {
        val app = getApplication<Application>()
        val dir = File(app.getExternalFilesDir(null), "camera").apply { mkdirs() }
        val file = File(dir, "photo_${System.currentTimeMillis()}.jpg")
        return FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
    }

    /**
     * Распознаёт текст на картинке (офлайн) и подставляет его в поле ввода —
     * дальше работает обычный текстовый перевод с автоопределением языка.
     */
    fun recognizeImage(uri: Uri) {
        imageUri = uri
        imageJob?.cancel()
        imageBusy = true
        imageError = null
        imageNoText = false
        imageJob = viewModelScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) { loadBitmap(uri) }
                if (bitmap == null) {
                    imageError = "Не удалось открыть картинку"
                    return@launch
                }
                val text = ocr.recognize(bitmap)
                bitmap.recycle()
                if (text.isBlank()) {
                    imageNoText = true
                    return@launch
                }
                onTextInputChange(text)
                translateText()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                imageError = e.message ?: "Ошибка распознавания"
            } finally {
                imageBusy = false
            }
        }
    }

    fun clearImage() {
        imageJob?.cancel()
        imageUri = null
        imageError = null
        imageNoText = false
        imageBusy = false
    }

    private fun loadBitmap(uri: Uri): Bitmap? {
        val resolver = getApplication<Application>().contentResolver
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, bounds)
        }
        var sample = 1
        while (bounds.outWidth / sample > 1800 || bounds.outHeight / sample > 1800) sample *= 2
        val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        return resolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, options)
        }
    }

    override fun onCleared() {
        ocr.close()
        speech.release()
        super.onCleared()
    }

    private companion object {
        const val MODEL_ID = "deepseek-chat"
    }
}
