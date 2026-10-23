#!/usr/bin/env node
/**
 * Загрузка бинарных зависимостей сборки.
 *
 * В репозитории они не лежат: AAR sherpa-onnx весит 47.8 МБ и не проходит
 * через blob-API GitHub («input was too large to process»). Скрипт кладёт
 * файлы на место и умеет докачивать после обрыва (Range).
 *
 * Запуск из корня проекта:
 *   node tools\fetch-deps.mjs
 *
 * Что качает:
 *   app/libs/sherpa-onnx-1.13.8.aar  — официальный AAR (Kotlin API + .so)
 *   app/src/main/assets/silero_vad.onnx — модель VAD (0.6 МБ)
 *
 * Модель распознавания Qwen3-ASR (~941 МБ) сюда НЕ входит: она качается
 * в приложении при включении локального движка (см. AsrModelStore).
 */

import { createWriteStream, existsSync, mkdirSync, statSync } from "node:fs";
import { request } from "node:https";
import { dirname, resolve } from "node:path";

const FILES = [
  {
    url: "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar",
    path: "app/libs/sherpa-onnx-1.13.8.aar",
    bytes: 50_129_134,
  },
  {
    url: "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
    path: "app/src/main/assets/silero_vad.onnx",
    bytes: 643_854,
  },
];

const MAX_ATTEMPTS = 40;

function get(url, headers, redirects = 0) {
  return new Promise((resolvePromise, reject) => {
    const req = request(url, { headers: { "User-Agent": "dsh", ...headers } }, (res) => {
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location && redirects < 6) {
        res.resume();
        return get(res.headers.location, headers, redirects + 1).then(resolvePromise, reject);
      }
      resolvePromise(res);
    });
    req.on("error", reject);
    req.setTimeout(120000, () => req.destroy(new Error("timeout")));
    req.end();
  });
}

async function download(file) {
  const dest = resolve(process.cwd(), file.path);
  mkdirSync(dirname(dest), { recursive: true });

  for (let attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
    const have = existsSync(dest) ? statSync(dest).size : 0;
    if (have >= file.bytes) break;
    try {
      const res = await get(file.url, { Range: `bytes=${have}-` });
      if (res.statusCode !== 206 && res.statusCode !== 200) {
        res.resume();
        throw new Error("HTTP " + res.statusCode);
      }
      const stream = createWriteStream(dest, { flags: res.statusCode === 206 ? "a" : "w" });
      await new Promise((resolvePromise, reject) => {
        res.pipe(stream);
        stream.on("finish", resolvePromise);
        stream.on("error", reject);
        res.on("error", reject);
      });
      const now = statSync(dest).size;
      console.log(`  ${file.path}: ${(now / 1048576).toFixed(1)} / ${(file.bytes / 1048576).toFixed(1)} MB`);
    } catch (e) {
      const have2 = existsSync(dest) ? statSync(dest).size : 0;
      console.log(`  обрыв (${e.message}), есть ${(have2 / 1048576).toFixed(1)} MB — докачиваю`);
      await new Promise((r) => setTimeout(r, 1500));
    }
  }

  const size = existsSync(dest) ? statSync(dest).size : 0;
  if (size !== file.bytes) {
    throw new Error(`${file.path}: ${size} Б вместо ${file.bytes} Б`);
  }
  console.log(`  готово: ${file.path}`);
}

for (const file of FILES) {
  if (existsSync(resolve(process.cwd(), file.path)) &&
      statSync(resolve(process.cwd(), file.path)).size === file.bytes) {
    console.log(`уже есть: ${file.path}`);
    continue;
  }
  console.log(`качаю ${file.path}…`);
  await download(file);
}
console.log("Все зависимости на месте.");
