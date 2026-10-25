package com.nya.dstranslate.data

import kotlinx.coroutines.CancellationException

/**
 * Короткий перевод «на лету» для окон поверх других приложений:
 * плавающего пузыря и диалога из меню выделения (ACTION_PROCESS_TEXT).
 *
 * Отличие от вкладки «Текст»: нет истории, нет контекста, нет TTS и записи —
 * только текст → стриминговый перевод в противоположный язык. Направление
 * определяется по письменности введённого/выделенного текста ([LangDetect]),
 * поэтому китайский всегда переводится на русский и наоборот.
 */
class QuickTranslator(
    private val prefs: Prefs,
    private val client: DeepSeekClient = DeepSeekClient()
) {

    /**
     * Потоково переводит [text] и на каждом куске вызывает [onProgress] —
     * так окно показывает перевод по мере генерации, а не после паузы.
     *
     * [origin] — метка для [ApiGuard] (`bubble`, `processText`), чтобы в
     * logcat было видно, какое окно отправило запрос.
     */
    suspend fun translate(
        text: String,
        origin: String,
        onProgress: (String) -> Unit
    ): QuickResult {
        val trimmed = text.trim()
        // Пустая строка — не ошибка сети, а отсутствие входа: окно просто молчит.
        if (trimmed.isEmpty()) return QuickResult.Empty

        val apiKey = prefs.apiKey
        // Ключ вводит пользователь (в APK его нет) — без него запрос не уходит.
        if (apiKey.isBlank()) return QuickResult.NoKey

        // Язык входа: кириллица → RU, иероглифы → ZH. Совсем без букв
        // (цифры/эмодзи) считаем китайским: для РФ-пользователя это редкий случай,
        // а модель всё равно вернёт осмысленный вариант.
        val from = LangDetect.detect(trimmed) ?: Lang.ZH
        val to = from.other()

        val builder = StringBuilder()
        return try {
            client.translateStream(
                apiKey = apiKey,
                model = prefs.model,
                systemPrompt = Prompts.system(from, to),
                userText = trimmed,
                origin = origin
            ).collect { chunk ->
                builder.append(chunk)
                // Показываем накопленный текст, а не отдельный кусок: окно
                // не должно «дёргаться» и терять уже показанные символы.
                onProgress(builder.toString())
            }

            // Хвостовые пробелы/маркеры модели в готовом переводе не нужны.
            var out = builder.toString().trim()
            if (out.startsWith(Prompts.KEEP_SENTINEL)) {
                // [[!]] — «смысл понятен, дальше перевод»: маркер снимаем.
                out = out.removePrefix(Prompts.KEEP_SENTINEL).trim()
            }
            if (out.isEmpty() || out == Prompts.SKIP_SENTINEL) {
                QuickResult.Empty
            } else {
                QuickResult.Ok(out)
            }
        } catch (e: CancellationException) {
            // Отмена (закрыли окно) — это не ошибка, наверх её не превращаем.
            throw e
        } catch (e: Exception) {
            QuickResult.Error(e.message ?: "Не удалось перевести текст")
        }
    }
}

/** Результат короткого перевода — окна сами решают, как это показать. */
sealed interface QuickResult {
    /** Готовый перевод. */
    data class Ok(val text: String) : QuickResult

    /** Ошибка сети/API/ключа — [message] показывается в окне как есть. */
    data class Error(val message: String) : QuickResult

    /** Переводить нечего (пустой вход или модель ответила [[SKIP]]). */
    data object Empty : QuickResult

    /** Не задан ключ DeepSeek — нужно открыть приложение и ввести его. */
    data object NoKey : QuickResult
}
