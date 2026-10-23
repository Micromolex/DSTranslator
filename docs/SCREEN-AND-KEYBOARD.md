# Перевод с экрана и клавиатура (IME): техническая оценка

Документ — для принятия решения по двум направлениям: (а) перевод текста с экрана **других** приложений,
(б) перевод внутри клавиатуры. Оценки усилий — человеко-дни одного разработчика на этой кодовой базе
(Compose, `minSdk 26`, `targetSdk 35`, `compileSdk 35`, ML Kit `text-recognition` + `text-recognition-chinese`
уже в APK, потоковый перевод через `DeepSeekClient.translateStream()`).

Уровни API ниже сверены с локальным `platforms/android-35/data/api-versions.xml` (Android SDK 35),
поведенческие ограничения — с разделами behavior changes для API 34/35/36. Ссылки — в конце.

---

## 1. Короткий вывод

1. **Дешевле всего — `ACTION_PROCESS_TEXT`**: «Перевести» в системном меню выделения текста. Разрешений ноль,
   Play-политика не затрагивается, 3–4 чел.-дня. Минус — низкая обнаруживаемость: пользователь должен сам выделить текст.
2. **Максимум пользы — AccessibilityService + пузырь**: читает текст из окон чужих приложений (WeChat, Alipay,
   карты, банки), переводит и умеет вставлять перевод обратно. 12–16 чел.-дней.
3. **MediaProjection как основу брать не надо**: `AccessibilityService.takeScreenshot()` (API 30) даёт тот же
   скриншот **без** диалога согласия и без FGS — 4–6 чел.-дней вместо 8–12.
4. **Клавиатура «как у всех» (RU + пиньинь + словарь) — нереалистично**: 40–80 чел.-дней. Минимальная
   переводческая клавиатура (RU-набор → ZH-вставка, ZH-вставка → RU-перевод) — 8–12 чел.-дней.
5. **Порядок внедрения: шаг 1 → шаг 2 → шаг 3.** Итого 23–32 чел.-дня; ощутимый эффект уже на 3–4-й день.
6. **Главный блокер не технический**: APK ставится с GitHub Releases, а с Android 13 такие приложения не могут
   включить спец. возможность без «Разрешить ограниченные настройки» (Настройки → Приложения → ⋮).
7. **Ограничение OCR**: у ML Kit нет кириллицы, поэтому «скриншотный» путь читает только китайский; для русских
   экранов нужен Tesseract `rus` (+15–25 МБ, +5–8 дней).

---

## 2. Перевод с экрана

### 2.1 AccessibilityService: текст из дерева окон

**Как работает.** Служба объявляется в манифесте с `android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"`,
intent-filter `android.accessibilityservice.AccessibilityService` и meta-data `android:accessibilityservice`
с XML-конфигом. Пользователь вручную включает её в Настройки → Специальные возможности
(`Settings.ACTION_ACCESSIBILITY_SETTINGS`, API 5). После этого служба получает `AccessibilityEvent`
и может обходить дерево узлов активного окна.

| API | Уровень | Зачем |
|---|---|---|
| `getRootInActiveWindow()` / `getRootInActiveWindow(displayId)` | 16 / 33 | корень активного окна |
| `getWindows()`, `getWindowsOnAllDisplays()` | 21 / 30 | все окна: диалоги, попапы, split-screen |
| `AccessibilityNodeInfo.getText()` / `getContentDescription()` | 14 | собственно текст |
| `findAccessibilityNodeInfosByViewId()` | 18 | точечно по `viewIdResourceName` (нужен `FLAG_REPORT_VIEW_IDS`) |
| `findAccessibilityNodeInfosByText()` | 14 | поиск подстроки |
| `refresh()` | 18 | перечитать узел, не перестраивая дерево |
| `ACTION_SET_TEXT` | 21 | записать текст в поле |
| `ACTION_SET_SELECTION`, `ACTION_COPY`, `ACTION_PASTE` | 18 | выделение и обмен с буфером |
| `ACTION_NEXT_AT_MOVEMENT_GRANULARITY` + `MOVEMENT_GRANULARITY_PARAGRAPH/LINE` | 16 | чтение длинного текста по абзацам |
| `AccessibilityNodeInfo.isAccessibilityDataSensitive()` | 34 | узел помечен как чувствительный — не читать |
| `AccessibilityNodeInfo.isPassword()` | 18 | поле пароля — исключить |

**Что реально получается.**

| Источник | Результат |
|---|---|
| Нативные Android-приложения (TextView, Compose с semantics, списки) | точный текст, 95–100 % символов, без ошибок распознавания |
| WebView / Chrome | текст есть, но приходит фрагментами по мере вёрстки; часто требует нескольких событий и склейки |
| Canvas-рисование, игры, часть Flutter/Unity без semantics | **ноль**: узлов с текстом нет |
| Текст внутри картинок, PDF-рендер, видео | **ноль**: нужен OCR |
| `FLAG_SECURE`-окна (банки, пароли, DRM) | окно не отдаётся вообще |

**Пузырь и панель перевода.** Отдельное разрешение `SYSTEM_ALERT_WINDOW` не нужно: служба добавляет окно
типа `TYPE_ACCESSIBILITY_OVERLAY` (API 22) через `WindowManager` и рисует Compose-контент в `ComposeView`.
Слой 2032 выше клавиатуры (`TYPE_INPUT_METHOD` = 2011), поэтому пузырь виден и над открытой клавиатурой.
Практическая схема: перетаскиваемый пузырь 48 dp → тап читает дерево → карточка с оригиналом и переводом
(стриминг из `DeepSeekClient`) → кнопки «Копировать», «Вставить в поле» (`ACTION_SET_TEXT`/`ACTION_PASTE`
на узле в фокусе), «Озвучить» (существующий TTS).

**Ограничения и требования.**

- Событий много: `TYPE_WINDOW_CONTENT_CHANGED` приходит на каждую перерисовку списка. Нужен debounce
  300–500 мс, обход в фоне (вызовы узлов — IPC), лимиты: ≤ 1500 узлов, ≤ 4000 символов за один запрос.
  Иначе — ANR и жор батареи.
- Дерево зависит от приложения и его версии: эвристики «где тут основной текст» ломаются на каждом обновлении
  чужого приложения. Это не разовая работа, а постоянная поддержка.
- Приватность: экран может содержать пароли, номера карт, переписку. Обязательны фильтры `isPassword()`,
  `isAccessibilityDataSensitive()` (API 34) и запрет на перевод системных диалогов ввода пароля.
  На сервер (DeepSeek) уходит только текст, скриншоты — никогда.

**Ограничения по версиям (важно для планирования).**

| Версия | Что меняется |
|---|---|
| Android 12 (API 31) | Атрибут `android:isAccessibilityTool` в конфиге службы (`AccessibilityServiceInfo.isAccessibilityTool()`): служба сама объявляет, инструмент доступности она или нет. По умолчанию `false` — для таких служб система показывает пользователю предупреждение |
| Android 13 (API 33) | **Restricted settings**: приложению, установленному не из магазина, нельзя включить спец. возможность, пока пользователь не откроет Настройки → Приложения → <приложение> → ⋮ → «Разрешить ограниченные настройки». Для раздачи через GitHub Releases это обязательный шаг онбординга |
| Android 14 (API 34) | `takeScreenshotOfWindow()`, `attachAccessibilityOverlayToWindow/Display()`, пометка узлов как data-sensitive |
| Android 15/16 | Отдельных новых запретов именно для служб доступности в документации нет; ограничения идут по линии Play-политики и `targetSdk` |

Отдельного запрета «не-акселераторских» сервисов в API 34 в AOSP нет — ужесточения идут по трём линиям:
`isAccessibilityTool` (31), restricted settings (33), data-sensitive (34). Формулировка «Android 14 запрещает
не-акселераторские сервисы» — миф, но проверка при публикации в Play стала строже (см. 2.6).

При переходе на `targetSdk 36` (обязателен для Play с 31.08.2026) добавляются общеплатформенные изменения,
которые задевают вёрстку пузыря и панели: edge-to-edge без opt-out, `onBackPressed` больше не вызывается
(только `OnBackPressedCallback`), ограничения ориентации и resizability игнорируются на экранах ≥ 600 dp
(актуально для планшета, на котором приложение и тестируется).

**Оценка: 12–16 чел.-дней**, из них: служба и конфиг — 1,5; обход дерева, эвристики, дедуп, лимиты — 5–7;
пузырь и панель — 2–3; вставка обратно — 1; онбординг и restricted settings — 1; прогон на 8–10 реальных
приложениях — 2.

### 2.2 Скриншот без MediaProjection: `AccessibilityService.takeScreenshot()`

Недооценённый вариант: та же служба доступности умеет снимать экран сама.

- `takeScreenshot(displayId, executor, callback)` — **API 30**; требует `android:canTakeScreenshot="true"`
  в конфиге службы и capability `CAPABILITY_CAN_TAKE_SCREENSHOT`.
- `takeScreenshotOfWindow(windowId, …)` — **API 34**: снимок одного окна, без чужих оверлеев и уведомлений.
- Отдаёт `HardwareBuffer` + `ColorSpace` → `Bitmap.wrapHardwareBuffer()`, буфер обязательно закрывать.
- Система ограничивает частоту: при слишком частых вызовах приходит `ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT`
  (константа есть в API), для защищённых окон — `ERROR_TAKE_SCREENSHOT_SECURE_WINDOW` (API 34).
- **Диалога согласия нет**, FGS не нужен, «красная рамка» и постоянный индикатор MediaProjection не появляются.

Дальше — существующий `OcrEngine.recognize(bitmap)`: китайская и латинская модели уже в APK, размер не растёт.
Ограничение то же, что и в проекте: **кириллицы нет**, русский экран не прочитается без Tesseract.

**Оценка: 4–6 чел.-дней** (снимок, выбор области, OCR, порядок блоков, склейка строк), +5–8 дней на Tesseract `rus`.

### 2.3 MediaProjection + скриншот + ML Kit OCR

**Плюс один:** работает там, где дерева нет (игры, Canvas, видео, Flutter), и снимает всё, что видно на экране.
**Минусов больше:**

- Диалог согласия — на **каждую** сессию: с Android 14 токен одноразовый, повторно использовать `Intent`
  нельзя, `getMediaProjection()` вызывается заново после каждого `stop()`.
- Обязателен foreground service с `foregroundServiceType="mediaProjection"`
  (разрешения `FOREGROUND_SERVICE` и `FOREGROUND_SERVICE_MEDIA_PROJECTION` — API 34) **до** вызова
  `getMediaProjection()`, плюс постоянное уведомление.
- `MediaProjection.Callback` нужно зарегистрировать **до** `createVirtualDisplay()`, иначе исключение.
- Одна активная проекция на приложение.
- Android 15 (API 35): частичный шаринг — `MediaProjectionConfig.createConfigForUserChoice()` и
  `createScreenCaptureIntent(config)` (API 34+); пользователь может выбрать одно приложение вместо всего экрана,
  и тогда координаты/область другие. Код должен поддерживать оба режима.
- Приватность: пользователь отдаёт весь экран, включая уведомления и чужие приложения; Play требует обосновать
  тип FGS при публикации.
- По производительности: снимок 100–300 мс + OCR 300–800 мс на кадр — это 1–2 fps, не «живые субтитры».

**Оценка: 8–12 чел.-дней** (FGS, согласие, `ImageReader`/`VirtualDisplay`, обработка `onStop`, оба режима 15),
+5–8 на Tesseract. Даёт то же, что 2.2, но дороже и с худшим UX — брать только если 2.2 упрётся в политику
или понадобится непрерывная запись экрана.

### 2.4 `ACTION_PROCESS_TEXT`: «Перевести» в меню выделения

Самый дешёвый и полностью легальный путь. Приложение объявляет activity:

```xml
<activity android:name=".ProcessTextActivity" android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.PROCESS_TEXT" />
        <category android:name="android.intent.category.DEFAULT" />
        <data android:mimeType="text/plain" />
    </intent-filter>
</activity>
```

Вызывающая сторона кладёт текст в `Intent.EXTRA_PROCESS_TEXT` (API 23) и флаг
`EXTRA_PROCESS_TEXT_READONLY`. Если `readonly = false`, приложение возвращает результат через
`setResult(RESULT_OK, intent.putExtra(EXTRA_PROCESS_TEXT, перевод))` — и **вызывающее приложение само
вставляет перевод вместо выделенного текста**. Это готовый сценарий «выделил → перевёл → заменилось на месте».

- Разрешений нет, спец. доступов нет, Play-политика не затрагивается.
- Работает в `EditText`, Chrome, Telegram, Gmail, большинстве нативных приложений.
- В Compose-полях стандартный `SelectionContainer` рисует собственную плавающую панель, и пункты
  `PROCESS_TEXT` в неё, как правило, не попадают — там нужна своя кнопка в кастомном toolbar.
- Обнаруживаемость низкая: пользователь должен догадаться выделить текст и открыть «⋮».

**Оценка: 3–4 чел.-дня** (activity + intent-filter + возврат результата — 1,5; переиспользование экрана
перевода — 1; прогон по 5 приложениям — 1).

### 2.5 Бонус: `ACTION_SEND` («Поделиться»)

Приём `text/plain` и `image/*` из меню «Поделиться» — 1–2 дня, переиспользует `translateText()` и
`OcrEngine.recognize()`. Закрывает сценарий «скриншот → поделиться → перевод», который на Android 12+
пользователи делают чаще, чем выделение текста. Делать вместе с 2.4.

### 2.6 Сравнение вариантов перевода с экрана

| Вариант | Точность | UX | Разрешения | Google Play | Чел.-дни |
|---|---|---|---|---|---|
| `ACTION_PROCESS_TEXT` | 100 % (текст отдаёт само приложение) | 3/5: два лишних тапа, не видно, что функция есть | нет | чисто | **3–4** |
| `ACTION_SEND` (текст/картинка) | 100 % / OCR-зависимая | 4/5: привычный «Поделиться» | нет | чисто | 1–2 |
| Accessibility, дерево узлов | 95–100 % на нативных, 0 % на Canvas/играх | 5/5: пузырь в один тап, работает поверх любого приложения | спец. возможность + restricted settings | **высокий риск**: декларация в Play Console, обоснование, заметное раскрытие (prominent disclosure) и согласие; переводчики-оверлеи регулярно отклоняют | **12–16** |
| Accessibility `takeScreenshot` + ML Kit | OCR: 90–98 % на ZH, 0 % на RU | 4/5: кнопка/пузырь, снимок без диалога | та же спец. возможность | тот же риск, что выше | **4–6** (+5–8 Tesseract RU) |
| MediaProjection + OCR | OCR: 90–98 % на ZH, 0 % на RU | 2/5: согласие каждый раз, уведомление, красная рамка | MediaProjection + FGS `mediaProjection` | средний риск, нужно обоснование FGS | **8–12** (+5–8 Tesseract) |
| Assist API (`VoiceInteractionService`, `ROLE_ASSISTANT`) | как у дерева узлов | 3/5: вызов ассистента, конфликт с Google | роль ассистента (забирает у Google) | чисто, но роль одна на устройство | 6–8 — **не рекомендуется** |

Комбинированная схема, которая даёт лучший результат за разумные деньги: **дерево узлов → при пустом
результате `takeScreenshot` + OCR**. Это +1–2 дня к 2.1 и снимает главную дыру (Canvas/WebView).

---

## 3. Клавиатура (IME)

### 3.1 Что требуется от собственной клавиатуры

Манифест + конфиг:

```xml
<service android:name=".ime.TrImeService"
         android:permission="android.permission.BIND_INPUT_METHOD"
         android:exported="true">
    <intent-filter><action android:name="android.view.InputMethod" /></intent-filter>
    <meta-data android:name="android.view.im" android:resource="@xml/method" />
</service>
```

`res/xml/method.xml` — подтипы (`android:imeSubtypeLocale="ru_RU"`, `"zh_CN"`), `settingsActivity`,
`supportsSwitchingToNextInputMethod`. Пользователь включает клавиатуру в Настройки → Языки и ввод и
переключается на неё; **программно назначить свою IME нельзя**. Для отладки:
`adb shell ime enable com.nya.dstranslate/.ime.TrImeService`, `adb shell ime set …`.

Минимально жизнеспособная клавиатура — это не «кнопки с буквами», а: раскладки RU/EN (+ZH), регистр и цифры,
предиктивный ввод и автокоррекция, словари на язык, жесты (свайп-набор — отдельный алгоритм и модель),
эмодзи, буфер обмена, темы, обработка `EditorInfo.inputType` (пароль, номер, email), отключение персонализации
в приватных полях, отсутствие утечек нажатий. Каждый пункт — недели.

### 3.2 Свой `InputMethodService` с нуля

Полноценная RU/ZH-клавиатура: пиньинь-конвертация (словарь + ранжирование кандидатов, ~50–100 тыс. записей),
предиктивная модель, свайп — это 40–80 чел.-дней и постоянная поддержка. Для проекта на одном разработчике —
тупик.

### 3.3 Форк открытой клавиатуры

- **HeliBoard** (GPLv3, форк OpenBoard, активный): готовые раскладки, словари, жесты, темы. RU есть,
  китайского ввода нет.
- **OpenBoard** (GPLv3) — то же, менее активный.
- **LeanType** — минималистичная база без словарей, удобно как скелет.
- **Trime / librime** (GPL3, Rime) — единственный зрелый открытый пиньинь-движок под Android;
  подключается как нативная библиотека (`librime` + `.yaml`-схемы), это +5–8 МБ и отдельная сборка NDK.

Реалистично: HeliBoard как основа (RU/EN/жесты/словари) + librime для ZH. **15–25 чел.-дней** интеграции,
плюс GPLv3-обязательства (исходники производной клавиатуры должны быть открыты) и регулярные merge upstream.
Для закрытого коммерческого продукта лицензия — блокер; для открытого проекта — нет.

### 3.4 Гибрид: минимальная «переводческая» клавиатура (рекомендуется)

Своя `InputMethodService` без претензий на повседневную клавиатуру: QWERTY RU/EN, панель перевода сверху,
кнопки «Перевести» и «Вставить». Пользователь держит её как **вторую** клавиатуру и переключается, когда нужно
написать по-китайски.

Ключевой сценарий, который не требует пиньиня: **печатаешь по-русски → «译» → в поле уходит китайский**
(`InputConnection.commitText(перевод, 1)`). Обратный сценарий: скопировал китайский текст (или вставил в поле
панели) → перевод на русский. Пиньинь-ввод при этом не нужен вообще — а он и есть самая дорогая часть.

Что обязательно учесть:

- `onStartInputView(EditorInfo, …)` → если `inputType` = пароль/`TYPE_TEXT_VARIATION_PASSWORD` — панель
  перевода отключить, историю не писать; `IME_FLAG_NO_PERSONALIZED_LEARNING` уважать.
- `EditorInfo.getInitialTextBeforeCursor/AfterCursor` (API 30), `getInitialSelectedText` (API 30) — чтобы
  переводить уже набранное, не переключаясь.
- `commitText(CharSequence, int, TextAttribute)` (API 33) — для стилей; на 26–32 — двухаргументная версия.
- `onUpdateEditorToolType` — **API 34** (не 33): про стилус, для перевода не нужен.
- Окно IME — `Dialog`; Compose в нём работает через `ComposeView`, но требует ручной установки
  `ViewTreeLifecycleOwner`/`SavedStateRegistryOwner` и аккуратности с `WindowInsets` — иначе падений
  «No LifecycleOwner» и дёрганой высоты. Для клавиатуры проще классические View + `KeyboardView`-подобная
  своя вёрстка (сам `KeyboardView` устарел с API 29).
- Высота: `onComputeInsets`, `onEvaluateFullscreenMode() = false`, `setExtractViewShown(false)`.

**Оценка: 8–12 чел.-дней**, из них: служба, конфиг, подтипы, переключение — 2; вёрстка клавиш RU/EN и панели — 3;
интеграция перевода и `commitText` — 1,5; приватность/пароли/настройки — 1; полировка и прогон в 6 приложениях
(Telegram, Chrome, WhatsApp, WeChat, Gmail, заметки) — 2–3.

### 3.5 «Панель поверх системной клавиатуры» — почему так не сделать

Соблазнительный обходной путь: не делать IME, а нарисовать свою панель над чужой клавиатурой. Технически окно
можно положить выше (`TYPE_APPLICATION_OVERLAY` = 2038, `TYPE_ACCESSIBILITY_OVERLAY` = 2032 против
`TYPE_INPUT_METHOD` = 2011), и пузырь над клавиатурой виден. Но:

- панель **без фокуса** может только показывать — вводить в неё нельзя;
- панель **с фокусом** забирает ввод, и системная клавиатура закрывается.

Итог: «показ перевода» над клавиатурой — можно (это и есть пузырь из 2.1, 3–5 дней на `SYSTEM_ALERT_WINDOW`-версию),
«набор» — только через собственную IME. Промежуточный вариант на 3–5 дней: пузырь, который берёт текст из буфера
обмена (`ClipboardManager.addPrimaryClipChangedListener`) и умеет вставлять результат через accessibility —
полезно, но не заменяет клавиатуру.

### 3.6 API, на которые опирается интеграция

| API | Уровень | Роль |
|---|---|---|
| `InputMethodService` (`onCreateInputView`, `onStartInputView`, `onFinishInput`) | 3 | каркас клавиатуры |
| `InputConnection.commitText` / `setComposingText` / `deleteSurroundingText` | 3 | вставка перевода и правка |
| `EditorInfo.hintLocales`, `getInitialTextBeforeCursor`, `getInitialSelectedText` | 24 / 30 | что уже набрано |
| `InputMethodService.onUpdateEditorToolType` | **34** | стилус (не про перевод) |
| `InputMethodManager.showSoftInput` | 3 | показать клавиатуру (только фокус, не выбор IME) |
| `ClipboardManager.addPrimaryClipChangedListener` / `getPrimaryClip` | 11 | IME читает буфер и в фоне; обычным приложениям с Android 10 это запрещено |
| `RoleManager.ROLE_ASSISTANT` | 29 | «стать ассистентом» (не рекомендуется) |
| `VoiceInteractionSession.onHandleAssist(AssistState)` / `AssistStructure` | 29 / 23 | одноразовое чтение экрана из Assist API |
| `TextLanguage` (`TextClassifier.detectLanguage`) | 29 | определение языка для выбора направления, офлайн |

Про `ClipboardOverlay`: **публичного API с таким именем нет.** Превью буфера обмена в Android 13 — системный
UI (`ClipboardOverlayController`), в него нельзя добавить свой пункт. Единственное, что доступно приложению, —
слушатель `OnPrimaryClipChangedListener`, и он не срабатывает, когда приложение в фоне (исключение — IME).
Именно это, а не «оверлей», и есть настоящий аргумент в пользу клавиатуры.

Про `TextClassifier`: переводить он не умеет — только разметку сущностей и язык (`TextLanguage`, API 29).
Для выбора направления перевода этого достаточно, если не хочется гонять определение через модель.

`RemoteInput`/Direct Reply касаются ответа из уведомлений; для перевода полезны маргинально (ответ в мессенджере
без открытия приложения) — 2–3 дня, низкий приоритет.

### 3.7 Сравнение вариантов клавиатуры

| Вариант | UX | Усилия | Ограничения | Play-политика |
|---|---|---|---|---|
| Полная своя RU/ZH-клавиатура | 5/5, если доделана | **40–80** | нужен словарь, пиньинь, предиктив, свайп; годы поддержки | клавиатуры проверяются строже: декларация сбора данных, запрет скрытого логирования нажатий |
| Форк HeliBoard + librime | 5/5 | **15–25** | GPLv3 на производную; native-сборка; merge upstream | «это чужая клавиатура» — меньше вопросов, но нужен fork-репозиторий и публикация исходников |
| Минимальная переводческая IME | 3/5: не повседневная, но закрывает «написать по-китайски» | **8–12** | нет словаря и предиктива — как обычная клавиатура плоха; пользователь переключается вручную | средняя: обязательна декларация, что нажатия не собираются |
| Пузырь над системной клавиатурой (показ) | 3/5 | **3–5** | ввод невозможен, только показ/вставка | низкая |
| Базовая линия: `ACTION_PROCESS_TEXT` вместо клавиатуры | 2/5 | 3–4 | набор не закрывает, только перевод готового текста | нет |

Главный риск клавиатуры — не код, а поведение: **пользователи не меняют клавиатуру**. Даже хорошая IME
остаётся включённой у 5–15 % установивших. Поэтому IME имеет смысл только как «вторая клавиатура для перевода»
с честным онбордингом, а не как замена Gboard.

---

## 4. Рекомендованная последовательность внедрения

### Шаг 1 — «Перевести» в меню выделения и «Поделиться» (3–4 дня)

- `ProcessTextActivity` с `ACTION_PROCESS_TEXT` (`text/plain`), возврат результата при `readonly = false`.
- Приём `ACTION_SEND` для `text/plain` и `image/*` (переиспользовать `translateText()` и `OcrEngine`).
- Переиспользование экрана «Текст»: вход в него с предзаполненным текстом и автозапуском перевода.
- Критерий готовности: в Chrome и Telegram выделение текста → «Перевести» → перевод заменил выделение;
  из галереи «Поделиться» картинкой → распознанный китайский текст → перевод.

Почему первым: нулевые разрешения, нулевой риск, сразу закрывает «перевести кусок текста в чужом приложении».
Это же — единственный шаг, который не ломается от обновлений Android.

### Шаг 2 — Перевод экрана: служба доступности + пузырь (+ OCR-фолбэк) (12–16 дней)

- `AccessibilityService` c `canRetrieveWindowContent`, `FLAG_REPORT_VIEW_IDS`, `canTakeScreenshot = true`.
- Онбординг: экран с объяснением, deep-link в `ACTION_ACCESSIBILITY_SETTINGS`, отдельная инструкция про
  «Разрешить ограниченные настройки» (Android 13+, критично для APK с GitHub).
- Сбор текста: обход дерева с debounce, дедуп, лимиты (≤ 1500 узлов, ≤ 4000 символов), фильтры
  `isPassword()` / `isAccessibilityDataSensitive()`.
- Пузырь `TYPE_ACCESSIBILITY_OVERLAY` + карточка перевода + «Копировать» / «Вставить» / «Озвучить».
- Фолбэк: если текста в дереве нет — `takeScreenshot()` (API 30+) → `OcrEngine.recognize()`.
- Критерий готовности: в WeChat, Alipay, Chrome и картах пузырь переводит экран за ≤ 2 с; на экране с паролем
  перевод не запускается.

### Шаг 3 — Переводческая клавиатура (8–12 дней)

- Своя `InputMethodService`, подтипы `ru_RU` и `zh_CN`, QWERTY + панель перевода.
- `commitText` перевода в поле; обратный сценарий — из буфера или из набранного
  (`getInitialTextBeforeCursor`).
- Пароли и приватные поля — панель отключена, история не пишется.
- Критерий готовности: в Telegram набранный по-русски текст одной кнопкой уходит по-китайски; в поле пароля
  панель не показывается.

### Что не делать (по крайней мере, сейчас)

- **MediaProjection** — дороже (8–12) и хуже по UX, чем `takeScreenshot` (4–6); нужен только для непрерывной
  записи экрана.
- **Полная клавиатура с пиньинем** — 40–80 дней, не окупается.
- **Assist API** — забирает роль ассистента у Google, пользователь не пойдёт на это ради перевода.
- **Tesseract для русского OCR** — только если появится запрос на перевод русских экранов; +15–25 МБ к APK
  (сейчас 32,6 МБ) и +5–8 дней.

**Итого:** 3–4 (шаг 1) + 12–16 (шаг 2) + 8–12 (шаг 3) = **23–32 чел.-дня**; первые 3–4 дня дают рабочую
функцию, 12–16 — основную ценность. Оценки — без Tesseract `rus` (+5–8) и без MediaProjection (+8–12).

---

## 5. Сводная таблица всех вариантов

| # | Вариант | Эффект для пользователя | Усилия, чел.-дни | Риск | Play-политика | Разрешения / доступы |
|---|---|---|---|---|---|---|
| 1 | `ACTION_PROCESS_TEXT` | перевести выделенный текст на месте | 3–4 | низкий: зависит от вёрстки чужого меню | нет ограничений | нет |
| 2 | `ACTION_SEND` (текст, картинка) | «Поделиться» → перевод | 1–2 | низкий | нет | нет |
| 3 | Accessibility: дерево узлов + пузырь | перевести любой экран, вставить обратно | 12–16 | высокий: хрупкие эвристики, ANR, приватность | высокий: декларация, обоснование, отклонения | спец. возможность; restricted settings на 13+ |
| 4 | Accessibility `takeScreenshot` + ML Kit | снимок экрана без диалога → перевод | 4–6 | средний: частота снимков, `FLAG_SECURE` | как п. 3 | та же спец. возможность |
| 5 | MediaProjection + OCR | всё, что видно, включая игры/видео | 8–12 | средний: жизненный цикл, согласие каждый раз | средний: обоснование FGS `mediaProjection` | MediaProjection + FGS + уведомление |
| 6 | Assist API (роль ассистента) | чтение экрана по вызову ассистента | 6–8 | высокий: конфликт с Google, одна роль | чисто | роль `ROLE_ASSISTANT` |
| 7 | Полная своя IME RU/ZH | замена клавиатуры | 40–80 | очень высокий | строже: декларация, запрет логирования | `BIND_INPUT_METHOD` |
| 8 | Форк HeliBoard + librime | полноценная RU/ZH-клавиатура | 15–25 | средний: GPLv3, native, upstream | средний | `BIND_INPUT_METHOD` |
| 9 | Минимальная переводческая IME | «написал по-русски — ушло по-китайски» | 8–12 | средний: пользователи не меняют клавиатуру | средний: декларация | `BIND_INPUT_METHOD` |
| 10 | Пузырь по буферу обмена | быстрый перевод скопированного | 3–5 | низкий | низкий | `SYSTEM_ALERT_WINDOW` (или overlay из п. 3) |
| 11 | Tesseract `rus` (надстройка к 4/5) | русский текст с экрана и фото | 5–8 | низкий, но +15–25 МБ APK | нет | нет |

---

## Источники и как проверялось

Официальная документация Android:

- [Create your own accessibility service](https://developer.android.com/guide/topics/ui/accessibility/service)
- [`AccessibilityService`](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService),
  [`AccessibilityServiceInfo`](https://developer.android.com/reference/android/accessibilityservice/AccessibilityServiceInfo),
  [`AccessibilityNodeInfo`](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo)
- [Media projection](https://developer.android.com/media/grow/media-projection),
  [`MediaProjectionManager`](https://developer.android.com/reference/android/media/projection/MediaProjectionManager),
  [`MediaProjectionConfig`](https://developer.android.com/reference/android/media/projection/MediaProjectionConfig)
- [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Behavior changes: all apps (Android 14)](https://developer.android.com/about/versions/14/behavior-changes-all),
  [Android 15](https://developer.android.com/about/versions/15/behavior-changes-all),
  [Android 16](https://developer.android.com/about/versions/16/behavior-changes-all),
  [Android 16, apps targeting 36](https://developer.android.com/about/versions/16/behavior-changes-16)
- [`Intent.ACTION_PROCESS_TEXT`](https://developer.android.com/reference/android/content/Intent#ACTION_PROCESS_TEXT)
- [Create an input method](https://developer.android.com/develop/ui/views/touch-and-input/creating-input-method),
  [`InputMethodService`](https://developer.android.com/reference/android/inputmethodservice/InputMethodService),
  [`InputConnection`](https://developer.android.com/reference/android/view/inputmethod/InputConnection),
  [`EditorInfo`](https://developer.android.com/reference/android/view/inputmethod/EditorInfo)
- [`RoleManager`](https://developer.android.com/reference/android/app/role/RoleManager),
  [`VoiceInteractionService`](https://developer.android.com/reference/android/service/voice/VoiceInteractionService),
  [`AssistStructure`](https://developer.android.com/reference/android/app/assist/AssistStructure)
- [Use of the AccessibilityService API (Google Play)](https://support.google.com/googleplay/android-developer/answer/10964491)
- [Target API level requirements for Google Play apps](https://support.google.com/googleplay/android-developer/answer/11926878)

Что проверено фактически:

- **Уровни API** (методы, поля, `since`) — по локальному `D:\Android\Sdk\platforms\android-35\data\api-versions.xml`.
  В частности: `takeScreenshot` — 30, `takeScreenshotOfWindow` — 34, `isAccessibilityDataSensitive` — 34,
  `isAccessibilityTool` — 31, `onUpdateEditorToolType` — **34** (не 33), `getInitialSelectedText` — 30,
  `commitText(…, TextAttribute)` — 33, `TYPE_ACCESSIBILITY_OVERLAY` — 22, `FOREGROUND_SERVICE_MEDIA_PROJECTION` — 34,
  `ACTION_PROCESS_TEXT` — 23, `TextLanguage` — 29, `ROLE_ASSISTANT` — 29.
- **Ограничения версий** — по разделам behavior changes для 14/15/16 и гайду Media projection.
- **Требования Google Play** — по странице политики AccessibilityService и странице target API (для новых
  релизов в Play требуется `targetSdk 36` с 31.08.2026; проект сейчас на 35 и в Play не публикуется).

Расхождение с распространёнными формулировками, которое стоит учитывать в оценке:

- «Android 14 запрещает не-акселераторские сервисы» — такого запрета нет; есть `isAccessibilityTool` (31),
  restricted settings для сайдлоада (33) и data-sensitive узлы (34).
- «ClipboardOverlay» — не публичный API, а системный UI Android 13; приложению доступен только слушатель буфера,
  и в фоне — лишь IME.
- «Панель поверх системной клавиатуры» — работает для показа (пузырь), не работает для набора: фокус закрывает IME.
- ML Kit не читает кириллицу, поэтому «перевод экрана» по умолчанию односторонний (ZH → RU), пока не добавлен
  Tesseract `rus`.

Замечание по среде: страницы `developer.android.com` в этой сессии отдавались без тела статьи (извлекалась только
навигация), поэтому поведенческие утверждения сверялись по reference/API-уровням из локального SDK и по
официальным разделам behavior changes; перед реализацией шага 2 имеет смысл открыть в браузере
`/about/versions/14/behavior-changes-all` и `/media/grow/media-projection` и сверить формулировки ещё раз.
