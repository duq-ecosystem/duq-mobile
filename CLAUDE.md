# CLAUDE.md — duq-mobile (Compose Multiplatform)

Кроссплатформенный клиент DUQ на **Compose Multiplatform** (Android + iOS), мигрированный
из монорепо `duq-next-generation/android/`. Один UI/логика на Kotlin для обеих платформ.

> Бэкенд — Python-ядро `duq-core` за nginx, домен `on-za-menya.online`, префикс `/duq`.
> Авторизация всех запросов — единый edge-токен `X-Auth-Token` (`AppSecrets.serverToken`,
> из CI-секрета `SERVER_TOKEN`). Контракт чата: REST `POST /duq/api/message` → стрим ответа
> по WS `/duq/ws` (TEXT_DELTA/TEXT_DONE); история `/duq/api/conversations[/{id}/messages]`.

## ⛔ Обновление приложения на телефон — ТОЛЬКО автообновлением (не `adb install -r`)
Доставка нового билда: `git push master` → GitHub Actions собирает APK → GitHub Release →
AppUpdater в приложении сам ставит (PackageInstaller). `adb` — только отладка/скриншоты/логи.

## Структура (KMP)
```
shared/                         # Kotlin Multiplatform — общий код
  src/commonMain/kotlin/com/duq/android/   # UI (Compose MP), логика, DTO, expect
    config/AppConfig.kt + AppSecrets        # конфиг (URL/таймауты) + секреты-holder
    network/  DuqHttpClient (expect), duq/ DuqProtocol+DuqApiDto (serialization), DuqRestClient (Ktor)
    data/  SettingsRepository (multiplatform-settings), model/Message
    logging/Logger (interface)   util/ReplyText, nowMillis (expect)   di/ Modules (Koin)
  src/androidMain/kotlin/...    # actual: OkHttp-engine, AndroidLogger, SharedPreferencesSettings, nowMillis
  src/iosMain/kotlin/...        # actual: Darwin-engine, IosLogger, NSUserDefaultsSettings, nowMillis, MainViewController
androidApp/                     # тонкий Android-хост (applicationId com.duq.android — СОХРАНЁН ради подписи)
iosApp/                         # Xcode-проект (Swift) — хостит MainViewController из shared
app/                            # РЕФЕРЕНС исходного Android (не в сборке) — сверять перенос, удалить в конце
```

## ⛔ Рабочие версии (выстрадано — НЕ менять без причины)
- **Kotlin 2.3.20** (НЕ 2.2.x: CMP 1.11.1 iOS klib собран 2.3.20, иначе «incompatible ABI» на iosArm64).
- CMP **1.11.1**, AGP 8.7.3, Gradle 8.9, Ktor 3.5.0, Koin 4.1.1, multiplatform-settings 1.2.0.
- НЕ использовать kotlinx-datetime (`Clock.System` не резолвится на iOS klib под K2.3.20) — время через `expect fun nowMillis(): Long`.
- `compose.components.uiToolingPreview` НЕ в commonMain (нет на iosArm64).
- Android lint: `lint { checkReleaseBuilds=false }` (lintVital крашит под K2.3).
- Версии jetbrains-navigation/lifecycle — выверять по Maven перед добавлением, не выдумывать.

## Цикл разработки
```
правка кода → git commit + push master → GitHub Actions (Android CI + iOS CI) → проверить зелёным:
  gh run watch <id> --exit-status   # Android ~4мин, iOS ~8-10мин
```
НЕ гонять тяжёлый Gradle/xcodebuild локально (нет macOS для iOS) — верификация через CI.
Перенос каждого модуля сверять с актуальным `app/` пофайлово (ловили потерю полей).

## iOS-сборка (.ipa) — официальная KMP Direct integration
`iosApp/iosApp.xcodeproj`: Run Script «Compile Kotlin Framework» (`embedAndSignAppleFrameworkForXcode`,
guard по `OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED`) ПЕРЕД Sources + **`ENABLE_USER_SCRIPT_SANDBOXING = NO`**
(без него Xcode-sandbox режет доступ Gradle к BUILT_PRODUCTS_DIR → embedAndSign SKIPPED → undefined symbols).
`FRAMEWORK_SEARCH_PATHS = $(SRCROOT)/../shared/build/xcode-frameworks/$(CONFIGURATION)/$(SDK_NAME)`,
`OTHER_LDFLAGS = -framework Shared`. ios.yml: `xcodebuild archive CODE_SIGNING_ALLOWED=NO` → Payload → unsigned .ipa.
Сайдлоад на iPhone 13 — через SideStore (подписывает на телефоне). Источник: kotlinlang.org Direct integration.

## CI / секреты
- `android.yml`: `:androidApp:assembleDebug` (release-подпись+Release+доставку включить, когда есть keystore-пароль).
- `ios.yml`: macos-14, `xcodebuild archive` → unsigned `.ipa` артефакт.
- Секреты репо (Settings→Secrets): `SERVER_TOKEN`, `GH_RELEASE_TOKEN`, `KEYSTORE_BASE64` (заведены).
  ⚠️ `KEYSTORE_PASSWORD`/`KEY_ALIAS`/`KEY_PASSWORD` — нужны для release-подписи (у Дениса).
- versionCode = `GITHUB_RUN_NUMBER + 400` (>319 последнего монорепо-билда, иначе AppUpdater сочтёт даунгрейдом).

## VPS / бэкенд
- IP `187.124.131.127` (Литва). Backend `https://on-za-menya.online` (nginx → duq-core).
- Endpoints: `/duq/api/*` (REST чата), `/duq/ws` (стрим+phone.command), `/stt`, `/tts`, `/core-update/*`.
- Закрытый периметр: все запросы несут `X-Auth-Token` (иначе 401 → fail2ban).

## Документация DUQ — в локальном Obsidian (`Coding/duq/`), не в репо. Память проекта: `duq-mobile-cmp-working-versions`.
