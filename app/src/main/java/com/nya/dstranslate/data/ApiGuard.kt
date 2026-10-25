package com.nya.dstranslate.data

import android.util.Log

/**
 * Сторож фоновых запросов к API.
 *
 * Зачем: приложение умеет слушать микрофон непрерывно («Авто», диалог), а запись
 * до сих пор продолжалась и после ухода с экрана (Home, «недавние», блокировка).
 * VAD «слышал» тишину/шум, движок отдавал final — и в DeepSeek уходил платный запрос,
 * хотя пользователь приложение уже закрыл.
 *
 * Теперь [com.nya.dstranslate.MainActivity] на `onStop` выставляет флаг «фон» и
 * гасит запись, а любой запрос, который всё-таки попытается уйти в фоне,
 * логируется с тегом [TAG] и стектрейсом — чтобы было видно, откуда он взялся.
 */
object ApiGuard {

    /** Тег для logcat: `adb logcat -s BACKGROUND_REQUEST`. */
    const val TAG = "BACKGROUND_REQUEST"

    /**
     * Приложение на экране. true по умолчанию: до первого onStop ничего не блокируем,
     * иначе стартовавшая при запуске проверка обновлений/перевод упёрлась бы в флаг.
     */
    @Volatile
    private var foreground: Boolean = true

    val isForeground: Boolean get() = foreground

    /**
     * Приложение снова на экране. Вызывается из `MainActivity.onStart`.
     * Не запускает запись сама — её пользователь включает кнопкой заново.
     */
    fun enterForeground() {
        foreground = true
    }

    /**
     * Приложение уходит с экрана. Возвращает true только при первом переходе
     * в фон — чтобы `onStop` не выполнял тяжёлую остановку дважды.
     */
    fun enterBackground(): Boolean {
        if (!foreground) return false
        foreground = false
        return true
    }

    /**
     * Сколько «внешних» сессий перевода открыто сейчас: плавающий пузырь и
     * окно «Перевести» из меню выделения (ACTION_PROCESS_TEXT).
     *
     * Эти окна работают ПОВЕРХ чужого приложения: Activity/сервис нашего
     * приложения при этом не на экране (MainActivity в фоне), но пользователь
     * прямо сейчас нажимает «Перевести» и ждёт ответ — запрос законный.
     * Поэтому такая сессия снимает заслон до своего закрытия.
     */
    private var externalSessions: Int = 0

    /** Открыть внешнюю сессию: пузырь показал окно ввода / диалог ProcessText. */
    @Synchronized
    fun beginExternalSession() {
        externalSessions++
    }

    /** Закрыть внешнюю сессию: окно пузыря закрыто / диалог ProcessText завершён. */
    @Synchronized
    fun endExternalSession() {
        // Ниже нуля не отпускаем: двойной onDestroy не должен «съесть» чужую сессию.
        if (externalSessions > 0) externalSessions--
    }

    /**
     * Проверка перед отправкой запроса.
     *
     * @param where откуда запрос: понятная метка для лога (`translateText`,
     *   `translateAuto`, `DeepSeekClient/auto` и т.п.).
     * @return true — приложение в фоне, запрос отправлять нельзя (уже залогирован).
     */
    fun blocked(where: String): Boolean {
        // Приложение на экране или открыто окно-переводчик поверх другого
        // приложения (пузырь / PROCESS_TEXT) — запрос разрешён.
        if (foreground || externalSessions > 0) return false
        logBackgroundRequest(where)
        return true
    }

    /**
     * Запись в logcat с тегом [TAG] и полным стектрейсом текущего потока —
     * именно стектрейс показывает, какой путь кода запустил запрос.
     */
    fun logBackgroundRequest(where: String) {
        val thread = Thread.currentThread()
        // Каждый кадр с отступом, как в обычном стектрейсе исключения.
        val stack = thread.stackTrace.joinToString("\n") { "    at $it" }
        Log.e(TAG, "API-запрос в фоне ($where), поток ${thread.name}:\n$stack")
    }
}
