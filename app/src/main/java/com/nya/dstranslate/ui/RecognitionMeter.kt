package com.nya.dstranslate.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.sin

/**
 * Полоска «распознавание идёт».
 *
 * Показывает три вещи одновременно, чтобы ожидание перестало быть немым:
 *  1. эквалайзер из пяти столбиков — микрофон действительно слушает;
 *  2. живой текст по словам: последнее распознанное слово подсвечено,
 *     за ним мигает курсор — видно, как слова появляются одно за другим;
 *  3. заполнение лимита реплики (5 секунд) — к концу полоски фраза
 *     закрывается сама, и уходит перевод.
 *
 * Виджет сознательно не хранит состояние: всё приходит из MainViewModel,
 * поэтому он одинаково работает в «Тексте» и моно-режимах.
 */
@Composable
fun RecognitionMeter(
    active: Boolean,
    liveText: String,
    progress: Float?,
    modifier: Modifier = Modifier
) {
    val s = LocalStrings.current

    // Одна бесконечная анимация на все эффекты: экономит перерисовки.
    val clock = rememberInfiniteTransition(label = "recognition")
    val phase by clock.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
        label = "equalizer"
    )
    // Мигающий курсор после последнего слова.
    val caretAlpha by clock.animateFloat(
        initialValue = 0.15f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(480), RepeatMode.Reverse),
        label = "caret"
    )

    val accent = if (active) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Эквалайзер: пока запись активна — столбики «дышат».
                EqualizerBars(active = active, phase = phase, color = accent)
                Spacer(Modifier.width(10.dp))

                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (active) s.recognizingNow else s.voiceReady,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = accent
                        )
                        // Счётчик фраз убран: виджет показывает только лимит.
                        Spacer(Modifier.weight(1f))
                        // Отсчёт лимита в секундах: 3 / 5 с.
                        if (active && progress != null) {
                            Text(
                                text = s.secondsOfLimit((progress * LIMIT_SECONDS).toInt()),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (progress >= 0.85f) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(Modifier.height(2.dp))

                    // Живой текст: слова появляются по мере распознавания.
                    val shown = liveText.trim()
                    if (shown.isEmpty()) {
                        Text(
                            text = if (active) s.wordByWordHint else s.voiceReadyHint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else {
                        Text(
                            text = liveAnnotated(shown, accent, caretAlpha),
                            fontSize = 17.sp,
                            lineHeight = 22.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Полоска лимита: заполняется ровно за 5 секунд звучащей речи.
            if (active && progress != null) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = if (progress >= 0.85f) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surface
                )
            }
        }
    }
}

/** Пять столбиков эквалайзера; высота — синус со сдвигом фазы по индексу. */
@Composable
private fun EqualizerBars(active: Boolean, phase: Float, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        repeat(BAR_COUNT) { index ->
            // 4 dp — «покой», до 16 dp — активная речь.
            val height = if (active) {
                4f + abs(sin(phase + index * 0.9f)) * 12f
            } else {
                4f
            }
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(height.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color)
            )
        }
    }
}

/**
 * Красит последнее слово акцентным цветом и добавляет мигающий курсор.
 * Разделителями считаются пробелы и пунктуация — работает и для кириллицы,
 * и для иероглифов (в китайском слово-фраза часто идёт без пробелов).
 */
private fun liveAnnotated(text: String, accent: Color, caretAlpha: Float) =
    buildAnnotatedString {
        val cut = text.lastIndexOfAny(SEPARATORS.toCharArray())
        if (cut in 0 until text.lastIndex) {
            append(text.substring(0, cut + 1))
            withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Bold)) {
                append(text.substring(cut + 1))
            }
        } else {
            withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Bold)) {
                append(text)
            }
        }
        withStyle(SpanStyle(color = accent.copy(alpha = caretAlpha))) { append("▍") }
    }

private const val BAR_COUNT = 5
private const val LIMIT_SECONDS = 5
private const val SEPARATORS = " \n\t，。！？、,.;:!?"
