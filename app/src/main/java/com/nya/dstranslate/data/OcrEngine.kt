package com.nya.dstranslate.data

import android.graphics.Bitmap
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Распознавание текста на картинке — целиком на устройстве, без интернета.
 *
 * У ML Kit есть модели только для латиницы и китайского (плюс деванагари,
 * японский, корейский) — кириллической модели нет, поэтому русский печатный
 * текст на фото этим движком не распознаётся.
 */
class OcrEngine {

    private val chinese = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    private val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun recognize(bitmap: Bitmap): String {
        val image = InputImage.fromBitmap(bitmap, 0)
        val zh = runCatching { chinese.process(image).awaitText() }.getOrDefault("")
        val lat = runCatching { latin.process(image).awaitText() }.getOrDefault("")

        val hanCount = zh.count { isHan(it.code) }
        val picked = when {
            zh.isBlank() && lat.isBlank() -> ""
            zh.isBlank() -> lat
            lat.isBlank() -> zh
            // Если китайская модель нашла настоящие иероглифы — верим ей,
            // иначе латиница обычно точнее.
            hanCount >= 2 -> zh
            lat.length > zh.length -> lat
            else -> zh
        }
        return picked.trim()
    }

    fun close() {
        runCatching { chinese.close() }
        runCatching { latin.close() }
    }

    private fun isHan(code: Int) =
        code in 0x4E00..0x9FFF || code in 0x3400..0x4DBF || code in 0xF900..0xFAFF

    private suspend fun Task<Text>.awaitText(): String = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { result -> if (cont.isActive) cont.resume(result.text) }
        addOnFailureListener { error -> if (cont.isActive) cont.resumeWithException(error) }
    }
}
