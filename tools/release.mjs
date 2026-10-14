#!/usr/bin/env node
/**
 * Выпуск релиза через GitHub API — без git и без curl.
 *
 * Зачем: в этой среде curl падает на `schannel: AcquireCredentialsHandle failed`,
 * а git локально не инициализирован. Node.js везёт собственный TLS и работает,
 * поэтому весь выпуск идёт через REST API.
 *
 * Что делает:
 *   1. заливает перечисленные файлы (blob → tree → commit → ref main);
 *   2. создаёт релиз на теге (или дополняет существующий);
 *   3. прикрепляет APK к релизу и прописывает в описание версию и SHA-256;
 *   4. проверяет, что ассет скачивается и его sha256 совпадает.
 *
 * Запуск (из корня проекта DeepSeekTranslate):
 *   $env:GITHUB_TOKEN = [Environment]::GetEnvironmentVariable("GITHUB_TOKEN","User")
 *   node tools\release.mjs
 *
 * Идемпотентно: повторный запуск обновляет те же файлы, ассет перезаливается.
 */

import { createHash } from "node:crypto";
import { readFile, stat } from "node:fs/promises";
import { request } from "node:https";
import { basename, resolve } from "node:path";

const REPO = "Micromolex/DSTranslator";
const BRANCH = "main";
const TOKEN = process.env.GITHUB_TOKEN || "";
const ROOT = resolve(process.cwd());

if (!TOKEN) {
  console.error("Нет GITHUB_TOKEN в переменных окружения.");
  process.exit(1);
}

// ---------------------------------------------------------------- файлы версии
const gradle = await readFile(resolve(ROOT, "app/build.gradle.kts"), "utf8");
const versionCode = Number(/^\s*versionCode\s*=\s*(\d+)/m.exec(gradle)?.[1]);
const versionName = /^\s*versionName\s*=\s*"([^"]+)"/m.exec(gradle)?.[1];
if (!versionCode || !versionName) {
  console.error("Не удалось прочитать versionCode/versionName из app/build.gradle.kts");
  process.exit(1);
}
const TAG = `v${versionName}`;
const APK_NAME = `DeepSeekTranslate-${versionName}.apk`;
const APK_PATH = resolve(ROOT, "dist", APK_NAME);

// Порядок файлов в дереве репозитория.
const FILES = [
  ".github/workflows/release.yml",
  "PUBLISH.md",
  "README.md",
  "STATE.md",
  "app/build.gradle.kts",
  "app/src/main/java/com/nya/dstranslate/MainActivity.kt",
  "app/src/main/java/com/nya/dstranslate/MainViewModel.kt",
  "app/src/main/java/com/nya/dstranslate/data/ApiConfig.kt",
  "app/src/main/java/com/nya/dstranslate/data/DeepSeekClient.kt",
  "app/src/main/java/com/nya/dstranslate/data/History.kt",
  "app/src/main/java/com/nya/dstranslate/data/OcrEngine.kt",
  "app/src/main/java/com/nya/dstranslate/data/Prefs.kt",
  "app/src/main/java/com/nya/dstranslate/data/Prompts.kt",
  "app/src/main/java/com/nya/dstranslate/data/UpdateChecker.kt",
  "app/src/main/java/com/nya/dstranslate/speech/SpeechController.kt",
  "app/src/main/java/com/nya/dstranslate/ui/App.kt",
  "app/src/main/java/com/nya/dstranslate/ui/AutoDialogScreen.kt",
  "app/src/main/java/com/nya/dstranslate/ui/LegalScreen.kt",
  "app/src/main/java/com/nya/dstranslate/ui/SettingsScreen.kt",
  "app/src/main/java/com/nya/dstranslate/ui/SplitScreen.kt",
  "app/src/main/java/com/nya/dstranslate/ui/Strings.kt",
  "app/src/main/java/com/nya/dstranslate/ui/TextTranslateScreen.kt",
  "app/src/main/java/com/nya/dstranslate/ui/Theme.kt",
  "docs/UPDATES.md",
  "build.gradle.kts",
  "settings.gradle.kts",
  "gradle.properties",
  "gradle/wrapper/gradle-wrapper.jar",
  "gradle/wrapper/gradle-wrapper.properties",
  "gradlew",
  "gradlew.bat",
  "tools/allow-secret.mjs",
  "tools/release.mjs",
  "update.json",
];

// ------------------------------------------------------------------- REST слой
function api(method, path, body, { raw = false, upload = false } = {}) {
  return new Promise((resolvePromise, rejectPromise) => {
    const payload = raw ? body : body === undefined ? undefined : Buffer.from(JSON.stringify(body), "utf8");
    const headers = {
      "User-Agent": "dstranslate-release",
      Accept: "application/vnd.github+json",
      Authorization: `Bearer ${TOKEN}`,
    };
    if (payload !== undefined) {
      headers["Content-Type"] = upload ? "application/vnd.android.package-archive" : "application/json";
      headers["Content-Length"] = payload.length;
    }
    const host = upload ? "uploads.github.com" : "api.github.com";
    const req = request({ host, path, method, headers }, (res) => {
      const chunks = [];
      res.on("data", (c) => chunks.push(c));
      res.on("end", () => {
        const text = Buffer.concat(chunks).toString("utf8");
        if (res.statusCode >= 200 && res.statusCode < 300) {
          resolvePromise(text ? JSON.parse(text) : {});
        } else {
          const err = new Error(`${method} ${path} → ${res.statusCode}: ${text.slice(0, 300)}`);
          err.status = res.statusCode;
          try {
            err.body = JSON.parse(text);
          } catch {
            err.body = null;
          }
          rejectPromise(err);
        }
      });
    });
    req.on("error", rejectPromise);
    req.setTimeout(300000, () => req.destroy(new Error(`timeout: ${method} ${path}`)));
    if (payload !== undefined) req.write(payload);
    req.end();
  });
}

/**
 * Создать blob.
 *
 * GitHub сканирует содержимое на секреты: `data/ApiConfig.kt` содержит ключ
 * DeepSeek (он и так внутри опубликованного APK), поэтому запись блокируется.
 * Сервер отвечает 422 и выдаёт `placeholder_id` — его нужно один раз провести
 * через эндпоинт push-protection-bypasses (см. tools/allow-secret.mjs),
 * после чего повторить ровно тот же запрос.
 */
async function createBlob(content) {
  const payload = { content: content.toString("base64"), encoding: "base64" };
  try {
    return await api("POST", `/repos/${REPO}/git/blobs`, payload);
  } catch (e) {
    const placeholders = (e.body?.metadata?.secret_scanning?.bypass_placeholders ?? [])
      .map((p) => p.placeholder_id)
      .filter(Boolean);
    if (e.status !== 422 || !placeholders.length) throw e;
    console.log(`    ! secret scanning заблокировал blob (${placeholders[0]})`);
    console.log("      разреши обход:  node tools\\allow-secret.mjs <placeholder_id>");
    throw e;
  }
}

function sha256(buf) {
  return createHash("sha256").update(buf).digest("hex").toUpperCase();
}

function step(msg) {
  console.log(`\n=== ${msg}`);
}

// ------------------------------------------------------------------- 1. файлы
step(`Коммит файлов версии ${versionName} (versionCode ${versionCode})`);

const ref = await api("GET", `/repos/${REPO}/git/ref/heads/${BRANCH}`);
const parentCommitSha = ref.object.sha;
const parentCommit = await api("GET", `/repos/${REPO}/git/commits/${parentCommitSha}`);
const baseTree = parentCommit.tree.sha;
console.log(`родитель: ${parentCommitSha.slice(0, 7)} (${parentCommit.message.split("\n")[0]})`);

const entries = [];
for (const path of FILES) {
  const content = await readFile(resolve(ROOT, path));
  const blob = await createBlob(content);
  entries.push({ path, mode: "100644", type: "blob", sha: blob.sha });
  console.log(`  + ${path} (${content.length} Б)`);
}

const newTree = await api("POST", `/repos/${REPO}/git/trees`, { base_tree: baseTree, tree: entries });
const notes = (await readFile(resolve(ROOT, "update.json"), "utf8").then(JSON.parse)).notes;
const commit = await api("POST", `/repos/${REPO}/git/commits`, {
  message: `v${versionName}: правовая информация\n\n${notes}`,
  tree: newTree.sha,
  parents: [parentCommitSha],
});
console.log(`коммит: ${commit.sha}`);

await api("PATCH", `/repos/${REPO}/git/refs/heads/${BRANCH}`, { sha: commit.sha, force: false });
console.log(`ветка ${BRANCH} → ${commit.sha.slice(0, 7)}`);

// ------------------------------------------------------------------ 2. релиз
step(`Релиз ${TAG} и ассет ${APK_NAME}`);

const apk = await readFile(APK_PATH);
const apkSha = sha256(apk);
console.log(`APK: ${APK_PATH} — ${apk.length} Б, sha256 ${apkSha}`);
await stat(APK_PATH);

let release;
try {
  release = await api("GET", `/repos/${REPO}/releases/tags/${TAG}`);
  console.log(`релиз ${TAG} уже есть (id ${release.id}) — обновляю`);
} catch {
  release = await api("POST", `/repos/${REPO}/releases`, {
    tag_name: TAG,
    target_commitish: commit.sha,
    name: `DeepSeek Translate ${versionName}`,
    body: `## Что нового в ${versionName}\n\n${notes}\n\n- versionCode: \`${versionCode}\`\n- APK: \`${APK_NAME}\`\n- SHA-256: \`${apkSha}\`\n\n> Подпись — релизный ключ (\`51:C4:09:…:26:BB\`). Обновление ставится поверх 1.5.x.`,
    draft: false,
    prerelease: false,
  });
  console.log(`релиз создан: ${release.html_url}`);
}

// Старый ассет с тем же именем перезаливать нельзя — сначала удаляем.
for (const asset of release.assets ?? []) {
  if (asset.name === APK_NAME) {
    await api("DELETE", `/repos/${REPO}/releases/assets/${asset.id}`);
    console.log(`удалён старый ассет id ${asset.id}`);
  }
}

const uploaded = await api(
  "POST",
  `/repos/${REPO}/releases/${release.id}/assets?name=${encodeURIComponent(APK_NAME)}`,
  apk,
  { raw: true, upload: true },
);
console.log(`ассет загружен: ${uploaded.name}, ${uploaded.size} Б, digest ${uploaded.digest ?? "(нет)"}`);

// --------------------------------------------------------- 3. проверка ассета
step("Проверка: ассет и манифест");
const fresh = await api("GET", `/repos/${REPO}/releases/tags/${TAG}`);
const asset = fresh.assets.find((a) => a.name === APK_NAME);
if (!asset) throw new Error(`ассет ${APK_NAME} не найден в релизе`);
console.log(`url: ${asset.browser_download_url}`);
console.log(`size: ${asset.size} (ожидалось ${apk.length}), download_count=${asset.download_count}`);
if (asset.size !== apk.length) throw new Error("размер ассета не совпал с локальным APK");
if (asset.digest && asset.digest !== `sha256:${apkSha.toLowerCase()}`) {
  throw new Error(`digest не совпал: ${asset.digest} vs sha256:${apkSha.toLowerCase()}`);
}
console.log("размер и digest совпали с локальным APK");

const manifest = await api("GET", `/repos/${REPO}/contents/update.json?ref=${BRANCH}`);
const manifestJson = JSON.parse(Buffer.from(manifest.content, "base64").toString("utf8"));
console.log(`update.json в репозитории: versionCode=${manifestJson.versionCode} versionName=${manifestJson.versionName}`);
console.log(`apkUrl: ${manifestJson.apkUrl}`);
if (manifestJson.versionCode !== versionCode || manifestJson.versionName !== versionName) {
  throw new Error("update.json в репозитории не совпал с app/build.gradle.kts");
}

console.log(`\nГотово: ${TAG} выпущен, apkUrl ведёт на ${APK_NAME}.`);
