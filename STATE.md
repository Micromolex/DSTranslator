# Состояние проекта (кратко)

**Приложение:** Android, перевод RU ⇄ 中文 (путунхуа) через DeepSeek.
**Текущая версия:** 1.5.5 (versionCode 15), подписана релизным ключом.
**Репозиторий:** https://github.com/Micromolex/DSTranslator — релиз **v1.5.5 опубликован**, APK внутри.
**Расположение:** `DeepSeekTranslate/`. Локально git не установлен — коммиты и релиз идут через GitHub API.

## Вкладки
- **Текст** — перевод, автосмена направления, история с удалением, кнопки «Галерея» и «Камера» (OCR на устройстве).
- **Диалог** — два экрана, верхний перевёрнут, перевод показывается на половине собеседника.
- **Авто** — непрерывная запись, язык определяется по маркерам модели `[[!]]` / `[[SKIP]]`, чипы «Авто / только RU / только ZH».
- **Настройки** — язык интерфейса, обновления, тумблеры.

## Ключевые файлы
| Файл | Что внутри |
|---|---|
| `app/src/main/java/com/nya/dstranslate/MainViewModel.kt` | вся логика |
| `.../speech/SpeechController.kt` | распознавание, TTS, глушение «пиликанья» |
| `.../data/DeepSeekClient.kt` | SSE-стриминг |
| `.../data/Prompts.kt` | промпты и маркеры, `LangDetect` |
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
OpenJDK 17 (`.tooling/jdk17/jdk-17.0.2/`), а `%TEMP%` в этой среде недоступен для записи —
временные папки тоже уводим в проект:

```powershell
$ws = "C:\Users\user\Documents\deepseek-harness\default-workspace\DeepSeekTranslate"
$env:TMP = "$ws\.tooling\tmp"; $env:TEMP = $env:TMP
$env:GRADLE_OPTS = "-Djava.io.tmpdir=$ws\.tooling\tmp"
$env:ANDROID_HOME = "D:\Android\Sdk"
$env:JAVA_HOME = "$ws\.tooling\jdk17\jdk-17.0.2"
$env:GRADLE_USER_HOME = "$ws\.tooling\gradle-home"
Set-Location $ws; .\gradlew.bat assembleRelease
```

Результат: `app\build\outputs\apk\release\app-release.apk` → копия в `dist\DeepSeekTranslate-1.5.5.apk`,
SHA-256 `DC9218AA01D0F709F95336476890269E61E24294C62ACCD226FB69B59CEEFF55`.
Ключ: `keystore/deepseek-translate.jks`, пароли в `keystore.properties`.

## Действия через GitHub API (git локально нет)
Токен — пользовательская переменная `GITHUB_TOKEN` (права `repo` + `workflow`).
Коммит файла: `PUT /repos/Micromolex/DSTranslator/contents/<путь>` с `base64`-содержимым и `sha`
для существующих файлов. Уже закоммичено: `update.json`, `PUBLISH.md`, `docs/UPDATES.md`,
`.github/workflows/release.yml`, `.../UpdateChecker.kt`, `app/build.gradle.kts` (бамп до 1.5.5).

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

## Незакрытые задачи
- Проверить на планшете: Настройки → «Проверить обновление» → установка 1.5.5.
- Удалить пробную ветку `ci-probe` и `ci-probe-note.txt`.
- Добавить секреты GitHub Actions для подписанной CI-сборки (см. `docs/UPDATES.md`, вариант B).
- `README.md` и `STATE.md` пока локальные (в репозиторий не коммичены).
- Возможен релей «комната на несколько переводчиков» (Cloudflare Workers + Durable Objects).
- Плагин `dsh-github-connect` не поставлен: собран под dsh 0.1.0-rc.6, у нас 0.2.0-rc.2;
  ставить можно только с исключением версии. Профиль `web` создан, но не используется —
  рабочий GUI крутит профиль `desktop`.
