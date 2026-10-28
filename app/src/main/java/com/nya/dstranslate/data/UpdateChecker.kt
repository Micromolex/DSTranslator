package com.nya.dstranslate.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Данные о новой версии из version.json. */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val notes: String,
    val minVersionCode: Int = 0
)

object UpdateChecker {

    /**
     * Адрес файла обновлений.
     *
     * Лежит в репозитории на GitHub и отдаётся в «сыром» виде (raw). Ссылку
     * вида /blob/ брать нельзя: GitHub по ней отдаёт HTML-страницу, а не JSON.
     * Поле `apkUrl` в этом файле указывает на APK из GitHub Releases.
     */
    const val DEFAULT_URL =
        "https://raw.githubusercontent.com/Micromolex/DSTranslator/main/update.json"

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun fetch(url: String): UpdateInfo = withContext(Dispatchers.IO) {
        if (url.isBlank()) throw IOException("адрес version.json не задан")
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Cache-Control", "no-cache")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("сервер ответил HTTP ${response.code}")
            val raw = response.body?.string().orEmpty()
            // Некоторые редакторы и Set-Content в PowerShell добавляют BOM —
            // org.json на нём спотыкается, поэтому срезаем.
            val body = raw.trim().removePrefix("\uFEFF").trim()
            if (body.isEmpty()) throw IOException("пустой ответ")
            if (body.startsWith("<")) {
                throw IOException(
                    "по этому адресу отдаётся HTML-страница, а не JSON. " +
                        "У ссылок вида github.com/.../blob/... замените начало на " +
                        "raw.githubusercontent.com (без /blob/)"
                )
            }
            val json = try {
                JSONObject(body)
            } catch (e: Exception) {
                throw IOException("ответ не является JSON: ${body.take(80)}")
            }
            // Основная схема: versionCode / versionName / apkUrl / notes.
            // Запасная (совместимость): latest_version / download_url.
            val legacyName = json.optString("latest_version", "")
            val code = json.optInt("versionCode", 0).takeIf { it > 0 }
                ?: versionCodeFromName(legacyName)
            if (code <= 0) {
                throw IOException(
                    "в файле обновлений нет поля versionCode " +
                        "(или latest_version вида \"1.6.0\")"
                )
            }
            UpdateInfo(
                versionCode = code,
                versionName = json.optString("versionName", "").ifBlank { legacyName.ifBlank { "?" } },
                apkUrl = json.optString("apkUrl", "").ifBlank { json.optString("download_url", "") },
                notes = json.optString("notes", ""),
                minVersionCode = json.optInt("minVersionCode", 0)
            )
        }
    }

    /**
     * Собирает versionCode из человекочитаемой версии: "1.6.0" -> 10600,
     * "1.10.2" -> 11002. Нужно только для запасной схемы latest_version.
     */
    private fun versionCodeFromName(name: String): Int {
        val parts = name.trim().removePrefix("v").split('.', '-', '+')
        fun part(i: Int) = parts.getOrNull(i)?.filter { it.isDigit() }?.take(3)?.toIntOrNull() ?: 0
        val major = part(0)
        val minor = part(1)
        val patch = part(2)
        if (major <= 0) return 0
        // Части могут быть двузначными, поэтому «склеиваем» их без потери места.
        return major * 10000 + minor.coerceAtMost(99) * 100 + patch.coerceAtMost(99)
    }

    /** Скачивает APK, периодически сообщая процент готовности. */
    suspend fun download(url: String, dest: File, onProgress: suspend (Int) -> Unit) =
        withContext(Dispatchers.IO) {
            if (url.isBlank()) throw IOException("в version.json не указан apkUrl")
            val request = Request.Builder().url(url).build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("сервер ответил HTTP ${response.code}")
                val body = response.body ?: throw IOException("пустой ответ")
                val total = body.contentLength()
                dest.parentFile?.mkdirs()
                body.byteStream().use { input ->
                    dest.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var read = 0L
                        var lastPercent = -1
                        while (true) {
                            // Закрытие окна отменяет корутину — загрузка
                            // должна остановиться, а не дососать гигабайты.
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n <= 0) break
                            output.write(buffer, 0, n)
                            read += n
                            if (total > 0) {
                                val percent = (read * 100 / total).toInt()
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    onProgress(percent)
                                }
                            }
                        }
                    }
                }
            }
        }
}
