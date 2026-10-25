# Выпуск обновления

**Правило по умолчанию:** готовое приложение обновляется сразу после публикации
релиза. `update.json` в `main` поднимается в том же выпуске на новый
`versionCode`; приложение проверяет манифест при запуске и сразу предлагает
обновление. Откладывать или выдавать обновление частями можно только при явной
оговорке «не выкладывать / позже / поэтапно».

Почтовой рассылки у проекта нет — уведомление о новой версии приходит внутри
приложения (диалог «Доступна версия …»). Поэтому «сразу обновлялось» = сразу
опубликованный релиз + сразу поднятый `update.json`.

## 1. Сборка

```powershell
$ws = "C:\Users\user\Documents\deepseek-harness\default-workspace\DeepSeekTranslate"
$env:TMP = "$ws\.tooling\tmp"; $env:TEMP = $env:TMP
$env:GRADLE_OPTS = "-Djava.io.tmpdir=$ws\.tooling\tmp"
$env:ANDROID_HOME = "D:\Android\Sdk"
$env:JAVA_HOME = "$ws\.tooling\jdk17\jdk-17.0.2"
$env:GRADLE_USER_HOME = "$ws\.tooling\gradle-home"
Set-Location $ws; .\gradlew.bat assembleRelease
```

Версия задаётся в `app/build.gradle.kts` (`versionCode`/`versionName`).
APK копируется в `dist\DeepSeekTranslate-<версия>.apk` (и в `dist\web\`).

## 2. Манифест

`update.json` в корне: `versionCode`, `versionName`, `apkUrl`
(`.../releases/latest/download/DeepSeekTranslate-<версия>.apk`), `notes`.
`notes` читает пользователь в диалоге обновления — это только новости версии.
Регламент выпуска («обновляется сразу, если не указано иного») в `notes` не
писать: он живёт в `STATE.md` и здесь, в инструкции для публикации.
`versionCode` обязан совпадать с `app/build.gradle.kts`, иначе `release.mjs`
остановится.

## 3. Публикация

```powershell
$node = "C:\Users\user\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\node\bin\node.exe"
$env:GITHUB_TOKEN = [Environment]::GetEnvironmentVariable("GITHUB_TOKEN","User")
Set-Location DeepSeekTranslate
& $node tools\release.mjs
```

Скрипт заливает исходники и `update.json` в `main`, создаёт тег/релиз,
прикрепляет APK и проверяет размер и SHA-256 ассета.

Если GitHub блокирует `ApiConfig.kt` («Secret detected in content»):

1. скрипт печатает `placeholder_id` и останавливается;
2. `& $node tools\allow-secret.mjs <placeholder_id>`;
3. снова `& $node tools\release.mjs`.

## 4. Проверка на устройстве

**Настройки → Проверить обновление** (или перезапуск приложения) — должен
появиться диалог с новой версией и кнопкой скачивания. Обновление встаёт поверх
только при совпадающей подписи (релизный ключ `51:C4:09:…:26:BB`).

| Сообщение | Что значит |
|---|---|
| «по этому адресу отдаётся HTML-страница, а не JSON» | `apkUrl`/адрес манифеста ведёт не на raw |
| «сервер ответил HTTP 404» | релиз с APK ещё не опубликован или имя файла не совпало |
| «в файле обновлений нет поля versionCode» | в `update.json` потерялось поле версии |
