package com.nya.dstranslate.data

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("ds_translate", Context.MODE_PRIVATE)

    var model: String
        get() = sp.getString("model", "deepseek-chat").orEmpty()
        set(v) = sp.edit().putString("model", v).apply()

    var autoSpeak: Boolean
        get() = sp.getBoolean("auto_speak", true)
        set(v) = sp.edit().putBoolean("auto_speak", v).apply()

    var useContext: Boolean
        get() = sp.getBoolean("use_context", true)
        set(v) = sp.edit().putBoolean("use_context", v).apply()

    var updateUrl: String
        get() = sp.getString("update_url", UpdateChecker.DEFAULT_URL).orEmpty()
        set(v) = sp.edit().putString("update_url", v.trim()).apply()

    var autoCheckUpdates: Boolean
        get() = sp.getBoolean("auto_check_updates", true)
        set(v) = sp.edit().putBoolean("auto_check_updates", v).apply()

    /**
     * Движок распознавания речи: `system` — системный SpeechRecognizer,
     * `local` — Qwen3-ASR на устройстве (sherpa-onnx).
     */
    var asrEngine: String
        get() = sp.getString("asr_engine", AsrModelStore.ENGINE_SYSTEM).orEmpty()
        set(v) = sp.edit().putString("asr_engine", v).apply()

    /**
     * Согласие с правовой информацией. Пока false — приложение не запускается:
     * при старте показывается диалог с кнопкой «Я согласен».
     */
    var legalAccepted: Boolean
        get() = sp.getBoolean("legal_accepted", false)
        set(v) = sp.edit().putBoolean("legal_accepted", v).apply()

    var uiLang: com.nya.dstranslate.ui.UiLang
        get() = if (sp.getString("ui_lang", "ru") == "zh") {
            com.nya.dstranslate.ui.UiLang.ZH
        } else {
            com.nya.dstranslate.ui.UiLang.RU
        }
        set(v) = sp.edit().putString("ui_lang", if (v == com.nya.dstranslate.ui.UiLang.ZH) "zh" else "ru").apply()
}
