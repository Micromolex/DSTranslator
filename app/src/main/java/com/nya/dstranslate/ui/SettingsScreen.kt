package com.nya.dstranslate.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nya.dstranslate.MainViewModel
import com.nya.dstranslate.overlay.PasteAccessibilityService
import com.nya.dstranslate.overlay.TranslateBubbleService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun SettingsScreen(vm: MainViewModel, onOpenLegal: () -> Unit = {}) {
    val s = LocalStrings.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ------------------------------------------------------- язык интерфейса
        Text(s.settingsLangTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(s.appTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    s.settingsLangDesc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ------------------------------------------------------------ обновления
        Text(s.settingsUpdates, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row {
                    Text(
                        "${s.installedVersion}: ",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "${vm.currentVersionName} (${vm.currentVersionCode})",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Row {
                    Text(
                        "${s.latestVersion}: ",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        vm.latestVersion ?: s.versionNotChecked,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = if (vm.updateAvailable != null) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                }

                Spacer(Modifier.height(2.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { vm.checkUpdates(manual = true) },
                        enabled = !vm.updateChecking
                    ) {
                        if (vm.updateChecking) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(s.checkUpdates)
                    }
                    vm.updateAvailable?.let { info ->
                        Spacer(Modifier.width(10.dp))
                        Button(
                            onClick = { vm.downloadAndInstallUpdate() },
                            enabled = vm.updateProgress == null
                        ) {
                            Text("${s.updateDownload} ${info.versionName}")
                        }
                    }
                }

                vm.updateStatus?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                vm.updateError?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                val progress = vm.updateProgress
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("${s.updating} $progress%", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // ---------------------------------------------------------------- режимы
        Text(s.settingsModes, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ModeInfo(s.settingsSplitTitle, s.settingsSplitDesc)
                ModeInfo(s.settingsAutoTitle, s.settingsAutoDesc)
                ModeInfo(s.settingsImageTitle, s.settingsImageDesc)
            }
        }

        // ------------------------------------- пузырь и меню «Перевести»
        Text(
            s.settingsOverlayTitle,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        OverlayCard()

        // ------------------------------------------------------------ поведение
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.settingsSpeakTitle, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            s.settingsSpeakDesc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = vm.autoSpeak, onCheckedChange = { vm.updateAutoSpeak(it) })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.settingsContextTitle, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            s.settingsContextDesc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = vm.useContext, onCheckedChange = { vm.updateUseContext(it) })
                }
            }
        }

        // ------------------------------------------------- распознавание речи
        Text(
            s.settingsStreamTitle,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    s.settingsStreamDesc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (vm.streamingAllReady) {
                    Text(
                        "${s.settingsStreamReady} · ${formatMb(vm.streamingAllTotalBytes)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    TextButton(onClick = { vm.deleteStreamingModels() }, enabled = !vm.streamingBusy) {
                        Text(s.settingsStreamDelete)
                    }
                } else {
                    Text(
                        "${s.settingsStreamMissing} · ${formatMb(vm.streamingAllTotalBytes)}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (vm.streamingBusy) {
                        LinearProgressIndicator(
                            progress = { vm.streamingProgress },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "${s.settingsStreamDownloading} ${
                                (vm.streamingProgress * 100).toInt()
                            }% · ${formatMb(vm.streamingAllDownloadedBytes)}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        Text(
                            "${s.settingsAsrStorage}: ${formatMb(vm.storageFreeBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            // Из настроек качаем полный набор: он нужен обычному «Авто».
                            onClick = { vm.downloadStreamingModels(all = true) },
                            enabled = vm.storageFreeBytes > vm.streamingAllTotalBytes + 100L * 1024 * 1024
                        ) {
                            Text(s.settingsStreamDownload)
                        }
                        if (vm.storageFreeBytes <= vm.streamingAllTotalBytes + 100L * 1024 * 1024) {
                            Text(
                                s.settingsStreamNoSpace,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    Text(
                        s.settingsAsrWifiHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                vm.streamingError?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        // ------------------------------------------------------------------ API
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    s.settingsApiTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    s.settingsApiDesc,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                // Статус ключа: сам ключ не показываем, только маску и факт сохранения.
                Text(
                    if (vm.apiKeyConfigured) {
                        "${s.settingsApiKeySaved}: ${vm.apiKeyHint}"
                    } else {
                        s.settingsApiKeyMissing
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (vm.apiKeyConfigured) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { vm.showApiKeyDialog() }) {
                        Text(
                            if (vm.apiKeyConfigured) s.settingsApiKeyChange
                            else s.settingsApiKeyEnter
                        )
                    }
                    if (vm.apiKeyConfigured) {
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { vm.clearApiKey() }) {
                            Text(s.settingsApiKeyClear)
                        }
                    }
                }
            }
        }

        // -------------------------------------- о приложении / правовая информация
        Text(s.settingsAboutTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenLegal)
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(s.settingsAboutRow, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        s.settingsAboutDesc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(8.dp))
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(1.dp))
    }
}

@Composable
private fun ModeInfo(title: String, desc: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(
            desc,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Размер в мегабайтах или гигабайтах — для модели и свободного места. */
private fun formatMb(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024) {
        String.format(Locale.US, "%.1f GB", mb / 1024.0)
    } else {
        String.format(Locale.US, "%.0f MB", mb)
    }
}

/**
 * Раздел «Поверх других приложений»: тумблер пузыря и доступ к спец. возможностям.
 *
 * Разрешение «Поверх других приложений» нельзя запросить диалогом — только
 * открыть системный экран (`ACTION_MANAGE_OVERLAY_PERMISSION`). Поэтому тумблер
 * сначала ведёт туда, а при возврате (activity result) проверяет разрешение
 * ещё раз и запускает службу. Служба спец. возможностей включается только
 * вручную в настройках Android — здесь лишь кнопка перехода и статус.
 */
@Composable
private fun OverlayCard() {
    val s = LocalStrings.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(ctx)) }
    var bubbleOn by remember { mutableStateOf(TranslateBubbleService.isRunning) }
    var accessibilityOn by remember { mutableStateOf(PasteAccessibilityService.isConnected) }
    // Тумблер включили, но разрешения ещё нет: после выдачи запускаем службу.
    var startAfterGrant by remember { mutableStateOf(false) }

    val overlayLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Пользователь мог не дать разрешение — тогда и пузырь не стартует.
        overlayGranted = Settings.canDrawOverlays(ctx)
        if (overlayGranted) {
            if (startAfterGrant) {
                startAfterGrant = false
                TranslateBubbleService.start(ctx)
                bubbleOn = true
            }
        } else {
            startAfterGrant = false
            Toast.makeText(ctx, s.settingsOverlayMissing, Toast.LENGTH_LONG).show()
        }
    }

    val accessibilityLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Система подключает службу асинхронно — статус проверяем с паузой.
        scope.launch {
            delay(500)
            accessibilityOn = PasteAccessibilityService.isConnected
        }
    }

    fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${ctx.packageName}")
        )
        // На части прошивок экран с package-uri не открывается — общий вариант.
        try {
            overlayLauncher.launch(intent)
        } catch (e: Exception) {
            overlayLauncher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        }
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {

            // ------------------------------------------------- плавающий пузырь
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.settingsBubbleTitle, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        s.settingsBubbleDesc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = bubbleOn && overlayGranted,
                    onCheckedChange = { on ->
                        if (on) {
                            if (overlayGranted) {
                                TranslateBubbleService.start(ctx)
                                bubbleOn = true
                            } else {
                                // Сначала разрешение, служба — после возврата.
                                startAfterGrant = true
                                openOverlaySettings()
                            }
                        } else {
                            TranslateBubbleService.stop(ctx)
                            bubbleOn = false
                        }
                    }
                )
            }

            Text(
                if (bubbleOn && overlayGranted) s.settingsBubbleOn else s.settingsBubbleOff,
                style = MaterialTheme.typography.bodySmall,
                color = if (bubbleOn && overlayGranted) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // ------------------------------------- вставка через спец. возможности
            Text(
                s.settingsAccessibilityTitle,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                s.settingsAccessibilityDesc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                if (accessibilityOn) s.settingsAccessibilityOn else s.settingsAccessibilityOff,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (accessibilityOn) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                }
            )
            TextButton(onClick = {
                // Системный список спец. возможностей: службу включает сам пользователь.
                accessibilityLauncher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }) {
                Text(s.settingsAccessibilityOpen)
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // --------------------------------------- меню «Перевести» (PROCESS_TEXT)
            Text(s.settingsProcessTextTitle, style = MaterialTheme.typography.bodyLarge)
            Text(
                s.settingsProcessTextDesc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
