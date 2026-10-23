package com.nya.dstranslate.ui

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nya.dstranslate.MainViewModel
import com.nya.dstranslate.Utterance
import com.nya.dstranslate.data.Lang
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Автодиалог по ролям: ИИ непрерывно слушает, сам определяет язык каждой реплики
 * и переводит её на второй язык.
 *
 * Два вида вывода:
 *  - «Авто» — карточки по ролям, у каждой фразы кнопки прослушивания;
 *  - моно-режимы «только RU» / «только ZH» — два окна: сверху оригинал,
 *    снизу перевод, прокрутка общая (см. [MonoSplitTranscript]).
 *
 * Сверху — только строка чипов (Авто / только RU / только ZH) и кнопка
 * очистки диалога: отдельной плашки со статусом «Автодиалог остановлен /
 * Слушаю…» больше нет.
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

    LaunchedEffect(vm.autoItems.size, mono) {
        if (!mono && vm.autoItems.isNotEmpty()) {
            cardsState.animateScrollToItem(vm.autoItems.lastIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Язык распознавания и очистка диалога — в одной строке.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
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
            IconButton(onClick = { vm.clearAuto() }) {
                Icon(Icons.Filled.DeleteSweep, contentDescription = s.autoClear)
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            if (vm.autoItems.isEmpty()) {
                Text(
                    text = if (mono) s.monoEmptyHint else s.autoEmptyHint,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp)
                )
            } else if (mono) {
                // Главное отличие моно-режимов: два окна — оригинал сверху,
                // перевод снизу, прокрутка у них общая (см. [MonoSplitTranscript]).
                MonoSplitTranscript(vm = vm)
            } else {
                LazyColumn(
                    state = cardsState,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
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
                    modifier = Modifier.padding(10.dp),
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
                    modifier = Modifier.padding(10.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }

        Button(
            onClick = { if (vm.autoRunning) vm.stopAuto() else vm.startAuto() },
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
 * Моно-режим («только RU» / «только ZH»): два окна вместо одной ленты.
 *
 * Верхнее окно — оригинал реплик, нижнее — их перевод. Прокрутка у окон общая:
 * потянули любое — второе встаёт на ту же фразу (индекс и смещение внутри неё),
 * поэтому оригинал не расходится с переводом. Высота у окон одинаковая,
 * разделитель отделяет их как в режиме «Диалог».
 */
@Composable
private fun MonoSplitTranscript(vm: MainViewModel) {
    val sourceState = rememberLazyListState()
    val translationState = rememberLazyListState()
    val items = vm.autoItems
    val s = LocalStrings.current

    // Новая реплика: обе половины уезжают к ней. Анимации запускаем вместе,
    // иначе синхронизация сначала «догонит» нижнее окно, а потом оно ещё и
    // доанимируется само — получается рывок.
    LaunchedEffect(items.size) {
        if (items.isNotEmpty()) {
            val last = items.lastIndex
            coroutineScope {
                launch { sourceState.animateScrollToItem(last) }
                launch { translationState.animateScrollToItem(last) }
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

    val panePadding = PaddingValues(vertical = 6.dp)

    Column(modifier = Modifier.fillMaxSize()) {
        // 1) Оригинал: то же самое, что услышал микрофон.
        Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
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
                        if (!item.isFinal) {
                            Spacer(Modifier.height(2.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                                Spacer(Modifier.size(6.dp))
                                Text(
                                    text = s.recognizing,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }
                    }
                }
            }
        }

        HorizontalDivider(
            thickness = 3.dp,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
        )

        // 2) Перевод: тот же список, но текст другой стороны.
        Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
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
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${item.side.flag} ${item.side.label}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!item.isFinal) {
                    Spacer(Modifier.size(8.dp))
                    Text(
                        s.recognizing,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                Spacer(Modifier.weight(1f))
                if (item.source.isNotBlank()) {
                    IconButton(onClick = onPlaySource) {
                        Icon(Icons.Filled.VolumeUp, contentDescription = s.speakPhrase)
                    }
                }
                if (item.target.isNotBlank()) {
                    IconButton(onClick = onPlayTranslation) {
                        Icon(
                            Icons.Filled.VolumeUp,
                            contentDescription = s.speakTranslation,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            if (item.source.isNotBlank()) {
                Text(
                    text = item.source,
                    fontSize = 22.sp,
                    lineHeight = 28.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
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
                    fontSize = 30.sp,
                    lineHeight = 38.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (item.error != null)
                        MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                if (item.busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                }
            }
        }
    }
}
