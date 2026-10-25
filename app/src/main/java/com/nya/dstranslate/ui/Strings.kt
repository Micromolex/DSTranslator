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

    // ---- обратный перевод (проверка) в режиме «Текст»
    val backTranslationAction get() = pick(
        "Обратный перевод (проверка)",
        "回译（检查）"
    )
    val backTranslationTitle get() = pick(
        "Обратный перевод (проверка):",
        "回译（检查）："
    )
    val backTranslationBusy get() = pick("Проверяю…", "正在回译…")
    val backTranslationError get() = pick(
        "Не удалось проверить перевод",
        "回译失败"
    )

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
    val autoEmptyHint get() = pick(
        "Нажмите «Начать» и просто разговаривайте по очереди — каждая реплика появится здесь с пометкой роли и переводом.",
        "点击「开始」后直接对话 — 每句话都会显示在这里，并标注说话人和译文。"
    )
    val autoStart get() = pick("Начать автодиалог", "开始自动对话")
    val autoStop get() = pick("Остановить", "停止")
    val chipAuto get() = pick("Авто", "自动")
    val chipOnlyRu get() = pick("только RU", "仅俄语")
    val chipOnlyZh get() = pick("только ZH", "仅中文")
    val autoClear get() = pick("Очистить диалог", "清空对话")
    val speakPhrase get() = pick("Озвучить фразу", "朗读原句")
    val speakTranslation get() = pick("Озвучить перевод", "朗读译文")
    val autoStreamMissing get() = pick(
        "Для «Авто» нужны локальные модели распознавания (русская и китайская потоковые модели + определение языка, ~150 МБ).",
        "「自动」模式需要本地识别模型（俄语与中文流式模型 + 语言识别，约 150 MB）。"
    )
    val autoStreamMissingZhFallback get() = pick(
        "Русская модель не скачана — «Авто» пока распознаёт только китайский. Скачайте модели, чтобы вернуть русский.",
        "俄语模型未下载 —「自动」目前只能识别中文。下载模型后可恢复俄语。"
    )
    val autoModelMissingRu get() = pick(
        "Для режима «только RU» нужна русская потоковая модель (~29 МБ).",
        "「仅俄语」模式需要俄语流式模型（约 29 MB）。"
    )
    val autoModelMissingZh get() = pick(
        "Для режима «только ZH» нужна китайская потоковая модель (~25 МБ).",
        "「仅中文」模式需要中文流式模型（约 25 MB）。"
    )
    val autoStreamDownload get() = pick("Скачать модели", "下载模型")
    val autoStreamDownloading get() = pick("Скачивание…", "下载中…")

    // ---- голосовой ввод в режиме «Текст»
    val voiceInput get() = pick("Наговорить", "语音输入")
    val voiceStop get() = pick("Остановить запись", "停止录音")
    val voiceListening get() = pick("Слушаю… говорите", "正在听…请说话")

    // ---- живая индикация распознавания (RecognitionMeter)
    val recognizingNow get() = pick("Распознаю речь…", "正在识别语音…")
    val voiceReady get() = pick("Микрофон", "麦克风")
    val wordByWordHint get() = pick(
        "Слова появятся здесь по мере распознавания",
        "识别到的文字会逐步显示在这里"
    )
    val voiceReadyHint get() = pick(
        "Нажмите микрофон и говорите — до 5 секунд на фразу",
        "点击麦克风开始说话 — 每句最长 5 秒"
    )

    /** Заполнение лимита реплики: «3 / 5 с». */
    fun secondsOfLimit(sec: Int) = pick("$sec / 5 с", "$sec / 5 秒")

    /** Заголовки двух окон моно-режима. */
    val monoOriginal get() = pick("Оригинал", "原文")
    val monoTranslation get() = pick("Перевод", "译文")
    val monoEmptyHint get() = pick(
        "Нажмите «Начать» — оригинал и перевод пойдут сплошным текстом, без карточек.",
        "点击「开始」— 原文与译文将以连续文本显示，不使用卡片。"
    )

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
            "Режимы «только RU» / «только ZH» используют те же модели, но с зафиксированным языком. " +
            "Автовоспроизведение отключено — фразу можно послушать кнопкой.",
        "AI 用一次连续录音同时听双方，麦克风不会在句子之间关闭。" +
            "自动判断每句话的语言，双方说话顺序不限。" +
            "「仅俄语」/「仅中文」使用同样的流式模型，但语言固定。" +
            "默认不自动朗读，可点按钮播放。"
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
        "В запрос уходят только последние 3 реплики — для связного перевода",
        "只发送最近 3 句对话，使翻译更连贯"
    )
    val settingsAsrStorage get() = pick("Свободно", "可用空间")
    val settingsAsrWifiHint get() = pick(
        "Скачивать лучше по Wi-Fi. После скачивания распознавание работает офлайн.",
        "建议使用 Wi-Fi 下载。下载后可离线识别。"
    )

    val settingsStreamTitle get() = pick("Распознавание речи", "语音识别")
    val settingsStreamDesc get() = pick(
        "Вкладка «Авто» слушает непрерывно и распознаёт на лету: русская и китайская " +
            "потоковые модели zipformer (int8) отдают промежуточный текст прямо во время речи, " +
            "плюс Whisper tiny для надёжного определения языка (окно 1.8 с, речь без пауз). " +
            "Режимы «только RU» / «только ZH» используют те же модели, но скачивают только " +
            "нужную языковую (RU ~29 МБ, ZH ~25 МБ). Запись идёт сразу в буфер, поэтому " +
            "первые слова не теряются, а в перевод уходит только законченная фраза.",
        "「自动」标签页持续监听并实时识别：俄语与中文流式 zipformer（int8）在说话过程中" +
            "即时显示中间文本，并用 Whisper tiny 可靠判断语言（1.8 秒窗口）。" +
            "「仅俄语」/「仅中文」使用同样的模型，只需下载对应语言模型（俄语约 29 MB，中文约 25 MB）。" +
            "录音立即写入缓冲区，开头的词不会丢失；只有完整句子才会送去翻译。"
    )
    val settingsStreamReady get() = pick("Модели скачаны", "模型已下载")
    val settingsStreamMissing get() = pick("Модели не скачаны", "模型未下载")
    val settingsStreamDownload get() = pick("Скачать модели (~150 МБ)", "下载模型（约 150 MB）")
    val settingsStreamDelete get() = pick("Удалить модели", "删除模型")
    val settingsStreamDownloading get() = pick("Скачивание…", "下载中…")
    val settingsStreamNoSpace get() = pick(
        "Недостаточно места для моделей (~150 МБ)",
        "存储空间不足（约 150 MB）"
    )

    val settingsApiTitle get() = pick("Модель и ключ", "模型与密钥")
    val settingsApiDesc get() = pick(
        "Ключ DeepSeek вводится один раз и хранится только на этом устройстве. " +
            "В самом приложении ключа нет; китайский перевод — строго путунхуа (普通话, 简体字).",
        "DeepSeek 密钥只需输入一次，仅保存在本设备上。" +
            "应用本身不内置密钥；中文翻译严格使用普通话（简体字）。"
    )
    val settingsApiKeyMissing get() = pick(
        "Ключ не задан — переводы не работают",
        "未设置密钥 — 翻译不可用"
    )
    val settingsApiKeySaved get() = pick("Ключ сохранён", "密钥已保存")
    val settingsApiKeyEnter get() = pick("Ввести ключ", "输入密钥")
    val settingsApiKeyChange get() = pick("Изменить ключ", "修改密钥")
    val settingsApiKeyClear get() = pick("Удалить ключ", "删除密钥")

    // ---- окно ввода ключа DeepSeek
    val apiKeyDialogTitle get() = pick("Ключ DeepSeek API", "DeepSeek API 密钥")
    val apiKeyDialogDesc get() = pick(
        "Вставьте ключ со страницы platform.deepseek.com. Он сохранится только на этом " +
            "устройстве и не попадёт в APK.",
        "请粘贴 platform.deepseek.com 上的密钥。它只保存在本设备，不会写入 APK。"
    )
    val apiKeyLabel get() = pick("Ключ", "密钥")
    val apiKeySave get() = pick("Сохранить", "保存")
    val apiKeyTooShort get() = pick(
        "Слишком короткий ключ — вставьте его целиком",
        "密钥太短，请完整粘贴"
    )
    val apiKeyWhere get() = pick(
        "Где взять ключ: platform.deepseek.com",
        "获取密钥：platform.deepseek.com"
    )
    val settingsLangTitle get() = pick("Язык интерфейса", "界面语言")
    val settingsLangDesc get() = pick(
        "Переключается нажатием на название в шапке приложения. Первое слово — язык интерфейса.",
        "点击顶部的名称即可切换。第一个词就是界面语言。"
    )

    // ---- пузырь поверх других приложений и меню «Перевести»
    val settingsOverlayTitle get() = pick("Поверх других приложений", "悬浮于其他应用之上")
    val settingsBubbleTitle get() = pick("Плавающий пузырь", "悬浮球")
    val settingsBubbleDesc get() = pick(
        "Маленькая иконка «文A» поверх WeChat и других приложений: тап — поле ввода, " +
            "перевод и «Вставить» прямо в открытое поле. Пузырь перетаскивается и " +
            "прилипает к краю экрана. Нужно разрешение «Поверх других приложений».",
        "「文A」小图标悬浮在微信等应用之上：点击打开输入框，翻译后可点「插入」直接写入当前输入框。" +
            "图标可拖动并吸附到屏幕边缘。需要「显示在其他应用上层」权限。"
    )
    val settingsBubbleOn get() = pick("Пузырь включён", "悬浮球已开启")
    val settingsBubbleOff get() = pick("Пузырь выключен", "悬浮球已关闭")
    val settingsOverlayMissing get() = pick(
        "Разрешите «Поверх других приложений» — без него пузырь не появится",
        "请允许「显示在其他应用上层」，否则悬浮球不会显示"
    )
    val settingsAccessibilityTitle get() = pick("Вставка через спец. возможности", "通过无障碍服务插入")
    val settingsAccessibilityDesc get() = pick(
        "Чтобы «Вставить» работало прямо в поле другого приложения, включите службу " +
            "«Вставка перевода» в настройках спец. возможностей Android. Без неё перевод " +
            "копируется в буфер — вставить придётся вручную.",
        "若要让「插入」直接写入其他应用的输入框，请在系统无障碍设置中启用「插入译文」服务。" +
            "未启用时译文会复制到剪贴板，需手动粘贴。"
    )
    val settingsAccessibilityOn get() = pick("Служба включена", "服务已启用")
    val settingsAccessibilityOff get() = pick("Служба выключена", "服务未启用")
    val settingsAccessibilityOpen get() = pick("Открыть спец. возможности", "打开无障碍设置")
    val settingsProcessTextTitle get() = pick("Меню «Перевести»", "「翻译」菜单")
    val settingsProcessTextDesc get() = pick(
        "Выделите текст в любом приложении → меню выделения (⋮) → «Перевести». Поверх " +
            "приложения появится компактное окно с переводом: «Заменить текст», «Скопировать» " +
            "или «Закрыть». Приложение не переключается на главный экран.",
        "在任意应用中选中文字 → 选择菜单（⋮）→「翻译」。译文会以紧凑窗口显示在原应用之上：" +
            "「替换文本」「复制」或「关闭」。不会切换到翻译器主界面。"
    )

    // ---- окно пузыря
    val bubbleTitle get() = pick("Перевод-пузырь", "悬浮翻译")
    val bubbleInputHint get() = pick("Введите или вставьте текст", "输入或粘贴文本")
    val bubblePaste get() = pick("Вставить", "插入")
    val bubblePasteDone get() = pick("Перевод вставлен", "译文已插入")
    val bubblePasteNeedService get() = pick(
        "Служба «Вставка перевода» выключена — открою спец. возможности. " +
            "Перевод скопирован, включите службу и нажмите «Вставить» ещё раз.",
        "「插入译文」无障碍服务未启用，正在打开设置。译文已复制，启用后请再次点击「插入」。"
    )
    val bubblePasteManual get() = pick(
        "Скопировано — вставьте вручную (спец. возможности выключены)",
        "已复制，请手动粘贴（无障碍服务未启用）"
    )
    val bubbleNoOverlayPermission get() = pick(
        "Нет разрешения «Поверх других приложений» — пузырь не показан",
        "缺少「显示在其他应用上层」权限，悬浮球未显示"
    )
    val bubbleEmptyInput get() = pick("Введите текст для перевода", "请输入要翻译的文本")
    val bubbleEmptyResult get() = pick("Перевод не получен — попробуйте ещё раз", "未获取到译文，请重试")
    val bubbleNoApiKey get() = pick(
        "Ключ DeepSeek не задан — откройте приложение и введите ключ в настройках",
        "未设置 DeepSeek 密钥 — 请打开应用并在设置中输入"
    )
    val bubbleChannelName get() = pick("Пузырь-переводчик", "悬浮翻译")
    val bubbleNotificationTitle get() = pick("Пузырь-переводчик включён", "悬浮翻译已开启")
    val bubbleNotificationText get() = pick(
        "Иконка «文A» поверх других приложений. Тап — перевод, перетаскивание — сдвинуть.",
        "「文A」图标浮于其他应用之上。点击翻译，拖动可移动。"
    )

    // ---- окно из меню выделения (PROCESS_TEXT)
    val processTextTitle get() = pick("Перевод", "翻译")
    val processNoText get() = pick("Нет выделенного текста", "没有选中文本")
    val processNoApiKey get() = pick(
        "Ключ DeepSeek не задан — откройте приложение и введите ключ в настройках",
        "未设置 DeepSeek 密钥 — 请打开应用并在设置中输入"
    )
    val replaceText get() = pick("Заменить текст", "替换文本")
    val close get() = pick("Закрыть", "关闭")

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
