package com.nya.dstranslate.ui

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nya.dstranslate.MainViewModel
import com.nya.dstranslate.SideState
import com.nya.dstranslate.data.Lang
import com.nya.dstranslate.p2p.P2pFailure
import com.nya.dstranslate.p2p.P2pPeer
import com.nya.dstranslate.p2p.P2pRole
import com.nya.dstranslate.p2p.P2pStatus
import com.nya.dstranslate.p2p.P2pTransport

/**
 * Режим «два экрана»: телефон кладётся между собеседниками.
 *
 * Верхняя половина перевёрнута на 180° и обращена к человеку напротив,
 * нижняя читается владельцем телефона.
 *
 * Перевод показывается НА ПОЛОВИНЕ СОБЕСЕДНИКА: сказал по-русски — китайский
 * текст появляется на верхней половине, а не у говорящего.
 *
 * Сверху — кнопка «Связать»: второй телефон подключается по Wi-Fi Direct
 * (резерв — Bluetooth) и получает перевод без интернета.
 */
@Composable
fun SplitScreen(vm: MainViewModel) {
    // Меню связки открывается поверх экрана и не мешает разговору.
    var linkMenu by remember { mutableStateOf(false) }

    // Уход с вкладки глушит микрофон — как и в режиме «Авто».
    // Связку не закрываем: соединение должно переживать переключение вкладок.
    DisposableEffect(Unit) {
        onDispose {
            vm.stopSplit()
            vm.stopSpeaking()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LinkBar(vm, onOpen = { linkMenu = true })
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

    if (linkMenu) {
        LinkDialog(vm, onClose = { linkMenu = false })
    }
}

/**
 * Строка связки над экранами.
 *
 * Пока соединения нет — заметная кнопка «🔗 Связать». После подключения
 * она превращается в зелёный статус «Подключено» с каналом и именем
 * собеседника; нажатие в обоих случаях открывает меню.
 */
@Composable
private fun LinkBar(vm: MainViewModel, onOpen: () -> Unit) {
    val s = LocalStrings.current
    val connected = vm.p2pConnected
    val chipColor = if (connected) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary
    val textColor = if (connected) Color.White else MaterialTheme.colorScheme.onPrimary

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = chipColor,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .clickable(onClick = onOpen)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(if (connected) "🟢" else "🔗", fontSize = 14.sp)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (connected) s.linkConnected else s.linkAction,
                    color = textColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (connected) {
                "${transportLabel(vm.p2pTransport, s)} · ${vm.p2pDetail.orEmpty()}"
            } else {
                s.linkMenuHint
            },
            fontSize = 11.sp,
            maxLines = 2,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
    }
}

/** Меню связки: роль, канал, поиск, список устройств и история обмена. */
@Composable
private fun LinkDialog(vm: MainViewModel, onClose: () -> Unit) {
    val s = LocalStrings.current
    val context = LocalContext.current

    // Пересчитываем разрешения после ответа системы: иначе список так и
    // останется «не выдан», пока меню не закроют и не откроют снова.
    var permissionsRevision by remember { mutableIntStateOf(0) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionsRevision++ }
    val missingPermissions = remember(permissionsRevision) { vm.p2pMissingPermissions() }

    // Для Bluetooth-хоста телефон должен быть видимым: система спрашивает
    // подтверждение отдельным окном, здесь только запускаем его.
    val discoverableLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }

    var useBluetooth by rememberSaveable { mutableStateOf(!vm.p2pWifiDirectAvailable) }
    val status = vm.p2pStatus

    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.linkMenuTitle, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (vm.p2pConnected) {
                    Text("🟢", fontSize = 16.sp)
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 430.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (missingPermissions.isNotEmpty()) {
                    // Предупреждение показываем, но роли не прячем: у пользователя
                    // остаётся шанс запустить канал, разрешения которого уже есть
                    // (например, Bluetooth при отказе от геолокации).
                    PermissionsBlock(
                        onGrant = { permissionLauncher.launch(missingPermissions.toTypedArray()) },
                        onSettings = { openAppSettings(context) }
                    )
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                }
                when (status) {
                    P2pStatus.CONNECTED -> ConnectedBlock(vm, onClose = onClose)
                    P2pStatus.SEARCHING, P2pStatus.WAITING, P2pStatus.CONNECTING ->
                        ProgressBlock(vm, onClose = onClose)
                    else -> {
                        // IDLE и ERROR: показываем выбор роли. После ошибки
                        // пользователь сразу видит причину и может повторить.
                        if (status == P2pStatus.ERROR) {
                            ErrorBlock(vm)
                        }
                        RolesBlock(
                            vm = vm,
                            useBluetooth = useBluetooth,
                            onUseBluetoothChange = { useBluetooth = it },
                            onStartHost = {
                                vm.startP2pHost(useBluetooth)
                                if (useBluetooth) requestDiscoverable(discoverableLauncher)
                            },
                            onStartClient = { vm.startP2pClient(useBluetooth) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) { Text(s.close) }
        }
    )
}

/** Выбор «Создать комнату» / «Подключиться» и канала связи. */
@Composable
private fun RolesBlock(
    vm: MainViewModel,
    useBluetooth: Boolean,
    onUseBluetoothChange: (Boolean) -> Unit,
    onStartHost: () -> Unit,
    onStartClient: () -> Unit
) {
    val s = LocalStrings.current

    Text(s.linkMenuHint, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(10.dp))

    if (vm.p2pWifiDirectAvailable) {
        Text(s.linkChannel, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !useBluetooth,
                onClick = { onUseBluetoothChange(false) },
                label = { Text(s.linkWifiDirect, fontSize = 12.sp) }
            )
            FilterChip(
                selected = useBluetooth,
                onClick = { onUseBluetoothChange(true) },
                label = { Text(s.linkBluetooth, fontSize = 12.sp) }
            )
        }
    } else {
        Text(s.linkNoWifiDirect, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
    }

    Spacer(Modifier.height(10.dp))

    OutlinedButton(
        onClick = onStartHost,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(vertical = 4.dp)) {
            Text(s.linkHost, fontWeight = FontWeight.Bold)
            Text(
                s.linkHostDesc,
                fontSize = 11.sp,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = onStartClient,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(vertical = 4.dp)) {
            Text(s.linkClient, fontWeight = FontWeight.Bold)
            Text(
                s.linkClientDesc,
                fontSize = 11.sp,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    if (useBluetooth) {
        Spacer(Modifier.height(8.dp))
        Text(
            s.linkDiscoverable,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Ожидание, поиск и подключение: спиннер, подпись и отмена. */
@Composable
private fun ProgressBlock(vm: MainViewModel, onClose: () -> Unit) {
    val s = LocalStrings.current
    val text = when (vm.p2pStatus) {
        P2pStatus.SEARCHING -> s.linkSearching
        P2pStatus.WAITING -> s.linkWaiting
        else -> s.linkConnecting
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(text, fontWeight = FontWeight.Bold)
            if (vm.p2pDetail != null) {
                Text(
                    "${transportLabel(vm.p2pTransport, s)} · ${vm.p2pDetail}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = { vm.stopP2p() }) { Text(s.cancel) }
    // Клиенту нужен список найденных устройств: он обновляется по мере поиска.
    if (vm.p2pStatus == P2pStatus.SEARCHING && vm.p2pRole == P2pRole.CLIENT) {
        PeerList(vm)
        Spacer(Modifier.height(6.dp))
        TextButton(onClick = { vm.refreshP2pPeers() }) { Text(s.linkRescan) }
    }
    if (vm.p2pStatus == P2pStatus.WAITING) {
        Spacer(Modifier.height(4.dp))
        Text(
            s.linkMenuHint,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Список найденных устройств: клиент нажимает на строку и подключается. */
@Composable
private fun PeerList(vm: MainViewModel) {
    val s = LocalStrings.current
    Spacer(Modifier.height(8.dp))
    if (vm.p2pPeers.isEmpty()) {
        Text(
            s.linkNoPeers,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    vm.p2pPeers.forEach { peer ->
        PeerRow(peer = peer, onClick = { vm.connectP2pPeer(peer) })
    }
}

@Composable
private fun PeerRow(peer: P2pPeer, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(if (peer.wifiDirect) "📶" else "🔵", fontSize = 14.sp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(peer.name, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(
                    peer.address,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                if (peer.wifiDirect) LocalStrings.current.linkWifiDirect
                else LocalStrings.current.linkBluetooth,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Подключено: канал, собеседник, история обмена и отключение. */
@Composable
private fun ConnectedBlock(vm: MainViewModel, onClose: () -> Unit) {
    val s = LocalStrings.current
    Surface(
        color = Color(0xFFE8F5E9),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(
                "🟢 ${s.linkConnected}",
                fontWeight = FontWeight.Bold,
                color = Color(0xFF2E7D32)
            )
            Text(
                "${roleLabel(vm.p2pRole, s)} · ${transportLabel(vm.p2pTransport, s)}" +
                    (vm.p2pDetail?.let { " · $it" } ?: ""),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            s.linkHistoryTitle,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f)
        )
        if (vm.p2pMessages.isNotEmpty()) {
            TextButton(onClick = { vm.clearP2pMessages() }) {
                Text(s.clear, fontSize = 11.sp)
            }
        }
    }
    if (vm.p2pMessages.isEmpty()) {
        Text(
            s.linkHistoryEmpty,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        // Последние 20 реплик: вся история не нужна в окне связки.
        vm.p2pMessages.takeLast(20).forEach { message ->
            Column(Modifier.padding(vertical = 4.dp)) {
                Text(
                    "${if (message.outgoing) "→" else "←"} " +
                        "${if (message.outgoing) s.linkSent else s.linkReceived} " +
                        message.sender.flag,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(message.original, fontSize = 13.sp)
                Text(
                    message.translated,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
    Spacer(Modifier.height(10.dp))
    OutlinedButton(
        onClick = {
            vm.stopP2p()
            onClose()
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(s.linkDisconnect)
    }
}

/** Ошибка: локализованная причина и техническая деталь мелким шрифтом. */
@Composable
private fun ErrorBlock(vm: MainViewModel) {
    val s = LocalStrings.current
    val failure = vm.p2pFailure
    val text = when (failure) {
        P2pFailure.PERMISSIONS -> s.linkErrPermissions
        P2pFailure.WIFI_DIRECT_UNAVAILABLE -> s.linkErrWifiUnavailable
        P2pFailure.WIFI_DIRECT_OFF -> s.linkErrWifiOff
        P2pFailure.BLUETOOTH_UNAVAILABLE -> s.linkErrBtUnavailable
        P2pFailure.BLUETOOTH_OFF -> s.linkErrBtOff
        P2pFailure.DISCOVERY_FAILED -> s.linkErrDiscovery
        P2pFailure.CONNECT_FAILED -> s.linkErrConnect
        P2pFailure.CONNECTION_LOST -> s.linkErrLost
        P2pFailure.SOCKET_FAILED -> s.linkErrSocket
        else -> s.linkErrUnknown
    }
    Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
    if (!vm.p2pDetail.isNullOrBlank()) {
        Text(
            vm.p2pDetail.orEmpty(),
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Spacer(Modifier.height(8.dp))
}

/** Нет разрешений: объяснение, запрос и переход в системные настройки. */
@Composable
private fun PermissionsBlock(onGrant: () -> Unit, onSettings: () -> Unit) {
    val s = LocalStrings.current
    Text(s.linkPermissionsNeeded, fontSize = 13.sp)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onGrant) { Text(s.linkGrant) }
        TextButton(onClick = onSettings) { Text(s.linkOpenSettings) }
    }
}

/** Подписи канала и роли — без них статус в меню был бы просто «Подключено». */
private fun transportLabel(transport: P2pTransport, s: Strings): String = when (transport) {
    P2pTransport.WIFI_DIRECT -> s.linkWifiDirect
    P2pTransport.BLUETOOTH -> s.linkBluetooth
    P2pTransport.NONE -> ""
}

private fun roleLabel(role: P2pRole, s: Strings): String = when (role) {
    P2pRole.HOST -> s.linkRoleHost
    P2pRole.CLIENT -> s.linkRoleClient
    P2pRole.NONE -> ""
}

/** Системный экран «Разрешить обнаружение по Bluetooth» (300 секунд). */
private fun requestDiscoverable(
    launcher: androidx.activity.result.ActivityResultLauncher<Intent>
) {
    try {
        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
            putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
        }
        launcher.launch(intent)
    } catch (_: Exception) {
        // Нет Bluetooth-адаптера — хост всё равно покажет ошибку канала.
    }
}

/** Настройки приложения: там пользователь снимает запрет «Не спрашивать». */
private fun openAppSettings(context: Context) {
    try {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } catch (_: Exception) {
        // Если настройки недоступны, меню остаётся с кнопкой «Разрешить».
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
                // фразы собеседника на язык этой стороны. Если перевода нет
                // из-за ошибки API, показываем причину, а не «Слушаю…»:
                // пустой экран выглядел как зависшее приложение.
                val bigText = when {
                    speaking -> st.live
                    st.incoming.isNotBlank() -> st.incoming
                    st.error != null -> st.error.orEmpty()
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
