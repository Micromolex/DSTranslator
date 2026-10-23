package com.nya.dstranslate.ui

import androidx.compose.runtime.staticCompositionLocalOf

/** Язык интерфейса. Первое слово в шапке приложения = язык интерфейса. */
enum class UiLang { RU, ZH }

/**
 * Все подписи интерфейса в двух языках.
 * Переключение — нажатием на заголовок в шапке.
 */
class Strings(val lang: UiLang) {

    private fun pick(ru: String, zh: String) = if (lang == UiLang.RU) ru else zh

    /** Заголовок: первое слово — язык интерфейса. */
    val appTitle: String get() = pick("Русский ↔ 中文", "中文 ↔ Русский")

    // ---- нижняя навигация
    val tabText get() = pick("Текст", "文本")
    val tabSplit get() = pick("Диалог", "对话")
    val tabAuto get() = pick("Авто", "自动")
    val tabImage get() = pick("Картинка", "图片")
    val tabSettings get() = pick("Настройки", "设置")

    // ---- общее
    val translate get() = pick("Перевести", "翻译")
    val translating get() = pick("Перевод…", "翻译中…")
    val copy get() = pick("Копировать", "复制")
    val copied get() = pick("Скопировано", "已复制")
    val clear get() = pick("Очистить", "清空")
    val delete get() = pick("Удалить", "删除")
    val speak get() = pick("Озвучить", "朗读")
    val stopSpeaking get() = pick("Остановить воспроизведение", "停止朗读")
    val later get() = pick("Позже", "稍后")
    val error get() = pick("Ошибка", "错误")

    // ---- режим «Текст»
    val inputLabel get() = pick("Текст", "文本")
    val outputPlaceholder get() = pick("Перевод появится здесь", "译文将显示在这里")
    val swapLanguages get() = pick("Поменять языки", "切换语言")
    val autoDirection get() = pick(
        "Направление перевода подбирается по языку ввода",
        "翻译方向根据输入语言自动判断"
    )
    val historyTitle get() = pick("История переводов", "翻译历史")
    val historyEmpty get() = pick("Здесь появятся ваши переводы", "翻译记录会显示在这里")
    val historyClear get() = pick("Очистить историю", "清空历史")
    val historyCleared get() = pick("История очищена", "历史已清空")

    // ---- режим «Диалог» (два экрана)
    val splitHint get() = pick(
        "Телефон кладётся между собеседниками: верхняя половина перевёрнута и обращена к человеку напротив.",
        "把手机放在两人中间：上半部分倒置，朝向对面的人。"
    )
    val splitFrom get() = pick("перевод с", "译自")
    val listening get() = pick("Слушаю…", "正在听…")
    val tapToTalk get() = pick("Нажмите, чтобы говорить", "点击开始说话")
    val pressMic get() = pick("Нажмите микрофон и говорите", "点击麦克风并说话")
    val recognizing get() = pick("распознаётся…", "识别中…")

    // ---- режим «Авто»
    val autoListening get() = pick("Слушаю обоих собеседников", "正在听双方说话")
    val autoListeningOnlyRu get() = pick("Слушаю только русскую речь", "只收听俄语")
    val autoListeningOnlyZh get() = pick("Слушаю только китайскую речь", "只收听中文")
    val autoWillListenRu get() = pick("Будет слушать только русскую речь", "将只收听俄语")
    val autoWillListenZh get() = pick("Будет слушать только китайскую речь", "将只收听中文")
    val autoStopped get() = pick("Автодиалог остановлен", "自动对话已停止")
    val autoIdleHint get() = pick(
        "ИИ сам распознаёт язык каждой реплики и переводит на второй язык",
        "AI 自动识别每句话的语言并翻译成另一种语言"
    )
    val autoEmptyHint get() = pick(
        "Нажмите «Начать» и просто разговаривайте по очереди — каждая реплика появится здесь с пометкой роли и переводом.",
        "点击「开始」后直接对话 — 每句话都会显示在这里，并标注说话人和译文。"
    )
    val autoStart get() = pick("Начать автодиалог", "开始自动对话")
    val autoStop get() = pick("Остановить", "停止")
    val chipAuto get() = pick("Авто", "自动")
    val chipOnlyRu get() = pick("только RU", "仅俄语")
    val chipOnlyZh get() = pick("только ZH", "仅中文")
    val speakPhrase get() = pick("Озвучить фразу", "朗读原句")
    val speakTranslation get() = pick("Озвучить перевод", "朗读译文")

    // ---- голосовой ввод в режиме «Текст»
    val voiceInput get() = pick("Наговорить", "语音输入")
    val voiceStop get() = pick("Остановить запись", "停止录音")
    val voiceListening get() = pick("Слушаю… говорите", "正在听…请说话")

    // ---- режим «Картинка» (внутри вкладки «Текст»)
    val imagePick get() = pick("Галерея", "相册")
    val imageCamera get() = pick("Камера", "相机")
    val imageProcessing get() = pick("Распознаю текст…", "正在识别文字…")
    val imageNoText get() = pick("Текст на картинке не найден", "未在图片中找到文字")
    val imageCyrillicWarning get() = pick(
        "Распознаются китайский и латинский текст; русский печатный пока нет.",
        "支持识别中文和拉丁文字，暂不支持俄文。"
    )

    // ---- Настройки
    val settingsUpdates get() = pick("Обновления", "更新")
    val installedVersion get() = pick("Установлено", "已安装")
    val latestVersion get() = pick("Актуальная версия", "最新版本")
    val versionNotChecked get() = pick("не проверялась", "尚未检查")
    val checkUpdates get() = pick("Проверить обновление", "检查更新")
    val upToDate get() = pick("Установлена последняя версия", "已是最新版本")
    val updateDownload get() = pick("Скачать", "下载")
    val updateAvailableTitle get() = pick("Доступна версия", "有新版本")
    val updateDownloadInstall get() = pick("Скачать и установить", "下载并安装")
    val updating get() = pick("Загрузка…", "下载中…")

    val settingsModes get() = pick("Режимы", "模式")
    val settingsSplitTitle get() = pick("Диалог (два экрана)", "对话（双屏）")
    val settingsSplitDesc get() = pick(
        "Телефон кладётся между собеседниками. Верхняя половина перевёрнута и обращена к человеку напротив, нижняя — к вам.",
        "把手机放在两人中间。上半部分倒置，朝向对面的人；下半部分朝向自己。"
    )
    val settingsAutoTitle get() = pick("Авто", "自动")
    val settingsAutoDesc get() = pick(
        "ИИ непрерывно слушает обоих ОДНОЙ записью: микрофон не выключается между фразами. " +
            "Язык каждой реплики определяется автоматически, собеседники могут говорить в любом порядке. " +
            "Автовоспроизведение отключено — фразу можно послушать кнопкой.",
        "AI 用一次连续录音同时听双方，麦克风不会在句子之间关闭。" +
            "自动判断每句话的语言，双方说话顺序不限。默认不自动朗读，可点按钮播放。"
    )
    val settingsImageTitle get() = pick("Перевод с картинки", "图片翻译")
    val settingsImageDesc get() = pick(
        "Кнопки «Галерея» и «Камера» на вкладке «Текст»: текст распознаётся " +
            "на устройстве без интернета и подставляется в поле ввода.",
        "「文本」标签页中的「相册」和「相机」按钮：文字在设备上离线识别后填入输入框。"
    )

    val settingsSpeakTitle get() = pick("Озвучивать перевод", "自动朗读译文")
    val settingsSpeakDesc get() = pick(
        "В режимах «Текст» и «Диалог». Запись на время озвучки приостанавливается автоматически.",
        "用于「文本」和「对话」模式。朗读时会自动暂停录音。"
    )
    val settingsContextTitle get() = pick("Учитывать контекст диалога", "参考对话上下文")
    val settingsContextDesc get() = pick(
        "Предыдущие реплики уходят в модель для связного перевода",
        "把之前的句子发给模型，使翻译更连贯"
    )
    val settingsAsrTitle get() = pick("Распознавание речи", "语音识别")
    val settingsAsrSystem get() = pick("Системный", "系统")
    val settingsAsrLocal get() = pick("Локальный (Qwen3-ASR)", "本地（Qwen3-ASR）")
    val settingsAsrDesc get() = pick(
        "Системный движок не умеет смешивать языки: сеанс идёт на одном языке. " +
            "Локальный распознаёт русский и китайский одной моделью, в том числе вперемешку " +
            "в одной фразе, и работает без интернета.",
        "系统引擎不能混合语言。本地模型用同一个模型识别俄语和中文（包括同一句里混用），可离线使用。"
    )
    val settingsAsrModelReady get() = pick("Модель скачана", "模型已下载")
    val settingsAsrModelMissing get() = pick("Модель не скачана", "模型未下载")
    val settingsAsrDownload get() = pick("Скачать модель (~1 ГБ)", "下载模型（约 1 GB）")
    val settingsAsrDelete get() = pick("Удалить модель", "删除模型")
    val settingsAsrDownloading get() = pick("Скачивание…", "下载中…")
    val settingsAsrWifiHint get() = pick(
        "Скачивать лучше по Wi-Fi. После скачивания распознавание работает офлайн.",
        "建议使用 Wi-Fi 下载。下载后可离线识别。"
    )
    val settingsAsrNoSpace get() = pick(
        "Недостаточно места для модели (~1 ГБ)",
        "存储空间不足（约 1 GB）"
    )
    val settingsAsrStorage get() = pick("Свободно", "可用空间")

    val settingsApiTitle get() = pick("Модель и ключ", "模型与密钥")
    val settingsApiDesc get() = pick(
        "Используется DeepSeek Chat. Ключ уже вшит в приложение — вводить не нужно. " +
            "Китайский перевод выполняется строго в путунхуа (普通话, 简体字).",
        "使用 DeepSeek Chat。密钥已内置，无需输入。中文翻译严格使用普通话（简体字）。"
    )
    val settingsLangTitle get() = pick("Язык интерфейса", "界面语言")
    val settingsLangDesc get() = pick(
        "Переключается нажатием на название в шапке приложения. Первое слово — язык интерфейса.",
        "点击顶部的名称即可切换。第一个词就是界面语言。"
    )

    // ---- «О приложении / Правовая информация»
    val settingsAboutTitle get() = pick("О приложении", "关于应用")
    val settingsAboutRow get() = pick("О приложении / Правовая информация", "关于应用 / 法律信息")
    val settingsAboutDesc get() = pick(
        "Автор, условия использования и контакт для связи",
        "作者、使用条款和联系方式"
    )
    val legalTitle get() = pick("Правовая информация", "法律信息")
    val back get() = pick("Назад", "返回")
    val legalAccept get() = pick("Я согласен", "我同意")
    val legalExit get() = pick("Закрыть приложение", "关闭应用")
    val legalDecline get() = pick("Отказаться", "拒绝")
    val legalDeclinedTitle get() = pick("Без согласия приложение не работает", "未同意则无法使用")
    val legalDeclinedMessage get() = pick(
        "Приложение использует сторонние ИИ-сервисы и переводит текст «как есть». " +
            "Чтобы продолжить, нужно принять условия. " +
            "Если вы не согласны — приложение закроется.",
        "本应用使用第三方 AI 服务，翻译结果「按原样」提供。" +
            "继续使用需要接受条款；若不同意，应用将关闭。"
    )
}

val RuStrings = Strings(UiLang.RU)
val ZhStrings = Strings(UiLang.ZH)

val LocalStrings = staticCompositionLocalOf { RuStrings }
