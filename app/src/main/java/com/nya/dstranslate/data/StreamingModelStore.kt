package com.nya.dstranslate.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * Модели локального распознавания (sherpa-onnx, int8).
 *
 * Раскладка на диске разделяет два разных типа моделей:
 *  - `models/streaming/` — потоковые (online) модели, которые умеют отдавать
 *    партиалы: ZH (`sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23`)
 *    и LID (`sherpa-onnx-whisper-tiny`);
 *  - `models/offline/ru/` — офлайн-модель русского языка GigaAM v3
 *    (RNN-T, e2e): она декодирует сегмент целиком и не является потоковой.
 *    Держать её в `streaming/` нельзя — это разные движки sherpa-onnx.
 *
 * Источник русской модели — `Smirnov75/GigaAM-v3-sherpa-onnx` (раннер
 * проверен: в ONNX-энкодере есть метаданные `is_giga_am`, `pred_hidden=320`,
 * `subsampling_factor=4`, а декодер приведён к входу `x, unused_x_len.1,
 * h.1, c.1` и выходу `dec [1,320,1]`, как требует sherpa-onnx 1.13.8).
 * Это offline-вариант `e2e_rnnt` в int8: пунктуация и нормализация чисел
 * приходят из самой модели.
 *
 * В APK модели не входят. Качаются в `filesDir` по HTTP Range, поэтому обрыв
 * связи не начинает загрузку заново. Размеры сверяются побайтно — ими же
 * проверяется докачка. Скачивать можно как весь набор, так и одну модель:
 * моно-режиму хватает одной языковой модели вместо полного набора.
 */
class StreamingModelStore(ctx: Context) {

    /** Корень потоковых моделей (китайская + LID). */
    val streamingRoot: File = File(ctx.filesDir, "models/streaming")

    /** Корень офлайн-моделей; русская GigaAM живёт отдельно от streaming. */
    val offlineRoot: File = File(ctx.filesDir, "models/offline")

    /** Русская офлайн-модель GigaAM v3. */
    val ruDir: File = File(offlineRoot, "ru")
    val zhDir: File = File(streamingRoot, "zh")
    val lidDir: File = File(streamingRoot, "lid")

    /** Один файл модели: откуда качать и сколько байт должно быть на диске. */
    data class Remote(val name: String, val bytes: Long)

    data class Spec(val dir: File, val path: String, val bytes: Long, val url: String)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val files: List<Spec> = buildList {
        RU_FILES.forEach { (name, remote) ->
            add(Spec(ruDir, name, remote.bytes, RU_BASE + remote.name))
        }
        ZH_FILES.forEach { (name, size) -> add(Spec(zhDir, name, size, ZH_BASE + name)) }
        LID_FILES.forEach { (name, size) -> add(Spec(lidDir, name, size, LID_BASE + name)) }
    }

    /**
     * Какие файлы нужны конкретному режиму: null — обычное «Авто»
     * (обе модели + LID), иначе только модель зафиксированного языка.
     */
    private fun specs(lang: Lang?): List<Spec> = when (lang) {
        null -> files
        Lang.RU -> files.filter { it.dir === ruDir }
        Lang.ZH -> files.filter { it.dir === zhDir }
    }

    /** Русская модель GigaAM v3 на месте. */
    fun isRuReady(): Boolean = RU_FILES.all { (name, remote) ->
        File(ruDir, name).length() == remote.bytes
    }

    /** Китайская streaming-модель на месте. */
    fun isZhReady(): Boolean = ZH_FILES.all { (name, size) -> File(zhDir, name).length() == size }

    /** LID (Whisper tiny) на месте. */
    fun isLidReady(): Boolean = LID_FILES.all { (name, size) -> File(lidDir, name).length() == size }

    /**
     * Удаляет файлы прежних версий моделей, которых больше нет в текущих
     * наборах, и переносит русскую модель из прежней папки `streaming/ru`
     * в `offline/ru` (в 1.6.15 и раньше она лежала не на своём месте).
     * Иначе после обновления в `filesDir` остаются сотни мегабайт мёртвого
     * груза, а проверки готовности не сходятся.
     */
    fun pruneObsolete() {
        // Старая раскладка: русская модель (zipformer, затем GigaAM v3) лежала
        // в models/streaming/ru. Это не потоковая модель — папку убираем целиком.
        File(streamingRoot, "ru").deleteRecursively()
        val sets = listOf(
            ruDir to RU_FILES.keys,
            zhDir to ZH_FILES.keys,
            lidDir to LID_FILES.keys
        )
        for ((dir, keep) in sets) {
            dir.listFiles()?.forEach { file ->
                if (file.isFile && file.name !in keep) file.delete()
            }
        }
    }

    /** Всё, что нужно обычному «Авто», скачано. */
    fun isReady(): Boolean = isRuReady() && isZhReady() && isLidReady()

    fun isLangReady(lang: Lang): Boolean =
        if (lang == Lang.RU) isRuReady() else isZhReady()

    /** Модели для конкретного режима: null — обычное «Авто». */
    fun isReadyFor(lang: Lang?): Boolean = if (lang == null) isReady() else isLangReady(lang)

    fun dirFor(lang: Lang): File = if (lang == Lang.RU) ruDir else zhDir

    /** Сколько уже скачано для режима [lang] (null — весь набор «Авто»). */
    fun downloadedBytes(lang: Lang? = null): Long =
        specs(lang).sumOf { spec -> File(spec.dir, spec.path).length().coerceIn(0L, spec.bytes) }

    /** Сколько всего занимает набор для режима [lang] (null — весь набор «Авто»). */
    fun totalBytes(lang: Lang? = null): Long = specs(lang).sumOf { it.bytes }

    /**
     * Докачивает всё недостающее для режима [lang]. [onProgress] получает
     * суммарно скачанные байты и вызывается из IO-потока.
     *
     * Размер проверяется по данным сервера, а не только по локальной таблице:
     *  - `Content-Length` (для ответа `200`) или общий размер из `Content-Range`
     *    (для `206`) обязателен; без него файл не принимается;
     *  - заявленный сервером размер должен совпасть с ожидаемым для файла;
     *  - если тело короче заявленного (обрыв, усечённый ответ), файл удаляется
     *    и выбрасывается «Загрузка прервана» — недокачанный файл никогда не
     *    попадёт в модель.
     *
     * Продолжение с места обрыва (`Range`) принимается только при `206`
     * и корректном `Content-Range`; при `200` файл перекачивается с нуля,
     * иначе в конец дописывался бы второй экземпляр файла.
     */
    suspend fun download(lang: Lang? = null, onProgress: (Long) -> Unit) =
        withContext(Dispatchers.IO) {
            val wanted = specs(lang)
            var completed = 0L

            for (spec in wanted) {
                val target = File(spec.dir, spec.path)
                target.parentFile?.mkdirs()
                if (target.length() == spec.bytes) {
                    completed += spec.bytes
                    onProgress(completed)
                    continue
                }

                var attempts = 0
                var lastError: String? = null
                while (true) {
                    currentCoroutineContext().ensureActive()
                    attempts++
                    if (attempts > MAX_ATTEMPTS) {
                        target.delete()
                        error(
                            "Не удалось скачать ${spec.path}" +
                                (lastError?.let { ": $it" } ?: "")
                        )
                    }
                    if (target.length() > spec.bytes) target.delete()
                    val have = target.length()

                    try {
                        val request = Request.Builder()
                            .url(spec.url)
                            // Без gzip: иначе Content-Length и счётчик байт
                            // относятся к сжатому телу, а не к файлу.
                            .header("Accept-Encoding", "identity")
                            .apply { if (have > 0) header("Range", "bytes=$have-") }
                            .build()
                        client.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) error("HTTP ${response.code}")
                            val body = response.body ?: error("Пустой ответ")

                            val resume = have > 0 && response.code == 206
                            val start = if (resume) have else 0L
                            val rangeHeader = response.header("Content-Range")
                            val rangeStart = startByteFromContentRange(rangeHeader)
                            val rangeTotal = totalBytesFromContentRange(rangeHeader)

                            // Если Range начался не с нашего места — доверять
                            // такому ответу нельзя, начинаем файл заново.
                            if (resume && rangeStart != null && rangeStart != have) {
                                target.delete()
                                error("Сервер вернул диапазон с $rangeStart вместо $have")
                            }

                            val declared = when {
                                response.code == 206 -> rangeTotal
                                    ?: body.contentLength().takeIf { it >= 0 }?.plus(have)
                                else -> body.contentLength().takeIf { it >= 0 }
                            } ?: error("Сервер не сообщил размер ${spec.path}")
                            if (declared != spec.bytes) {
                                error(
                                    "Размер ${spec.path} на сервере ($declared Б) " +
                                        "не совпал с ожидаемым (${spec.bytes} Б)"
                                )
                            }

                            // Сервер обязан прислать ровно недостающий кусок.
                            val bodyLength = body.contentLength()
                            val expectedBody = declared - start
                            if (bodyLength >= 0 && bodyLength != expectedBody) {
                                error(
                                    "Неверный Content-Length у ${spec.path}: " +
                                        "$bodyLength Б вместо $expectedBody Б"
                                )
                            }

                            if (have > 0 && !resume) target.delete()
                            RandomAccessFile(target, "rw").use { out ->
                                if (resume) out.seek(start) else out.setLength(0)
                                var written = start
                                body.byteStream().use { input ->
                                    val buffer = ByteArray(64 * 1024)
                                    while (true) {
                                        currentCoroutineContext().ensureActive()
                                        val n = input.read(buffer)
                                        if (n < 0) break
                                        out.write(buffer, 0, n)
                                        written += n
                                        if (written > declared) break
                                        onProgress(completed + written.coerceAtMost(spec.bytes))
                                    }
                                }
                                if (written < declared) {
                                    throw IncompleteDownload(
                                        "Загрузка прервана: ${spec.path} — " +
                                            "получено $written из $declared байт"
                                    )
                                }
                                if (written > declared) {
                                    throw IncompleteDownload(
                                        "Загрузка прервана: ${spec.path} — " +
                                            "сервер отдал $written байт вместо $declared"
                                    )
                                }
                            }
                        }
                        if (target.length() == spec.bytes) break
                        throw IncompleteDownload(
                            "Загрузка прервана: ${spec.path} — " +
                                "на диске ${target.length()} из ${spec.bytes} байт"
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: IncompleteDownload) {
                        // Недокачанный файл не оставляем и не пытаемся запускать.
                        target.delete()
                        throw IllegalStateException(e.message)
                    } catch (e: Exception) {
                        // Обрыв соединения — докачиваем с того же места.
                        lastError = e.message
                        if (target.length() >= spec.bytes) break
                        delay(1000)
                    }
                }

                completed += spec.bytes
                onProgress(completed)
            }

            if (!isReadyFor(lang)) {
                // Битые недокачки не оставляем: следующая попытка начнётся с нуля.
                specs(lang).forEach { spec ->
                    val file = File(spec.dir, spec.path)
                    if (file.length() != spec.bytes) file.delete()
                }
                error("Загрузка прервана: файлы моделей не скачаны полностью")
            }
        }

    /**
     * Короткий/лишний ответ сервера: файл нельзя оставлять на диске.
     * Отличается от обычных сетевых ошибок, чтобы не уйти в повторный цикл
     * докачки: такой ответ уже прошёл проверку заголовков.
     */
    private class IncompleteDownload(message: String) : Exception(message)

    /** Начало диапазона из заголовка `bytes 100-199/12345`. */
    private fun startByteFromContentRange(value: String?): Long? =
        value?.substringAfter("bytes ", "")?.substringBefore('-')?.toLongOrNull()

    /** Общий размер файла из заголовка `bytes 100-199/12345`. */
    private fun totalBytesFromContentRange(value: String?): Long? =
        value?.substringAfter('/', "")?.toLongOrNull()

    /** Удалить все модели (освободить место). */
    fun delete() {
        streamingRoot.deleteRecursively()
        offlineRoot.deleteRecursively()
    }

    /**
     * Удалить модели одного языка. LID и вторая модель не трогаются:
     * обычному «Авто» они ещё могут понадобиться.
     */
    fun deleteFor(lang: Lang) {
        dirFor(lang).deleteRecursively()
    }

    companion object {
        /**
         * GigaAM v3 e2e RNN-T (int8) в формате sherpa-onnx.
         *
         * `Smirnov75/GigaAM-v3-sherpa-onnx` — вариант `e2e_rnnt`: пунктуация
         * и числа приходят из модели, отдельной постобработки нет. Локальные
         * имена файлов — короткие (как ждёт код), удалённые — префиксные,
         * как в самом репозитории. Размеры ниже — точные размеры файлов
         * в репозитории; ими же проверяется целостность и докачка.
         */
        private const val RU_BASE =
            "https://huggingface.co/Smirnov75/GigaAM-v3-sherpa-onnx/resolve/main/"
        private const val ZH_BASE =
            "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23/resolve/main/"
        private const val LID_BASE =
            "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main/"

        /** Файлы русской GigaAM v3: локальное имя → файл в репозитории и размер. */
        val RU_FILES = linkedMapOf(
            "encoder.int8.onnx" to Remote("gigaam_v3_e2e_rnnt_encoder_int8.onnx", 318_995_997L),
            "decoder.onnx" to Remote("gigaam_v3_e2e_rnnt_decoder.onnx", 4_600_058L),
            "joiner.onnx" to Remote("gigaam_v3_e2e_rnnt_joint.onnx", 2_712_896L),
            "tokens.txt" to Remote("gigaam_v3_e2e_rnnt_tokens.txt", 13_353L),
        )

        /** Файлы китайской streaming-модели (int8 encoder/decoder/joiner). */
        val ZH_FILES = linkedMapOf(
            "encoder-epoch-99-avg-1.int8.onnx" to 21_621_684L,
            "decoder-epoch-99-avg-1.int8.onnx" to 1_888_682L,
            "joiner-epoch-99-avg-1.int8.onnx" to 1_795_562L,
            "tokens.txt" to 48_697L,
        )

        /** Whisper tiny для LID: только encoder и decoder, токены не нужны. */
        val LID_FILES = linkedMapOf(
            "tiny-encoder.int8.onnx" to 12_937_772L,
            "tiny-decoder.int8.onnx" to 89_855_401L,
        )

        private const val MAX_ATTEMPTS = 40
    }
}
