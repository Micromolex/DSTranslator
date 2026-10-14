package com.nya.dstranslate

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nya.dstranslate.data.Prefs
import com.nya.dstranslate.ui.AppRoot
import com.nya.dstranslate.ui.DsTheme
import com.nya.dstranslate.ui.Legal
import com.nya.dstranslate.ui.RuStrings
import com.nya.dstranslate.ui.Strings
import com.nya.dstranslate.ui.UiLang
import com.nya.dstranslate.ui.ZhStrings

class MainActivity : ComponentActivity() {

    private val requestMic =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestMic.launch(Manifest.permission.RECORD_AUDIO)
        val prefs = Prefs(applicationContext)
        setContent {
            DsTheme {
                // Пока согласие не получено, приложение не запускается: вместо
                // интерфейса показывается диалог, а «закрытие» диалога завершает приложение.
                var accepted by remember { mutableStateOf(prefs.legalAccepted) }

                if (accepted) {
                    val vm: MainViewModel = viewModel()
                    LaunchedEffect(Unit) {
                        vm.initTts()
                        // Автопроверка обновлений при запуске — тихо, без ошибок на экране.
                        if (vm.autoCheckUpdates) vm.checkUpdates(manual = false)
                    }
                    AppRoot(vm)
                } else {
                    LegalConsentGate(
                        strings = if (prefs.uiLang == UiLang.ZH) ZhStrings else RuStrings,
                        onAccept = {
                            prefs.legalAccepted = true
                            accepted = true
                        },
                        onExit = { finishActivity() }
                    )
                }
            }
        }
    }

    /** Закрыть приложение: без согласия работать нельзя. */
    private fun finishActivity() {
        finishAndRemoveTask()
    }
}

/**
 * Гейт первого запуска.
 *
 * Согласился — [onAccept]. Закрыл окно (кнопкой «Назад» или тапом мимо) —
 * показывается предупреждение: «принять» возвращает к тексту,
 * «закрыть приложение» завершает приложение.
 */
@Composable
private fun LegalConsentGate(
    strings: Strings,
    onAccept: () -> Unit,
    onExit: () -> Unit
) {
    val s = strings
    var declined by remember { mutableStateOf(false) }

    if (declined) {
        // Отказ: приложение закрывается, но у пользователя остаётся выбор.
        BackHandler { onExit() }
        AlertDialog(
            onDismissRequest = { onExit() },
            title = { Text(s.legalDeclinedTitle, fontWeight = FontWeight.Bold) },
            text = { Text(s.legalDeclinedMessage, fontSize = 15.sp) },
            confirmButton = {
                Button(onClick = { declined = false }) { Text(s.legalAccept) }
            },
            dismissButton = {
                TextButton(onClick = onExit) { Text(s.legalExit) }
            }
        )
        return
    }

    // Системная «Назад» в диалоге согласия = отказ (диалог закрыт без согласия).
    BackHandler { declined = true }

    AlertDialog(
        onDismissRequest = { declined = true },
        title = { Text(s.legalTitle, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Один TextView с переносами строк — тот же текст, что в настройках.
                Text(Legal.text(), fontSize = 15.sp, lineHeight = 22.sp)
                Spacer(Modifier.height(4.dp))
            }
        },
        confirmButton = {
            Button(onClick = onAccept) { Text(s.legalAccept) }
        }
    )
}
