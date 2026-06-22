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

Мобильный клиент DUQ (`com.duq.android`) к движку **OpenClaw**. Kotlin + Jetpack
Compose + Hilt. Двусторонняя нативная связь с ботом через gateway по WebSocket.

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
- Backend (gateway): `https://on-za-menya.online` (nginx → openclaw `:18789`).
- Эндпоинты: `/openclaw` (WS), `/stt/v1/...` (faster-whisper :8765), `/tts` (Silero :8766, bind 0.0.0.0).
- 🔒 **Закрытый периметр (2026-06-21): ВСЕ серверные запросы app шлют `X-Auth-Token` (edge-токен).**
  nginx гейтит каждый эндпоинт — без токена `401` → fail2ban банит IP. Прямые порты закрыты ufw
  (наружу только 80/443/22/wg). Токен = `BuildConfig.SERVER_TOKEN` (CI-секрет `SERVER_TOKEN`,
  = vault-sync token), добавляется единым хелпером `network/ServerAuth.kt` `withServerAuth()` на
  TTS/STT/core-update/gateway-WS (operator+node). Пусто в сборке → заголовок не шлётся (отладка).
  ⚠️ Новый билд app ОБЯЗАН нести токен, иначе после гейта отвалится от gateway (раскатывать app
  ДО включения гейта). Деталь периметра — корневой `CLAUDE.md` § «Закрытый периметр».
- Движок: npm-global `openclaw` (`/usr/lib/node_modules/openclaw`), systemd `duq-openclaw`.
  Конфиг: `/root/.openclaw/openclaw.json`. Применить: `systemctl restart duq-openclaw`.
- **Vision:** провайдер `google` (native `api: google-generative-ai`!), `imageModel`
  `google/gemini-3.1-flash-lite`, ключ `GEMINI_API_KEY` в `/root/.openclaw/.env`. Chat
  остаётся на opencode-go. (opencode-go vision не работает — Cloudflare 1010.)
- Обновления APK: **GitHub Releases** (не VPS). Skill `phone-control`:
  `/root/.openclaw/workspace/skills/phone-control/SKILL.md` (workspace = plaintext-мозг;
  E2EE-копия в вольте `/opt/obsidian-vault/cortex/skills/...`, two-way синк).

---

## Архитектура связи (OpenClaw)

WS endpoint: `wss://on-za-menya.online/openclaw` (`SettingsRepository.DEFAULT_GATEWAY_URL`,
переопределяется в настройках). **Protocol v4** (сервер пиннит `2026.6.x` — заявлять
именно 4, диапазон 1..99 сервер отвергал).

**Две роли с одного устройства, РАЗНЫЕ Ed25519-ключи:**

| Роль | Направление | Назначение | Клиент |
|---|---|---|---|
| **operator** | телефон → бот | чат (стриминг ответов) | `OpenClawGatewayClient` |
| **node** | бот → телефон | нативное управление телефоном через `node.invoke` | `OpenClawNodeClient` |

Node — родной механизм OpenClaw (не самописный). Велосипед `__duq_cmd`-в-чате **удалён**.

### Identity (`auth/DeviceIdentityManager`)

Софтовый **Ed25519 через BouncyCastle** (AndroidKeyStore Ed25519 оказался ненадёжен —
NPE в getCertificate(), сигнатуры отвергались как `DEVICE_AUTH_SIGNATURE_INVALID`).
Контракт gateway байт-в-байт:
- `publicKey` = base64url(raw 32-byte pubkey)
- `device.id` = `SHA256(raw pubkey).hex` (выводится, не хранится)
- `signature` = base64url(raw 64-byte подпись)
- 32-байтный seed — в зашифрованных prefs. **Отдельные ключи** `operator` / `node`.

### Node-команды (бот → телефон)

`OpenClawNodeClient` объявляет capabilities + commands, отвечает на `node.invoke.request`
через `node.invoke.result` (схема требует поля `id` = invokeId и `ok`; НЕ `requestId`).

| Команда | Что делает | Статус |
|---|---|---|
| `location.get` | гео {lat,lon} | ✅ |
| `notify.show` | уведомление {title,body} | ✅ (в т.ч. через движок) |
| `camera.snap` | фото JPEG (CameraX) | ✅ + vision-описание (движок, gemini) |
| `screen.record` | видео экрана MP4 (MediaProjection) | ✅ (CLI; нужен tap согласия + invoke-timeout ≥90s) |
| `voice.activate` | голосовой ввод (VAD-запись → STT → транскрипт боту) | ✅ |

`CAPS = [location, notify, voice, camera, screen]`. Согласие на запись экрана —
невидимая `ScreenConsentActivity` (full-screen intent, безопасно из фона).

### История чата (СЕРВЕРНАЯ — не локальная)

Транскрипт живёт на сервере (openclaw владеет сессией `main`). Клиент при первом
`CONNECTED` тянет `request("chat.history", {sessionKey:"main", limit})` →
`{messages:[{role,content}]}` (`OpenClawGatewayClient.fetchHistory`) и сеет в
`_messages` **только пока чат пуст** (`ConversationViewModel.restoreServerHistory`) —
иначе live-стрим, прилетевший в окно коннекта, задвоит пузырь. `chat.history` уже
display-normalized сервером (срезаны tool-call XML / директивы / `NO_REPLY`).
**НЕ хранить чат локально** (был prefs+Gson велосипед — удалён): единый источник
правды на сервере → история одинаковая на всех устройствах, переживает рестарт/kill.

### Пайринг

Bootstrap-токен из QR (ML Kit) или ручной ввод setup-code → `device.pair`. Тот же
токен переиспользуется для node-пайринга. Поллинг pending — неблокирующий.

#### ⛔ Две роли на ОДНОМ телефоне (это архитектура OpenClaw, не наша)
- **operator** = телефон→бот (чат). **node** = бот→телефон (камера/локация/уведомления).
- Две отдельные WS-сессии, ДВА токена. Обе используют ОДИН Ed25519-ключ (`keyName="operator"`,
  shared — bootstrap биндится к одному publicKey). phone-control работает ТОЛЬКО через node.

#### ⛔ Auth-цепочка node-клиента (durability-ordered, v222 / 2026-06-20)
`OpenClawNodeClient.connect()` выбирает токен по убыванию долговечности:
**node-token → operator device-token → bootstrap (last resort)**. Раньше без node-токена
сразу падал на bootstrap (TTL 10 мин) → закрылось окно `nodes approve` = нода намертво
в `bootstrap_token_invalid`, хотя у телефона есть валидный долгоживущий operator-токен на
ТОМ ЖЕ ключе. Теперь node без своего токена авторизуется operator-токеном — gateway
кеширует node-pairing по подписанному `device.id` (`reconcileNodePairingOnConnect`), так
что валидной device-подписи достаточно, чтобы заново открыть NODE-level pending без
зависимости от 10-мин bootstrap. Доп.: при auth-reject node-токена (revoke / ротация
ключа) он **сбрасывается** (`saveNodeToken("")`) → реконнект уходит на operator-token
(one-shot, не зацикливается). Лог коннекта: `Node connecting … (auth=node-token|operator-token|bootstrap)`.
E2E-проверено на устройстве (v222): нода переживает `nodes remove`, сама переподключается,
`notify.show`→shown:true, `location.get`→lat/lon. ⛔ Это НЕ замена правилу «не `pm clear`»
(см. ниже) — ключ всё равно беречь; код-фикс лишь убирает 10-мин хрупкость восстановления.

#### ⛔ Восстановление node-пайринга (выстрадано часами 2026-06-19/06-20 — делать ИМЕННО так)
Симптом: чат работает, а камера/локация/`nodes invoke` — нет; в логах gateway
`bootstrap_token_invalid` каждые ~2 мин, либо `node did not declare any supported commands`.

**⛔⛔ ПЕРВОПРИЧИНА «нода постоянно слетает» (2026-06-20, докопано до дна):**
node-токен (долгоживущий, в `nodes/paired.json`) привязан к **Ed25519-ключу устройства**
(`device_key_seed` в prefs; operator и node делят ОДИН ключ — `OpenClawNodeClient`
`keyName="operator"`). Пока ключ жив — нода коннектится своим node-токеном вечно. Ключ
**переживает обновление APK** (seed в `EncryptedSharedPreferences`; проверено — 0
`Encrypted prefs failed` за 20ч, operator-чат стабилен). Ключ **меняется ТОЛЬКО при
`pm clear` / полном сбросе данных.** Каждый `pm clear` → новый ключ → старый node-токен
мёртв → нужен re-pair. А `OpenClawNodeClient.connect()` без node-токена авторизует ноду
**протухающим bootstrap (TTL 10 мин!)**, не долгоживущим operator-токеном — профукал
10-мин окно `nodes approve` → нода застряла навсегда в `bootstrap_token_invalid`.
**Вывод: НЕ делать `pm clear` для починки ноды — это и есть то, что её роняет.**
Сбрасывать только operator-токен через **Settings → Unpair** (`clearPairing()` —
удаляет только `device_token`, СОХРАНЯЕТ seed/ключ → re-pair идёт на ТОМ ЖЕ ключе,
старый/новый node-токен под тем же deviceId, нода стабильна).

**Грабли approve (2026-06-20):**
- `openclaw devices approve <reqId>` требует **ПОЛНЫЙ** requestId (короткий 8-символьный
  → `unknown requestId`). Брать полный из `openclaw devices list`.
- gateway **in-memory pendingById рассинхронен с файлами**: `devices list`/файл
  `devices/pending.json` показывают pending, а `approve`/`nodes pending` его «не видят».
  Лечится **рестартом gateway** (`systemctl restart duq-openclaw` — перечитывает pending),
  ЛИБО рестартом app (создаёт свежий pending в живом gateway).
- node-роль на устройстве с уже-одобренным operator = pending статус **`role upgrade,
  repair`** → одобряется обычным `devices approve <полный-reqId>`.
- **node-регистрацию с commands в `nodes/paired.json` создаёт ТОЛЬКО `openclaw nodes
  approve <полный-reqId>`** (после device-approve и рестарта app, когда node-клиент
  поднялся `role=node tokenIssued=true` и движок выдал NODE-level pending). `devices
  approve` даёт node-ТОКЕН, но БЕЗ commands. Это разные шаги.

**Процедура БЕЗ смены ключа (НЕ `pm clear` — сохраняет identity, e2e-проверено 2026-06-20):**
```bash
# 1. Settings → Unpair (сбрасывает ТОЛЬКО operator-токен, ключ цел). Затем рестарт app:
adb shell am force-stop com.duq.android && adb shell am start -n com.duq.android/.MainActivity
#    app покажет PairingScreen (роутинг по isPaired только на старте).
# 2. "Enter code manually" → вставить СВЕЖИЙ setup-code → Pair (живёт 10 мин!):
openclaw qr --setup-code-only --no-ascii | tail -1
#    (этот setup-code — чистый base64 [A-Za-z0-9], вводится adb `input text` как есть)
# 3. operator redeem'ится сам; node-роль = device-pending "role upgrade, repair".
#    Если CLI не видит pending (рассинхрон) → systemctl restart duq-openclaw, затем:
openclaw devices list                          # взять ПОЛНЫЙ requestId (role=node)
openclaw devices approve <ПОЛНЫЙ-reqId>         # → node device-token под текущим ключом
# 4. рестарт app — node-клиент берёт node-токен, поднимается role=node, объявляет commands:
adb shell am force-stop com.duq.android && adb shell am start -n com.duq.android/.MainActivity
#    в duq.log: "Node connected — role=node tokenIssued=true" + "node token persisted"
openclaw nodes status                          # покажет "Approval pending ... nodes approve <reqId>"
openclaw nodes approve <ПОЛНЫЙ-reqId>          # ← создаёт запись с commands в nodes/paired.json
# 5. дать права (иначе location.get = "location unavailable", FGS как dataSync):
adb shell pm grant com.duq.android android.permission.ACCESS_FINE_LOCATION
# 6. E2E (реальный результат, не логи):
openclaw nodes invoke --node <ПОЛНЫЙ-id> --command notify.show --params '{"title":"t","body":"b"}'  # → shown:true + видно на экране
openclaw nodes invoke --node <ПОЛНЫЙ-id> --command location.get --params '{}'                        # → lat/lon
# 7. убрать осиротевшую старую identity (старый deviceId под мёртвый ключ):
openclaw nodes remove --node <старый-id>
```
- `nodes list`/`nodes pending` после рестарта gateway могут показывать **устаревший
  in-memory кэш** — сверяйся с файлами `~/.openclaw/devices/{paired,pending}.json` и
  `~/.openclaw/nodes/paired.json` (там истина).
- v172 слал лишний scope `operator.admin` (3 scopes) → bootstrap-профиль (2 scopes:
  read/write) не совпадал → reject. **С v173+ (OPERATOR_SCOPES=read,write) пейрится.**

#### ⛔ FGS-краш после сброса данных (ИСПРАВЛЕНО)
После `pm clear` приложение крашилось на старте: `DuqListenerService` стартовал
foreground-service с `type=location` БЕЗ runtime-разрешения (`SecurityException`,
targetSDK34). Фикс: типы `location`/`camera` добавляются в FGS только когда право
реально выдано (`hasPermission(...)`), иначе сервис стартует как `dataSync` и не падает.

---

## Голосовой ввод/вывод (operator) — push-to-talk

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
Оба клиента (`OpenClawNodeClient`, `OpenClawGatewayClient`) зовут её в одну строку
(`whisper.tryTranscribe(file) ?: transcribeOnServer(file)`) — без дублирования логики.
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

**Многошаговые операции (inline tool-use, как в Claude):** tool/command шаги агента
(gateway `event:"agent" stream:"item"`) → `OcAgentStep` привязываются к своему ответу
по `runId` и рендерятся **внутри пузыря бота** сворачиваемым блоком (`MessageBubble`
→ `ToolStepsBlock`): свёрнут по умолчанию, живой лейбл + спиннер пока выполняется,
«✓ Использовал инструменты · N» после. Привязка — чистый `ChatStepReducer` (`Message.steps`,
идемпотентное создание пузыря: шаг может прийти до первой text-дельты). На final/aborted
шаги помечаются done (не стираются). Конфликта со стримом нет — шаги и chat-дельты идут
одним упорядоченным каналом с общим `runId`. Старая строка под уткой (`activeSteps`) убрана.

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

## Обновление ЯДРА openclaw из приложения (≠ APK-self-update)

APK-апдейт (выше) обновляет САМО приложение; этот механизм обновляет **ядро openclaw
на VPS** из приложения. Кнопка в разделе «Движок». **E2E проверено на устройстве 2026-06-18.**

**⛔ КЛЮЧЕВОЕ: апдейт ядра НЕ должен трогать память.** Индекс строится ПИННУТОЙ
gguf-моделью (`memorySearch.model`) — бамп версии ядра её не меняет → индекс переживает
апдейт. Встроенный gateway `update.run` (голый npm) ломает индекс — **НЕ использовать**.
Только `scripts/update-openclaw.sh`. История/грабли — память `duq-openclaw-core-update-memory`.

**Финальный скрипт `update-openclaw.sh` (канон, всё через гит):**
`snapshot sqlite → systemctl stop → npm install -g openclaw@latest → systemctl start →
readiness(/health) → self-check(health-monitor) → пишет result.json → авто-коммит OPENCLAW_VERSION`.
- **НЕТ** `doctor`, `plugins install`, `rm sqlite`, реиндекса, `sleep`, `timeout`. `current`
  читается из package.json (ноль виснущих openclaw-CLI). `latest` — `npm view`.
- **stop ПЕРЕД npm** (не restart в конце): npm перезаписывает хешированные dist-чанки —
  на живом gateway это «Cannot find module»-ошибки инструментов на всё окно установки.
- **Снапшот памяти** (`*.sqlite → *.pre-update` после stop) — мгновенный откат если будущая
  версия побьёт индекс. **Trap** на падении поднимает gateway обратно (не оставить бот лежать).
- **readiness** = ждём HTTP `/health` движка (`curl --retry-connrefused`, до ~4.5 мин прогрева),
  не `systemctl is-active`. **self-check интеграций** — вердикт встроенного `health-monitor`
  из журнала (НЕ `doctor`). Итог пишется в `/tmp/openclaw-update-result.json` `{version,ok,summary,ts}`.

**Бэкенд (duq-next-generation):**
- Ручка `/core-update` (FastAPI `:8767` за nginx): `GET /status` (current/latest/
  updateAvailable/running/log + **`result`** из result.json), `POST /run` → скрипт detached.
  `/status` МГНОВЕННЫЙ: current из package.json, latest из фон-кеша (был 13с → readTimeout app падал).
  systemd `duq-update-server` (живой файл = симлинк на репо: `/opt/duq-core-update/update_server.py`).
- ⛔ `notify-openclaw-update.sh` (серверный cron) **УДАЛЁН** — уведомления шлёт приложение
  (надёжно, через /status), а cron'овский `openclaw nodes invoke` виснет (см. ниже) + был с timeout.
- `TimeoutStopSec=45с` (systemd drop-in, в гите) — graceful-стоп 13-21с, дефолтные 90с = лишний даунтайм.

**App:** `network/CoreUpdateClient` (HTTP, readTimeout 30с) · `ui/control/SectionScreen` →
`EngineScreen`/`EngineCard` (версии **только в покое**; во время апдейта «Обновляется до X…»
+ живой хвост лога; кнопка «Обновить ядро» **только при `updateAvailable`**, иначе «✓ Установлена
последняя версия»; поллинг плавный — Loading только при первой загрузке, не мигает) ·
`SectionViewModel.loadCore()` поллит и зовёт `CoreUpdateNotifier.notifyResult()` ·
`update/CoreUpdateNotifier`: `check()` шлёт пуш «доступна версия» (дедуп по версии);
**`notifyResult(status)`** — после апдейта читает `status.result` и шлёт пуш
**«✅ Ядро обновлено — Добро пожаловать в ядро X! Все системы в норме»** либо «⚠️ ошибка»
(дедуп по `result.ts`) · deep-link `type=core_update` → `open_section=engine` → Движок.

**E2E на устройстве (2026-06-18):** Движок → кнопка → ручка → апдейт 6.1→6.8 → self-check →
**уведомление «Добро пожаловать в ядро 2026.6.8! Все системы в норме» реально пришло на телефон**.
Память **221/195 цела**, exec не ломается, ~6-8 мин. Доказательство: `update-server/E2E-VERIFIED.md`.

**⚠️ Почему убраны `doctor` и серверный notify-CLI:** на боксе Мумбаи **CPU задушен ~15-30x**
(бенч `node -e` цикл 10M = 1143мс vs норма ~50мс) → openclaw грузит свой модуль-трее (600+
файлов) ~60с на каждый CLI-вызов, старт ядра ~137с, `doctor`/`nodes invoke` «висят» минутами.
Это НЕ openclaw и НЕ конфиг — крипл-VPS. **Переезд в Литву (в работе) это чинит** — после
переезда перемерить бенчем; если CPU норм, CLI станет быстрым и можно вернуть `doctor`/серверный notify.

---

## Структура (`app/src/main/java/com/duq/android/`)

```
network/openclaw/  OpenClawGatewayClient (operator), OpenClawNodeClient (node), OpenClawProtocol
auth/              DeviceIdentityManager (Ed25519 software)
service/           DuqListenerService (foreground WS), VoiceCommandProcessor, BootReceiver,
                   DuqAccessibilityService, DuqVoiceInteractionService(+Session), DuqNotificationManager
audio/             AudioRecorder, VoiceActivityDetector (Silero), BeepPlayer, ChatAudioPlaybackManager
wakeword/          WakeWordManager (Porcupine) + Factory
camera/            CameraCapture (CameraX)        location/  Fused* + LocationReporter
screen/            ScreenCaptureManager, ScreenConsentActivity, ScreenRecorder (MediaProjection)
update/            AppUpdater + UpdateWorker (self-update с GitHub Releases) + CoreUpdateNotifier  logging/  FileLogger, Logger
network/           CoreUpdateClient (HTTP /core-update/status|run — обновление ядра openclaw)
network/           TtsClient (POST /tts → WAV для контекстного TTS)
config/            AppConfig (все таймауты/лимиты + STT_URL/TTS_URL/UPDATE_* — ЕДИНЫЙ источник)
data/              SettingsRepository (токены/seeds/gatewayUrl), model/
ui/                Compose: MainScreen, PairingScreen(+VM), SettingsScreen, ConversationViewModel, components/
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
- **Вечный спиннер, ответ не приходит (исправлено 2026-06-19):** gateway (openclaw ≥2026.6.8)
  хранит сессию и вещает chat/agent-фреймы под КАНОНИЧЕСКИМ sessionKey `agent:main:main`,
  а клиент слал/слушал bare `main`. Сервер вход нормализует (`loadSessionEntry → canonicalKey`),
  поэтому отправка в `main` работала и сессия создавалась — но broadcast возвращался как
  `agent:main:main`, и приёмный фильтр `sk != chatKey()` (=`"main"`) молча отбрасывал ответ
  (`skip chat for session=agent:main:main`). При этом tool-шаг памяти проходил (его фильтр
  `startsWith("agent:main")`) → создавал бабл, который спиннил вечно.
  **Правильный фикс:** `chatKey()` теперь возвращает канонический `agent:$activeAgentId:main`
  ВЕЗДЕ (send/subscribe/history/приём) — клиент говорит на том же языке, что сервер хранит и
  вещает, рассинхрона нет. **Доп. страховка** (не первопричина): watchdog в `ConversationViewModel`
  — 90с без терминального фрейма (final/error/aborted) → показывает сообщение об ошибке вместо
  вечного спиннера. Реальную причину падения покажет только серверный `error`-фрейм; watchdog —
  fallback на случай немого обрыва.

## Отладка

FileLogger пишет на устройство (debug-путь — см. память проекта `duq-android-connection-and-build`).

```bash
adb logcat | grep -E "Duq|OpenClaw|WakeWord|VoiceActivity"
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
