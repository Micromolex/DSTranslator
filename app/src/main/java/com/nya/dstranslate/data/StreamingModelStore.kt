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
 * Модели потокового распознавания (sherpa-onnx, int8).
 *
 * Наборы:
 *  - RU: `sherpa-onnx-streaming-zipformer-small-ru-vosk-int8-2025-08-16`
 *    (потоковый zipformer, обучен на русском) — нужен режимам «Авто» и «только RU»;
 *  - ZH: `sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23` (потоковый
 *    zipformer, китайский) — «Авто» и «только ZH»;
 *  - LID: `sherpa-onnx-whisper-tiny` — только encoder/decoder, нужен
 *    `SpokenLanguageIdentification` для определения языка в обычном «Авто».
 *    Моно-режимам он не требуется: язык у них зафиксирован чипом.
 *
 * В APK модели не входят. Качаются в `filesDir` по HTTP Range, поэтому обрыв
 * связи не начинает загрузку заново. Размеры сверяются побайтно — ими же
 * проверяется докачка. Скачивать можно как весь набор, так и одну модель:
 * моно-режиму хватает ~25 МБ вместо ~150 МБ.
 */
class StreamingModelStore(ctx: Context) {

    val root: File = File(ctx.filesDir, "models/streaming")
    val ruDir: File = File(root, "ru")
    val zhDir: File = File(root, "zh")
    val lidDir: File = File(root, "lid")

    data class Spec(val dir: File, val path: String, val bytes: Long, val url: String)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val files: List<Spec> = buildList {
        RU_FILES.forEach { (name, size) -> add(Spec(ruDir, name, size, RU_BASE + name)) }
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

    /** Русская streaming-модель на месте. */
    fun isRuReady(): Boolean = RU_FILES.all { (name, size) -> File(ruDir, name).length() == size }

    /** Китайская streaming-модель на месте. */
    fun isZhReady(): Boolean = ZH_FILES.all { (name, size) -> File(zhDir, name).length() == size }

    /** LID (Whisper tiny) на месте. */
    fun isLidReady(): Boolean = LID_FILES.all { (name, size) -> File(lidDir, name).length() == size }

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
     */
    suspend fun download(lang: Lang? = null, onProgress: (Long) -> Unit) =
        withContext(Dispatchers.IO) {
            val wanted = specs(lang)
            root.mkdirs()
            var completed = 0L

            for (spec in wanted) {
                val target = File(spec.dir, spec.path)
                if (target.length() == spec.bytes) {
                    completed += spec.bytes
                    onProgress(completed)
                    continue
                }
                if (target.length() > spec.bytes) target.delete()
                target.parentFile?.mkdirs()

                var attempts = 0
                while (target.length() < spec.bytes) {
                    currentCoroutineContext().ensureActive()
                    attempts++
                    if (attempts > MAX_ATTEMPTS) error("Не удалось скачать ${spec.path}")
                    val have = target.length()
                    try {
                        val request = Request.Builder()
                            .url(spec.url)
                            .header("Range", "bytes=$have-")
                            .build()
                        client.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) error("HTTP ${response.code}")
                            val body = response.body ?: error("Пустой ответ")
                            RandomAccessFile(target, "rw").use { out ->
                                out.seek(have)
                                var written = have
                                body.byteStream().use { input ->
                                    val buffer = ByteArray(64 * 1024)
                                    while (true) {
                                        currentCoroutineContext().ensureActive()
                                        val n = input.read(buffer)
                                        if (n < 0) break
                                        out.write(buffer, 0, n)
                                        written += n
                                        onProgress(completed + written.coerceAtMost(spec.bytes))
                                    }
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Обрыв — норма для больших файлов: докачиваем с того же места.
                        if (target.length() >= spec.bytes) break
                        delay(1000)
                    }
                }
                completed += spec.bytes
                onProgress(completed)
            }
            if (!isReadyFor(lang)) error("Файлы моделей скачаны не полностью")
        }

    /** Удалить все streaming-модели (освободить ~150 МБ). */
    fun delete() {
        root.deleteRecursively()
    }

    /**
     * Удалить модели одного языка. LID и вторая модель не трогаются:
     * обычному «Авто» они ещё могут понадобиться.
     */
    fun deleteFor(lang: Lang) {
        dirFor(lang).deleteRecursively()
    }

    companion object {
        private const val RU_BASE =
            "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-small-ru-vosk-int8-2025-08-16/resolve/main/"
        private const val ZH_BASE =
            "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23/resolve/main/"
        private const val LID_BASE =
            "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main/"

        /** Файлы русской streaming-модели: имя → точный размер в байтах. */
        val RU_FILES = linkedMapOf(
            "encoder.int8.onnx" to 26_214_060L,
            "decoder.onnx" to 2_093_080L,
            "joiner.int8.onnx" to 259_417L,
            "tokens.txt" to 6_388L,
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
