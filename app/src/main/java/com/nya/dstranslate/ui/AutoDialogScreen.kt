package com.nya.dstranslate.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nya.dstranslate.MainViewModel
import com.nya.dstranslate.Utterance
import com.nya.dstranslate.data.Lang

/**
 * Автодиалог по ролям: ИИ непрерывно слушает, сам определяет язык каждой реплики
 * и переводит её на второй язык.
 *
 * Два вида вывода:
 *  - «Авто» — карточки по ролям, у каждой фразы кнопки прослушивания;
 *  - моно-режимы «только RU» / «только ZH» — сплошной текст без карточек:
 *    оригинал строкой, под ним перевод (см. [MonoTranscript]).
 */
@Composable
fun AutoDialogScreen(vm: MainViewModel) {
    val listState = rememberLazyListState()
    val s = LocalStrings.current

    // Уход с вкладки обязательно глушит микрофон: иначе запись продолжается
    // в фоне и жрёт батарею, а переводы сыпятся в закрытый экран.
    DisposableEffect(Unit) {
        onDispose {
            vm.stopAuto()
            vm.stopSpeaking()
        }
    }

    LaunchedEffect(vm.autoItems.size) {
        if (vm.autoItems.isNotEmpty()) listState.animateScrollToItem(vm.autoItems.lastIndex)
    }

    // Моно-режим: выбран один язык, реплики не диалог, а сплошной текст.
    val mono = vm.autoOverride != null
    val phrases = vm.autoItems.count { it.isFinal }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Surface(
            color = if (vm.autoRunning) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = when {
                            !vm.autoRunning -> s.autoStopped
                            vm.autoOverride == Lang.RU -> s.autoListeningOnlyRu
                            vm.autoOverride == Lang.ZH -> s.autoListeningOnlyZh
                            else -> s.autoListening
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (vm.autoRunning) {
                            // В моно-режиме подсказываем формат вывода.
                            if (mono) s.monoHint
                            else "${vm.autoNextLang.flag} ${vm.autoNextLang.label}"
                        } else {
                            when (vm.autoOverride) {
                                Lang.RU -> s.autoWillListenRu
                                Lang.ZH -> s.autoWillListenZh
                                null -> s.autoIdleHint
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { vm.clearAuto() }) {
                    Icon(Icons.Filled.DeleteSweep, contentDescription = s.clear)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = vm.autoOverride == null,
                onClick = { vm.setAutoLanguage(null) },
                label = { Text(s.chipAuto) }
            )
            FilterChip(
                selected = vm.autoOverride == Lang.RU,
                onClick = { vm.setAutoLanguage(Lang.RU) },
                label = { Text("${Lang.RU.flag} ${s.chipOnlyRu}") }
            )
            FilterChip(
                selected = vm.autoOverride == Lang.ZH,
                onClick = { vm.setAutoLanguage(Lang.ZH) },
                label = { Text("${Lang.ZH.flag} ${s.chipOnlyZh}") }
            )
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
                // Главное отличие моно-режимов: никаких карточек и пузырей,
                // только текст — оригинал и под ним перевод.
                MonoTranscript(vm = vm, listState = listState)
            } else {
                LazyColumn(
                    state = listState,
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

        // Живая визуализация: эквалайзер, слова по мере распознавания и
        // заполнение лимита в 10 секунд. На время озвучки микрофон на паузе —
        // и эквалайзер честно замирает.
        if (vm.autoRunning) {
            RecognitionMeter(
                active = !vm.isSpeaking,
                liveText = vm.liveText,
                progress = vm.inputProgress,
                phraseCount = phrases
            )
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
 * Моно-режим («только RU» / «только ZH»): сплошной поток текста.
 *
 * Карточек, фонов и пузырей нет — только строки:
 *   оригинал (мельче, приглушённо) → перевод (крупно, жирно).
 * Черновик текущей реплики уже лежит в [MainViewModel.autoItems] с
 * `isFinal = false`, поэтому слова появляются прямо в потоке.
 */
@Composable
private fun MonoTranscript(vm: MainViewModel, listState: LazyListState) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        // Большой межфразовый отступ вместо разделителей: текст остаётся сплошным.
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        items(vm.autoItems, key = { it.id }) { item ->
            // Что показывать вторым (главным) блоком: перевод, ошибку или «…»,
            // пока модель ещё стримит ответ.
            val target = item.target.ifBlank {
                when {
                    item.error != null -> item.error.orEmpty()
                    item.busy -> "…"
                    else -> ""
                }
            }
            Column(Modifier.fillMaxWidth()) {
                // 1) Оригинал: тише и мельче, чтобы глаз цеплялся за перевод.
                if (item.source.isNotBlank()) {
                    Text(
                        text = item.source,
                        fontSize = 16.sp,
                        lineHeight = 23.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(3.dp))
                }
                // 2) Перевод: крупно и жирно, как основной текст документа.
                if (target.isNotBlank()) {
                    Text(
                        text = target,
                        fontSize = 24.sp,
                        lineHeight = 32.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (item.error != null) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface
                    )
                }
                // 3) Черновик ещё распознаётся — мягкий маркер рядом с текстом.
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
                            text = LocalStrings.current.recognizing,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            }
        }
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
