package com.nya.dstranslate.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nya.dstranslate.MainViewModel
import com.nya.dstranslate.SideState
import com.nya.dstranslate.data.Lang

/**
 * Режим «два экрана»: телефон кладётся между собеседниками.
 *
 * Верхняя половина перевёрнута на 180° и обращена к человеку напротив,
 * нижняя читается владельцем телефона.
 *
 * Перевод показывается НА ПОЛОВИНЕ СОБЕСЕДНИКА: сказал по-русски — китайский
 * текст появляется на верхней половине, а не у говорящего.
 */
@Composable
fun SplitScreen(vm: MainViewModel) {
    // Уход с вкладки глушит микрофон — как и в режиме «Авто».
    DisposableEffect(Unit) {
        onDispose {
            vm.stopSplit()
            vm.stopSpeaking()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            HalfPane(vm, Lang.ZH, rotated = true, modifier = Modifier.fillMaxSize())
        }
        HorizontalDivider(
            thickness = 3.dp,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
        )
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            HalfPane(vm, Lang.RU, rotated = false, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun HalfPane(
    vm: MainViewModel,
    lang: Lang,
    rotated: Boolean,
    modifier: Modifier = Modifier
) {
    val st: SideState = vm.sideState(lang)
    val s = LocalStrings.current
    val active = vm.splitActive == lang
    val speaking = st.live.isNotBlank()

    val bg by animateColorAsState(
        targetValue = if (active) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surface,
        label = "halfBg"
    )

    Surface(color = bg, modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(if (rotated) Modifier.rotate(180f) else Modifier)
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "${lang.flag} ${lang.label}",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${s.splitFrom} ${lang.other().flag} ${lang.other().label}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
                if (st.spoken.isNotBlank() && !speaking) {
                    IconButton(onClick = { vm.speakSideOwn(lang) }) {
                        Icon(
                            Icons.Filled.VolumeUp,
                            contentDescription = s.speak,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (st.incoming.isNotBlank() || st.incomingSource.isNotBlank()) {
                    IconButton(onClick = { vm.speakSideIncoming(lang) }) {
                        Icon(
                            Icons.Filled.VolumeUp,
                            contentDescription = s.speak,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.Center
            ) {
                // Крупно: либо собственная распознаваемая речь, либо перевод
                // фразы собеседника на язык этой стороны.
                val bigText = when {
                    speaking -> st.live
                    st.incoming.isNotBlank() -> st.incoming
                    else -> ""
                }
                if (bigText.isBlank()) {
                    Text(
                        text = if (active) s.listening else s.pressMic,
                        fontSize = 22.sp,
                        lineHeight = 28.sp,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    if (speaking) {
                        Text(
                            text = s.recognizing,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = bigText,
                            fontSize = 34.sp,
                            lineHeight = 42.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            color = if (st.error != null && !speaking)
                                MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        if (st.busy && !speaking) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                        }
                    }
                    // Мелко: оригинал фразы собеседника
                    if (!speaking && st.incomingSource.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "${st.targetLang.flag} ${st.incomingSource}",
                            fontSize = 18.sp,
                            lineHeight = 24.sp,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(74.dp)
                        .clip(CircleShape)
                        .background(
                            if (active) MaterialTheme.colorScheme.secondary
                            else MaterialTheme.colorScheme.primary
                        )
                        .clickable {
                            if (active) vm.stopSplit() else vm.startSplit(lang)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (active) Icons.Filled.Stop else Icons.Filled.Mic,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(34.dp)
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (active) s.listening else s.tapToTalk,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
