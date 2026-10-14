# Выпустить обновление за 3 шага

Файлы уже готовы: `update.json` (манифест), `dist/DeepSeekTranslate-1.5.5.apk`
(подписан релизным ключом), адрес манифеста вшит в приложение.

## Шаг 1. Загрузить APK в GitHub Release

1. Открой <https://github.com/Micromolex/DSTranslator/releases/new>
2. **Choose a tag** → впиши `v1.5.5` → **Create new tag on publish**.
3. Заголовок: `DeepSeekTranslate 1.5.5`.
4. Прикрепи файл `dist/DeepSeekTranslate-1.5.5.apk`
   (важно: имя файла должно совпасть — оно уже прописано в `update.json`).
5. **Publish release**.

## Шаг 2. Закоммитить файлы

Через веб-интерфейс (**Add file → Upload files** / **Edit**) добавить:

- `update.json` — манифест, который читает приложение
- `.github/workflows/release.yml` — автосборка по тегу (для будущих версий)
- `docs/UPDATES.md`, `PUBLISH.md`, `README.md`, `STATE.md`
- `app/build.gradle.kts`, `app/src/main/java/com/nya/dstranslate/data/UpdateChecker.kt`

## Шаг 3. Проверить на планшете

В приложении: **Настройки → Проверить обновление** (или просто перезапустить —
проверка идёт при запуске). Должно предложить 1.5.5 и скачать APK.

Если ошибка — приложение теперь пишет конкретную причину:

| Сообщение | Что значит |
|---|---|
| «по этому адресу отдаётся HTML-страница, а не JSON» | адрес ведёт на `github.com/.../blob/...`, нужен `raw.githubusercontent.com` |
| «сервер ответил HTTP 404» | релиз с APK ещё не опубликован (имя файла в `apkUrl` не совпало) |
| «в файле обновлений нет поля versionCode» | в `update.json` потерялось поле версии |

---

**Приложение установит обновление поверх только при совпадающей подписи.** APK собран
релизным ключом (`keystore/deepseek-translate.jks`) — обновление с 1.5.4 встанет нормально.
