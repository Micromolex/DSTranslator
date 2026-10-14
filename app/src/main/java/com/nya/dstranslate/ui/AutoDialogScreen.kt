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
 * и переводит её на второй язык. Крупный шрифт, без автовоспроизведения,
 * у каждой фразы — кнопка прослушивания.
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
                        text = if (vm.autoRunning) s.autoListening else s.autoStopped,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (vm.autoRunning)
                            "${vm.autoNextLang.flag} ${vm.autoNextLang.label}"
                        else s.autoIdleHint,
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
                    s.autoEmptyHint,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp)
                )
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
