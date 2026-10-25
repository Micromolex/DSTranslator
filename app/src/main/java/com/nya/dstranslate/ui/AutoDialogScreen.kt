package com.nya.dstranslate.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nya.dstranslate.MainViewModel
import com.nya.dstranslate.Utterance
import com.nya.dstranslate.data.Lang
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Пропорции двух окон моно-режима: оригинал (ввод) — 40 %, перевод — 60 %.
 * В ручных режимах языки известны, поэтому длинный переведённый текст важнее
 * короткого оригинала. В обычном «Авто» делить нечего — реплики идут одной
 * лентой карточек.
 */
private const val MONO_SOURCE_WEIGHT = 0.4f
private const val MONO_TRANSLATION_WEIGHT = 0.6f

/**
 * Автодиалог по ролям: ИИ непрерывно слушает, сам определяет язык каждой реплики
 * и переводит её на второй язык.
 *
 * Все три режима идут через потоковое распознавание (см. StreamingAutoEngine):
 *  - «Авто» — карточки по ролям, язык каждой реплики определяется LID;
 *  - моно-режимы «только RU» / «только ZH» — два окна: сверху оригинал,
 *    снизу перевод, прокрутка общая (см. [MonoSplitTranscript]).
 *
 * Раскладка сверху вниз: чипы языка и очистка, плашка промежуточного текста
 * (серая, не переводится), список реплик, служебные сообщения, кнопка старта.
 * Плашка partial специально стоит НАД списком: раньше она была внизу и
 * наезжала на перевод и кнопку.
 */
@Composable
fun AutoDialogScreen(vm: MainViewModel) {
    val cardsState = rememberLazyListState()
    val s = LocalStrings.current

    // Уход с вкладки обязательно глушит микрофон: иначе запись продолжается
    // в фоне и жрёт батарею, а переводы сыпятся в закрытый экран.
    DisposableEffect(Unit) {
        onDispose {
            vm.stopAuto()
            vm.stopSpeaking()
        }
    }

    // Моно-режим: выбран один язык, реплики не диалог, а две широкие колонки.
    val mono = vm.autoOverride != null

    // Автопрокрутка карточек. Ключ включает длину последнего перевода:
    // перевод приходит кусками, высота последней карточки растёт, и без
    // этого пользователь не увидит конец длинной реплики без ручной прокрутки.
    val lastIndex = vm.autoItems.lastIndex
    val lastTargetLength = vm.autoItems.lastOrNull()?.target?.length ?: 0
    LaunchedEffect(lastIndex, lastTargetLength, mono) {
        if (!mono && lastIndex >= 0) {
            // Большой offset «дотягивает» карточку до низа: LazyList сам
            // ограничивает прокрутку фактическим концом списка.
            cardsState.scrollToItem(lastIndex, Int.MAX_VALUE)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // Отступы и промежутки ужаты: освободившееся место получает
            // окно перевода (Box с weight(1f) ниже). Сами чипы не уменьшаем.
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Язык распознавания и очистка диалога — в одной строке.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = vm.autoOverride == null,
                onClick = { vm.setAutoLanguage(null) },
                label = { Text(s.chipAuto, fontSize = 13.sp) }
            )
            FilterChip(
                selected = vm.autoOverride == Lang.RU,
                onClick = { vm.setAutoLanguage(Lang.RU) },
                label = { Text(s.chipOnlyRu, fontSize = 13.sp) }
            )
            FilterChip(
                selected = vm.autoOverride == Lang.ZH,
                onClick = { vm.setAutoLanguage(Lang.ZH) },
                label = { Text(s.chipOnlyZh, fontSize = 13.sp) }
            )
            Spacer(Modifier.weight(1f))
            // Компактная корзина: строка чипов не должна занимать лишнюю высоту.
            IconButton(
                onClick = { vm.clearAuto() },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(Icons.Filled.DeleteSweep, contentDescription = s.autoClear)
            }
        }

        // Нужные модели. «Авто» требует обе + LID, моно-режим — только свою.
        if (!vm.streamingReady) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = when (vm.autoOverride) {
                            Lang.RU -> s.autoModelMissingRu
                            Lang.ZH -> s.autoModelMissingZh
                            else -> if (vm.streamingUsable) {
                                s.autoStreamMissingZhFallback
                            } else {
                                s.autoStreamMissing
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    if (vm.streamingBusy) {
                        LinearProgressIndicator(
                            progress = { vm.streamingProgress },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "${s.autoStreamDownloading} ${(vm.streamingProgress * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    } else {
                        Button(onClick = { vm.downloadStreamingModels() }) {
                            Text(s.autoStreamDownload)
                        }
                    }
                    vm.streamingError?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        // Промежуточный (partial) текст streaming-модели — серый, без перевода.
        // Стоит сразу под чипами отдельной строкой и не перекрывает перевод:
        // высота ограничена, длинный текст обрезается.
        if (vm.liveText.isNotBlank() || vm.autoRunning) {
            LivePartialStrip(text = vm.liveText)
        }

        Box(modifier = Modifier.weight(1f)) {
            if (vm.autoItems.isEmpty()) {
                Text(
                    text = if (mono) s.monoEmptyHint else s.autoEmptyHint,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center).padding(16.dp)
                )
            } else if (mono) {
                // Главное отличие моно-режимов: два окна — оригинал сверху,
                // перевод снизу, прокрутка у них общая (см. [MonoSplitTranscript]).
                MonoSplitTranscript(vm = vm)
            } else {
                LazyColumn(
                    state = cardsState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(vm.autoItems, key = { it.id }) { item ->
                        AutoCard(
                            item = item,
                            onPlayTranslation = { vm.playAuto(item) },
                            onPlaySource = { vm.playAutoSource(item) }
                        )
                    }
                }
            }
        }

        vm.autoNotice?.let { note ->
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "↻ $note",
                    modifier = Modifier.padding(8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        vm.autoError?.let { err ->
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    err,
                    modifier = Modifier.padding(8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }

        Button(
            onClick = { if (vm.autoRunning) vm.stopAuto() else vm.startAuto() },
            // Без моделей текущего режима старт невозможен; в «Авто» допускаем
            // работу только на китайском, если русская модель не скачалась.
            enabled = vm.autoRunning || vm.streamingUsable,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (vm.autoRunning) MaterialTheme.colorScheme.secondary
                else MaterialTheme.colorScheme.primary
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(62.dp)
        ) {
            Icon(
                imageVector = if (vm.autoRunning) Icons.Filled.Stop else Icons.Filled.Mic,
                contentDescription = null,
                modifier = Modifier.size(26.dp)
            )
            Spacer(Modifier.size(10.dp))
            Text(
                text = if (vm.autoRunning) s.autoStop else s.autoStart,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * Плашка промежуточного текста сразу под переключателями языков.
 *
 * Пока движок слушает, здесь серым италиком печатается то, что уже распознано,
 * но ещё не закрыто паузой. Обе модели (RU и ZH) потоковые, поэтому текст
 * обновляется прямо во время речи — как субтитры. Перевод появится только
 * после final, поэтому плашка не мешает основному окну: она занимает одну-две
 * строки по центру и обрезается.
 */
@Composable
private fun LivePartialStrip(text: String) {
    val shown = text.ifBlank { LocalStrings.current.voiceListening }
    val empty = text.isBlank()
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (empty) 0.35f else 0.6f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = shown,
                fontStyle = if (empty) FontStyle.Normal else FontStyle.Italic,
                fontSize = 18.sp,
                lineHeight = 21.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                // По центру — как настоящие субтитры; пустое «Слушаю…» тоже.
                textAlign = TextAlign.Center,
                color = if (empty) {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * Моно-режим («только RU» / «только ZH»): два окна вместо одной ленты.
 *
 * Верхнее окно — оригинал реплик, нижнее — их перевод. Прокрутка у окон общая:
 * потянули любое — второе встаёт на ту же фразу (индекс и смещение внутри неё),
 * поэтому оригинал не расходится с переводом. Высота делится неравномерно:
 * 40 % оригиналу и 60 % переводу (см. [MONO_SOURCE_WEIGHT]) — читать длинный
 * перевод удобнее, чем разглядывать уже знакомый оригинал. Разделитель
 * отделяет окна как в режиме «Диалог».
 */
@Composable
private fun MonoSplitTranscript(vm: MainViewModel) {
    val sourceState = rememberLazyListState()
    val translationState = rememberLazyListState()
    val items = vm.autoItems
    val s = LocalStrings.current

    // Новая реплика: обе половины уезжают к ней. Ключ включает длину перевода —
    // когда final-перевод дописывается, список тоже подтягиваем к низу.
    val lastTargetLength = items.lastOrNull()?.target?.length ?: 0
    LaunchedEffect(items.size, lastTargetLength) {
        if (items.isNotEmpty()) {
            val last = items.lastIndex
            coroutineScope {
                // Оба окна — к самому низу списка: большой offset ограничится
                // фактическим концом списка.
                launch { sourceState.scrollToItem(last, Int.MAX_VALUE) }
                launch { translationState.scrollToItem(last, Int.MAX_VALUE) }
            }
        }
    }

    // Синхронизация прокрутки. snapshotFlow отдаёт пару состояний; по тому,
    // какое из них изменилось, понимаем, какое окно тянул пользователь,
    // и ставим второе на ту же позицию. Флаг syncing глушит эхо от нашего
    // собственного scrollToItem, чтобы окна не «догоняли» друг друга вечно.
    LaunchedEffect(sourceState, translationState) {
        var lastSource =
            sourceState.firstVisibleItemIndex to sourceState.firstVisibleItemScrollOffset
        var lastTranslation =
            translationState.firstVisibleItemIndex to translationState.firstVisibleItemScrollOffset
        var syncing = false
        snapshotFlow {
            intArrayOf(
                sourceState.firstVisibleItemIndex,
                sourceState.firstVisibleItemScrollOffset,
                translationState.firstVisibleItemIndex,
                translationState.firstVisibleItemScrollOffset
            )
        }.collect { pos ->
            if (syncing) return@collect
            val lastIndex = items.lastIndex
            if (lastIndex < 0) return@collect
            val source = pos[0] to pos[1]
            val translation = pos[2] to pos[3]
            val sourceMoved = source != lastSource
            val translationMoved = translation != lastTranslation
            if (!sourceMoved && !translationMoved) return@collect
            lastSource = source
            lastTranslation = translation
            // Оба сдвинулись одновременно — это наша же автопрокрутка к новой
            // реплике, не пользователь: синхронизировать нечего.
            if (sourceMoved == translationMoved) return@collect
            syncing = true
            try {
                if (sourceMoved) {
                    translationState.scrollToItem(
                        source.first.coerceIn(0, lastIndex),
                        source.second
                    )
                } else {
                    sourceState.scrollToItem(
                        translation.first.coerceIn(0, lastIndex),
                        translation.second
                    )
                }
            } finally {
                // После выравнивания запоминаем фактические позиции: иначе
                // ответное изменение второго окна сочлось бы движением пользователя.
                lastSource =
                    sourceState.firstVisibleItemIndex to sourceState.firstVisibleItemScrollOffset
                lastTranslation =
                    translationState.firstVisibleItemIndex to translationState.firstVisibleItemScrollOffset
                syncing = false
            }
        }
    }

    val panePadding = PaddingValues(vertical = 4.dp)

    Column(modifier = Modifier.fillMaxSize()) {
        // 1) Оригинал: то же самое, что услышал микрофон. В моно-режиме язык
        // оригинала известен заранее, поэтому удобнее отдать больше места
        // переводу. 40 % высоты — ввод, остальное — читаемый результат.
        Column(modifier = Modifier.weight(MONO_SOURCE_WEIGHT).fillMaxWidth()) {
            PaneCaption(text = s.monoOriginal)
            LazyColumn(
                state = sourceState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = panePadding,
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                items(items, key = { it.id }) { item ->
                    Column(Modifier.fillMaxWidth()) {
                        if (item.source.isNotBlank()) {
                            Text(
                                text = item.source,
                                fontSize = 22.sp,
                                lineHeight = 30.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        HorizontalDivider(
            thickness = 3.dp,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
        )

        // 2) Перевод: тот же список, но текст другой стороны. 60 % высоты —
        // длинный перевод читается без постоянной прокрутки. В обычном «Авто»
        // деление 50/50 не нужно: там реплики идут карточками одной лентой.
        Column(modifier = Modifier.weight(MONO_TRANSLATION_WEIGHT).fillMaxWidth()) {
            PaneCaption(text = s.monoTranslation)
            LazyColumn(
                state = translationState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = panePadding,
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                items(items, key = { it.id }) { item ->
                    val target = item.target.ifBlank {
                        when {
                            item.error != null -> item.error.orEmpty()
                            item.busy -> "…"
                            else -> ""
                        }
                    }
                    if (target.isNotBlank()) {
                        Text(
                            text = target,
                            fontSize = 22.sp,
                            lineHeight = 30.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (item.error != null) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

/** Маленькая подпись окна: «Оригинал» / «Перевод». */
@Composable
private fun PaneCaption(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp)
    )
}

/**
 * Маленький динамик внутри шапки карточки.
 *
 * Отдельный на каждый язык (оригинал и перевод озвучиваются разными голосами),
 * но размер иконки — 17 dp, а вся кнопка — 26 dp: шапка остаётся плоской и
 * не отнимает высоту у текста. Обычный [IconButton] здесь не подходит: его
 * минимальная область нажатия 40–48 dp раздувала бы шапку.
 */
@Composable
private fun SmallSpeaker(
    description: String,
    tint: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.VolumeUp,
            contentDescription = description,
            tint = tint,
            modifier = Modifier.size(17.dp)
        )
    }
}

@Composable
private fun AutoCard(
    item: Utterance,
    onPlayTranslation: () -> Unit,
    onPlaySource: () -> Unit
) {
    val fromRu = item.side == Lang.RU
    val s = LocalStrings.current
    val bg by animateColorAsState(
        targetValue = when {
            item.error != null -> MaterialTheme.colorScheme.secondaryContainer
            fromRu -> MaterialTheme.colorScheme.surfaceVariant
            else -> MaterialTheme.colorScheme.primaryContainer
        },
        label = "autoCard"
    )

    Card(
        colors = CardDefaults.cardColors(containerColor = bg),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        // Отступы ужаты: шапка 20 dp + вертикальные 6 dp, всё остальное —
        // сам текст (80–90 % высоты плашки).
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            // Плоская компактная шапка: флаг мелкий, название языка 12 sp,
            // справа — два маленьких динамика (свой для каждого языка).
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(20.dp)
            ) {
                Text(
                    text = item.side.flag,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(end = 4.dp)
                )
                Text(
                    text = item.side.label,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!item.isFinal) {
                    Spacer(Modifier.size(6.dp))
                    Text(
                        s.recognizing,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                Spacer(Modifier.weight(1f))
                if (item.source.isNotBlank()) {
                    SmallSpeaker(
                        description = s.speakPhrase,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        onClick = onPlaySource
                    )
                }
                if (item.target.isNotBlank()) {
                    SmallSpeaker(
                        description = s.speakTranslation,
                        tint = MaterialTheme.colorScheme.primary,
                        onClick = onPlayTranslation
                    )
                }
            }

            if (item.source.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = item.source,
                    fontSize = 16.sp,
                    lineHeight = 21.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.target.ifBlank {
                        when {
                            item.error != null -> item.error.orEmpty()
                            item.busy -> "…"
                            else -> ""
                        }
                    },
                    fontSize = 26.sp,
                    lineHeight = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (item.error != null)
                        MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                if (item.busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                }
            }
        }
    }
}
