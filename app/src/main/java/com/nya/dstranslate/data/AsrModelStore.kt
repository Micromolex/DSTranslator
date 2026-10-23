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
 * Модель Qwen3-ASR-0.6B (int8) для локального распознавания.
 *
 * Весит почти 1 ГБ, поэтому в APK её не кладём: качаем при первом включении
 * локального движка в `filesDir`. Файлы берутся по прямым ссылкам с
 * HuggingFace-зеркала — tar.bz2 на Android пришлось бы распаковывать
 * отдельной библиотекой, а отдельные файлы докачиваются по HTTP Range.
 *
 * Контрольные размеры взяты из карточки модели: сверяем ими не только
 * «скачано/не скачано», но и докачку после обрыва.
 */
class AsrModelStore(ctx: Context) {

    val dir: File = File(ctx.filesDir, "models/qwen3-asr")
    val tokenizerDir: File = File(dir, "tokenizer")

    data class Spec(val path: String, val bytes: Long)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** Все файлы на месте и ровно того размера. */
    fun isReady(): Boolean = FILES.all { File(dir, it.path).length() == it.bytes }

    /** Сколько уже скачано — для полоски прогресса. */
    fun downloadedBytes(): Long =
        FILES.sumOf { spec -> File(dir, spec.path).length().coerceIn(0L, spec.bytes) }

    /** Путь к папке с токенизатором (передаётся в конфиг распознавателя). */
    fun tokenizerPath(): String = tokenizerDir.absolutePath

    fun file(path: String): File = File(dir, path)

    /**
     * Докачивает всё недостающее. [onProgress] вызывается из IO-потока —
     * обновлять состояние UI нужно через main.
     */
    suspend fun download(onProgress: (Long) -> Unit) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        tokenizerDir.mkdirs()
        var completed = 0L

        for (spec in FILES) {
            val target = File(dir, spec.path)
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
                        .url(BASE_URL + spec.path)
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
                    // Обрыв связи — норма для больших файлов, докачиваем с того же места.
                    if (target.length() >= spec.bytes) break
                    delay(1000)
                }
            }
            completed += spec.bytes
            onProgress(completed)
        }
        if (!isReady()) error("Файлы модели скачаны не полностью")
    }

    /** Удалить модель (освободить ~1 ГБ). */
    fun delete() {
        dir.deleteRecursively()
    }

    fun totalBytes(): Long = TOTAL

    companion object {
        const val ENGINE_SYSTEM = "system"
        const val ENGINE_LOCAL = "local"

        const val BASE_URL =
            "https://huggingface.co/csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25/resolve/main/"

        /** Размеры файлов этой сборки модели (побайтно совпадают с релизом sherpa-onnx). */
        val FILES = listOf(
            Spec("conv_frontend.onnx", 44_148_281L),
            Spec("encoder.int8.onnx", 182_491_662L),
            Spec("decoder.int8.onnx", 755_914_231L),
            Spec("tokenizer/merges.txt", 1_671_853L),
            Spec("tokenizer/vocab.json", 2_776_833L),
            Spec("tokenizer/tokenizer_config.json", 12_487L),
        )

        val TOTAL: Long = FILES.sumOf { it.bytes }

        private const val MAX_ATTEMPTS = 40
    }
}
