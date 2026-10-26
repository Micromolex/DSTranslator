package com.nya.dstranslate.overlay

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.nya.dstranslate.data.Prefs
import com.nya.dstranslate.ui.RuStrings
import com.nya.dstranslate.ui.Strings
import com.nya.dstranslate.ui.UiLang
import com.nya.dstranslate.ui.ZhStrings

/**
 * Служба спец. возможностей — только для одной операции: **вставить перевод
 * в то поле, где сейчас стоит курсор в другом приложении** (WeChat, заметки и т.п.).
 *
 * Как это работает:
 *  1. Служба запоминает последнее редактируемое поле ЧУЖОГО приложения — по
 *     событиям фокуса, смены выделения и смены окна. Своё приложение (пузырь,
 *     диалог) пропускается.
 *  2. При «Вставить» окно пузыря уже закрыто, фокус вернулся в чужое приложение.
 *     Служба кладёт перевод в буфер и шлёт полю `ACTION_PASTE` — то же самое,
 *     что делает системный пункт «Вставить» в контекстном меню поля.
 *  3. Если приложение отказывает `ACTION_PASTE`, остаётся резервный
 *     `ACTION_SET_TEXT` — текст всё равно попадёт в поле.
 *  4. Если поля нет вовсе (`rootInActiveWindow`/`findFocus()` вернули null)
 *     или оба способа отклонены, служба **не падает**: текст остаётся в буфере,
 *     а пользователю показывается «Текст скопирован. Вставьте вручную».
 *
 * Служба не читает переписку и ничего не отправляет: события используются
 * только чтобы запомнить последнее чужое поле ввода.
 *
 * Почему всё в `try/catch`: в 1.6.25 система сообщала «Сервис работает
 * некорректно». Причина — необработанные исключения Android при работе с
 * чужими окнами (узел уже уничтожен, `refresh()` бросает `IllegalStateException`,
 * дерево успело перестроиться). Теперь ни одно событие и ни одна вставка
 * не могут уронить службу: любая ошибка логируется и приводит к ручной вставке.
 */
class PasteAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "PasteAS"

        /** Живой экземпляр — по нему пузырь понимает, доступна ли вставка. */
        @Volatile
        var instance: PasteAccessibilityService? = null
            private set

        /** Включена ли служба в системе прямо сейчас (подключена ли). */
        val isConnected: Boolean get() = instance != null

        /**
         * Служба включена в системных настройках, даже если ещё не успела
         * подключиться. Нужно, чтобы пузырь не открывал настройки повторно,
         * пока система поднимает службу.
         */
        fun isEnabled(context: Context): Boolean {
            val manager = context.getSystemService(AccessibilityManager::class.java)
                ?: return false
            // FEEDBACK_ALL_MASK — все типы обратной связи: служба подключена,
            // если система видит её включённой, независимо от настроек.
            return manager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            ).any { info ->
                val serviceInfo = info.resolveInfo?.serviceInfo ?: return@any false
                serviceInfo.packageName == context.packageName &&
                    serviceInfo.name == PasteAccessibilityService::class.java.name
            }
        }
    }

    /**
     * Последнее редактируемое поле ЧУЖОГО приложения. Ссылку держим, чтобы
     * «Вставить» сработало даже после того, как окно пузыря перехватило фокус:
     * `findFocus()` в этот момент вернул бы наше собственное поле.
     */
    private var lastEditable: AccessibilityNodeInfo? = null

    /** Главный поток: перечитывать фокус после клика/смены окна удобнее здесь. */
    private val handler = Handler(Looper.getMainLooper())

    /** Подписи для подсказки — по языку интерфейса приложения. */
    private lateinit var strings: Strings

    override fun onCreate() {
        super.onCreate()
        // Язык берём в onCreate: к моменту первого события он уже известен.
        strings = if (Prefs(this).uiLang == UiLang.ZH) ZhStrings else RuStrings
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        applyServiceInfo()
    }

    /**
     * Донастроить службу в рантайме.
     *
     * `canRetrieveWindowContent` задаётся **только** XML-метаданными
     * (`res/xml/accessibility_service_config.xml`, там `true`) — через
     * `setServiceInfo` это поле не меняется. В рантайме подтверждаем события и
     * флаги: без `FLAG_RETRIEVE_INTERACTIVE_WINDOWS` после закрытия пузыря фокус
     * в чужом приложении не находится.
     */
    private fun applyServiceInfo() {
        runCatching {
            val info = serviceInfo ?: AccessibilityServiceInfo()
            info.eventTypes =
                AccessibilityEvent.TYPE_VIEW_FOCUSED or
                AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_VIEW_CLICKED
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            info.flags = info.flags or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            info.notificationTimeout = 100L
            serviceInfo = info
            Log.i(TAG, "Служба подключена; события фокуса/выделения/окна включены")
        }.onFailure { t ->
            // Даже ошибка настройки не должна приводить к «служба работает некорректно».
            Log.w(TAG, "Не удалось применить serviceInfo", t)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Вся работа с чужими окнами — под try/catch: узлы могут исчезнуть
        // между событием и обращением к ним, и система снимает службу.
        try {
            when (event?.eventType) {
                // Основной путь: пользователь тапнул по полю ввода — запоминаем его.
                AccessibilityEvent.TYPE_VIEW_FOCUSED -> rememberEditable(event.source)

                // Смена выделения: WeChat и часть других приложений не шлют
                // TYPE_VIEW_FOCUSED, но присылают это событие.
                AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                    val source = runCatching { event.source }.getOrNull()
                    if (source != null && isForeignEditable(source, refresh = false)) {
                        rememberEditable(source)
                    } else {
                        // Источника нет — перечитываем фокус сами, чуть позже.
                        handler.postDelayed({ safeRememberFocusedField() }, 120)
                    }
                }

                // После смены окна или клика фокус внутри поля выставляется
                // не мгновенно: небольшая пауза, затем перечитываем сами.
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_VIEW_CLICKED ->
                    handler.postDelayed({ safeRememberFocusedField() }, 120)

                else -> Unit
            }
        } catch (t: Throwable) {
            // Не даём службе упасть: система показала бы «Сервис работает некорректно».
            Log.w(TAG, "onAccessibilityEvent: событие пропущено", t)
        }
    }

    override fun onInterrupt() {
        // Прерывать нечего: служба ничего не выполняет в фоне.
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        lastEditable?.recycle()
        lastEditable = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** Запомнить [node], если это редактируемое поле другого приложения. */
    private fun rememberEditable(node: AccessibilityNodeInfo?) {
        if (!isForeignEditable(node, refresh = false)) return
        // Своё приложение и нередактируемые узлы отбрасываем: нам нужно
        // именно поле ввода, в которое вернётся фокус после закрытия пузыря.
        if (lastEditable === node) return
        lastEditable?.recycle()
        lastEditable = node
    }

    /** Обёртка для отложенных вызовов: исключение не должно уронить службу. */
    private fun safeRememberFocusedField() {
        try {
            rememberFocusedField()
        } catch (t: Throwable) {
            Log.w(TAG, "rememberFocusedField: фокус не прочитан", t)
        }
    }

    /** Перечитать текущий фокус во всех окнах (после смены окна/клика). */
    private fun rememberFocusedField() {
        val focused = runCatching { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
        if (isForeignEditable(focused, refresh = false)) {
            rememberEditable(focused)
            return
        }

        // Фокуса не видно — ищем редактируемое поле в каждом окне.
        val windows = runCatching { windows }.getOrNull() ?: return
        for (window in windows) {
            val root = runCatching { window.root }.getOrNull() ?: continue
            val found = runCatching { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }
                .getOrNull()
            val editable = if (isForeignEditable(found, refresh = false)) {
                found
            } else {
                findFirstEditable(root, depth = 0, budget = 120)
            }
            if (editable != null) {
                rememberEditable(editable)
                return
            }
        }
    }

    /**
     * Вставить [text] в активное поле.
     *
     * Сначала пробуем именно `ACTION_PASTE` (как системное «Вставить»), и только
     * если приложение этот экшен игнорирует — `ACTION_SET_TEXT`.
     *
     * Метод никогда не бросает исключений: любая ошибка = `false`, текст уже
     * лежит в буфере, пользователь получает подсказку о ручной вставке.
     *
     * @param showManualHint показывать ли Toast «Текст скопирован. Вставьте
     *   вручную». Пузырь делает несколько попыток подряд и просит подсказку
     *   только на последней, иначе пользователь увидел бы три одинаковых тоста.
     * @return true — текст вставлен; false — поля нет/приложение не приняло
     *   (тогда вызывающий код предлагает скопировать вручную).
     */
    fun pasteIntoFocusedField(text: String, showManualHint: Boolean = true): Boolean {
        if (text.isBlank()) return false
        return try {
            pasteInternal(text, showManualHint)
        } catch (t: Throwable) {
            Log.w(TAG, "Вставка не удалась — служба остаётся живой", t)
            // Текст кладём в буфер: даже после сбоя его можно вставить вручную.
            copyToClipboard(text)
            if (showManualHint) showManualPasteToast()
            false
        }
    }

    /** Основной путь вставки; вызывается только из [pasteIntoFocusedField]. */
    private fun pasteInternal(text: String, showManualHint: Boolean): Boolean {
        // ACTION_PASTE берёт содержимое буфера обмена — кладём перевод туда.
        // Запись в буфер из службы доступна и когда наше приложение не на переднем плане.
        copyToClipboard(text)

        val targets = collectTargets()
        if (targets.isEmpty()) {
            // rootInActiveWindow == null и findFocus(FOCUS_INPUT) == null:
            // поля ввода сейчас нет (или приложение скрывает дерево).
            Log.w(TAG, "Поле ввода не найдено (root/findFocus = null) — ручная вставка")
            if (showManualHint) showManualPasteToast()
            return false
        }

        // 1. «Вставить» — тот же путь, что у системного меню поля ввода.
        for (node in targets) {
            // Поле могло потерять фокус, пока мы шли к нему: возвращаем его.
            runCatching { node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
            val pasted = runCatching {
                node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            }.getOrDefault(false)
            if (pasted) {
                Log.i(TAG, "Вставка: ACTION_PASTE сработал")
                return true
            }
        }

        // 2. Резерв: ACTION_PASTE вернул false — ставим текст целиком.
        //    Именно этот путь спасает WebView и часть версий WeChat.
        for (node in targets) {
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text
                )
            }
            val set = runCatching {
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }.getOrDefault(false)
            if (set) {
                Log.i(TAG, "Вставка: ACTION_SET_TEXT сработал (резерв)")
                return true
            }
        }

        // 3. Оба способа отклонены: текст остался в буфере, подсказываем вручную.
        Log.w(TAG, "Вставка: ACTION_PASTE и ACTION_SET_TEXT отклонены")
        if (showManualHint) showManualPasteToast()
        return false
    }

    /**
     * Кандидаты в порядке приоритета: запомненное поле, глобальный фокус,
     * `rootInActiveWindow`, фокус в каждом окне, первое редактируемое поле окна.
     */
    private fun collectTargets(): List<AccessibilityNodeInfo> {
        val list = ArrayList<AccessibilityNodeInfo>(4)

        fun add(candidate: AccessibilityNodeInfo?) {
            if (!isForeignEditable(candidate, refresh = true)) return
            val node = candidate ?: return
            // Один и тот же узел не должен попасть в список дважды: иначе
            // ACTION_PASTE выполнится повторно и текст вставится два раза.
            if (list.none { it == node }) {
                list.add(node)
            }
        }

        // 1. Последнее поле, которое пользователь открыл до пузыря.
        add(lastEditable)

        // 2. Текущий фокус: после закрытия окна это поле чужого приложения.
        add(runCatching { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull())

        // 3. Активное окно. `rootInActiveWindow` без canRetrieveWindowContent
        //    всегда null — тогда работают остальные кандидаты.
        val activeRoot = runCatching { rootInActiveWindow }.getOrNull()
        if (activeRoot != null) {
            add(runCatching { activeRoot.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull())
            add(findFirstEditable(activeRoot, depth = 0, budget = 120))
            // Сам корень может быть полем ввода (редко, но бывает).
            add(activeRoot)
        }

        // 4. Все интерактивные окна: у части приложений фокус лежит не в активном.
        val windows = runCatching { windows }.getOrNull()
        if (windows != null) {
            for (window in windows) {
                val root = runCatching { window.root }.getOrNull() ?: continue
                val focused = runCatching {
                    root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                }.getOrNull()
                add(focused)
                if (focused == null || !isForeignEditable(focused, refresh = false)) {
                    add(findFirstEditable(root, depth = 0, budget = 120))
                }
                // Сам корень может быть полем ввода (редко, но бывает).
                add(root)
            }
        }
        return list
    }

    /** Первое редактируемое поле в дереве; ограничения — чтобы не зависнуть. */
    private fun findFirstEditable(
        node: AccessibilityNodeInfo,
        depth: Int,
        budget: Int
    ): AccessibilityNodeInfo? {
        if (budget <= 0 || depth > 12) return null
        if (node.isEditable && node.packageName?.toString() != packageName) return node
        var left = budget
        for (i in 0 until node.childCount) {
            if (left <= 0) break
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            left -= 1
            val found = findFirstEditable(child, depth + 1, left)
            if (found != null) return found
        }
        return null
    }

    /** Поле другого приложения, которое всё ещё существует и принимает текст. */
    private fun isForeignEditable(node: AccessibilityNodeInfo?, refresh: Boolean): Boolean {
        if (node == null) return false
        // Своё приложение пропускаем: поле ввода пузыря/диалога нам не цель.
        if (node.packageName?.toString() == packageName) return false
        // refresh() на уничтоженном узле бросает исключение — отсюда runCatching.
        if (refresh && !runCatching { node.refresh() }.getOrDefault(false)) return false
        return runCatching { node.isEditable && node.isVisibleToUser }.getOrDefault(false)
    }

    /** Положить текст в буфер: он нужен и для ACTION_PASTE, и для ручной вставки. */
    private fun copyToClipboard(text: String) {
        runCatching {
            val clipboard = getSystemService(ClipboardManager::class.java) ?: return
            clipboard.setPrimaryClip(ClipData.newPlainText("Перевод", text))
        }.onFailure { t -> Log.w(TAG, "Буфер обмена недоступен", t) }
    }

    /** «Текст скопирован. Вставьте вручную» — на главном потоке, из любого места. */
    private fun showManualPasteToast() {
        handler.post {
            runCatching {
                Toast.makeText(
                    applicationContext,
                    strings.pasteManualToast,
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
