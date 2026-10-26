package com.nya.dstranslate.data

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/** Пара реплик диалога, передаваемая в модель как контекст. */
data class ChatTurn(val from: String, val to: String)

/**
 * Ошибка перевода с человекочитаемым [message] для интерфейса.
 *
 * Отдельный тип нужен, чтобы клиент не превращал собственную понятную ошибку
 * (401, пустой ответ, таймаут) в безымянный `IOException` — вызывающий код
 * просто показывает `e.message`.
 */
class DeepSeekException(message: String, cause: Throwable? = null) : IOException(message, cause)

class DeepSeekClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Стриминговый перевод. Эмитит кусочки перевода по мере генерации —
     * именно это даёт эффект «перевода в реальном времени».
     *
     * [apiKey] приходит из приватных настроек устройства ([Prefs.apiKey]):
     * в APK ключа нет, поэтому без введённого ключа запрос не отправляется.
     *
     * Диагностика: каждый сетевой шаг пишет в Logcat (`adb logcat -s DeepSeekAPI`):
     * - маскированный ключ и длину заголовка `Authorization`;
     * - HTTP-код, `Content-Type` и `x-request-id`;
     * - **тело ответа** при ошибке (401/402/429/5xx) — без него причина
     *   «перевод не появился» была не видна;
     * - первые символы неразобранного SSE/JSON, если ответ пришёл, но текста
     *   в нём нет.
     */
    fun translateStream(
        apiKey: String,
        model: String,
        systemPrompt: String,
        userText: String,
        history: List<ChatTurn> = emptyList(),
        origin: String = "unknown"
    ): Flow<String> = flow {
        // Последний рубеж: даже если какой-то путь кода забыл проверку, в фоне
        // запрос не уходит — он попадает в logcat как BACKGROUND_REQUEST.
        if (ApiGuard.blocked("DeepSeekClient/$origin")) {
            throw DeepSeekException("Приложение в фоне — запрос к API не отправлен")
        }
        // Ключ нормализуем: частая причина 401 — вставленный с переводом строки,
        // кавычками или уже готовым префиксом «Bearer ». Пустым он быть не должен.
        val key = normalizeKey(apiKey)
        if (key.isEmpty()) {
            Log.e(TAG, "Запрос $origin отменён: ключ DeepSeek пуст (Authorization не отправлен)")
            throw DeepSeekException("Не задан API-ключ DeepSeek — введите его в настройках")
        }
        // В лог — только маска: полный ключ не должен утекать в logcat/баг-репорты.
        Log.i(
            TAG,
            "Запрос $origin: ключ ${maskKey(key)} (len=${key.length}), " +
                "префикс sk-=${key.startsWith("sk-")}, модель=$model"
        )

        val messages = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", systemPrompt))
            // Скользящее окно контекста: НЕ вся история диалога, а только
            // последние [CONTEXT_TURNS] реплик (пара user+assistant на каждую).
            // Так модель понимает, о чём речь, но запрос не растёт вместе
            // с длиной разговора и токены не тратятся на старые фразы.
            history.takeLast(CONTEXT_TURNS).forEach { turn ->
                put(JSONObject().put("role", "user").put("content", turn.from))
                put(JSONObject().put("role", "assistant").put("content", turn.to))
            }
            put(JSONObject().put("role", "user").put("content", userText))
        }

        val payload = JSONObject().apply {
            put("model", model)
            put("stream", true)
            put("temperature", 0.2)
            put("top_p", 0.95)
            put("max_tokens", 1024)
            put("messages", messages)
        }

        // Заголовок собираем явно и проверяем его содержимое перед отправкой:
        // пустой/битый ключ должен стать понятной ошибкой, а не молчаливым 401.
        val authorization = "Bearer $key"
        val request = Request.Builder()
            .url(ApiConfig.BASE_URL)
            .addHeader("Authorization", authorization)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(payload.toString().toRequestBody(JSON))
            .build()

        val call = http.newCall(request)
        // Отмена корутины (уход в фон, новый перевод) должна рвать и сетевой
        // вызов: blocking `execute()` сам по себе отмену не замечает.
        val cancelOnJobEnd = currentCoroutineContext()[Job]?.invokeOnCompletion {
            runCatching { call.cancel() }
        }
        try {
            // Сам сетевой вызов тоже под try: обрыв DNS/TLS/таймаут раньше
            // не давал в logcat ни кода, ни текста — только «перевод не пришёл».
            val response = try {
                call.execute()
            } catch (e: IOException) {
                if (!currentCoroutineContext().isActive) {
                    throw CancellationException("Запрос к API отменён")
                }
                Log.e(TAG, "Сетевая ошибка $origin: ${e.javaClass.simpleName}: ${e.message}", e)
                throw DeepSeekException(networkMessage(e), e)
            }

            response.use { r ->
                val code = r.code
                val contentType = r.header("Content-Type").orEmpty()
                Log.i(
                    TAG,
                    "Ответ $origin: HTTP $code, Content-Type=${contentType.ifEmpty { "-" }}, " +
                        "request-id=${r.header("x-request-id") ?: "-"}"
                )
                // 401/402/429/500 и любые другие неуспешные коды: читаем тело
                // ЦЕЛИКОМ и пишем его в logcat. `body.string()` безопасен,
                // потому что при ошибке мы не читаем поток SSE построчно.
                if (!r.isSuccessful) {
                    val body = readBody(r)
                    Log.e(
                        TAG,
                        "HTTP $code — текст ответа DeepSeek (${body.length} символов): " +
                            body.take(MAX_LOG_CHARS)
                    )
                    throw DeepSeekException(httpMessage(code, body))
                }

                // Некоторые прокси/шлюзы игнорируют `stream: true` и отвечают
                // обычным JSON. Раньше такой ответ молча превращался в пустоту
                // (ни одна строка не начиналась с `data:`), теперь он читается
                // как `choices[0].message.content`.
                if (!contentType.contains("event-stream", ignoreCase = true)) {
                    val body = readBody(r)
                    Log.w(
                        TAG,
                        "Ответ $origin не SSE (Content-Type=${contentType.ifEmpty { "?" }}), " +
                            "разбираю как JSON, ${body.length} символов"
                    )
                    val text = extractContentFromJson(body)
                    if (text.isNullOrBlank()) {
                        Log.e(
                            TAG,
                            "В JSON нет choices[0].message.content — ответ: " + body.take(MAX_LOG_CHARS)
                        )
                        throw DeepSeekException(EMPTY_REPLY)
                    }
                    emit(text.trim())
                    return@use
                }

                val source = r.body?.source() ?: run {
                    Log.e(TAG, "HTTP 200 без тела ответа (body == null)")
                    throw DeepSeekException(EMPTY_REPLY)
                }
                // Диагностический буфер: если модель не вернула ни одного
                // куска, в logcat попадут реальные строки ответа.
                val rawTail = StringBuilder()
                var emittedChars = 0
                var sawDone = false
                while (!source.exhausted()) {
                    // Каждая строка — точка проверки отмены: если приложение уже
                    // в фоне, поток SSE прекращается и токены не тратятся дальше.
                    currentCoroutineContext().ensureActive()
                    val line = source.readUtf8Line() ?: break
                    if (rawTail.length < MAX_LOG_CHARS) rawTail.append(line).append('\n')
                    // Служебные строки SSE (`event:`, комментарии `: keep-alive`)
                    // перевода не содержат — пропускаем.
                    if (!line.startsWith("data:")) continue
                    val data = line.substring(5).trim()
                    if (data.isEmpty()) continue
                    if (data == "[DONE]") {
                        sawDone = true
                        break
                    }
                    val json = runCatching { JSONObject(data) }.getOrNull()
                    if (json == null) {
                        Log.w(TAG, "SSE-кусок не разобран как JSON: ${data.take(300)}")
                        continue
                    }
                    // Ошибка может прийти и внутри успешного HTTP 200 —
                    // например, при обрыве генерации на стороне сервера.
                    val streamError = json.optJSONObject("error")
                    if (streamError != null) {
                        val message = streamError.optString("message").ifBlank { data }
                        Log.e(TAG, "Ошибка в SSE-потоке: $message")
                        throw DeepSeekException("Ошибка перевода: $message")
                    }
                    val chunk = json.optJSONArray("choices")
                        ?.optJSONObject(0)
                        ?.optJSONObject("delta")
                        ?.optString("content")
                        .orEmpty()
                    if (chunk.isEmpty()) continue
                    emittedChars += chunk.length
                    emit(chunk)
                }
                Log.i(TAG, "SSE завершён: символов=$emittedChars, [DONE]=$sawDone")
                // HTTP 200, но перевода нет: раньше интерфейс оставлял пустоту
                // или дублировал оригинал. Теперь это явная ошибка.
                if (emittedChars == 0) {
                    Log.e(
                        TAG,
                        "SSE без единого символа перевода (DONE=$sawDone). Начало ответа: " +
                            rawTail.toString().take(MAX_LOG_CHARS)
                    )
                    throw DeepSeekException(EMPTY_REPLY)
                }
            }
        } catch (e: DeepSeekException) {
            // Уже содержательная ошибка с понятным текстом — не заворачиваем.
            throw e
        } catch (e: IOException) {
            // Сокет закрыли мы сами из-за отмены — это не ошибка сети.
            if (!currentCoroutineContext().isActive) {
                throw CancellationException("Запрос к API отменён")
            }
            Log.e(TAG, "Обрыв соединения $origin: ${e.javaClass.simpleName}: ${e.message}", e)
            throw DeepSeekException(networkMessage(e), e)
        } finally {
            // Штатное завершение: снимаем обработчик, чтобы не отменять уже закрытый вызов.
            cancelOnJobEnd?.dispose()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Ключ из настроек → значение заголовка `Authorization`.
     *
     * Пользователь может вставить ключ с переводом строки, кавычками или
     * скопировать его вместе с префиксом `Bearer` — всё это давало 401,
     * хотя «ключ вроде введён». Пробелы внутри ключа недопустимы, поэтому
     * удаляем их все (у настоящего ключа их не бывает).
     */
    private fun normalizeKey(raw: String): String {
        var key = raw.trim()
        if (key.startsWith("Bearer ", ignoreCase = true)) {
            key = key.substring(7).trim()
            Log.w(TAG, "Из ключа удалён лишний префикс «Bearer»")
        }
        // Кавычки вокруг строки — тоже частая ошибка копирования из чата.
        key = key.trim('"', '\'', '“', '”', '\u00A0')
        val withoutSpaces = key.filterNot { it.isWhitespace() }
        if (withoutSpaces != key) {
            Log.w(TAG, "Из ключа удалены пробелы/переводы строк")
        }
        return withoutSpaces
    }

    /** Маска ключа для logcat: «sk-…» и последние 4 символа, не весь ключ. */
    private fun maskKey(key: String): String =
        if (key.length <= 8) "***" else "${key.take(5)}…${key.takeLast(4)}"

    /** Тело ответа целиком, но без падения на уже закрытом потоке. */
    private fun readBody(response: Response): String =
        runCatching { response.body?.string().orEmpty() }.getOrDefault("")

    /**
     * Разбор обычного (не-SSE) ответа: `choices[0].message.content`.
     * Возвращает null, если поля нет или оно пустое.
     */
    private fun extractContentFromJson(body: String): String? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        // Ошибка тоже может прийти с кодом 200 (например, от прокси).
        json.optJSONObject("error")?.let { err ->
            val message = err.optString("message").ifBlank { body.take(200) }
            Log.e(TAG, "JSON-ответ содержит error: $message")
            throw DeepSeekException("Ошибка перевода: $message")
        }
        return json.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            ?.takeUnless { it.isBlank() }
    }

    /** Понятный текст для интерфейса по HTTP-коду; тело уже ушло в logcat. */
    private fun httpMessage(code: Int, body: String): String {
        val detail = extractError(body)
        Log.w(TAG, "Человекочитаемая причина для HTTP $code: $detail")
        return when (code) {
            // 401 — ключ не принят: чаще всего скопирован не полностью
            // или отозван на platform.deepseek.com.
            401 -> "Ошибка перевода: ключ DeepSeek не принят (HTTP 401). " +
                "Проверьте ключ в настройках."

            // 402 — на балансе аккаунта DeepSeek закончились средства.
            402 -> "Ошибка перевода: на балансе DeepSeek недостаточно средств (HTTP 402)."

            // 429 — превышен лимит запросов, помогает только пауза.
            429 -> "Ошибка перевода: слишком частые запросы к DeepSeek (HTTP 429). " +
                "Повторите через минуту."

            500, 502, 503, 504 -> "Ошибка перевода: сервер DeepSeek недоступен (HTTP $code)."

            else -> if (detail.isNotBlank()) {
                "Ошибка перевода: DeepSeek ответил HTTP $code — ${detail.take(160)}"
            } else {
                "Ошибка перевода: DeepSeek ответил HTTP $code"
            }
        }
    }

    /** Понятный текст для сетевого исключения (тело ответа тут отсутствует). */
    private fun networkMessage(e: IOException): String = when (e) {
        is SocketTimeoutException ->
            "Ошибка перевода: DeepSeek не ответил вовремя (таймаут сети)."

        is UnknownHostException ->
            "Ошибка перевода: нет доступа к api.deepseek.com — проверьте интернет."

        is SSLException ->
            "Ошибка перевода: защищённое соединение с DeepSeek не установлено (SSL)."

        else -> "Ошибка перевода: сеть недоступна (${e.javaClass.simpleName})."
    }

    private fun extractError(body: String): String =
        runCatching { JSONObject(body).optJSONObject("error")?.optString("message").orEmpty() }
            .getOrNull().takeUnless { it.isNullOrBlank() } ?: body.take(300)

    companion object {
        /**
         * Сколько предыдущих реплик диалога уходит в запрос.
         * 3 реплики — достаточно для смысла и дешёво по токенам; жёсткий
         * потолок продублирован здесь, чтобы никакой вызывающий код
         * случайно не отправил всю историю целиком.
         */
        const val CONTEXT_TURNS = 3

        /**
         * Заглушка вместо пустого перевода. Ровно эту строку видят
         * интерфейс и logcat, когда API ответил 200, но текста не прислал:
         * дублировать оригинал в поле перевода нельзя.
         */
        const val EMPTY_REPLY = "Ошибка перевода: DeepSeek вернул пустой ответ"

        /** Сколько символов тела ответа писать в logcat (без гигантских строк). */
        private const val MAX_LOG_CHARS = 1500

        /** Тег Logcat: `adb logcat -s DeepSeekAPI`. */
        private const val TAG = "DeepSeekAPI"

        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
