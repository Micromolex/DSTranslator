# Локальное распознавание речи RU ⇄ ZH для Android-переводчика

**Дата:** октябрь 2026
**Область:** замена системного `SpeechRecognizer` на офлайн-движок с поддержкой русского и китайского (в т.ч. code-switching) в приложении DeepSeekTranslate.
**Метод:** проверка первоисточников (arXiv, карточки HuggingFace, документация sherpa-onnx, Vosk, NVIDIA, Sber), плюс единственный найденный публичный замер на Android-железе ([VoicePing, 09.2026](https://voiceping.net/en/blog/research-offline-speech-transcription-benchmark/)).

---

## 1. Краткий вывод

1. **Главный факт:** Qwen3-ASR **открыт** (Apache 2.0, веса на HuggingFace, 30.01.2026) — это не только облачный API. Есть 0.6B и 1.7B, оба заявляют 52 языка/диалекта, включая русский, и LID внутри одной фразы.
2. **Но:** это LLM-decoder ASR весом 1.88 ГБ (bf16) / 937 МБ (int8 ONNX) и RTF ≈ 0.5 на слабом ARM. Для фраз 5–10 с это 2–4 с ожидания — приемлемо для перевода «по кнопке», неприемлемо для живых субтитров.
3. **Рекомендация сейчас:** Qwen3-ASR-0.6B int8 через **sherpa-onnx** (готовый Kotlin API, `hotwords`) + Silero VAD, нарезка по паузам, загрузка модели по требованию. Это **единственный** вариант, который закрывает и RU, и ZH, и смешанную речь **одной моделью** за 4–6 человеко-дней.
4. **Резервный путь (если не хватит скорости/качества):** гибрид GigaAM v3 (RU, 225 МБ) + SenseVoice (ZH, 240 МБ) + VAD + маршрутизация по письменности. Быстрее в 3–5 раз, лучше русский, но **не** тянет смешение языков внутри одной фразы.
5. **Не делать:** аудио-LLM на телефоне (Qwen2.5-Omni 3B, Gemma-3n/4 audio) — RTF > 1 и 3–4 ГБ RAM; Vosk small как основной движок — WER 23–32 %; whisper large-v3-turbo как интерактивный движок — RTF 0.6 на S10.
6. **Ключевой риск:** подтверждённого теста именно **RU↔ZH** code-switching ни у Qwen3-ASR, ни у других моделей нет. Это надо проверить на 20–30 собственных фразах до того, как строить архитектуру вокруг этого сценария.

---

## 2. Что не так с текущим подходом

Сейчас используется `android.speech.SpeechRecognizer` с `RecognizerIntent` (см. `app/src/main/java/com/nya/dstranslate/speech/SpeechController.kt`: `EXTRA_PARTIAL_RESULTS`, `EXTRA_ENABLE_LANGUAGE_DETECTION`, `EXTRA_ENABLE_LANGUAGE_SWITCH`, `EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES`).

Проблемы — не в реализации, а в природе API:

| # | Ограничение | Что это значит на практике |
|---|---|---|
| 1 | **Это чужой процесс, а не библиотека** | `SpeechRecognizer` — клиент к установленному сервису распознавания (обычно Google). Качество зависит от прошивки, версии сервиса, наличия языковых пакетов. На планшете может стоять урезанный/старый движок — приложение не может это ни увидеть, ни исправить. |
| 2 | **Один язык на сеанс** | `EXTRA_LANGUAGE` задаёт язык на всю сессию. Переключение — только на границе сегмента, и то если движок его поддерживает. |
| 3 | **Переключение языка — опционально и с API 34** | `EXTRA_ENABLE_LANGUAGE_SWITCH` добавлен в **API 34** (Android 14), и в документации прямо сказано: «Depending on the recognizer implementation, this value may have no effect» + «The corresponding language models must be downloaded». То есть на Android < 14 механизма нет вовсе, на 14+ он может молча не работать ([Android API ref](https://learn.microsoft.com/en-us/dotnet/api/android.speech.recognizerintent.extraenablelanguageswitch?view=net-android-34.0), [SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer)). |
| 4 | **Нет настоящего code-switching** | Даже с переключением распознаватель выбирает **один** язык на сегмент. Фраза «я хочу купить 这个» физически не может быть распознана корректно: либо русские слова уйдут в китайскую модель (мусор), либо наоборот. |
| 5 | **Нет доступа к аудио** | Нельзя сохранить фразу, прогнать вторым движком, поправить VAD, добавить глоссарий. Всё, что пришло в `Bundle`, — финальный текст (и иногда `DETECTED_LANGUAGE`). |
| 6 | **Сеть и приватность** | Часть запросов уходит в облако; офлайн-режим зависит от `isOnDeviceRecognitionAvailable()` и скачанных пакетов. Для переводчика разговоров это ещё и задержка. |
| 7 | **Нет управления словарём** | Нельзя передать имена, топонимы, названия лекарств, артикулы. Для переводчика это самый частый источник ошибок. |
| 8 | **Деградация вместо ошибки** | `ERROR_NO_MATCH` / `ERROR_SPEECH_TIMEOUT` не несут информации; при неверном языке приходит пустой результат, а не «я не уверен». |

Что уже сделано правильно в приложении: probe по отсутствию партиалов, принудительный язык, рестарт сессии, пауза микрофона на время TTS. Это хорошая обвязка — её надо сохранить, поменяв только **источник текста**.

---

## 3. Qwen3-ASR — что это на самом деле

### 3.1 Проверенные факты

| Параметр | Значение | Источник |
|---|---|---|
| Открытые веса | **Да.** `Qwen/Qwen3-ASR-0.6B`, `Qwen/Qwen3-ASR-1.7B`, `Qwen/Qwen3-ForcedAligner-0.6B` | [HF коллекция](https://huggingface.co/collections/Qwen/qwen3-asr), [arXiv:2601.21337](https://arxiv.org/abs/2601.21337) |
| Дата релиза | 29–30 января 2026 (arXiv v1 29.01, v2 30.01) | [arXiv](https://arxiv.org/abs/2601.21337) |
| Лицензия | **Apache 2.0** (и веса, и код) — коммерческое использование разрешено | [arXiv abstract](https://arxiv.org/abs/2601.21337), [HF card](https://huggingface.co/Qwen/Qwen3-ASR-0.6B) |
| Размер 0.6B | `model.safetensors` = **1 876 091 704 Б ≈ 1.75 ГиБ** (bf16); int8 ONNX-экспорт ≈ **937 МБ** (см. 3.3) | [HF tree API](https://huggingface.co/api/models/Qwen/Qwen3-ASR-0.6B/tree/main) |
| Размер 1.7B | ≈ **4 ГБ** весов (bf16), минимум ~4 ГБ VRAM для инференса | [qwen-ai.com](https://qwen-ai.com/qwen-asr/) |
| Архитектура | AuT-энкодер (300M / 180M) + projector + **Qwen3 LLM-декодер** (1.7B / 0.6B); 12.5 Гц аудио-токены; «dynamic flash attention window» 1–8 с позволяет одним набором весов работать и в streaming, и в offline | [arXiv](https://arxiv.org/abs/2601.21337), [qwen-ai.com](https://qwen-ai.com/qwen-asr/) |
| Языки | 30 языков + 22 китайских диалекта; **русский в списке**, китайский — основной | [sherpa-onnx docs (список языков модели)](https://raw.githubusercontent.com/k2-fsa/sherpa/master/docs/source/onnx/qwen3-asr/pretrained.rst) |
| LID | Заявлено 97.9 % на 30 языках (вендор) | [qwen-ai.com](https://qwen-ai.com/qwen-asr/) |
| Code-switching | Заявлен для смешанных высказываний (пример вендора — EN+ZH) | [qwen-ai.com](https://qwen-ai.com/qwen-asr/) |
| Облачный вариант | `qwen3-asr-flash`, `qwen3-asr-flash-filetrans` через DashScope / Model Studio (OpenAI-совместимый эндпоинт) — **только API** | [Alibaba Cloud API ref](https://www.alibabacloud.com/help/en/model-studio/qwen-asr-api-reference) |

### 3.2 Где Qwen3-ASR реально запускается на Android

Ключевая находка: **sherpa-onnx уже поддерживает Qwen3-ASR**, включая int8-экспорт и Kotlin/Java API.

- Модель: `sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25`: `conv_frontend.onnx` 42 МБ + `encoder.int8.onnx` 174 МБ + `decoder.int8.onnx` 721 МБ + tokenizer ≈ **940 МБ** ([docs](https://raw.githubusercontent.com/k2-fsa/sherpa/master/docs/source/onnx/qwen3-asr/pretrained.rst)).
- В списке поддерживаемых языков этой сборки — **русский (ru)** и **китайский (zh)**, плюс 22 диалекта; в `test_wavs` есть `ru1.wav`, `codeswitch.wav`, `noise1-en.wav` ([docs](https://raw.githubusercontent.com/k2-fsa/sherpa/master/docs/source/onnx/qwen3-asr/pretrained.rst)).
- Kotlin-конфиг уже есть: `OfflineQwen3AsrModelConfig(convFrontend, encoder, decoder, tokenizer, maxNewTokens, hotwords)` — тип модели № 61 в `getOfflineModelConfig` ([OfflineRecognizer.kt](https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/master/sherpa-onnx/kotlin-api/OfflineRecognizer.kt)).
- Стриминга «из коробки» нет: документация предлагает `sherpa-onnx-vad-microphone-simulated-streaming-asr` — то есть **VAD-нарезка + offline-декод каждого сегмента** ([real-time.rst](https://raw.githubusercontent.com/k2-fsa/sherpa/master/docs/source/onnx/qwen3-asr/real-time.rst)).
- Есть также GGUF для llama.cpp (`ggml-org/Qwen3-ASR-0.6B-GGUF`, `-1.7B-GGUF`) и LiteRT-экспорт (`litert-community/Qwen3-ASR-0.6B`), но **без документации и бенчмарков**.

### 3.3 Измеренная скорость

Единственный публичный замер на Android-железе (Galaxy S10, Exynos 9820, Android 12, 30-секундный клип):

| Конфигурация | Время | RTF |
|---|---|---|
| Qwen3-ASR 0.6B, **ONNX Runtime INT8** | 15 881 мс | **0.53** |
| Qwen3-ASR 0.6B, «pure C/NEON» | 338 261 мс | 11.28 (непригодно) |
| Whisper large-v3-turbo, sherpa-onnx | 17 930 мс | 0.60 |
| Parakeet TDT 0.6B v3, sherpa-onnx | 2 841 мс | 0.09 |
| SenseVoice Small, sherpa-onnx | 1 725 мс | 0.06 |

Источник: [VoicePing benchmark, 21.09.2026](https://voiceping.net/en/blog/research-offline-speech-transcription-benchmark/).

**Вывод по скорости:** S10 — слабый SoC 2019 года. Современный планшет (Snapdragon 8-й серии / Dimensity 9000) на int8 CPU обычно в ~2 раза быстрее → **RTF ≈ 0.2–0.3**, то есть фраза 8 с распознаётся за **1.6–2.4 с**. Это оценка, а не измерение: **не подтверждено** для конкретного планшета пользователя.

Важно: RTF 0.064 из пресс-релиза — это серверный throughput при concurrency 128 на GPU, **не** показатель телефона ([arXiv](https://arxiv.org/abs/2601.21337)).

### 3.4 Чего в источниках нет (помечаю как «не подтверждено»)

| Утверждение | Статус |
|---|---|
| Qwen3-ASR держит **RU↔ZH** code-switching внутри одной фразы | **Не подтверждено.** Заявлен code-switching в целом; в тестах sherpa-onnx `codeswitch.wav` — это EN/FR/IT/ES, а пример вендора — EN+ZH. Отдельного RU+ZH теста нет ни у кого. |
| Qwen3-ASR streaming на Android в реальном времени | **Не подтверждено.** В мобильном рантайме доступен только offline-декод по VAD-сегментам. |
| Работа через LiteRT на GPU/NPU телефона | **Не подтверждено.** Репозиторий есть, README пустой, замеров нет. |
| Качество русского у 0.6B vs GigaAM/Parakeet | **Не подтверждено.** Публичных WER по русскому для Qwen3-ASR в карточке нет. |

---

## 4. Альтернативы

Общая опорная точка для колонки «скорость»: замеры VoicePing на Galaxy S10 (2019). Современный планшет ≈ ×2 быстрее; все значения с «≈» — оценка.

### 4.1 whisper.cpp (ggml) + Silero VAD

Портированный на C/C++ Whisper; собирается под Android NDK, есть готовый пример `examples/whisper.android` ([репозиторий](https://github.com/ggml-org/whisper.cpp)).

| Параметр | Значение |
|---|---|
| Языки | 99 языков, включая ru и zh — но качество по ним разное |
| Размер | tiny 39M (~31 МБ ggml) · base 74M · small 244M · **large-v3-turbo 809M** (~1.0 ГБ в sherpa-onnx-пакете, ~1.6 ГБ fp16) |
| Лицензия | MIT (код); веса Whisper — открытые, permissive ([openai/whisper](https://github.com/openai/whisper)) |
| Офлайн на Android | Да, официальный пример NDK |
| Стриминг | Нет. Только псевдо-стриминг: VAD (Silero, ~2 МБ, MIT) → нарезка → декод сегмента |
| Скорость на ARM | Turbo: RTF 0.60 на S10 (sherpa-onnx) → ≈0.3 на планшете, 10 с ≈ 3 с. В том же тесте сборка **whisper.cpp tiny дала RTF 3.52** (105 с на 30 с аудио) — аномалия сборки/потоков, а не свойство движка, но показывает цену неаккуратной интеграции |
| Зрелость | Очень высокая: тысячи интеграций, готовые биндинги, огромное сообщество |

**Code-switching:** плохо, и это фундаментально. Декодер Whisper фиксирует **один языковой токен на 30-секундное окно**; на смешанном высказывании модель транскрибирует доминирующий язык, а вкрапления переводит, выбрасывает или коверкает. Никакой prompt engineering это не лечит ([разбор с ссылками на первоисточники](https://mskazemi.com/yazses/design/specs/polyglot-switch.html)). Практический обходной путь — per-span LID + повторный декод с принудительным языком, но это уже своя надстройка и +30–50 % ошибок на смешанных спанах.

### 4.2 sherpa-onnx

Один C++/ONNX Runtime-движок с десятками семейств моделей и **готовым Android AAR + Kotlin API** ([android/SherpaOnnxAar](https://github.com/k2-fsa/sherpa-onnx/tree/master/android/SherpaOnnxAar)).

| Параметр | Значение |
|---|---|
| Языки | Зависит от модели. Для RU: zipformer-ru (74 МБ int8), streaming-zipformer-small-ru-vosk (95 МБ), GigaAM v2/v3 CTC (225 МБ), NeMo multi be/de/en/es/fr/hr/it/pl/ru/uk (без zh). Для ZH: paraformer-zh, zipformer-zh, zh-en (code-switch zh↔en), TeleSpeech, SenseVoice, FireRedASR. Мультиязычная: Qwen3-ASR 0.6B (52), Omnilingual 300M |
| Размер | От 74 МБ (RU zipformer) до 940 МБ (Qwen3-ASR) |
| Лицензия | Сам движок Apache 2.0; лицензии моделей — по карточкам (Apache/MIT/CC-BY) |
| Офлайн на Android | Да, JNI + AAR, `System.loadLibrary("sherpa-onnx-jni")` |
| Стриминг | **Да, нативный** для zipformer-transducer (партиалы каждые ~100 мс); для остальных — VAD-нарезка |
| Скорость на ARM | Streaming zipformer 20M: RTF 0.12 на S10; Parakeet v3: 0.09; SenseVoice: 0.06 |
| Зрелость | Высокая: активная разработка, десятки Android-демо, единый API на 15+ семейств |

Практический минус: **16 KB page size**. Google Play требует поддержку 16 КБ страниц для приложений под Android 15+; у sherpa-onnx это уже ловили на `libonnxruntime4j_jni.so` ([issue #2641](https://github.com/k2-fsa/sherpa-onnx/issues/2641)). Перед релизом надо проверить `zipalign -c -P 16` и версию AAR.

### 4.3 Vosk

Kaldi-based, самый простой в интеграции, настоящий стриминг с партиалами.

| Параметр | Значение |
|---|---|
| Языки | Русский и китайский есть отдельными моделями, мультиязычной нет |
| Размер | RU: small 45 МБ / big 1.8 ГБ · ZH: small 42 МБ / big 1.3 ГБ |
| Лицензия | Apache 2.0 |
| Офлайн на Android | Да, официальный AAR |
| Стриминг | **Да**, нативный, с партиалами |
| Скорость на ARM | Очень высокая (маленькие модели, RTF ≈ 0.1 и лучше) |
| Зрелость | Высокая, но развитие замедлилось |

**Качество — приговор.** По данным самого вендора ([alphacephei.com/vosk/models](https://alphacephei.com/vosk/models)):

- `vosk-model-small-ru-0.22`: **WER 22.7 / 32.0 / 29.9 %** (аудиокниги / YouTube / устройства);
- `vosk-model-ru-0.42`: 4.5 % на аудиокнигах, но **19.5 % на YouTube** и 1.8 ГБ (на планшете — RTF ≫ 1);
- `vosk-model-small-cn-0.22`: **CER 23.5 %** (SpeechIO-02), 17.2 % (THCHS);
- `vosk-model-cn-0.22`: 14.0 %, 1.3 ГБ.

То есть mobile-модели Vosk дают ~каждое четвёртое-третье слово с ошибкой. Для переводчика это нерабочий уровень: переводчик «додумывает» смысл, и ошибки становятся неочевидными.

**Два распознавателя на одном потоке — можно ли?** Технически да: `Model` загружается на язык, `Recognizer` создаётся на модель; два `Recognizer` можно кормить одними и теми же PCM-чанками. Цена — двойная память (~300 МБ на small-модель по документации Vosk) и двойной CPU. Но:
- встроенного арбитража «какой язык сейчас» нет — выбирать придётся самому (см. §4.8);
- официальной поддержки такого сценария нет, это обходной путь (обсуждение переключения языков — [issue #749](https://github.com/alphacep/vosk-api/issues/749));
- оба движка будут выдавать гипотезы на «чужом» языке, и это надо фильтровать.

### 4.4 SenseVoice (FunAudioLLM)

| Параметр | Значение |
|---|---|
| Языки | **Только zh, yue, en, ja, ko.** Русского **нет** — это подтверждено карточкой модели и разделом What's New: «for Mandarin, Cantonese, English, Japanese, and Korean» ([HF card](https://huggingface.co/FunAudioLLM/SenseVoiceSmall)) |
| Размер | 234M, int8 ≈ 240 МБ |
| Лицензия | **Кастомная** (`model-license` на HF), не Apache/MIT — для коммерции нужна юридическая проверка |
| Офлайн на Android | Да, через sherpa-onnx (есть даже QNN-варианты для Hexagon NPU) |
| Стриминг | Нет (non-autoregressive, декодирует сегмент целиком), но очень быстрый |
| Скорость на ARM | **RTF 0.06** на S10 → ≈0.03 на планшете: 10 с ≈ 0.3 с |
| Зрелость | Средняя: отличный движок, но экосистема уже/капризнее, чем у Whisper |

Плюсы: LID-тег и эмоция/события в выдаче, отличная пунктуация для zh, лучшая в классе скорость. Минус для нашей задачи — **русский отсутствует**, поэтому SenseVoice может быть только «китайской половиной» гибрида.

### 4.5 Parakeet (NVIDIA)

| Параметр | Значение |
|---|---|
| Языки | v2 — только английский; **v3 — 25 европейских, включая русский** (FLEURS ru WER 5.51 %, CoVoST ru 3.00 %), китайского **нет** ([HF card](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3)) |
| Размер | 600M, int8-пакет sherpa-onnx ≈ 671 МБ |
| Лицензия | **CC-BY-4.0** (коммерция разрешена с атрибуцией) |
| Офлайн на Android | Да: int8-экспорт в sherpa-onnx (nemo_transducer), есть community-сборки |
| Стриминг | Нет (FastConformer-TDT, чанки с контекстом; в sherpa-onnx — offline) |
| Скорость на ARM | **RTF 0.09** на S10 → ≈0.05 на планшете: 10 с ≈ 0.5 с |
| Зрелость | Средне-высокая; NVIDIA-стек (NeMo), но CPU/C++ путь через sherpa-onnx уже рабочий |

Отличия: пунктуация и капитализация «из коробки», таймстемпы, **автоопределение языка** — очень полезно для маршрутизации в гибриде. Минус: 671 МБ и нет китайского, то есть в одиночку задачу не решает.

### 4.6 Moonshine

| Параметр | Значение |
|---|---|
| Языки | Изначально English-only (tiny 27M / base 61M). В sherpa-onnx есть v2-квантованные **поязыковые** модели: en, **zh**, ja, vi, uk, es, ar. **Русского варианта нет** (есть украинский) |
| Размер | tiny ≈125 МБ, base ≈290 МБ (пакеты sherpa-onnx) |
| Лицензия | MIT (код и веса, см. карточку [moonshine-ai/moonshine-tiny](https://huggingface.co/moonshine-ai/moonshine-tiny)) |
| Офлайн на Android | Да, через sherpa-onnx |
| Стриминг | Нет |
| Скорость на ARM | Самая быстрая из всех: RTF 0.05 (tiny) / 0.08 (base) на S10 |
| Зрелость | Средняя; для русского неприменим |

Вывод: интересен как «быстрый китайский черновик», но не как основа.

### 4.7 GigaAM (Sber)

| Параметр | Значение |
|---|---|
| Языки | **Только русский.** v3 (2025), 4 варианта: ctc / rnnt и e2e-ctc / e2e-rnnt (с пунктуацией и регистром) ([ai-sage/GigaAM-v3](https://huggingface.co/ai-sage/GigaAM-v3)) |
| Размер | Conformer 16×768; **CTC int8 ONNX = 225 МБ** ([HF tree](https://huggingface.co/api/models/csukuangfj/sherpa-onnx-nemo-ctc-giga-am-v3-russian-2025-12-16/tree/main)) |
| Лицензия | **MIT** |
| Офлайн на Android | Да, через sherpa-onnx (`nemo_ctc`, типы 19/27 в Kotlin API) |
| Стриминг | Нет (offline CTC/RNNT по сегментам) |
| Скорость на ARM | Не измерена публично; по классу модели (225 МБ, non-autoregressive) ожидаемо **RTF ≈ 0.05–0.1** — «не подтверждено» |
| Зрелость | Средняя: сильный RU-бенчмарк, но узкое сообщество; интеграция в sherpa-onnx есть |

Лучшее соотношение «качество русского / размер» среди открытых. Идеальная «русская половина» гибрида.

### 4.8 Аудио-LLM: Qwen2-Audio, Qwen2.5-Omni, Gemma-3n/4 audio

| Параметр | Значение |
|---|---|
| Языки | Qwen2-Audio 7B, Qwen2.5-Omni 3B/7B — мультиязычные, ru и zh присутствуют; Gemma-3n E2B/E4B — аудио-вход заявлен |
| Размер | 3B в Q4 ≈ 2 ГБ + аудио-энкодер + KV-cache; 7B — 4–5 ГБ |
| Лицензия | Apache 2.0 у Qwen-семейства; Gemma — Gemma Terms of Use |
| Офлайн на Android | Технически возможно (llama.cpp/GGUF), но 3–4 ГБ RAM и RTF > 1 |
| Стриминг | Нет; авторегрессивная генерация, задержка растёт с длиной ответа |
| Скорость на ARM | Непригодна: десятки секунд на фразу 8 с; публичных мобильных замеров ASR-качества нет |
| Зрелость | Низкая для ASR-задачи: инструменты есть, рабочие мобильные пайплайны — нет |

| Модель | Реальность на телефоне |
|---|---|
| **Qwen2-Audio 7B** | llama.cpp прямо пишет про свои GGUF-конверсии: «no pre-quantized GGUF this model, as they have **very poor result**» ([docs/multimodal.md](https://raw.githubusercontent.com/ggml-org/llama.cpp/master/docs/multimodal.md)). Как ASR — не вариант |
| **Qwen2.5-Omni 3B / 7B** | GGUF есть, аудио-вход поддержан. Но 3B в Q4 ≈ 2 ГБ + аудио-энкодер + KV-cache → 3–4 ГБ RAM, RTF ≫ 1 на планшете. Для перевода фразы 8 с — десятки секунд |
| **Gemma-3n E2B/E4B audio** | В llama.cpp появилось семейство `gemma-4-E2B/E4B` с аудио-входом. Мобильный путь (LiteRT/MediaPipe) для аудио — **не подтверждено**; замеров ASR-качества на ARM нет |

Принципиальная проблема не в размере, а в том, что аудио-LLM **генерирует ответ авторегрессивно**: даже при идеальном железе задержка пропорциональна длине текста, а не длине аудио. Для «нажал-сказал-перевёл» это худший класс latency при отсутствии выигрыша в WER на коротких бытовых фразах.

### 4.9 Гибрид: два одноязычных движка + VAD + маршрутизация

Идея: не искать мультиязычную модель, а держать два быстрых одноязычных движка и решать, какой язык звучит.

```
mic 16k → Silero VAD → сегмент → ┬→ GigaAM v3 (RU)      → гипотеза A + score
                                  └→ SenseVoice (ZH)     → гипотеза B + score
                                            ↓
                        маршрутизация: скрипт (кириллица/ханьцзы) + score + LID
                                            ↓
                                    выбранный текст → DeepSeek
```

Ключевое наблюдение: **для пары RU/ZH LID почти не нужен**. Письменности не пересекаются: если в гипотезе есть кириллица — это русский, если ханьцзы — китайский. Достаточно посчитать долю символов каждого алфавита и отбросить гипотезу-мусор (китайская модель на русской речи выдаёт редкие/бессмысленные иероглифы, русская на китайской — транслит-абракадабру). Это дешевле и надёжнее, чем fastText-LID, и не требует отдельной модели.

| Параметр | Значение |
|---|---|
| Языки | RU + ZH по отдельности; **смешение внутри одной фразы — нет** |
| Размер | GigaAM 225 МБ + SenseVoice 240 МБ + VAD 2 МБ ≈ **470 МБ** |
| Лицензия | MIT + кастомная (SenseVoice) — проверить |
| Офлайн на Android | Да, оба через sherpa-onnx (один рантайм, два recognizer'а) |
| Стриминг | Возможен: streaming-zipformer-small-ru + streaming-zipformer-zh, оба с партиалами |
| Скорость на ARM | ≈0.1 суммарно (два движка последовательно на одном сегменте) → 10 с ≈ 1 с |
| Зрелость | Средняя: два движка, своя логика арбитража, но всё внутри одного API |

Слабое место: **внутрифразовое смешение**. Если пользователь говорит «мне нужен  receipt», VAD не разделит это на два языка (нет паузы), и вторая половина уйдёт в мусор. Частично лечится принудительной нарезкой по смене письменности в партиалах, но это хрупко.

---

## 5. Реалистичная архитектура для приложения

### 5.1 Принцип: движок — сменный, обвязка — существующая

`SpeechController` уже даёт нужный контракт (`onPartial`, `onFinal`, `onError`, `onLanguageDetected`, `onProbeFailed`, `stopSpeaking` + пауза микрофона). Меняем только источник текста: интерфейс `AsrEngine` с двумя реализациями — `SystemAsrEngine` (текущая) и `LocalAsrEngine` (sherpa-onnx). `MainViewModel` (строки 378–421) не меняется: там уже есть `mergeSpoken`, `LangDetect.detect`, `translateText()`.

```
AudioRecord 16 kHz mono PCM16
        ↓ (ring buffer 100 мс)
Silero VAD (sherpa-onnx Vad)  ── речь не началась ─→ таймаут, onSilence
        ↓ сегмент: 0.4 с … 12 с, padding 0.3 с
Qwen3-ASR-0.6B int8 (OfflineRecognizer, numThreads=4)
        ↓ текст + (опц.) таймстемпы
нормализация (ITN уже внутри) + скрипт-детект → ru / zh / mixed
        ↓
DeepSeek chat/completions  (перевод, целевой язык известен из скрипта)
        ↓
TTS (Locale.SIMPLIFIED_CHINESE / ru-RU) — микрофон на паузе
```

### 5.2 Code-switching: три уровня, а не один

1. **Внутри фразы** — только Qwen3-ASR. Он единственный, кто вообще пытается. Проверить на своих фразах; если качество плохое — честно ограничить сценарий.
2. **Между фразами** (реальный разговорный режим: одна реплика — один язык) — работает и гибрид; здесь достаточно скрипт-детекта по финальному тексту.
3. **Ручное переключение** — уже есть (`forcedLanguageTag`). Оставить как «аварийный» режим: пользователь знает, на каком языке говорит.

Рекомендация: не пытаться определить язык **до** распознавания. Qwen3-ASR сам делает LID; скрипт-детект постфактум дешевле и точнее для бинарного случая.

### 5.3 Фразы 5–10 секунд

- VAD-сегментация с `minSilenceDuration ≈ 0.35 с`, `maxSpeechDuration ≈ 12 с` — фраза не рвётся на вдохе (в приложении это уже решено параметрами `EXTRA_SPEECH_INPUT_*` для системного движка, теперь надо те же значения перенести в VAD).
- **Партиалы для Qwen3-ASR не делать.** Авторегрессивный декодер на каждом пере-декоде будет жечь батарею и «дёргать» текст. Вместо этого: индикатор «слушаю» → VAD-конец → декод (1.5–3 с) → перевод. Для переводчика разговоров это естественный ритм.
- Если партиалы нужны для UX — двухпроходная схема: **SenseVoice** (RTF 0.06) даёт черновик каждые 700 мс, Qwen3-ASR — финальный текст по концу сегмента. Стоимость: два движка в памяти (~1.2 ГБ) → включать только на устройствах с ≥6 ГБ RAM.
- Максимум `maxNewTokens = 256` для фразы 12 с: больше не нужно, лишние токены — это лишние секунды.

### 5.4 Где хранить модели

| Вариант | Плюсы | Минусы | Вердикт |
|---|---|---|---|
| `assets/` в APK | работает сразу | +940 МБ к APK, Play не пропустит (лимит базового модуля), обновление приложения = повторная загрузка | **Нет** |
| Play Asset Delivery (fast-follow / on-demand) | бесплатно, CDN Google, обновления отдельно | только при публикации в Play | Если публикуемся в Play |
| Скачивание с собственного CDN в `filesDir` | контроль версий, возможность отката, работает вне Play | свой хостинг, нужна докачка и проверка SHA-256 | **Да** — у проекта уже есть `update.json` + `serve-updates.cmd` |

Практика: манифест `asr-models.json` (url, sha256, bytes, version), скачивание в `filesDir/models/qwen3-asr/`, докачка по `Range`, проверка хеша, распаковка не нужна (ONNX читаются напрямую). Модель грузить **лениво** при первом включении локального режима; показывать размер и требовать Wi-Fi по умолчанию.

### 5.5 Ограничения Android, которые реально ударят

| Ограничение | Последствие | Что делать |
|---|---|---|
| **ABI**: сейчас в проекте `arm64-v8a` + `armeabi-v7a` | 32-битный процесс — адресное пространство ~3 ГБ; 940 МБ модели + арены ORT → OOM | Для ASR оставить только `arm64-v8a`, `abiFilters`/splits |
| **16 KB page size** (требование Play для targetSdk 35) | `.so` не грузится на устройствах с 16 КБ страницами | Проверить `zipalign -c -P 16 -v 4 app.apk`, обновить AAR sherpa-onnx ([issue #2641](https://github.com/k2-fsa/sherpa-onnx/issues/2641)) |
| **Память**: native-аллокации не видны Java-heap, но LMK убивает процесс | На 4 ГБ планшете модель 940 МБ + приложение + TTS — риск | `ActivityManager.isLowRamDevice` → не предлагать локальный режим; `numThreads` 2–4; не держать две модели одновременно |
| **Батарея/тепло** | Постоянный автодиалог с 4 потоками на 2–3 с на фразу греет и троттлит | Декодировать **только** по концу VAD-сегмента; в автодиалоге — `numThreads=2`; не держать `AudioRecord` в hot loop при тишине |
| **Cold start** | Загрузка 940 МБ ONNX с флеша — 0.5–2 с | Прогрев при старте экрана, `keepLoaded` при активном диалоге, выгрузка по таймауту |
| **Разрешения/фокус аудио** | Конфликт с TTS и другими приложениями | Уже решено в `SpeechController` (пауза микрофона на TTS) — сохранить |
| **NDK/JNI** | Своя сборка = свои падения | Использовать готовый AAR, не собирать sherpa-onnx из исходников |

### 5.6 Критерии приёмки (чтобы решение было измеримым)

- RTF ≤ 0.35 на целевом планшете (фраза 10 с → ≤ 3.5 с).
- WER русского ≤ 12 % и CER китайского ≤ 8 % на собственном наборе из 30 фраз (бытовые, с именами и числами).
- Смешанная фраза: оба языка присутствуют в выводе хотя бы в 7 из 10 случаев (для Qwen3-ASR).
- Cold start ≤ 3 с; пик RSS ≤ 1.6 ГБ; расход батареи ≤ 3 % за 10 минут автодиалога.
- Ни одного `OOM`/ANR на 4 ГБ устройстве.

---

## 6. Сравнительная таблица (8 критериев, 1–5)

Критерии: **RU** — качество русского; **ZH** — качество китайского; **CS** — смешение RU↔ZH; **Off** — офлайн/приватность; **Spd** — скорость на ARM; **Size** — размер/память (5 = мало); **Mat** — зрелость экосистемы; **Str** — стриминг/латентность. Веса: 0.15 / 0.15 / 0.20 / 0.10 / 0.15 / 0.10 / 0.05 / 0.10.

| Вариант | RU | ZH | CS | Off | Spd | Size | Mat | Str | Взвеш. | Комментарий |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| **Qwen3-ASR-0.6B int8** (sherpa-onnx) | 4 | 5 | 5 | 5 | 3 | 2 | 4 | 3 | **4.00** | Единственный, кто делает и то, и другое, и смешение. Тяжёлый, не для субтитров |
| **Гибрид** GigaAM v3 + SenseVoice + VAD | 5 | 4 | 2.5 | 5 | 5 | 4 | 4 | 4 | **4.10** | Лучший по скорости/размеру/русскому. Ломается на внутрифразовом смешении |
| **sherpa-onnx streaming** (ru-vosk-small + zh zipformer) | 2 | 3 | 3 | 5 | 5 | 5 | 4 | 5 | **3.80** | Настоящий стриминг, но русский = качество Vosk small |
| **Vosk** (small ru + small cn, параллельно) | 2 | 2 | 3 | 5 | 5 | 5 | 5 | 5 | **3.70** | Проще всего, но WER 23–32 % — для перевода мало |
| **GigaAM v3 CTC** (только RU) | 5 | 1 | 1 | 5 | 5 | 5 | 3 | 2 | 3.20 | Лучший русский на 225 МБ |
| **SenseVoice** (только ZH) | 1 | 4 | 1 | 5 | 5 | 4 | 4 | 3 | 3.10 | Быстрый и точный zh, русского нет |
| **Parakeet v3** (только RU) | 5 | 1 | 1 | 4 | 5 | 3 | 4 | 2 | 2.95 | RU WER 5.5 %, пунктуация, LID. 671 МБ, без zh |
| **Whisper large-v3-turbo** | 4 | 3 | 1 | 5 | 2 | 2 | 5 | 1 | 2.60 | Универсален, но медленный и не умеет CS |
| **Аудио-LLM** (Qwen2.5-Omni 3B) | 3 | 4 | 4 | 3 | 1 | 1 | 2 | 1 | 2.60 | Технически «может», практически — нет |
| **Whisper small** | 3 | 2 | 1 | 5 | 3 | 3 | 5 | 1 | 2.55 | Компромисс, который ничего не выигрывает |

Оценки «RU/ZH» — синтез опубликованных WER (GigaAM/Parakeet — лучшие по RU; Qwen3-ASR — SOTA по ZH) и заявлений вендоров; **собственного замера на нашем домене нет**.

**Почему итоговая рекомендация — не строка с максимальным баллом.** Взвешенная сумма не является решающей функцией: гибрид (4.10) обходит Qwen3-ASR (4.00) за счёт скорости и размера, но его оценка CS = 2.5 держится на допущении «одна реплика — один язык». Если реальный сценарий пользователя — внутрифразовое смешение, гибрид падает до CS = 1 (итог ≈ 3.8), а Qwen3-ASR остаётся единственным работоспособным. Плюс стоимость: 4–6 дней против 8–12. Поэтому порядок такой: сначала один движок, который закрывает worst case, затем оптимизация скорости гибридом.

---

## 7. Стоимость внедрения (человеко-дни)

Оценка для разработчика, знакомого с проектом, но без ML-опыта. Включает: PoC на устройстве, интеграцию, UI, тесты на целевом планшете, документацию. Не включает дизайн и переводы.

| Вариант | Дни | Из чего складывается | Риск |
|---|---:|---|---|
| **Qwen3-ASR-0.6B через sherpa-onnx** | **4–6** | AAR/JNI 0.5 · аудио-пайплайн (AudioRecord 16k, ring buffer, VAD) 1.0 · интеграция в `SpeechController` (сегменты, отмена, потоки, колбэки) 1.0 · загрузка/хранение моделей (манифест, sha256, докачка) 1.0 · UI/настройки/фолбэк на системный движок 1.0 · замеры и подбор потоков 0.5–1.0 | Средний: 940 МБ и RTF на планшете |
| **Гибрид** GigaAM + SenseVoice + VAD + маршрутизация | 8–12 | Всё выше, плюс второй движок 1.0 · арбитраж по скрипту 1.0 · два прогона на сегмент и тайминг 1.0 · тесты на «мусорных» гипотезах 1.0 · отладка ложных срабатываний 1.0–2.0 | Средний: качество арбитража |
| sherpa-onnx streaming (ru + zh) | 5–7 | Два streaming-recognizer'а, склейка партиалов, коммит по паузе, антидребезг текста | Средний: качество ru-vosk-small |
| **Vosk** (2 recognizer'а) | 3–4 | Готовый AAR, два `Model`, общий PCM, скрипт-фильтр | Низкий по коду, высокий по результату |
| Whisper.cpp (large-v3-turbo / small) | 6–9 | NDK/CMake, сборка ggml aarch64, квантование, VAD, долгая возня с производительностью и потоками | Высокий: RTF |
| SenseVoice (только ZH) | 3–4 | Как Qwen3, но модель 240 МБ | Низкий (но не решает задачу) |
| Parakeet v3 (только RU) | 4–6 | int8-экспорт/конфиг nemo_transducer, LID, пунктуация, интеграция | Средний: 671 МБ, поддержка в sherpa-onnx не «первого класса» |
| GigaAM v3 (только RU) | 3–5 | `nemo_ctc` конфиг, 225 МБ, e2e-вариант для пунктуации | Низкий |
| Аудио-LLM (Qwen2.5-Omni 3B) | 15–25 | Свой runtime/GGUF, аудио-препроцессинг, промпт-инжиниринг, борьба с RAM и latency | **Очень высокий**: скорее всего не пройдёт приёмку |
| Qwen3-ASR-Flash (облако, для эталона) | 1–2 | HTTP-клиент, ключ, пакетный прогон 30 фраз | Низкий; нужен только для сравнения |

**Вывод по стоимости:** единственный вариант «дёшево и закрывает всё» — Qwen3-ASR-0.6B. Гибрид дороже в 2 раза, но даёт 3–5-кратный выигрыш по скорости и лучший русский — это разумный шаг 2, а не шаг 1.

---

## 8. Итоговая рекомендация

### 8.1 Сделать сейчас (минимум усилий, 4–6 дней)

1. **Взять Qwen3-ASR-0.6B int8 из sherpa-onnx** (`sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25`, 940 МБ) и подключить как `LocalAsrEngine` за существующим интерфейсом `SpeechController`.
2. **VAD-нарезка Silero**, без партиалов от Qwen. Индикатор «слушаю/распознаю» вместо живого текста. Отправка в DeepSeek — по концу сегмента.
3. **Загрузка модели по требованию** из своего CDN с sha256 и докачкой; в APK не класть.
4. **`hotwords`** из глоссария приложения (имена, топонимы, термины) — это бесплатный прирост качества на 5–10 %, поддержано в `OfflineQwen3AsrModelConfig`.
5. **Оставить системный движок как фолбэк**: модель не скачана, `isLowRamDevice`, ошибка инициализации.
6. **Сразу замерить** на планшете пользователя: RTF, RSS, 30 фраз (10 RU, 10 ZH, 10 смешанных). Параллельно прогнать те же 30 фраз через **Qwen3-ASR-Flash** (≈$0.32/час, [API ref](https://www.alibabacloud.com/help/en/model-studio/qwen-asr-api-reference)) — это даст верхнюю границу качества и ответ на вопрос «хватит ли 0.6B или нужен 1.7B».

### 8.2 Следующий шаг (по результатам замеров, 5–10 дней)

- **Если RTF > 0.35 или размер/RAM мешают** → гибрид: GigaAM v3 CTC (225 МБ, RU) + SenseVoice (240 МБ, ZH) + скрипт-маршрутизация; Qwen3-ASR оставить как режим «смешанная речь» (переключатель в настройках). Итого ~470 МБ и RTF ≈ 0.1.
- **Если русского не хватает по качеству** → заменить русскую половину на GigaAM v3 e2e (пунктуация+регистр) или Parakeet v3 (WER 5.5 % на FLEURS ru, но 671 МБ).
- **Если не хватает точности в домене** → `hotwords` + few-shot промпт в DeepSeek; при явной нехватке — LoRA-файнтюн на 1.7B (отдельный проект, 10–20 дней, нужен GPU).
- **Если нужны живые субтитры** → двухпроходная схема: SenseVoice-черновик + Qwen3-финал, только на устройствах ≥6 ГБ RAM.

### 8.3 Чего не делать

| Не делать | Почему |
|---|---|
| Аудио-LLM на устройстве (Qwen2.5-Omni, Gemma-3n/4 audio) | RTF ≫ 1, 3–4 ГБ RAM, задержка растёт с длиной текста; для ASR-задачи выигрыша нет. Qwen2-Audio в llama.cpp прямо помечен как «very poor result» |
| Vosk small как основной движок | WER 23–32 % (RU), CER 17–24 % (ZH) по данным вендора — переводчик будет «додумывать» |
| whisper large-v3-turbo как интерактивный движок | RTF 0.60 на S10, 10 с ≈ 3–6 с; и всё равно не умеет code-switching |
| Полагаться на `EXTRA_ENABLE_LANGUAGE_SWITCH` | API 34+, «may have no effect», требует скачанных пакетов, переключает только на границе сегмента |
| Класть 940 МБ в APK/AAB | Лимиты Play, обновление приложения = повторная загрузка |
| `armeabi-v7a` для ASR | 32-бит: адресное пространство и OOM на модели 1 ГБ |
| Строить всю архитектуру вокруг RU↔ZH code-switching, не проверив его | Ни одна модель не подтвердила этот сценарий тестом; сначала 20–30 фраз, потом архитектура |
| Собирать sherpa-onnx/whisper.cpp из исходников «для контроля» | Готовый AAR/пример покрывает 100 % нужд; своя сборка = свои баги и 16 KB page size |

---

## Источники

1. Qwen3-ASR Technical Report — [arXiv:2601.21337](https://arxiv.org/abs/2601.21337)
2. Карточка Qwen3-ASR-0.6B (Apache 2.0, 1.88 ГБ) — [HuggingFace](https://huggingface.co/Qwen/Qwen3-ASR-0.6B)
3. Обзор Qwen3-ASR (варианты, языки, железо) — [qwen-ai.com](https://qwen-ai.com/qwen-asr/)
4. sherpa-onnx: Qwen3-ASR pretrained models (размеры, языки, VAD-стриминг) — [docs](https://raw.githubusercontent.com/k2-fsa/sherpa/master/docs/source/onnx/qwen3-asr/pretrained.rst), [real-time.rst](https://raw.githubusercontent.com/k2-fsa/sherpa/master/docs/source/onnx/qwen3-asr/real-time.rst)
5. sherpa-onnx Kotlin API (`OfflineQwen3AsrModelConfig`, `hotwords`) — [OfflineRecognizer.kt](https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/master/sherpa-onnx/kotlin-api/OfflineRecognizer.kt)
6. sherpa-onnx Android AAR — [android/SherpaOnnxAar](https://github.com/k2-fsa/sherpa-onnx/tree/master/android/SherpaOnnxAar); 16 KB pages — [issue #2641](https://github.com/k2-fsa/sherpa-onnx/issues/2641)
7. Бенчмарк офлайн-ASR на Android/iOS/macOS/Windows (S10, RTF по моделям) — [VoicePing, 21.09.2026](https://voiceping.net/en/blog/research-offline-speech-transcription-benchmark/)
8. Vosk: модели, размеры, WER/CER, Apache 2.0 — [alphacephei.com/vosk/models](https://alphacephei.com/vosk/models); переключение языков — [issue #749](https://github.com/alphacep/vosk-api/issues/749)
9. SenseVoice Small: языки (zh/yue/en/ja/ko), лицензия `model-license` — [HuggingFace](https://huggingface.co/FunAudioLLM/SenseVoiceSmall)
10. Parakeet TDT 0.6B v3: 25 языков вкл. ru, WER, CC-BY-4.0 — [HuggingFace](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3)
11. GigaAM v3 (Sber, MIT, только ru) — [ai-sage/GigaAM-v3](https://huggingface.co/ai-sage/GigaAM-v3); int8 ONNX 225 МБ — [sherpa-onnx export](https://huggingface.co/csukuangfj/sherpa-onnx-nemo-ctc-giga-am-v3-russian-2025-12-16)
12. Moonshine — [moonshine-ai/moonshine-tiny](https://huggingface.co/moonshine-ai/moonshine-tiny)
13. llama.cpp: поддержка аудио-моделей, Qwen3-ASR GGUF, пометка о плохом результате Qwen2-Audio — [docs/multimodal.md](https://raw.githubusercontent.com/ggml-org/llama.cpp/master/docs/multimodal.md)
14. Whisper и code-switching (один языковой токен на окно 30 с) — [YazSes spec](https://mskazemi.com/yazses/design/specs/polyglot-switch.html)
15. Android `EXTRA_ENABLE_LANGUAGE_SWITCH`, API 34, «may have no effect» — [Android API reference](https://learn.microsoft.com/en-us/dotnet/api/android.speech.recognizerintent.extraenablelanguageswitch?view=net-android-34.0), [SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer)
16. Qwen3-ASR-Flash (только API, DashScope/Model Studio) — [Alibaba Cloud](https://www.alibabacloud.com/help/en/model-studio/qwen-asr-api-reference)
