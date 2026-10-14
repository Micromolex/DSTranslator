# Состояние проекта (кратко)

**Приложение:** Android, перевод RU ⇄ 中文 (путунхуа) через DeepSeek.
**Текущая версия:** 1.6.0 (versionCode 16), подписана релизным ключом. **Локально собрана, в GitHub ещё не выпущена.**
**Предыдущий релиз:** 1.5.5 (versionCode 15) — опубликован, APK внутри.
**Репозиторий:** https://github.com/Micromolex/DSTranslator
**Расположение:** `DeepSeekTranslate/`. Локально git не установлен (`git.exe` есть в `D:\Android\Git\cmd\git.exe`,
но репозиторий не инициализирован) — коммиты и релиз идут через GitHub API.

## Вкладки
- **Текст** — перевод, автосмена направления, история с удалением, кнопки «Галерея» и «Камера» (OCR на устройстве).
- **Диалог** — два экрана, верхний перевёрнут, перевод показывается на половине собеседника.
- **Авто** — непрерывная запись, язык определяется по маркерам модели `[[!]]` / `[[SKIP]]`, чипы «Авто / только RU / только ZH».
- **Настройки** — язык интерфейса, обновления, тумблеры, «О приложении / Правовая информация».

## Правовая защита (v1.6.0)
- Экран настроек **«О приложении / Правовая информация»** — `ui/LegalScreen.kt`,
  открывается нажатием на карточку в конце настроек (локальный флаг `showLegal` в `App.kt`,
  системная «Назад» возвращает в настройки).
- **Гейт первого запуска** — `MainActivity.kt`, `LegalConsentGate`: пока согласие не дано,
  `AppRoot` не создаётся вообще. Закрытие диалога (в т.ч. системной «Назад») → предупреждение
  с выбором «Я согласен» / «Закрыть приложение» (`finishAndRemoveTask()`).
- Флаг согласия: `Prefs.legalAccepted` → `SharedPreferences("ds_translate")`, ключ `legal_accepted`.
- Текст в одном месте — `Legal.text()` (4 строки: копирайт, оговорка про ИИ и as is, отказ от
  ответственности, `t.me/micromolex`); и экран, и диалог рендерят его одним `Text` с переносами строк.
- Строки интерфейса RU/ZH — в `ui/Strings.kt` (`settingsAbout*`, `legal*`).
- Раздел **«Правовая информация»** добавлен в `README.md`.

## Ключевые файлы
| Файл | Что внутри |
|---|---|
| `app/src/main/java/com/nya/dstranslate/MainActivity.kt` | гейт согласия + запуск UI |
| `.../MainViewModel.kt` | вся логика |
| `.../ui/LegalScreen.kt` | `Legal.text()` и экран правовой информации |
| `.../speech/SpeechController.kt` | распознавание, TTS, глушение «пиликанья» |
| `.../data/DeepSeekClient.kt` | SSE-стриминг |
| `.../data/Prompts.kt` | промпты и маркеры, `LangDetect` |
| `.../data/Prefs.kt` | все настройки, `legalAccepted` |
| `.../data/OcrEngine.kt` | ML Kit, китайский + латиница (кириллицы НЕТ) |
| `.../data/UpdateChecker.kt` | проверка и скачивание обновлений, `DEFAULT_URL` |
| `.../ui/Strings.kt` | все строки RU/ZH |
| `update.json` | манифест обновления (корень репозитория) |
| `.github/workflows/release.yml` | CI: сборка релиза по тегу |
| `docs/UPDATES.md` | как выпускать обновления через GitHub |
| `PUBLISH.md` | выпуск обновления за 3 шага |

## Обновления — работает end-to-end
- `UpdateChecker.DEFAULT_URL` = `https://raw.githubusercontent.com/Micromolex/DSTranslator/main/update.json`
  (именно **raw**: ссылка `/blob/` отдаёт HTML, приложение про это честно пишет).
- `apkUrl` ведёт на GitHub Releases: `.../releases/latest/download/DeepSeekTranslate-<версия>.apk`.
- Понимаются обе схемы манифеста: `versionCode/versionName/apkUrl` и краткая `latest_version/download_url`
  (во второй `versionCode` считается как major×10000 + minor×100 + patch).

Опубликованный релиз **v1.5.5** (проверено):
- ассет `DeepSeekTranslate-1.5.5.apk`, 32 635 162 Б, digest по API GitHub — `sha256:dc9218aa...eff55`,
  совпал с локальной сборкой байт в байт;
- манифест в репозитории: `versionCode 15`, `versionName "1.5.5"`;
- ссылка из `apkUrl` отвечает `302 → 302 → CDN`, `HTTP 206`, тип `application/vnd.android.package-archive`;
- описание релиза заполнено (что нового, versionCode, SHA-256, предупреждение о подписи).

## Сборка — только JDK 17
AGP 8.11 не запускается на Java 25 из Android Studio, поэтому в проекте лежит портативный
OpenJDK 17 (`.tooling/jdk17/jdk-17.0.2/`), а `%TEMP%` в этой среде уводим в проект —
Kotlin-демон не может писать в `C:\Users\user\AppData\Local\kotlin\daemon` (AccessDenied),
поэтому компиляция идёт фолбэком «Compile without Kotlin daemon» — это нормально, сборка проходит:

```powershell
$ws = "C:\Users\user\Documents\deepseek-harness\default-workspace\DeepSeekTranslate"
$env:TMP = "$ws\.tooling\tmp"; $env:TEMP = $env:TMP
$env:GRADLE_OPTS = "-Djava.io.tmpdir=$ws\.tooling\tmp"
$env:ANDROID_HOME = "D:\Android\Sdk"
$env:JAVA_HOME = "$ws\.tooling\jdk17\jdk-17.0.2"
$env:GRADLE_USER_HOME = "$ws\.tooling\gradle-home"
Set-Location $ws; .\gradlew.bat assembleRelease
```

Результат v1.6.0: `app\build\outputs\apk\release\app-release.apk` → копия в `dist\DeepSeekTranslate-1.6.0.apk`
(и в `dist\web\` для локальной раздачи), 32 635 166 Б,
SHA-256 `4DD853E78FC14FEF72B1BEC5227EE12E53C201194732CB172931090A5FDCE3E5`.
Подпись проверена `apksigner verify --print-certs`: схема v2, один подписант, SHA-256 сертификата
`51:C4:09:…:26:BB` — тот же релизный ключ, что у 1.5.5, значит встанет поверх.
Ключ: `keystore/deepseek-translate.jks`, пароли в `keystore.properties`.

**Правка файлов с кириллицей:** PowerShell 5.1 `Get-Content`/`Set-Content` без `-Encoding utf8`
портит UTF-8 и добавляет BOM (так был испорчен `App.kt` и перезаписан заново). Для текстовых
правок использовать инструмент `edit`, а не конвейер PowerShell.

## Действия через GitHub API (git локально нет)
Токен — пользовательская переменная `GITHUB_TOKEN` (права `repo` + `workflow`).
Коммит файла: `PUT /repos/Micromolex/DSTranslator/contents/<путь>` с `base64`-содержимым и `sha`
для существующих файлов. Уже закоммичено: `update.json`, `PUBLISH.md`, `docs/UPDATES.md`,
`.github/workflows/release.yml`, `.../UpdateChecker.kt`, `app/build.gradle.kts` (бамп до 1.5.5).
Для v1.6.0 через API нужно закоммитить: `MainActivity.kt`, `ui/App.kt`, `ui/SettingsScreen.kt`,
`ui/LegalScreen.kt` (новый), `ui/Strings.kt`, `data/Prefs.kt`, `app/build.gradle.kts`, `update.json`.

## CI (release.yml) — состояние честно
- Триггер — **только теги** `v*` (проверено: пуш в ветку рана не запускает).
- Три рана со статусом `failure` в логе — артефакт загрузки самого workflow-файла: GitHub
  создаёт ран на коммит с новым/изменённым workflow и завершает его без job'ов. Реальный тег
  с этим файлом ещё не приходил, поэтому настоящей сборки в CI не было.
- Actions в репозитории работают: минимальный workflow в ветке `ci-probe` завершился `success`.
- Секретов для подписи (`ANDROID_KEYSTORE_B64`, `ANDROID_STORE_PASSWORD`, `ANDROID_KEY_ALIAS`,
  `ANDROID_KEY_PASSWORD`) в репозитории нет — без них CI-сборка будет отладочной (не встанет поверх).
- Ветку `ci-probe` и файл `ci-probe-note.txt` надо удалить.

## Известные ограничения
1. OCR не читает русский печатный текст (у ML Kit нет кириллической модели).
2. Автоопределение языка распознавателем не поддерживается на этом планшете — работает через маркеры модели.
3. Туннель cloudflared с этого компьютера не работает (530) — протестировано.
4. Большие загрузки с CDN GitHub **на этом компьютере** рвутся (`curl: (56) Recv failure` на 10–16 МБ),
   хотя диапазонные запросы идут по ~1 МБ/0.7 с. Целостность ассета подтверждена digest'ом по API,
   но если планшет в той же сети — держать в уме запасной канал (Cloudflare Pages).
5. Клиентская установка APK на планшет **ещё не проверена** — устройства под рукой нет.
6. Эмулятор для проверки UI не годится: APK собран только под ARM (`arm64-v8a`, `armeabi-v7a`
   из-за нативных библиотек ML Kit), а AVD `Pixel7_API35` в `D:\Android\avd` — x86_64.
   Согласие при первом запуске проверено статически: сборка проходит, строки
   (`Я согласен`, `legal_accepted`, `Aleksandr Zakandykin`) присутствуют в `classes2.dex` собранного APK.

## Незакрытые задачи
- **Выпустить 1.6.0 в GitHub** (тег `v1.6.0` + релиз с `DeepSeekTranslate-1.6.0.apk`; `update.json`
  уже указывает на 1.6.0, пока релиза нет — `apkUrl` не отвечает). Перед этим закоммитить файлы v1.6.0.
- Проверить на планшете: первый запуск → диалог согласия → «Я согласен» → перезапуск (диалога быть не должно).
- Проверить на планшете: Настройки → «О приложении / Правовая информация» (текст + возврат «Назад»).
- Проверить на планшете: обновление 1.5.5 → 1.6.0 из настроек.
- Удалить пробную ветку `ci-probe` и `ci-probe-note.txt`.
- Добавить секреты GitHub Actions для подписанной CI-сборки (см. `docs/UPDATES.md`, вариант B).
- `README.md` и `STATE.md` пока локальные (в репозиторий не коммичены).
- Возможен релей «комната на несколько переводчиков» (Cloudflare Workers + Durable Objects).
- Плагин `dsh-github-connect` не поставлен: собран под dsh 0.1.0-rc.6, у нас 0.2.0-rc.2;
  ставить можно только с исключением версии. Профиль `web` создан, но не используется —
  рабочий GUI крутит профиль `desktop`.
