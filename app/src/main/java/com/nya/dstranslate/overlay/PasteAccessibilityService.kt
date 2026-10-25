package com.nya.dstranslate.overlay

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Служба спец. возможностей — только для одной операции: **вставить перевод
 * в то поле, где сейчас стоит курсор в другом приложении** (WeChat, заметки и т.п.).
 *
 * Зачем она нужна: обычное «Копировать» заставляет пользователя ещё раз тапать
 * по чужому полю и держать палец для «Вставить». Служба делает это сама:
 * находит сфокусированное редактируемое поле и выполняет `ACTION_SET_TEXT`
 * (а если приложение его игнорирует — кладёт текст в буфер и шлёт `ACTION_PASTE`).
 *
 * Служба не читает переписку и ничего не отправляет: события используются
 * только чтобы запомнить последнее чужое поле ввода; нажатий она не эмулирует.
 */
class PasteAccessibilityService : AccessibilityService() {

    companion object {
        /** Живой экземпляр — по нему пузырь понимает, доступна ли вставка. */
        @Volatile
        var instance: PasteAccessibilityService? = null
            private set

        /** Включена ли служба в системе прямо сейчас. */
        val isConnected: Boolean get() = instance != null
    }

    /**
     * Последнее редактируемое поле ЧУЖОГО приложения. Ссылку держим, чтобы
     * «Вставить» сработало даже после того, как окно пузыря перехватило фокус:
     * `findFocus()` в этот момент вернул бы наше собственное поле.
     */
    private var lastEditable: AccessibilityNodeInfo? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_VIEW_FOCUSED) return

        // Своё приложение пропускаем: поле ввода пузыря/диалога нам не цель.
        val pkg = event.packageName?.toString()
        if (pkg == null || pkg == packageName) return

        // event.source отдаётся во владение вызывающему — старую ссылку освобождаем.
        val node = event.source ?: return
        if (node.isEditable && node.isVisibleToUser) {
            lastEditable?.recycle()
            lastEditable = node
        } else {
            node.recycle()
        }
    }

    override fun onInterrupt() {
        // Прерывать нечего: служба ничего не выполняет в фоне.
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        lastEditable?.recycle()
        lastEditable = null
        super.onDestroy()
    }

    /**
     * Вставить [text] в активное поле.
     *
     * @return true — текст вставлен; false — поля нет/приложение не приняло
     *   (тогда вызывающий код предлагает скопировать вручную).
     */
    fun pasteIntoFocusedField(text: String): Boolean {
        if (text.isBlank()) return false

        // Кандидаты по порядку: запомненное чужое поле, затем текущий фокус.
        val remembered = lastEditable?.takeIf { isValid(it) }
        val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { isValid(it) }
        val targets = listOfNotNull(remembered, focused)

        for (node in targets) {
            // ACTION_SET_TEXT — «поставить текст целиком», без клавиатуры.
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text
                )
            }
            if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                return true
            }

            // Некоторые поля (в т.ч. часть версий WeChat) SET_TEXT игнорируют:
            // тогда кладём текст в буфер и просим поле выполнить «Вставить».
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(ClipData.newPlainText("Перевод", text))
            if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
                return true
            }
        }
        return false
    }

    /** Поле всё ещё существует и принимает текст. */
    private fun isValid(node: AccessibilityNodeInfo): Boolean {
        if (node.packageName?.toString() == packageName) return false
        return runCatching { node.refresh() && node.isEditable }.getOrDefault(false)
    }
}
