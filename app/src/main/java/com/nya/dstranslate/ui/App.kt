package com.nya.dstranslate.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nya.dstranslate.MainViewModel
import com.nya.dstranslate.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(vm: MainViewModel) {
    var tab by remember { mutableIntStateOf(1) }
    var showLegal by remember { mutableStateOf(false) }
    val strings = if (vm.uiLang == UiLang.RU) RuStrings else ZhStrings

    CompositionLocalProvider(LocalStrings provides strings) {
        val s = LocalStrings.current

        // Смена вкладки обрывает озвучку — она не должна звучать с прошлого экрана.
        DisposableEffect(tab) {
            onDispose { vm.stopSpeaking() }
        }

        if (showLegal) {
            // Экран «О приложении / Правовая информация» вместо настроек.
            BackHandler { showLegal = false }
            LegalScreen(onBack = { showLegal = false })
        } else {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable { vm.toggleUiLang() }
                            ) {
                                androidx.compose.foundation.Image(
                                    painter = painterResource(R.drawable.ic_launcher),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = s.appTitle,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 21.sp
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors()
                    )
                },
                bottomBar = {
                    NavigationBar {
                        NavigationBarItem(
                            selected = tab == 0,
                            onClick = { tab = 0 },
                            icon = { Icon(Icons.Filled.Translate, contentDescription = null) },
                            label = { Text(s.tabText, fontSize = 11.sp) }
                        )
                        NavigationBarItem(
                            selected = tab == 1,
                            onClick = { tab = 1 },
                            icon = { Icon(Icons.Filled.ScreenRotation, contentDescription = null) },
                            label = { Text(s.tabSplit, fontSize = 11.sp) }
                        )
                        NavigationBarItem(
                            selected = tab == 2,
                            onClick = { tab = 2 },
                            icon = { Icon(Icons.Filled.AutoAwesome, contentDescription = null) },
                            label = { Text(s.tabAuto, fontSize = 11.sp) }
                        )
                        NavigationBarItem(
                            selected = tab == 3,
                            onClick = { tab = 3 },
                            icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                            label = { Text(s.tabSettings, fontSize = 11.sp) }
                        )
                    }
                }
            ) { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                ) {
                    when (tab) {
                        0 -> TextTranslateScreen(vm)
                        1 -> SplitScreen(vm)
                        2 -> AutoDialogScreen(vm)
                        else -> SettingsScreen(vm, onOpenLegal = { showLegal = true })
                    }
                }
            }
        }

        UpdateDialog(vm)
    }
}

@Composable
private fun UpdateDialog(vm: MainViewModel) {
    val info = vm.updateAvailable ?: return
    if (!vm.updateDialogVisible) return
    val s = LocalStrings.current
    val progress = vm.updateProgress

    AlertDialog(
        onDismissRequest = { if (progress == null) vm.dismissUpdateDialog() },
        title = { Text("${s.updateAvailableTitle} ${info.versionName}", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("${s.installedVersion}: ${vm.currentVersionName} (${vm.currentVersionCode})")
                Spacer(Modifier.height(8.dp))
                if (info.notes.isNotBlank()) {
                    Text(info.notes, fontSize = 15.sp)
                    Spacer(Modifier.height(8.dp))
                }
                if (progress != null) {
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("${s.updating} $progress%")
                }
                vm.updateError?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
                }
            }
        },
        confirmButton = {
            Button(onClick = { vm.downloadAndInstallUpdate() }, enabled = progress == null) {
                Text(if (progress != null) s.updating else s.updateDownloadInstall)
            }
        },
        dismissButton = {
            TextButton(onClick = { vm.dismissUpdateDialog() }, enabled = progress == null) {
                Text(s.later)
            }
        }
    )
}
