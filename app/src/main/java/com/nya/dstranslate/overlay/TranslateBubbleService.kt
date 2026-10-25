package com.nya.dstranslate.overlay

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
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
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
 * Плавающий пузырь-переводчик: маленький кружок поверх других приложений.
 *
 * Тап по кружку открывает компактное окно: поле ввода, «Перевести», результат
 * и «Вставить» — перевод уходит прямо в поле того приложения, которое сейчас
 * открыто (через [PasteAccessibilityService]). Кружок можно перетаскивать.
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
    }

    private lateinit var wm: WindowManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var popup: PopupWindow? = null

    private lateinit var prefs: Prefs
    private lateinit var strings: Strings
    private val translator by lazy { QuickTranslator(prefs) }

    // Виджеты окна — живут, пока окно показано; после dismiss ссылки не нужны.
    private var inputField: EditText? = null
    private var resultView: TextView? = null
    private var progressBar: ProgressBar? = null
    private var translateButton: Button? = null

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

    // ------------------------------------------------------------------ кружок

    private fun addBubble() {
        val size = dp(56)
        val bubble = FrameLayout(this)
        val dot = TextView(this).apply {
            text = "译"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(PRIMARY)
                setStroke(dp(2), Color.parseColor("#BFD8F0"))
            }
        }
        bubble.addView(dot, FrameLayout.LayoutParams(size, size))
        bubble.elevation = dp(8).toFloat()

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
            x = (screenW - size - dp(8)).coerceAtLeast(0)
            y = (screenH / 2)
        }

        bubbleParams = params
        bubbleView = bubble
        wm.addView(bubble, params)
        attachDrag(bubble)
    }

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

                MotionEvent.ACTION_UP -> {
                    // Палец не сдвинулся — это тап: открыть/закрыть окно.
                    if (!dragging) togglePopup()
                    true
                }

                else -> false
            }
        }
    }

    /** Не даём утащить кружок за границы экрана. */
    private fun clampToScreen(params: WindowManager.LayoutParams) {
        val (w, h) = screenSize()
        params.x = params.x.coerceIn(0, (w - params.width).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (h - params.height).coerceAtLeast(0))
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
        val content = buildPopupContent()

        // Окно ввода открыто поверх чужого приложения — разрешаем API-запрос,
        // хотя MainActivity в этот момент в фоне.
        if (!sessionOpen) {
            ApiGuard.beginExternalSession()
            sessionOpen = true
        }

        val (screenW, screenH) = screenSize()
        val popupWidth = minOf(screenW - dp(24), dp(360))
        val popup = PopupWindow(content, popupWidth, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        this.popup = popup
        // Прозрачный фон окна обязателен: без него PopupWindow не ловит
        // тап «мимо» и не закрывается касанием другого приложения.
        popup.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        // Тап мимо окна и системная «Назад» закрывают его, как обычный диалог.
        popup.isOutsideTouchable = true
        popup.elevation = dp(14).toFloat()
        popup.inputMethodMode = PopupWindow.INPUT_METHOD_NEEDED
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

        // Высоту считаем заранее, чтобы решить: показать окно под кружком
        // или над ним (иначе на нижней половине экрана оно уедет за край).
        content.measure(
            View.MeasureSpec.makeMeasureSpec(popupWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(screenH / 2, View.MeasureSpec.AT_MOST)
        )
        val popupHeight = content.measuredHeight.coerceAtLeast(dp(240))

        val bubble = bubbleParams ?: return
        val gap = dp(8)
        var x = bubble.x + bubble.width - popupWidth
        var y = bubble.y + bubble.height + gap
        if (y + popupHeight > screenH - dp(16)) {
            // Не влезает вниз — открываем над кружком.
            y = bubble.y - popupHeight - gap
        }
        x = x.coerceIn(dp(8), (screenW - popupWidth - dp(8)).coerceAtLeast(dp(8)))
        y = y.coerceIn(dp(8), (screenH - popupHeight - dp(8)).coerceAtLeast(dp(8)))

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

    /** Карточка окна: белая, со скруглениями и тонкой рамкой. */
    private fun buildPopupContent(): View {
        val s = strings
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(1), Color.parseColor("#D6DEE8"))
            }
        }

        // Шапка: заголовок + «✕».
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            TextView(this).apply {
                text = s.bubbleTitle
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(TEXT_DARK)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        header.addView(
            TextView(this).apply {
                text = "✕"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setTextColor(Color.parseColor("#6B7A8C"))
                setPadding(dp(8), dp(4), dp(4), dp(8))
                setOnClickListener { hidePopup() }
            }
        )
        card.addView(header)

        // Поле ввода: 1–4 строки, дальше прокрутка внутри поля.
        val edit = EditText(this).apply {
            hint = s.bubbleInputHint
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(TEXT_DARK)
            setHintTextColor(Color.parseColor("#8B98A8"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            minLines = 1
            maxLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#F3F6FA"))
                setStroke(dp(1), Color.parseColor("#D6DEE8"))
            }
        }
        inputField = edit
        card.addView(
            edit,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        )

        // Кнопка перевода — на всю ширину: главное действие окна.
        val translate = Button(this).apply {
            text = s.translate
            isAllCaps = false
            setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(PRIMARY)
            setOnClickListener { startTranslation() }
        }
        translateButton = translate
        card.addView(
            translate,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(44)
            ).apply { topMargin = dp(10) }
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
                dp(3)
            ).apply { topMargin = dp(8) }
        )

        // Результат: фиксированная высота, чтобы окно не скакало от строки к строке.
        val result = TextView(this).apply {
            text = s.outputPlaceholder
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(Color.parseColor("#8B98A8"))
            setTextIsSelectable(true)
        }
        resultView = result
        val scroll = ScrollView(this).apply {
            addView(result)
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#F7F9FC"))
            }
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        card.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(170)
            ).apply { topMargin = dp(10) }
        )

        // Действия с готовым переводом.
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val paste = Button(this).apply {
            text = s.bubblePaste
            isAllCaps = false
            setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(PRIMARY)
            setOnClickListener { pasteResult() }
        }
        val copy = Button(this).apply {
            text = s.copy
            isAllCaps = false
            setTextColor(TEXT_DARK)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#E6ECF4"))
            setOnClickListener { copyResult() }
        }
        actions.addView(paste, LinearLayout.LayoutParams(0, dp(42), 1f))
        actions.addView(
            copy,
            LinearLayout.LayoutParams(0, dp(42), 1f).apply { leftMargin = dp(8) }
        )
        card.addView(
            actions,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        )

        return card
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

        translateJob = scope.launch {
            val result = translator.translate(input, origin = "bubble") { partial ->
                resultView?.text = partial
            }
            progressBar?.visibility = View.GONE
            translateButton?.isEnabled = true
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
     * «Вставить»: закрываем окно (чтобы фокус вернулся в чужое приложение),
     * ждём кадр-два и просим службу спец. возможностей вставить текст.
     */
    private fun pasteResult() {
        val text = translatedText
        if (text.isBlank()) {
            Toast.makeText(this, strings.bubbleEmptyResult, Toast.LENGTH_SHORT).show()
            return
        }

        val service = PasteAccessibilityService.instance
        hidePopup()
        if (service == null) {
            // Служба выключена — не оставляем пользователя без выхода:
            // копируем в буфер и просим вставить вручную.
            copyToClipboard(text)
            Toast.makeText(this, strings.bubblePasteManual, Toast.LENGTH_LONG).show()
            return
        }

        val bubble = bubbleView
        val handlerView = bubble ?: return
        handlerView.postDelayed({
            if (service.pasteIntoFocusedField(text)) {
                Toast.makeText(this, strings.bubblePasteDone, Toast.LENGTH_SHORT).show()
            } else {
                copyToClipboard(text)
                Toast.makeText(this, strings.bubblePasteManual, Toast.LENGTH_LONG).show()
            }
        }, 300)
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("Перевод", text))
    }

    // ---------------------------------------------------------------- утилиты

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)
}
