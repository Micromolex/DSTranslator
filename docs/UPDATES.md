# Обновления через GitHub

Приложение при запуске (и по кнопке «Проверить обновление» в настройках)
запрашивает JSON-манифест, сравнивает `versionCode` со своей сборкой и предлагает
скачать новый APK.

Манифест и файлы живут в репозитории: <https://github.com/Micromolex/DSTranslator>

| Что | Где | Адрес |
|---|---|---|
| Манифест | корень репозитория, `update.json` | `https://raw.githubusercontent.com/Micromolex/DSTranslator/main/update.json` |
| APK | GitHub Releases | `https://github.com/Micromolex/DSTranslator/releases/latest/download/DeepSeekTranslate-<версия>.apk` |

Адрес манифеста вшит в приложение — `UpdateChecker.DEFAULT_URL`
(`app/src/main/java/com/nya/dstranslate/data/UpdateChecker.kt`). Вводить ничего не надо.

## Важно: raw, а не blob

Ссылка `github.com/.../blob/.../update.json` открывает **HTML-страницу** GitHub, а не
файл. Приложение это распознаёт и пишет в ошибке, что вместо JSON пришёл HTML.

```
https://github.com/Micromolex/DSTranslator/blob/main/update.json           -> HTML (не годится)
https://raw.githubusercontent.com/Micromolex/DSTranslator/main/update.json -> JSON (то, что нужно)
```

Проверка вручную:

```powershell
curl.exe -sSL https://raw.githubusercontent.com/Micromolex/DSTranslator/main/update.json
```

## Формат `update.json`

```json
{
  "versionCode": 16,
  "versionName": "1.6.0",
  "apkUrl": "https://github.com/Micromolex/DSTranslator/releases/latest/download/DeepSeekTranslate-1.6.0.apk",
  "notes": "Что нового",
  "minVersionCode": 0
}
```

| Поле | Смысл |
|---|---|
| `versionCode` | Целое. **Должно быть больше** `versionCode` установленной сборки, иначе обновление не предложится |
| `versionName` | Строка для показа пользователю |
| `apkUrl` | Прямая HTTPS-ссылка на APK (GitHub Releases или любой другой хостинг) |
| `notes` | Текст «что нового» |
| `minVersionCode` | Необязательно, зарезервировано под принудительные обновления |

Поддерживается и укороченная схема `{"latest_version": "1.5.5", "download_url": "..."}` —
`versionCode` в этом случае считается из номера версии (1.5.5 → 10505).

### Нумерация версий — важно не перепутать

`versionCode` — это **счётчик сборок** (14, 15, 16, …), а не номер версии.
Приложение сравнивает именно его, поэтому **в манифесте всегда указывай то же
`versionCode`, что в `app/build.gradle.kts`**.

Не ставь в `versionCode` «красивый» номер вида 10505: тогда приложение будет
считать себя новее любого обычного обновления 16, 17, 18 и перестанет видеть новые
версии. Схема `latest_version` считается по формуле `major×10000 + minor×100 + patch`
и предназначена только для чужих/упрощённых манифестов.

## Выпуск новой версии — вариант A, вручную

Репозиторий обновляется через GitHub API: локально git не инициализирован, а `curl`
в этой среде падает на TLS. Готовый скрипт — `tools/release.mjs` (Node.js, свой TLS):

```powershell
$node = "C:\Users\user\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\node\bin\node.exe"
$env:GITHUB_TOKEN = [Environment]::GetEnvironmentVariable("GITHUB_TOKEN","User")
node tools\release.mjs
```

Он сам заливает файлы версии в `main`, создаёт релиз на теге, прикрепляет
`dist\DeepSeekTranslate-<версия>.apk` и проверяет размер и sha256 ассета.

### Secret scanning: `ApiConfig.kt`

GitHub не даёт записать в репозиторий файл с ключом DeepSeek и отвечает
`422 Secret detected in content` (ключ при этом и так лежит внутри APK). Порядок:

```powershell
node tools\release.mjs                      # печатает placeholder_id и останавливается
node tools\allow-secret.mjs <placeholder_id>  # разрешает ровно эту запись
node tools\release.mjs                      # тот же blob проходит
```

Разрешение одноразовое и живёт ограниченное время — шаги нужно делать подряд.

Вручную то же самое:

1. Подними версию в `app/build.gradle.kts`:

   ```kotlin
   versionCode = 16          // обязательно +1 к предыдущей
   versionName = "1.6.0"
   ```

2. Собери APK (нужен **JDK 17**, см. «Сборка» в `STATE.md`):

   ```powershell
   .\gradlew.bat assembleRelease
   ```

   Готовый файл: `app\build\outputs\apk\release\app-release.apk`.

3. Переименуй его в `DeepSeekTranslate-1.6.0.apk` и положи в `dist\`.

4. На GitHub: **Releases → Draft a new release** → тег `v1.6.0` → прикрепи туда
   этот APK → **Publish release**.

5. Открой `update.json` в репозитории (**карандаш → Commit changes**) и поправь
   `versionCode`, `versionName`, имя файла в `apkUrl` и `notes`.

6. Через минуту-другую приложение на планшете при запуске само предложит обновление.

Ссылка `.../releases/latest/download/<имя файла>` всегда ведёт на последний релиз,
поэтому `apkUrl` не устаревает — но **имя файла в `apkUrl` должно точно совпадать
с именем прикреплённого APK**.

## Выпуск новой версии — вариант B, автоматически (GitHub Actions)

В репозитории лежит `.github/workflows/release.yml`. Он собирает APK по тегу и сам
прикрепляет его к релизу. Один раз надо добавить секреты
(**Settings → Secrets and variables → Actions**):

| Секрет | Значение |
|---|---|
| `ANDROID_KEYSTORE_B64` | `keystore/deepseek-translate.jks` в base64 |
| `ANDROID_STORE_PASSWORD` | пароль хранилища |
| `ANDROID_KEY_ALIAS` | `dstranslate` |
| `ANDROID_KEY_PASSWORD` | пароль ключа |

Base64 одной строкой (PowerShell):

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes('keystore\deepseek-translate.jks')) | Set-Clipboard
```

Дальше достаточно поставить тег и отправить его:

```powershell
git tag v1.6.0
git push origin v1.6.0
```

**Без секретов сборка подпишется отладочным ключом — обновление поверх установленного
приложения не встанет.** Для этого секреты и нужны.

Если APK с нужным именем **уже прикреплён к релизу** (сборка сделана локально релизным
ключом — так выпущена 1.6.0), workflow это видит и пропускает сборку: ничего не
перезаписывается. Это защита от ситуации «локально выпустили подписанный APK, потом
прилетел тег и CI подменил его отладочным».

## Локальный сервер для проверки

Если GitHub-манифест менять не хочется, а проверить обновление надо:

```powershell
.\serve-updates.cmd
```

Скрипт поднимет HTTP-сервер на порту 8080 и напечатает адрес
`http://<IP>:8080/version.json` — его нужно один раз вписать в приложении
(сохранённый адрес перебивает вшитый). В `dist/web/version.json` уже стоит
`versionCode = 15`, чтобы с планшета (14) обновление точно сработало.

Режим `-Tunnel` (публичная ссылка cloudflared) **не работает** — см. ниже.

## Про подпись APK — важно

Android устанавливает обновление поверх приложения **только если подпись совпадает**.

| | |
|---|---|
| Файл | `keystore/deepseek-translate.jks` |
| Alias | `dstranslate` |
| Пароли | `keystore.properties` (в корне проекта) |
| SHA-256 | `51:C4:09:54:21:0A:7D:D8:E0:8A:78:7C:DB:92:BD:51:00:BB:24:D3:B9:BF:97:3D:A7:23:D7:BB:2D:D1:26:BB` |
| Годен до | октябрь 2056 |

**Сохрани `keystore/` и `keystore.properties` в надёжном месте.** Потеря ключа =
приложение больше нельзя обновить, только переустанавливать вручную.

Сборки **до v1.5** подписаны отладочным ключом. Прежде чем ставить v1.5+, старую
версию нужно удалить — иначе Android откажет в установке из-за другой подписи.

## Вариант 3 (резерв): Cloudflare Pages

Публиковать папку `dist/web/` целиком:

1. Зарегистрируйся на <https://dash.cloudflare.com> (бесплатный аккаунт).
2. **Workers & Pages → Create → Pages → Upload assets**.
3. Назови проект, например `deepseek-translate`.
4. Перетащи туда **содержимое** `dist/web/` (`version.json` и APK).
5. Адрес будет вида `https://deepseek-translate.pages.dev`; ссылка на манифест —
   `https://deepseek-translate.pages.dev/version.json`.

Лимиты бесплатного тарифа: неограниченные запросы и трафик, до 25 МБ на файл.
APK на 17 МБ проходит с запасом.

## Почему не туннель cloudflared

**Проверено: не работает.** `serve-updates.cmd -Tunnel` регистрируется в Cloudflare
(`Registered tunnel connection ... protocol=http2`), локальный сервер отдаёт файлы
(`http=200`), но публичный адрес отвечает `530` на каждый запрос — край Cloudflare
не маршрутизирует трафик к туннелю. Пробовал QUIC, HTTP/2 и ipv4 — результат один.
Для разных сетей нужен статический хостинг (GitHub Releases или Cloudflare Pages),
он не держит живых процессов на компьютере.
