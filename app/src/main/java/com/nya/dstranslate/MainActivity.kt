package com.nya.dstranslate

import android.Manifest
import android.content.Intent
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nya.dstranslate.data.ApiGuard
import com.nya.dstranslate.data.Prefs
import com.nya.dstranslate.ui.AppRoot
import com.nya.dstranslate.ui.DsTheme
import com.nya.dstranslate.ui.Legal
import com.nya.dstranslate.ui.RuStrings
import com.nya.dstranslate.ui.Strings
import com.nya.dstranslate.ui.UiLang
import com.nya.dstranslate.ui.ZhStrings

class MainActivity : ComponentActivity() {

    companion object {
        /**
         * Extra для быстрого перехода из пузыря: `intent.putExtra(OPEN_TAB, AUTO)`.
         * Приложение поднимается на передний план и открывает нужную вкладку.
         */
        const val EXTRA_OPEN_TAB = "OPEN_TAB"

        /** Значение [EXTRA_OPEN_TAB] — вкладка «Авто» (см. индексы вкладок в App.kt). */
        const val TAB_AUTO = "AUTO"
    }

    /**
     * ViewModel создаётся внутри Compose — только после согласия с правовой
     * информацией. Держим на него ссылку, чтобы останавливать запись и запросы
     * в `onStop` (Home, «недавние», блокировка экрана), а не только в `onCleared`.
     */
    private var activeViewModel: MainViewModel? = null

    /**
     * Запрос на конкретную вкладку от пузыря. `null` — вкладку выбирает
     * пользователь. Живёт в Activity, а не в Compose: extra может прийти
     * и в `onCreate` (приложение было закрыто), и в `onNewIntent` (уже открыто).
     */
    private var openTabRequest by mutableStateOf<String?>(null)

    private val requestMic =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestMic.launch(Manifest.permission.RECORD_AUDIO)
        // Холодный старт всегда открывает «Текст» — это решает AppRoot
        // (начальное значение вкладки), а extra лишь переопределяет его,
        // если приложение открыли из пузыря под вкладку «Авто».
        openTabRequest = readOpenTab(intent)
        val prefs = Prefs(applicationContext)
        setContent {
            DsTheme {
                // Пока согласие не получено, приложение не запускается: вместо
                // интерфейса показывается диалог, а «закрытие» диалога завершает приложение.
                var accepted by remember { mutableStateOf(prefs.legalAccepted) }

                if (accepted) {
                    val vm: MainViewModel = viewModel()
                    // Ссылка для onStop: после успешной компоновки она точно актуальна.
                    SideEffect { activeViewModel = vm }
                    LaunchedEffect(Unit) {
                        vm.initTts()
                        // Автопроверка обновлений при запуске — тихо, без ошибок на экране.
                        if (vm.autoCheckUpdates) vm.checkUpdates(manual = false)
                    }
                    AppRoot(
                        vm = vm,
                        openTab = openTabRequest,
                        onOpenTabHandled = { openTabRequest = null }
                    )
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

    /**
     * Приложение уже открыто (в фоне) и его поднимают из пузыря: Activity
     * не пересоздаётся (`launchMode="singleTop"`), extra приходит сюда.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // setIntent нужен, чтобы getIntent() дальше отдавал новый extra
        // (например, после пересоздания Activity).
        setIntent(intent)
        openTabRequest = readOpenTab(intent)
    }

    /** Достать из Intent запрос вкладки; всё, кроме «Авто», игнорируется. */
    private fun readOpenTab(intent: Intent?): String? {
        val value = intent?.getStringExtra(EXTRA_OPEN_TAB) ?: return null
        return if (value.equals(TAB_AUTO, ignoreCase = true)) TAB_AUTO else null
    }

    /**
     * Приложение на экране: снимаем запрет на запросы к API.
     *
     * Флаг ставим напрямую, даже если ViewModel ещё не создан (диалог согласия):
     * иначе после пересоздания Activity новые переводы блокировались бы как «фоновые».
     */
    override fun onStart() {
        super.onStart()
        ApiGuard.enterForeground()
        // Если VM уже есть, сообщаем и ей (на будущее: там же живёт логика записи).
        activeViewModel?.onAppForeground()
    }

    /**
     * Приложение ушло с экрана: Home, «недавние», блокировка, переход в другое
     * приложение. Здесь и только здесь гасим микрофон и все запросы к API.
     *
     * `onStop`, а не `onPause`: короткие системные диалоги не должны прерывать
     * разговор, а свёрнутое приложение уже не видно пользователю.
     */
    override fun onStop() {
        val vm = activeViewModel
        if (vm != null) {
            vm.onAppBackground()
        } else {
            // ViewModel ещё не создан — запись и не могла начаться, но флаг
            // всё равно закрываем, чтобы ни один путь не отправил запрос.
            ApiGuard.enterBackground()
        }
        super.onStop()
    }

    override fun onDestroy() {
        // Ссылка на VM не должна пережить Activity: следующий запуск создаст новый.
        activeViewModel = null
        super.onDestroy()
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
