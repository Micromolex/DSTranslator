package com.nya.dstranslate.data

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
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Пара реплик диалога, передаваемая в модель как контекст. */
data class ChatTurn(val from: String, val to: String)

class DeepSeekException(message: String) : IOException(message)

class DeepSeekClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Стриминговый перевод. Эмитит кусочки перевода по мере генерации —
     * именно это даёт эффект «перевода в реальном времени».
     */
    fun translateStream(
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
        val apiKey = ApiConfig.DEEPSEEK_API_KEY
        if (apiKey.isBlank()) throw DeepSeekException("Не задан API-ключ DeepSeek")

        val messages = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", systemPrompt))
            history.takeLast(6).forEach { turn ->
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

        val request = Request.Builder()
            .url(ApiConfig.BASE_URL)
            .addHeader("Authorization", "Bearer $apiKey")
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
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    throw DeepSeekException("HTTP ${response.code}: ${extractError(body)}")
                }
                val source = response.body?.source() ?: throw DeepSeekException("Пустой ответ")
                while (!source.exhausted()) {
                    // Каждая строка — точка проверки отмены: если приложение уже
                    // в фоне, поток SSE прекращается и токены не тратятся дальше.
                    currentCoroutineContext().ensureActive()
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.substring(5).trim()
                    if (data.isEmpty() || data == "[DONE]") continue
                    val chunk = runCatching {
                        JSONObject(data).optJSONArray("choices")
                            ?.optJSONObject(0)
                            ?.optJSONObject("delta")
                            ?.optString("content")
                            .orEmpty()
                    }.getOrDefault("")
                    if (chunk.isNotEmpty()) emit(chunk)
                }
            }
        } catch (e: IOException) {
            // Сокет закрыли мы сами из-за отмены — это не ошибка сети.
            if (!currentCoroutineContext().isActive) {
                throw CancellationException("Запрос к API отменён")
            }
            throw e
        } finally {
            // Штатное завершение: снимаем обработчик, чтобы не отменять уже закрытый вызов.
            cancelOnJobEnd?.dispose()
        }
    }.flowOn(Dispatchers.IO)

    private fun extractError(body: String): String =
        runCatching { JSONObject(body).optJSONObject("error")?.optString("message").orEmpty() }
            .getOrNull().takeUnless { it.isNullOrBlank() } ?: body.take(300)

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
