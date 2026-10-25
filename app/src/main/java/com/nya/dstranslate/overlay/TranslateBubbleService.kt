package com.nya.dstranslate.overlay

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Point
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.text.InputType
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.nya.dstranslate.MainActivity
import com.nya.dstranslate.R
import com.nya.dstranslate.data.ApiGuard
import com.nya.dstranslate.data.Prefs
import com.nya.dstranslate.data.QuickResult
import com.nya.dstranslate.data.QuickTranslator
import com.nya.dstranslate.ui.RuStrings
import com.nya.dstranslate.ui.Strings
import com.nya.dstranslate.ui.UiLang
import com.nya.dstranslate.ui.ZhStrings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Плавающий пузырь-переводчик: маленькая иконка «文A» поверх других приложений.
 *
 * Тап по пузырю открывает компактную карточку: поле ввода, «Перевести»,
 * прокручиваемый результат и маленькие кнопки «Вставить» / «Копировать» /
 * «Закрыть». «Вставить» уходит в поле того приложения, которое сейчас открыто
 * (через [PasteAccessibilityService]), а окно пузыря закрывается — фокус
 * возвращается в чужой чат.
 *
 * Пузырь перетаскивается: после отпускания он «прилипает» к ближайшему краю
 * экрана, как системные плавающие кнопки.
 *
 * Почему ForegroundService: система не должна прибить окно, пока пользователь
 * читает переписку в другом приложении. Тип службы — `specialUse` (плавающее
 * окно поверх других приложений), объявлен в манифесте.
 */
class TranslateBubbleService : Service() {

    companion object {
        /** Показать пузырь (идемпотентно: повторный вызов ничего не ломает). */
        fun start(ctx: Context) {
            if (isRunning) return
            val intent = Intent(ctx, TranslateBubbleService::class.java)
            // startForegroundService обязателен с Android 8: служба сразу
            // должна показать уведомление, иначе система её убьёт.
            ContextCompat.startForegroundService(ctx, intent)
        }

        /** Убрать пузырь и остановить службу. */
        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, TranslateBubbleService::class.java))
        }

        /** Пузырь на экране прямо сейчас. */
        @Volatile
        var isRunning: Boolean = false
            private set

        private const val CHANNEL_ID = "bubble"
        private const val NOTIFICATION_ID = 7301

        /** Цвета повторяют основную палитру приложения (Theme.kt: primary). */
        private val PRIMARY: Int = Color.parseColor("#1B5E9C")
        private val TEXT_DARK: Int = Color.parseColor("#1B2530")
        private val TEXT_MUTED: Int = Color.parseColor("#6B7A8C")
        private val BORDER: Int = Color.parseColor("#D6DEE8")
        private val FIELD_BG: Int = Color.parseColor("#F3F6FA")
        private val RESULT_BG: Int = Color.parseColor("#F7F9FC")
    }

    private lateinit var wm: WindowManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var popup: PopupWindow? = null
    private var snapAnimator: ValueAnimator? = null

    private lateinit var prefs: Prefs
    private lateinit var strings: Strings
    private val translator by lazy { QuickTranslator(prefs) }

    // Виджеты окна — живут, пока окно показано; после dismiss ссылки не нужны.
    private var inputField: EditText? = null
    private var resultView: TextView? = null
    private var progressBar: ProgressBar? = null
    private var translateButton: TextView? = null

    private var translateJob: Job? = null
    private var translatedText: String = ""

    /** Открыта ли внешняя сессия в [ApiGuard] (снимает заслон фоновых запросов). */
    private var sessionOpen = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        strings = if (prefs.uiLang == UiLang.ZH) ZhStrings else RuStrings
        wm = getSystemService(WindowManager::class.java)

        createChannel()
        // Уведомление показываем до проверки разрешения: если разрешения нет,
        // служба всё равно успеет корректно завершиться без ANR "startForeground".
        startForeground(NOTIFICATION_ID, buildNotification())

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, strings.bubbleNoOverlayPermission, Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        addBubble()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_NOT_STICKY: систему не просим поднимать пузырь без ведома
        // пользователя (после перезагрузки разрешение может быть отозвано).
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        snapAnimator?.cancel()
        hidePopup()
        bubbleView?.let { runCatching { wm.removeView(it) } }
        bubbleView = null
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------- уведомление

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        // Канал создаём один раз: повторное создание с тем же id безопасно.
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                strings.bubbleChannelName,
                // LOW: без звука и вибрации — пузырь не должен отвлекать.
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            // IMMUTABLE обязателен с Android 12 для Intents без изменяемых данных.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(strings.bubbleNotificationTitle)
            .setContentText(strings.bubbleNotificationText)
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    // ------------------------------------------------------------------ пузырь

    /**
     * Маленькая белая иконка «文A» вместо прежнего синего круга «译»:
     * занимает 40 dp, не закрывает чат и читается как «перевод текста».
     */
    private fun addBubble() {
        // Защита от второго окна: служба должна показывать ровно один пузырь.
        if (bubbleView != null) return

        val size = dp(40)
        val bubble = FrameLayout(this).apply {
            contentDescription = strings.settingsBubbleTitle
            // Тень у самого окна: elevation у TYPE_APPLICATION_OVERLAY
            // поддерживается начиная с Android 9, на старых просто не рисуется.
            elevation = dp(6).toFloat()
        }
        val glyph = TextView(this).apply {
            // «文» крупнее, «A» мельче — привычный значок перевода.
            text = SpannableStringBuilder().apply {
                append("文")
                setSpan(
                    RelativeSizeSpan(1.18f),
                    0,
                    1,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                append("A")
                setSpan(
                    RelativeSizeSpan(0.82f),
                    1,
                    2,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                setSpan(
                    StyleSpan(Typeface.BOLD),
                    0,
                    length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            setTextColor(PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(13).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(1), BORDER)
            }
        }
        bubble.addView(glyph, FrameLayout.LayoutParams(size, size))
        // Внутренний отступ-рамка, чтобы «文A» не прилипал к краям.
        glyph.setPadding(dp(2), dp(2), dp(2), dp(2))

        val (screenW, screenH) = screenSize()
        val params = WindowManager.LayoutParams(
            size,
            size,
            // Окно поверх любых приложений; фокус не забирает — иначе
            // клавиатура в чужом приложении закрывалась бы при каждом тапе.
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Стартовая позиция — правый край, чуть выше середины экрана.
            x = (screenW - size - dp(6)).coerceAtLeast(dp(4))
            y = (screenH * 0.42f).toInt()
        }

        bubbleParams = params
        bubbleView = bubble
        wm.addView(bubble, params)
        attachDrag(bubble)

        // Синхронизируем позицию с уже сохранённой при повторном включении?
        // Позиция живёт в памяти службы: повторный старт создаёт новый пузырь.
        clampToScreen(params)
        wm.updateViewLayout(bubble, params)
    }

    @Suppress("ClickableViewAccessibility")
    private fun attachDrag(view: View) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var startX = 0
        var startY = 0
        var downRawX = 0f
        var downRawY = 0f
        var dragging = false

        view.setOnTouchListener { _, event ->
            val params = bubbleParams ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Новое касание отменяет «прилипание» от прошлого.
                    snapAnimator?.cancel()
                    startX = params.x
                    startY = params.y
                    downRawX = event.rawX
                    downRawY = event.rawY
                    dragging = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    // Порог касания отсекает «дрожание» пальца при тапе.
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        params.x = startX + dx
                        params.y = startY + dy
                        clampToScreen(params)
                        wm.updateViewLayout(view, params)
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        // Потянули — доводим до ближайшего края, окно не открываем.
                        snapToEdge(view, params)
                    } else {
                        // Палец не сдвинулся — это тап: открыть/закрыть окно.
                        togglePopup()
                    }
                    true
                }

                else -> false
            }
        }
    }

    /** Не даём утащить пузырь за границы экрана и под системные панели. */
    private fun clampToScreen(params: WindowManager.LayoutParams) {
        val (w, h) = screenSize()
        val edge = dp(4)
        val safeTop = dp(24)
        val safeBottom = dp(16)
        params.x = params.x.coerceIn(edge, (w - params.width - edge).coerceAtLeast(edge))
        params.y = params.y.coerceIn(
            safeTop,
            (h - params.height - safeBottom).coerceAtLeast(safeTop)
        )
    }

    /**
     * «Прилипание»: после отпускания пузырь плавно уезжает к левому или правому
     * краю — как системные плавающие кнопки. Вертикаль остаётся там, где отпустили.
     */
    private fun snapToEdge(view: View, params: WindowManager.LayoutParams) {
        val (w, _) = screenSize()
        val margin = dp(4)
        val right = (w - params.width - margin).coerceAtLeast(margin)
        // Ближайший край по центру пузыря.
        val targetX = if (params.x + params.width / 2 < w / 2) margin else right
        if (params.x == targetX) return

        snapAnimator?.cancel()
        snapAnimator = ValueAnimator.ofInt(params.x, targetX).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                params.x = animation.animatedValue as Int
                // Окно могло быть уничтожено, пока шла анимация.
                if (bubbleView === view) {
                    runCatching { wm.updateViewLayout(view, params) }
                }
            }
            start()
        }
    }

    private fun screenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val point = Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getSize(point)
            point.x to point.y
        }
    }

    // -------------------------------------------------------------------- окно

    private fun togglePopup() {
        if (popup?.isShowing == true) {
            hidePopup()
        } else {
            showPopup()
        }
    }

    private fun showPopup() {
        val (screenW, screenH) = screenSize()
        // Окно не выше 60 % экрана: чат под ним остаётся видимым.
        val maxWindowH = (screenH * 0.60f).toInt()
        val content = buildPopupContent(maxWindowH)

        // Окно ввода открыто поверх чужого приложения — разрешаем API-запрос,
        // хотя MainActivity в этот момент в фоне.
        if (!sessionOpen) {
            ApiGuard.beginExternalSession()
            sessionOpen = true
        }

        val popupWidth = minOf(screenW - dp(20), dp(360))
        val popup = PopupWindow(content, popupWidth, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        this.popup = popup
        // Прозрачный фон окна обязателен: без него PopupWindow не ловит
        // тап «мимо» и не закрывается касанием другого приложения.
        popup.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        // Тап мимо окна и системная «Назад» закрывают его, как обычный диалог.
        popup.isOutsideTouchable = true
        popup.elevation = dp(14).toFloat()
        popup.inputMethodMode = PopupWindow.INPUT_METHOD_NEEDED
        // Клавиатура приподнимает окно, а не перекрывает поле ввода.
        popup.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
        )
        popup.setOnDismissListener {
            // Закрытие окна = конец внешней сессии: фоновые запросы снова под замком.
            if (sessionOpen) {
                ApiGuard.endExternalSession()
                sessionOpen = false
            }
            translateJob?.cancel()
            translateJob = null
            inputField = null
            resultView = null
            progressBar = null
            translateButton = null
        }

        // Высоту считаем заранее, чтобы решить: показать окно под пузырем
        // или над ним (иначе на нижней половине экрана оно уедет за край).
        content.measure(
            View.MeasureSpec.makeMeasureSpec(popupWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxWindowH, View.MeasureSpec.AT_MOST)
        )
        val popupHeight = content.measuredHeight.coerceAtLeast(dp(180))

        val bubble = bubbleParams ?: return
        val gap = dp(8)
        var x = bubble.x + bubble.width - popupWidth
        var y = bubble.y + bubble.height + gap
        if (y + popupHeight > screenH - dp(16)) {
            // Не влезает вниз — открываем над пузырем.
            y = bubble.y - popupHeight - gap
        }
        x = x.coerceIn(dp(10), (screenW - popupWidth - dp(10)).coerceAtLeast(dp(10)))
        y = y.coerceIn(dp(10), (screenH - popupHeight - dp(10)).coerceAtLeast(dp(10)))

        popup.showAtLocation(bubbleView ?: content, Gravity.TOP or Gravity.START, x, y)

        // Сразу ставим курсор в поле и показываем клавиатуру.
        inputField?.requestFocus()
        inputField?.post {
            val imm = getSystemService(InputMethodManager::class.java)
            imm?.showSoftInput(inputField, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hidePopup() {
        popup?.dismiss()
        popup = null
    }

    /**
     * Компактная карточка окна.
     *
     * Заголовка «Перевод-пузырь» нет — окно и так открывается по тапу на
     * пузырь. Отступы уменьшены, кнопки действий — маленькие текстовые,
     * результат прокручивается и не даёт карточке вырасти выше [maxWindowH].
     */
    private fun buildPopupContent(maxWindowH: Int): View {
        val s = strings
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Отступы 10 dp вместо прежних 16/12/14: карточка аккуратнее.
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(1), BORDER)
            }
        }

        // Поле ввода: 1–3 строки, дальше прокрутка внутри поля.
        val edit = EditText(this).apply {
            hint = s.bubbleInputHint
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(TEXT_DARK)
            setHintTextColor(Color.parseColor("#8B98A8"))
            setPadding(dp(10), dp(8), dp(10), dp(8))
            minLines = 1
            maxLines = 3
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            background = GradientDrawable().apply {
                cornerRadius = dp(11).toFloat()
                setColor(FIELD_BG)
                setStroke(dp(1), BORDER)
            }
        }
        inputField = edit
        card.addView(
            edit,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        // Кнопка перевода — компактная, но на всю ширину: главное действие окна.
        val translate = actionButton(
            label = s.translate,
            bg = PRIMARY,
            fg = Color.WHITE,
            heightDp = 34
        ) { startTranslation() }
        translateButton = translate
        card.addView(
            translate,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(34)
            ).apply { topMargin = dp(8) }
        )

        val progress = ProgressBar(
            this,
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        progressBar = progress
        card.addView(
            progress,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(2)
            ).apply { topMargin = dp(6) }
        )

        // Результат: высота ограничена остатком от 60 % экрана, длинный
        // текст прокручивается. MaxHeightScrollView нужен, чтобы короткий
        // перевод не растягивал окно, а длинный — не вылезал за предел.
        val result = TextView(this).apply {
            text = s.outputPlaceholder
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(Color.parseColor("#8B98A8"))
            setTextIsSelectable(true)
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        resultView = result
        // Сколько места остаётся под результат: из 60 % вычитаем поле
        // ввода, кнопку, полоску, отступы и ряд действий (~240 dp с запасом
        // на крупный системный шрифт).
        val resultMaxH = (maxWindowH - dp(240)).coerceAtLeast(dp(80))
        val scroll = MaxHeightScrollView(this, resultMaxH).apply {
            addView(result)
            isFillViewport = true
            background = GradientDrawable().apply {
                cornerRadius = dp(11).toFloat()
                setColor(RESULT_BG)
            }
            setPadding(dp(9), dp(8), dp(9), dp(8))
            // Полоса прокрутки — только когда текст не помещается.
            isVerticalScrollBarEnabled = true
        }
        card.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        )

        // Действия с готовым переводом — маленькие текстовые кнопки.
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val paste = actionButton(
            label = "⤵ ${s.bubblePaste}",
            bg = PRIMARY,
            fg = Color.WHITE,
            heightDp = 32
        ) { pasteResult() }
        val copy = actionButton(
            label = "⧉ ${s.copy}",
            bg = Color.parseColor("#E6ECF4"),
            fg = TEXT_DARK,
            heightDp = 32
        ) { copyResult() }
        // «Закрыть» — совсем маленькая иконка: заголовка с «✕» больше нет.
        val close = actionButton(
            label = "✕",
            bg = Color.TRANSPARENT,
            fg = TEXT_MUTED,
            heightDp = 32
        ) { hidePopup() }
        actions.addView(paste, LinearLayout.LayoutParams(0, dp(32), 1f))
        actions.addView(
            copy,
            LinearLayout.LayoutParams(0, dp(32), 1f).apply { leftMargin = dp(6) }
        )
        actions.addView(
            close,
            LinearLayout.LayoutParams(dp(34), dp(32)).apply { leftMargin = dp(4) }
        )
        card.addView(
            actions,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        )

        return card
    }

    /** Маленькая кнопка-текст: одинаковый вид у «Перевести», «Вставить», «Копировать». */
    private fun actionButton(
        label: String,
        bg: Int,
        fg: Int,
        heightDp: Int,
        onClick: () -> Unit
    ): TextView = TextView(this).apply {
        text = label
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(fg)
        gravity = Gravity.CENTER
        setPadding(dp(6), 0, dp(6), 0)
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(bg)
            // У прозрачной кнопки рамки нет; у остальных — тонкая, чтобы
            // светлая кнопка не сливалась с карточкой.
            if (bg != Color.TRANSPARENT) setStroke(dp(1), BORDER)
        }
        minHeight = dp(heightDp)
        isClickable = true
        setOnClickListener { onClick() }
    }

    /**
     * Прокручиваемый результат с верхней границей высоты: карточка не растёт
     * выше разрешённых 60 % экрана даже на очень длинном переводе.
     */
    private class MaxHeightScrollView(context: Context, private val maxHeightPx: Int) :
        ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            // AT_MOST: ScrollView сам выберет min(контент, maxHeightPx).
            val capped = MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST)
            super.onMeasure(widthMeasureSpec, capped)
        }
    }

    // ---------------------------------------------------------------- перевод

    private fun startTranslation() {
        val s = strings
        val input = inputField?.text?.toString().orEmpty().trim()
        if (input.isEmpty()) {
            Toast.makeText(this, s.bubbleEmptyInput, Toast.LENGTH_SHORT).show()
            return
        }

        translateJob?.cancel()
        translatedText = ""
        resultView?.text = ""
        // Пока идёт стриминг, текст уже обычный, а не серый плейсхолдер.
        resultView?.setTextColor(TEXT_DARK)
        progressBar?.visibility = View.VISIBLE
        translateButton?.isEnabled = false
        translateButton?.alpha = 0.5f

        translateJob = scope.launch {
            val result = translator.translate(input, origin = "bubble") { partial ->
                resultView?.text = partial
            }
            progressBar?.visibility = View.GONE
            translateButton?.isEnabled = true
            translateButton?.alpha = 1f
            when (result) {
                is QuickResult.Ok -> {
                    translatedText = result.text
                    resultView?.text = result.text
                    resultView?.setTextColor(TEXT_DARK)
                }

                is QuickResult.Error -> {
                    resultView?.text = result.message
                    resultView?.setTextColor(Color.parseColor("#B3261E"))
                }

                QuickResult.NoKey -> {
                    // Ключ вводится в основном приложении: подсказываем, где.
                    resultView?.text = s.bubbleNoApiKey
                    resultView?.setTextColor(Color.parseColor("#B3261E"))
                }

                QuickResult.Empty -> {
                    resultView?.text = s.bubbleEmptyResult
                    resultView?.setTextColor(Color.parseColor("#B3261E"))
                }
            }
        }
    }

    private fun copyResult() {
        if (translatedText.isBlank()) {
            Toast.makeText(this, strings.bubbleEmptyResult, Toast.LENGTH_SHORT).show()
            return
        }
        copyToClipboard(translatedText)
        Toast.makeText(this, strings.copied, Toast.LENGTH_SHORT).show()
    }

    /**
     * «Вставить»: закрываем окно (фокус возвращается в чужое приложение),
     * ждём, пока клавиатура и окно уйдут, и просим службу спец. возможностей
     * отправить в активное поле `ACTION_PASTE`. Окно пузыря после этого закрыто.
     *
     * Если служба выключена — копируем текст и открываем настройки спец.
     * возможностей: без неё системная вставка в чужое поле невозможна.
     */
    private fun pasteResult() {
        val text = translatedText
        if (text.isBlank()) {
            Toast.makeText(this, strings.bubbleEmptyResult, Toast.LENGTH_SHORT).show()
            return
        }

        // Гасим клавиатуру до закрытия окна: иначе она останется висеть
        // поверх чужого чата и перекроет поле ввода.
        val imm = getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(inputField?.windowToken, 0)
        hidePopup()

        val service = PasteAccessibilityService.instance
        if (service == null) {
            // Служба может быть уже включена, но система ещё её не подключила
            // (например, пользователь только что вернулся из настроек).
            if (PasteAccessibilityService.isEnabled(this)) {
                waitForServiceAndPaste(text, attempt = 0)
                return
            }
            copyToClipboard(text)
            Toast.makeText(this, strings.bubblePasteNeedService, Toast.LENGTH_LONG).show()
            // Пользователь должен включить службу сам — открываем нужный экран.
            bubbleView?.postDelayed({ openAccessibilitySettings() }, 500)
            return
        }

        pasteWithRetry(service, text, attempt = 0)
    }

    /** Дождаться подключения уже включённой службы и вставить текст. */
    private fun waitForServiceAndPaste(text: String, attempt: Int) {
        val anchor = bubbleView ?: return
        anchor.postDelayed({
            val live = PasteAccessibilityService.instance
            if (live != null) {
                pasteWithRetry(live, text, attempt = 0)
            } else if (attempt < 3) {
                waitForServiceAndPaste(text, attempt + 1)
            } else {
                copyToClipboard(text)
                Toast.makeText(this, strings.bubblePasteManual, Toast.LENGTH_LONG).show()
            }
        }, 350)
    }

    /**
     * Фокус в чужом приложении возвращается не мгновенно: после dismiss окна
     * ему нужно 1–2 кадра. Делаем несколько попыток с нарастающей паузой,
     * чтобы вставка не зависела от скорости устройства.
     */
    private fun pasteWithRetry(
        service: PasteAccessibilityService,
        text: String,
        attempt: Int
    ) {
        val anchor = bubbleView ?: return
        val delays = longArrayOf(350L, 650L, 1100L)
        val delay = delays.getOrElse(attempt) { delays.last() }
        anchor.postDelayed({
            // Служба могла отключиться между попытками.
            val live = PasteAccessibilityService.instance
            if (live == null) {
                copyToClipboard(text)
                Toast.makeText(this, strings.bubblePasteManual, Toast.LENGTH_LONG).show()
                return@postDelayed
            }
            if (live.pasteIntoFocusedField(text)) {
                Toast.makeText(this, strings.bubblePasteDone, Toast.LENGTH_SHORT).show()
            } else if (attempt < delays.lastIndex) {
                pasteWithRetry(live, text, attempt + 1)
            } else {
                // Поле не найдено/приложение не приняло — не теряем перевод.
                copyToClipboard(text)
                Toast.makeText(this, strings.bubblePasteManual, Toast.LENGTH_LONG).show()
            }
        }, delay)
    }

    /** Открыть системный список спец. возможностей — службу включает пользователь. */
    private fun openAccessibilitySettings() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("Перевод", text))
    }

    // ---------------------------------------------------------------- утилиты

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)
}
