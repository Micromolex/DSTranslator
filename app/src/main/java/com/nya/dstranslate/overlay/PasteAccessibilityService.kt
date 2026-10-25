package com.nya.dstranslate.overlay

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Служба спец. возможностей — только для одной операции: **вставить перевод
 * в то поле, где сейчас стоит курсор в другом приложении** (WeChat, заметки и т.п.).
 *
 * Как это работает:
 *  1. Служба запоминает последнее редактируемое поле ЧУЖОГО приложения — по
 *     событиям фокуса и смены окна. Своё приложение (пузырь, диалог) пропускается.
 *  2. При «Вставить» окно пузыря уже закрыто, фокус вернулся в чужое приложение.
 *     Служба кладёт перевод в буфер и шлёт полю `ACTION_PASTE` — то же самое,
 *     что делает системный пункт «Вставить» в контекстном меню поля.
 *  3. Если приложение отказывает `ACTION_PASTE`, остаётся резервный
 *     `ACTION_SET_TEXT` — текст всё равно попадёт в поле.
 *
 * Служба не читает переписку и ничего не отправляет: события используются
 * только чтобы запомнить последнее чужое поле ввода.
 */
class PasteAccessibilityService : AccessibilityService() {

    companion object {
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

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            // Основной путь: пользователь тапнул по полю ввода — запоминаем его.
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> rememberEditable(event.source)
            // Некоторые приложения (в т.ч. часть версий WeChat) не шлют
            // TYPE_VIEW_FOCUSED на своё поле: после смены окна или клика
            // перечитываем фокус сами.
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                // Небольшая пауза: фокус внутри окна выставляется после
                // самого клика, сразу читать рано.
                handler.postDelayed({ rememberFocusedField() }, 120)
            }
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
     * @return true — текст вставлен; false — поля нет/приложение не приняло
     *   (тогда вызывающий код предлагает скопировать вручную).
     */
    fun pasteIntoFocusedField(text: String): Boolean {
        if (text.isBlank()) return false

        val targets = collectTargets()
        if (targets.isEmpty()) return false

        // ACTION_PASTE берёт содержимое буфера обмена — кладём перевод туда.
        // Запись в буфер из службы доступна и когда наше приложение не на переднем плане.
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("Перевод", text))

        // 1. «Вставить» — тот же путь, что у системного меню поля ввода.
        for (node in targets) {
            // Поле могло потерять фокус, пока мы шли к нему: возвращаем его.
            runCatching { node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
            val pasted = runCatching {
                node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            }.getOrDefault(false)
            if (pasted) return true
        }

        // 2. Резерв: некоторые поля ACTION_PASTE не поддерживают — ставим текст целиком.
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
            if (set) return true
        }
        return false
    }

    /**
     * Кандидаты в порядке приоритета: запомненное поле, глобальный фокус,
     * фокус в каждом окне, первое редактируемое поле окна.
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

        // 3. Все интерактивные окна: у части приложений фокус лежит не в активном.
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
        if (refresh && !runCatching { node.refresh() }.getOrDefault(false)) return false
        return runCatching { node.isEditable && node.isVisibleToUser }.getOrDefault(false)
    }
}
