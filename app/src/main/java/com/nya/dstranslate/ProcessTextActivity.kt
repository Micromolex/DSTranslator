package com.nya.dstranslate

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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

/**
 * «Перевести» из меню выделения текста (ACTION_PROCESS_TEXT).
 *
 * Главное требование: окно открывается ПОВЕРХ текущего приложения (WeChat,
 * браузер, мессенджер), а не переключает на главный экран переводчика.
 * Для этого Activity объявлена в манифесте с темой `Theme.DeepSeekTranslate.Dialog`
 * (родитель — `Theme.AppCompat.Dialog`): плавающее окно, затемнённый фон,
 * компактная карточка по центру. Пользователь нажимает «Заменить текст» —
 * перевод возвращается в исходное приложение через `setResult`.
 */
class ProcessTextActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var prefs: Prefs
    private lateinit var strings: Strings
    private lateinit var translator: QuickTranslator

    private lateinit var resultView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var replaceButton: Button

    /** Готовый перевод: уходит в setResult при «Заменить текст». */
    private var translated: String = ""

    /** Вход пришёл из режима «только чтение» — замену в оригинале не предлагаем. */
    private var readOnly: Boolean = false

    private var job: Job? = null

    /** Открыта ли внешняя сессия в ApiGuard (окно поверх чужого приложения). */
    private var sessionOpen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        strings = if (prefs.uiLang == UiLang.ZH) ZhStrings else RuStrings
        translator = QuickTranslator(prefs)

        // Выделенный текст и признак «только чтение» приходят в extras.
        // CharSequence, а не String: некоторые приложения присылают Spanned —
        // приведение к String молча теряло бы текст.
        val source = extractProcessText(intent)
        readOnly = intent?.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false) ?: false

        setContentView(buildContentView(source))
        // Тап мимо карточки закрывает окно и возвращает в чужое приложение.
        setFinishOnTouchOutside(true)

        // Компактность: 92 % ширины экрана, но не шире 420 dp на планшете.
        val width = minOf(
            (resources.displayMetrics.widthPixels * 0.92f).toInt(),
            dp(420)
        )
        window?.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)

        if (source.isEmpty()) {
            showError(strings.processNoText)
        } else {
            // Окно перевода открыто поверх чужого приложения — снимаем заслон
            // фоновых запросов на время его жизни.
            ApiGuard.beginExternalSession()
            sessionOpen = true
            startTranslation(source)
        }
    }

    override fun onDestroy() {
        job?.cancel()
        // Закрываем именно свою сессию: счётчик в ApiGuard не должен «течь».
        if (sessionOpen) {
            ApiGuard.endExternalSession()
            sessionOpen = false
        }
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------- UI

    private fun buildContentView(source: String): View {
        val s = strings
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.WHITE)
            }
        }

        val title = TextView(this).apply {
            text = s.processTextTitle
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(TEXT_DARK)
        }
        card.addView(title)

        // Выделенный текст — коротко, в две строки: окно не должно вырастать.
        if (source.isNotEmpty()) {
            val preview = TextView(this).apply {
                text = source
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(Color.parseColor("#6B7A8C"))
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
            card.addView(
                preview,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }
            )
        }

        val progress = ProgressBar(
            this,
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            isIndeterminate = true
        }
        progressBar = progress
        card.addView(
            progress,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(3)
            ).apply { topMargin = dp(12) }
        )

        // Готовый перевод: прокручивается, если длинный; можно выделять/копировать.
        val result = TextView(this).apply {
            text = ""
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(TEXT_DARK)
            setTextIsSelectable(true)
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        resultView = result
        val scroll = ScrollView(this).apply {
            addView(result)
            isFillViewport = true
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#F7F9FC"))
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        card.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(170)
            ).apply { topMargin = dp(12) }
        )

        // «Заменить текст» — в исходном приложении; в read-only режиме кнопки нет.
        val replace = Button(this).apply {
            text = s.replaceText
            isAllCaps = false
            isEnabled = false
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(PRIMARY)
            setOnClickListener { returnTranslation() }
        }
        replaceButton = replace

        val copy = Button(this).apply {
            text = s.copy
            isAllCaps = false
            setTextColor(TEXT_DARK)
            backgroundTintList = ColorStateList.valueOf(Color.parseColor("#E6ECF4"))
            setOnClickListener { copyTranslation() }
        }

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (!readOnly) {
            actions.addView(replace, LinearLayout.LayoutParams(0, dp(44), 1f))
            actions.addView(
                copy,
                LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(8) }
            )
        } else {
            // Только чтение: единственное действие — скопировать.
            actions.addView(copy, LinearLayout.LayoutParams(0, dp(44), 1f))
        }
        card.addView(
            actions,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        )

        val close = Button(this).apply {
            text = s.close
            isAllCaps = false
            setTextColor(Color.parseColor("#1B5E9C"))
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            setOnClickListener {
                // Просто закрыть: оригинал в чужом приложении не меняется.
                setResult(RESULT_CANCELED)
                finish()
            }
        }
        card.addView(
            close,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(40)
            ).apply { topMargin = dp(4) }
        )

        return card
    }

    // -------------------------------------------------------------- перевод

    private fun startTranslation(source: String) {
        job?.cancel()
        job = scope.launch {
            val result = translator.translate(source, origin = "processText") { partial ->
                // Живой показ: перевод дописывается прямо в окне.
                resultView.text = partial
            }
            progressBar.visibility = View.GONE
            when (result) {
                is QuickResult.Ok -> {
                    translated = result.text
                    resultView.text = result.text
                    replaceButton.isEnabled = true
                }

                is QuickResult.Error -> showError(result.message)
                QuickResult.NoKey -> showError(strings.processNoApiKey)
                QuickResult.Empty -> showError(strings.bubbleEmptyResult)
            }
        }
    }

    private fun showError(message: String) {
        progressBar.visibility = View.GONE
        resultView.setTextColor(Color.parseColor("#B3261E"))
        resultView.text = message
    }

    private fun returnTranslation() {
        if (translated.isBlank()) return
        // Возврат текста вызывающему приложению: оно само заменит выделение.
        val data = Intent().apply {
            putExtra(Intent.EXTRA_PROCESS_TEXT, translated)
        }
        setResult(RESULT_OK, data)
        finish()
    }

    private fun copyTranslation() {
        if (translated.isBlank()) {
            Toast.makeText(this, strings.bubbleEmptyResult, Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("Перевод", translated))
        Toast.makeText(this, strings.copied, Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------- утилиты

    /**
     * Выделенный текст из `ACTION_PROCESS_TEXT`.
     *
     * `EXTRA_PROCESS_TEXT` по контракту — CharSequence. Часть приложений
     * кладёт туда String, часть — Spanned; читаем оба варианта и только потом
     * приводим к строке, иначе выделение с форматированием могло оказаться пустым.
     */
    private fun extractProcessText(intent: Intent?): String {
        if (intent == null) return ""
        val raw: CharSequence? = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
            ?: intent.getStringExtra(Intent.EXTRA_PROCESS_TEXT)
        return raw?.toString()?.trim().orEmpty()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)

    private companion object {
        val PRIMARY: Int = Color.parseColor("#1B5E9C")
        val TEXT_DARK: Int = Color.parseColor("#1B2530")
    }
}
