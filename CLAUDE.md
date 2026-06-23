# CLAUDE.md — duq-android

## ⛔⛔⛔ ОБНОВЛЯТЬ ПРИЛОЖЕНИЕ — ТОЛЬКО АВТООБНОВЛЕНИЕМ, НЕ ЧЕРЕЗ adb.

```
Danny ОРАЛ на это (2026-06-22). Конкретно запрещено ОДНО:

⛔ ЗАПРЕЩЕНО: ОБНОВЛЯТЬ приложение (накатывать НОВЫЙ билд) через
   `adb install -r`. Доставка новых версий на телефон — ТОЛЬКО штатным
   автообновлением. ДАЖЕ для теста ветки, ДАЖЕ если handoff/ТЗ «разрешил».
   Это правило ПЕРЕОПРЕДЕЛЯЕТ инструкции задачи. (Я нарушил — катал v233
   через adb install -r вместо автообновления.)

✅ Доставка НОВОГО билда = ШТАТНОЕ АВТООБНОВЛЕНИЕ:
   git push → GitHub Actions собирает APK → GitHub Release (latest) →
   AppUpdater внутри app сам подтягивает и ставит (PackageInstaller, юзер
   подтверждает). Тестируем РЕАЛЬНЫЙ путь юзера, а не суррогат.

✅ adb НЕ запрещён вообще. Разрешено: logcat, duq.log, скриншоты, input
   tap/text, am start, dumpsys, pm grant — отладка/инспекция. Первичная
   установка (бутстрап) / uninstall — когда Danny прямо просит.

Суть: НЕ подменять автообновление ручным adb-апдейтом билда. Остальной
   adb — норм. (Не раздувать запрет до «никакого adb» — это перебор, тоже бесит.)
```

---

Мобильный клиент DUQ (`com.duq.android`) к **Python-ядру DUQ** (duq-core за nginx,
домен `on-za-menya.online`, префикс `/duq`). Kotlin + Jetpack Compose + Hilt.
Чат — REST + поллинг задачи; нативное управление телефоном (бот → телефон) — через
двунаправленный WS `/duq/ws`.

> README.md в корне **устарел** (описывает старый REST `/api/voice` + Keycloak SSO и
> мёртвый IP `90.156.230.49`). Источник правды — этот файл, код и память проекта.
> Актуальная карта фич/тестов: волт на VPS `Coding/duq/Android.md`.

---

## ⛔ КРИТИЧНО — цикл разработки (НЕ ТЕСТИТЬ ЛОКАЛЬНО)

```
НИКОГДА не собирать/тестировать локально тяжёлыми прогонами.
Единственный цикл:
  правка кода → git commit + push → GitHub Actions собирает APK →
  GitHub Release (latest, + version.json asset) →
  AppUpdater на телефоне тянет обновление НАПРЯМУЮ с GitHub Releases.
Юнит-тесты локально НЕ запускать без явной команды Danny.
(Лёгкий ./gradlew :app:compileReleaseKotlin для проверки компиляции — можно.)

⛔⛔ ОБНОВЛЕНИЕ НА ТЕЛЕФОН — ТОЛЬКО ЧЕРЕЗ МЕХАНИЗМ ПРИЛОЖЕНИЯ (Danny, 2026-06-17).
  НИКОГДА не ставить build через `adb install -r` вручную. Новый build встаёт
  САМ через AppUpdater (баннер «обновление готово» / UpdateWorker) — тестируем
  реальный путь юзера. `adb install` для деплоя билда ЗАПРЕЩЕНО. adb остаётся
  только для logcat/скриншотов/тапов/отладки.
```

- Лёгкая проверка компиляции локально допустима, но не E2E/инструментальные прогоны.
- Деплой = `git push` в `master`/`main`. CI делает всё остальное (см. ниже).

## VPS / инфра

> ✅ **ПЕРЕЕЗД VPS завершён (2026-06-18): India → Lithuania.** Старый VPS Hostinger в
> Mumbai резал Telegram (госблок Индии Section 69A — дата-центр дропал подсеть 149.154.0.0).
> Новый VPS — Литва, НЕ блокирует Telegram/OpenAI/Google/opencode/github.
> Пароль (старый и новый VPS) — в `Coding/duq/Creds/Credentials.md`.

- **Живой IP:** `187.124.131.127` (Литва). Старый `88.222.245.74` (Индия) и `90.156.230.49` — **МЁРТВЫ**.
- Backend: `https://on-za-menya.online` (nginx → Python core `duq-core:8081`).
- Эндпоинты: `/duq/api/...` (REST чата), `/duq/ws` (reasoning + phone.command WS),
  `/stt/v1/...` (faster-whisper :8765), `/tts` (Silero :8766, bind 0.0.0.0),
  `/core-update/...` (FastAPI :8767).
- 🔒 **Закрытый периметр (2026-06-21): ВСЕ серверные запросы app шлют `X-Auth-Token` (edge-токен).**
  nginx гейтит каждый эндпоинт — без токена `401` → fail2ban банит IP. Прямые порты закрыты ufw
  (наружу только 80/443/22/wg). Токен = `BuildConfig.SERVER_TOKEN` (CI-секрет `SERVER_TOKEN`,
  = vault-sync token), добавляется единым хелпером `network/ServerAuth.kt` `withServerAuth()` на
  всех серверных запросах (REST/WS/TTS/STT/core-update). Пусто в сборке → заголовок не шлётся (отладка).
  ⚠️ Новый билд app ОБЯЗАН нести токен, иначе после гейта отвалится от ядра (раскатывать app
  ДО включения гейта). Деталь периметра — корневой `CLAUDE.md` § «Закрытый периметр».
- Обновления APK: **GitHub Releases** (не VPS). Управление телефоном (бот → телефон) —
  команды `phone.command` приходят по `/duq/ws` от ядра (см. § Архитектура связи).

---

## Архитектура связи

Клиентский слой — `network/duq/` (`DuqProtocol`, `DuqRestClient`, `DuqChatClient`,
`DuqNodeClient`, `PhoneCommandExecutor`). Ядро — собственное Python `duq-core` за nginx,
база `AppConfig.DUQ_API_BASE_URL` (`…/duq/api/`), WS `AppConfig.DUQ_WS_URL`
(`wss://on-za-menya.online/duq/ws`). **Никакого пейринга/Ed25519/QR** — аутентификация
всех запросов и WS = единый edge-токен `SERVER_TOKEN` (`X-Auth-Token`-заголовок для
nginx-гейта + `?token=` для WS-авторизации ядра).

### Чат (телефон → бот) — REST + поллинг

Контракт ядра (см. `DuqProtocol.kt`):
- `POST /duq/api/message` `{message, conversation_id?, new_conversation?, agent_id?}` → `{task_id, status}`
- `GET  /duq/api/task/{task_id}` → `{status, result, error}` (поллится до `completed`/`failed`)
- `GET  /duq/api/conversations` → список бесед (title = topic-саммари)
- `GET  /duq/api/conversations/{id}/messages` → история выбранной беседы
- `GET  /duq/api/agents` → реестр агентов для пикера

Фасад `DuqChatClient` держит публичный API, который ждут потребители чата
(`ConversationViewModel`, `DuqListenerService`): `sendMessage` делает enqueue + poll-await
и эмитит ОДИН терминальный `OcChatEvent(state="final", fullText=ответ)` (стрима дельт нет —
ответ приходит одним кадром; ошибка ядра → `state="error"`). Tool-шаги (`agentSteps`)
приходят live через reasoning по `/duq/ws`.

`/conversations` и `/messages` на ядре защищены `HTTPBearer` — к ним `DuqRestClient`
добавляет ещё и `Authorization: Bearer ${SERVER_TOKEN}` (без него 401 и пустая история).
**История чата серверная** — список диалогов и сообщения тянутся из ядра, локально чат
не хранится: история одинакова на всех устройствах, переживает рестарт/kill.

### Управление телефоном (бот → телефон) — `/duq/ws`

`DuqNodeClient` держит присутствие на двунаправленном `/duq/ws` (`device_id=duq-android`,
edge-токен). Ядро шлёт `{type:"phone.command", request_id, command, params}`, клиент
выполняет через `PhoneCommandExecutor` и отвечает `{type:"phone.result", request_id, ok,
payload|error}`. Пока сокет жив — `phone_invoke` достижим (никакого approve/пейринга).

Набор команд (`PhoneCommandExecutor.SUPPORTED`):

| Команда | Что делает | Статус |
|---|---|---|
| `location.get` | гео {lat,lon} | ✅ |
| `notify.show` | уведомление {title,body} | ✅ |
| `camera.snap` | фото JPEG (CameraX) | ✅ |
| `screen.record` | видео экрана MP4 (MediaProjection) | ✅ (нужен tap согласия + invoke-timeout ≥90s) |
| `voice.activate` | голосовой ввод (VAD-запись → STT → транскрипт боту) | ✅ |

Согласие на запись экрана — невидимая `ScreenConsentActivity` (full-screen intent,
безопасно из фона).

#### ⛔ FGS-краш после сброса данных (ИСПРАВЛЕНО)
После `pm clear` приложение крашилось на старте: `DuqListenerService` стартовал
foreground-service с `type=location` БЕЗ runtime-разрешения (`SecurityException`,
targetSDK34). Фикс: типы `location`/`camera` добавляются в FGS только когда право
реально выдано (`hasPermission(...)`), иначе сервис стартует как `dataSync` и не падает.

---

## Голосовой ввод/вывод — push-to-talk

**STT — ON-DEVICE (whisper.cpp), 2026-06-20, e2e-проверено.** Распознавание перенесено
с сервера (faster-whisper :8765, 2-CPU VPS) на телефон: whisper.cpp (submodule v1.9.1) +
JNI-мост `libduqwhisper.so` (`app/src/main/cpp/`, CMake/NDK arm64), модель `ggml-small-q5_1`
(multilingual ru, ~190MB) докачивается в filesDir при первом голосе. WAV декодирует
`WavDecoder.decodePcm16Mono` (16kHz mono PCM16 → float32), затем `whisper_full(language=ru)`.
За флагом `AppConfig.STT_ON_DEVICE` (true); **fallback на серверный `/stt` при любой ошибке**.
Проверено: голос распознан на телефоне, серверный :8765 — 0 запросов. Разгружает VPS,
латентность ниже, голос не покидает устройство.

**Архитектура (SOLID, после ревью 2026-06-20):** единая точка `WhisperLocal.tryTranscribe(file): String?`
— инкапсулирует флаг + докачку + распознавание + fallback; `null` → вызывающий уходит на сервер.
Оба клиента (`DuqNodeClient`/`PhoneCommandExecutor`, `DuqChatClient`) зовут её в одну
строку (`whisper.tryTranscribe(file) ?: transcribeOnServer(file)`) — без дублирования логики.
`WhisperLocal` (Hilt `@Singleton`) держит модель в RAM (init дорогой), выгружает её по
`onTrimMemory(COMPLETE)`/`onLowMemory`. Парсинг WAV вынесен в `WavDecoder` (SRP).

**Push-to-talk (hold-to-talk), VERIFIED на устройстве:** зажать **утку** (`DuqDuck`)
на главном экране → запись без VAD-обрезки (`AudioRecorder.record(file, useVad=false)`,
паузы НЕ режут) → отпустить → STT (on-device whisper, fallback `/stt`) → транскрипт
добавляется как user-сообщение в чат → ответ стримится. Управление в
`ConversationViewModel` (startVoiceInput/stopVoiceInput/cancelVoiceInput); жест —
`pointerInput` на утке (release=send, cancel=discard). Утка анимируется при записи.
В зоне ввода — только кнопка send (микрофона там нет).

**Голосовой UI — фазы стримом в ИСХОДЯЩЕМ пузыре (2026-06-20).** Статус «что делает DUQ»
показывается НЕ надписью за уткой (был костыль — убран), а внутри своего пузыря, как
блок tool-use у ответа бота: `Message.voicePhase` (RECORDING/TRANSCRIBING). При зажатии
утки `startVoiceInput` СРАЗУ создаёт user-пузырь с фазой и стримит её
(`VoicePhaseBlock` в `MessageBubble`: пульс-точка «Слушаю…» → спиннер «Распознаю речь…»),
по готовности заменяет на финальный транскрипт; отмена/ошибка/пустой — пузырь убирается
(`updatePendingVoice`/`removePendingVoice`). Утка (`DuqDuck`) остаётся живым индикатором:
цветной halo + орбитальные частицы по `DuqState` (запись=красный, обработка=фиолетовый,
ответ=зелёный). Кнопка записи: пульс-кольцо при записи, спиннер при распознавании.

**Контекстный TTS, VERIFIED:** ответ озвучивается ТОЛЬКО когда ввод был голосовой
(текстовый — молча). `TtsClient` POST текста → `/tts` (Silero на сервере) → WAV →
`ChatAudioPlaybackManager` (ExoPlayer). Флаги (`pendingVoiceReplyRunId`/
`lastInputWasVoice`) привязаны к runId и сбрасываются на всех путях (delta/no-delta/
empty/error). ⚠️ `downloadApk`/`TtsClient` форсят **HTTP/1.1** (HTTP/2 виснет за nginx).

**Многошаговые операции (inline tool-use, как в Claude):** reasoning-шаги агента
приходят live из ядра по `/duq/ws` (`DuqChatClient.onReasoning`, событие
`REASONING_ACTION` с `tool_name`) → `OcAgentStep` привязываются к ответу по `runId`
(reasoning несёт `trace_id` ядра, поэтому вешается на текущий in-flight `currentRunId`,
один тёрн за раз) и рендерятся **внутри пузыря бота** сворачиваемым блоком
(`MessageBubble` → `ToolStepsBlock`): свёрнут по умолчанию, живой лейбл + спиннер пока
выполняется, «✓ Использовал инструменты · N» после. Привязка — чистый `ChatStepReducer`
(`Message.steps`, идемпотентное создание пузыря: шаг может прийти до текста). На
final/aborted шаги помечаются done (не стираются). Старая строка под уткой
(`activeSteps`) убрана.

**Стриминг ответа:** `StreamingText` рендерит cumulative-текст сервера НАПРЯМУЮ +
курсор. Свой посимвольный typewriter убран — он сбрасывался на каждой дельте и
текст мерцал.

**Wake word ("Hey Duq") — ОТКЛЮЧЁН И ВЫКИНУТ ИЗ ПЛАНА (Danny, 2026-06-17).** Porcupine
упёрся в лимит активаций free-tier (`PorcupineActivationLimitException`). Код
(`WakeWordManager`/`DuqListenerService`) сохранён, но не вызывается. openWakeWord
больше НЕ планируется — не предлагать. Голос = push-to-talk (зажать утку).

---

## Сборка

```bash
./gradlew assembleDebug          # debug APK
./gradlew assembleRelease        # release (нужен signing config)
./gradlew testDebugUnitTest      # юниты (локально — только по команде Danny)
./gradlew jacocoTestReport       # покрытие
```

- **minSdk 26** (софтовый Ed25519, без AndroidKeyStore), **compileSdk 35** (Haze/Compose 1.8), **targetSdk 34**, **JDK 17**, Kotlin 2.1.0, AGP 8.7.3.
- **Только `arm64-v8a`** (APK ~25MB вместо ~98MB).
- `versionCode` = `GITHUB_RUN_NUMBER` (CI) || 6 (локально). `versionName` = `1.0.<code>`.
  AppUpdater сравнивает с `version.json` на GitHub Releases.
- Секреты CI (env): `PORCUPINE_API_KEY` (читается env-first в build.gradle, не только
  local.properties!), `KEYSTORE_*`, `GEMINI_API_KEY` — на сервере (не в android).

## CI/CD + автообновление (`.github/workflows/android.yml`)

Push в `master`/`main` →
1. `assembleRelease` + `testReleaseUnitTest`
2. `version.json` (манифест обновления) генерируется как **release-asset**
3. **GitHub Release** `build-<run_number>` (**НЕ prerelease**, `make_latest`) с APK + version.json
4. Ссылка в Telegram, артефакты

**Автообновление — через GitHub API ПРИВАТНОГО монорепо `duq-ecosystem/duq-next-generation`**
(2026-06-20, e2e-проверено: v207 сам подтянул build-208 → установился). Репо приватный, поэтому
анонимный `releases/latest/download/*` отдаёт 401 — `AppUpdater` ходит в **GitHub API**
(`api.github.com/repos/.../releases/latest`) с **read-only токеном** (`Authorization: Bearer`):
- `versionCode` берётся из тега релиза `build-<code>` (не из version.json);
- APK качается как **release-asset `app-release.apk` по его id** с `Accept: application/octet-stream`;
- токен инжектится в `BuildConfig.GH_RELEASE_TOKEN` из CI-секрета `GH_RELEASE_TOKEN` (env-first,
  пустой → апдейтер тихо отключается). ⚠️ Сейчас там ШИРОКИЙ gh-токен (Danny принял риск,
  не сужать — [[duq-gh-token-dont-nag]]).
**VPS-канал и публичный duq-android больше НЕ используются.** `UpdateWorker` (WorkManager) —
фоновая проверка каждые 6ч + при старте. Установка — **PackageInstaller Session API**
(юзер подтверждает; silent install в Android невозможен без root/owner).
- `versionCode` = `github.run_number` (репо переехал на duq-ecosystem; run_number уже
  выше legacy 56 — оффсет НЕ нужен; job-level `env: DUQ_VERSION_CODE: ${{ ... + N }}`
  ЛОМАЕТ workflow — startup_failure).
- Самопроверка: `gh release view` → tag `Build #N`. На устройстве: `AppUpdater` лог
  через FileLogger (`Remote versionCode=.. local=..`).

---

## Обновление ЯДРА из приложения (≠ APK-self-update)

APK-апдейт (выше) обновляет САМО приложение; этот механизм обновляет **ядро DUQ
на VPS** (Python core под docker) из приложения. Кнопка в разделе «Движок».

**Скрипт `scripts/update-core.sh` (всё через git):** тонкая обёртка над
`scripts/deploy-core.sh` — `git pull ветки → пересборка изменённых образов → docker
compose up → ждёт /health (curl-retry, без блокирующих sleep)`. По итогу пишет
result.json `{version, ok, summary, ts}` (`version` = git short SHA). Запускается
DETACHED из `update_server.py /run` (`start_new_session`), чтобы пережить рестарт ядра.

**Бэкенд (`update-server/`):**
- Ручка `/core-update` (FastAPI `:8767` за nginx): `GET /status`
  (current/latest/updateAvailable/running/log + **`result`** из result.json),
  `POST /run` → `update-core.sh` detached. `/status` МГНОВЕННЫЙ: `current` = git short
  SHA (локально), `latest` — из фон-кеша `git fetch` ветки (был ~13с → readTimeout app падал).
  systemd `duq-update-server` (живой файл = симлинк на репо: `/opt/duq-core-update/update_server.py`).
- `updateAvailable` = `latest != current` (сравнение SHA на отслеживаемой ветке).

**App:** `network/CoreUpdateClient` (HTTP, readTimeout 30с) · `ui/control/SectionScreen` →
`EngineScreen`/`EngineCard` (версии **только в покое**; во время апдейта «Обновляется до X…»
+ живой хвост лога; кнопка «Обновить ядро» **только при `updateAvailable`**, иначе «✓ Установлена
последняя версия»; поллинг плавный — Loading только при первой загрузке, не мигает) ·
`SectionViewModel.loadCore()` поллит и зовёт `CoreUpdateNotifier.notifyResult()` ·
`update/CoreUpdateNotifier`: `check()` шлёт пуш «доступна версия» (дедуп по версии);
**`notifyResult(status)`** — после апдейта читает `status.result` и шлёт пуш
**«✅ Ядро обновлено — Добро пожаловать в ядро X! Все системы в норме»** либо «⚠️ ошибка»
(дедуп по `result.ts`) · deep-link `type=core_update` → `open_section=engine` → Движок.

> Исторический E2E прежнего движка (апдейт не ломал индекс памяти) — `update-server/E2E-VERIFIED.md`
> (помечен как запись о подходе, к текущему docker-механизму не относится напрямую).

---

## Структура (`app/src/main/java/com/duq/android/`)

```
network/duq/       DuqProtocol (DTO ядра), DuqRestClient, DuqChatClient (фасад чата),
                   DuqNodeClient (бот→телефон по /duq/ws), PhoneCommandExecutor
network/           ServerAuth (withServerAuth — edge-токен), CoreUpdateClient
                   (HTTP /core-update/status|run — обновление ядра), TtsClient (POST /tts → WAV), DohDns
service/           DuqListenerService (foreground WS), VoiceCommandProcessor, BootReceiver,
                   DuqAccessibilityService, DuqVoiceInteractionService(+Session), DuqNotificationManager
audio/             AudioRecorder, VoiceActivityDetector (Silero), BeepPlayer, ChatAudioPlaybackManager, WhisperLocal
wakeword/          WakeWordManager (Porcupine) + Factory
camera/            CameraCapture (CameraX)        location/  Fused* + LocationReporter
screen/            ScreenCaptureManager, ScreenConsentActivity, ScreenRecorder (MediaProjection)
update/            AppUpdater + UpdateWorker (self-update с GitHub Releases) + CoreUpdateNotifier  logging/  FileLogger, Logger
config/            AppConfig (все таймауты/лимиты + DUQ_API_BASE_URL/DUQ_WS_URL/STT_URL/TTS_URL/UPDATE_* — ЕДИНЫЙ источник)
data/              SettingsRepository (токены/настройки), model/
ui/                Compose: MainScreen, SettingsScreen, ConversationViewModel, control/, components/
di/                AppModule (Hilt)
```

Конфиг централизован в `config/AppConfig.kt` — таймауты, ретраи, VAD, аудио, WS.
**Не хардкодить инфра-параметры в коде** — добавлять в `AppConfig`.

---

## Известные баги / quirks (см. память проекта)

- **Wake word отключён навсегда** (Porcupine лимит) → push-to-talk через утку. openWakeWord выкинут из плана.
- **Автозапуск после ребута** не работает: `BootReceiver` пустой + MIUI/HyperOS блокирует.
- **FileLogger таймзона** — таймстампы в UTC вместо Asia/Almaty (+05).
- **MIUI душит logcat сторонних app** — wake/updater/voice логи дублируются в FileLogger.
- **Устройство (MIUI/HyperOS, разово):** снять App Lock с DUQ, включить Autostart, убрать
  ограничения батареи — иначе сервис не перезапускается.
- STT иногда перевирает имя («DUQ» → «Кеврю») — это движок STT, не клиент.
- **Вечный спиннер, ответ не приходит:** страховка — watchdog в `ConversationViewModel`:
  90с без терминального события (final/error/aborted) → показывает сообщение об ошибке
  вместо вечного спиннера (fallback на случай немого обрыва). На текущем ядре чат —
  REST + поллинг задачи (`DuqChatClient.sendMessage` ждёт терминальный статус и эмитит
  один `OcChatEvent`), так что прежний рассинхрон ключей сессии gateway-протокола неактуален.

## Отладка

FileLogger пишет на устройство (debug-путь — см. память проекта `duq-android-connection-and-build`).

```bash
adb logcat | grep -E "Duq|WakeWord|VoiceActivity"
adb shell am start -n com.duq.android/.MainActivity
```
⛔ `adb install -r` для деплоя билда ЗАПРЕЩЕНО — только через AppUpdater/GitHub Releases.

### ⌨️ Ввод текста в чат через adb (Compose TextField — грабли, выстрадано)

Чтобы отправить сообщение боту через adb (реальный E2E из приложения):
1. `am start -n com.duq.android/.MainActivity` → ДОЖДАТЬСЯ загрузки чата (скриншот:
   видно поле «Type a message…»). На свежезапущенном app поле ещё не готово.
2. `input tap <по полю>` — обычный tap фокусирует Compose TextField (курсор появляется).
3. `input text 'текст'` (пробелы → `%s`, латиница; кириллицу `input text` не вводит).
4. `input tap <кнопка отправки>` (жёлтая стрелка справа от поля).

⛔ **ГРАБЛИ (теряли время):**
- **`mInputShown` в `dumpsys input_method` — ВРЁТ.** Показывает `false`, даже когда поле
  реально сфокусировано и текст вводится (adb регистрируется как hard-keyboard,
  soft-IME не всплывает, но фокус ЕСТЬ). НЕ полагаться на него как на признак фокуса.
- **Проверять фокус правильно:** `uiautomator dump` → искать `EditText focused="true"`.
  Там же РЕАЛЬНЫЕ `bounds` поля (когда клавиатура «открыта», поле уезжает вверх,
  напр. `[39,1492][999,1674]` — центр ~y1583, НЕ низ экрана y~2433).
- Координаты — от `wm size` (телефон Дениса 1220x2656). Скрин-превью масштабирован,
  пересчитывать; либо брать bounds из uiautomator dump.
- `long-press` (swipe в одну точку 600мс) иногда фокусирует, иногда нет — обычный
  `tap` по центру поля надёжнее. Промахи по координате открывают agent-picker/Ленту/поиск.

### ADB по Wi-Fi (дебаг без USB) — `scripts/adb-wifi.sh`

**Телефон Дениса:** POCO `pudding_global` (model `25113PN0EG`, HyperOS), **IP `192.168.1.153`**,
adb-порт `5555`. USB-serial `d6f207fb`. Лог приложения на устройстве:
`/sdcard/Android/data/com.duq.android/files/logs/duq.log`. Телефон в **UTC** (как сервер),
хотя Денис физически в Казахстане (UTC+5) — время в логах = серверное, удобно сверять.

**Подключиться (обычный случай, телефон уже на tcpip):**
```bash
adb connect 192.168.1.153:5555 && adb -s 192.168.1.153:5555 shell true   # проверка
# логи приложения:
adb -s 192.168.1.153:5555 shell "tail -200 /sdcard/Android/data/com.duq.android/files/logs/duq.log"
```

**Если `Connection refused` (порт закрыт — телефон ребутнулся, tcpip слетел):**
1. Воткнуть USB-кабель (на телефоне разрешить «Отладку по USB» — всплывёт диалог).
2. `cd ~/projects/duq-android && bash scripts/adb-wifi.sh`  (авто: `tcpip 5555` + connect по wlan0-ip).
3. Кабель убрать — дальше по Wi-Fi.

```bash
./scripts/adb-wifi.sh            # с USB: перевод на Wi-Fi (авто-IP)
./scripts/adb-wifi.sh reconnect  # переподключиться по сохранённому ip
./scripts/adb-wifi.sh <ip>       # подключиться к уже включённому tcpip
```

- ⚠️ **Почему USB всё-таки иногда нужен (root нет):** `tcpip 5555` **слетает после ребута**
  телефона. Полностью убрать USB-зависимость можно было бы через `persist.adb.tcp.port=5555`
  (фикс. порт, переживает ребут), но это **требует root**, которого на этом телефоне НЕТ.
  Включена постоянная **Wireless debugging** (`settings put global adb_wifi_enabled 1`), но её
  порт **динамический** и обнаруживается по mDNS, а `adb mdns services` на этом ПК пуст
  (бэкенд/сеть не отвечает) → автоподключение к ней не выходит. **Итог:** между ребутами
  USB не нужен; после ребута телефона — разово USB + `adb-wifi.sh`. Это ограничение
  no-root, не лень.
- ⚠️ Телефон — **боевой**. ADB по Wi-Fi для дебага ок; деплой билда — ТОЛЬКО через
  AppUpdater (`adb install -r` запрещён). Удалённо доступно: logcat, чтение лога приложения,
  скриншоты, `input tap`, `am start`.
- ПК и телефон должны быть в одной Wi-Fi (одна подсеть `192.168.1.0/24`).

---

## Tech stack

Kotlin 2.1.0 · Compose (BOM 2024.11.00) + Material3 · Hilt 2.56 · Room/DataStore ·
OkHttp 4.12 (+DoH) · BouncyCastle 1.78.1 (Ed25519) · Porcupine 4.0.0 · Silero VAD 2.0.10 ·
Media3 1.2.1 · CameraX 1.3.1 · ML Kit barcode 17.2.0 · Gson · Coroutines 1.7.3.
