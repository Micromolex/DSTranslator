package com.nya.dstranslate.data

/**
 * Параметры DeepSeek API.
 *
 * Сам ключ здесь НЕ хранится: с версии 1.6.14 он вводится пользователем
 * в окне настроек и лежит только в приватных SharedPreferences устройства
 * ([Prefs.apiKey]). В APK и в исходниках ключа нет — украденный APK больше
 * не раскрывает чужой токен.
 */
object ApiConfig {
    /** Endpoint DeepSeek Chat Completions. */
    const val BASE_URL = "https://api.deepseek.com/chat/completions"
}
