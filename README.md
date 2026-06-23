# Duq Android

> Voice + chat AI assistant for Android, native two-way link to the DUQ core engine.

Mobile client for DUQ (`com.duq.android`). Wake-word voice capture, streaming chat,
and **native bot→phone control** over a single WebSocket gateway. The phone is both an
*operator* (you chat with the engine) and a *node* (the engine controls the phone).

> For development context, build/deploy loop, and known quirks see [`CLAUDE.md`](./CLAUDE.md).

## Features

- 🔐 **Ed25519 device identity** — software keypair (BouncyCastle), per-device pairing via QR
- 🎤 **Push-to-talk** — hold the duck mascot to record, release to send (STT)
- 🔊 **Contextual TTS** — replies are spoken aloud only when you spoke (typed stays silent)
- 💬 **Streaming chat** — operator session over WebSocket, cumulative-text frames
- 🪜 **Live step progress** — see what the agent is doing mid-reply (📧 checking email, 🔍 search)
- 🤖 **Native phone control** — engine invokes `location.get` / `notify.show` / `camera.snap` / `screen.record`
- 🔄 **In-app self-update** — pulls new APK directly from GitHub Releases

> Wake word ("Hey Duq") is currently disabled (Porcupine free-tier activation limit);
> voice input is push-to-talk. A license-free openWakeWord model is planned.

## Architecture

```
                       wss://on-za-menya.online/duq/ws
                                      │
        ┌─────────────────────────────┴─────────────────────────────┐
        ▼ operator session (phone → engine)        ▼ node session (engine → phone)
  DuqChatClient                               DuqNodeClient
  chat / streaming replies                    node.invoke → camera/screen/location/notify
        │                                            │
  Hold duck (push-to-talk) → AudioRecorder (PCM 16k WAV)
        → STT /stt/v1/audio/transcriptions → transcript as chat message → reply streamed
        → if input was voice: reply text → /tts (Silero) → spoken aloud
```

Both sessions run from one device with **separate Ed25519 keypairs** (`operator` / `node`).
Device identity matches the gateway contract byte-for-byte:

- `publicKey` = base64url(raw 32-byte Ed25519 public key)
- `device.id` = `SHA256(raw public key).hex`
- `signature` = base64url(raw 64-byte Ed25519 signature)

### Node commands (engine → phone)

| Command | Action | Status |
|---|---|---|
| `location.get` | geolocation `{lat,lon}` | ✅ |
| `notify.show` | notification `{title,body}` | ✅ |
| `camera.snap` | photo (CameraX, JPEG) | ✅ — fires silently, no UI indicator yet |
| `screen.record` | screen video (MediaProjection, MP4) | ✅ — needs consent tap + invoke timeout ≥90s |
| `voice.activate` | voice input | ⚠️ stub — not yet implemented |

## Quick Start

### Prerequisites

- Android SDK 34, **JDK 17**, device on **Android 8.0+ (API 26)**
- Porcupine API key ([free key](https://console.picovoice.ai/)) → `local.properties` as `PORCUPINE_API_KEY`

### Build & install

```bash
git clone https://github.com/Danny-sth/duq-android.git
cd duq-android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The release pipeline ships **arm64-v8a only** (~25 MB). `versionCode` derives from the CI
run number so every published build is strictly newer (the in-app updater relies on it).

### Pairing

Open the app → scan the pairing QR (ML Kit). The bootstrap token is exchanged for a device
token via `device.pair` / `device.pair.resolved`, then reused for the node session.

## Tech Stack

| Category | Technology |
|----------|------------|
| Language | Kotlin 2.1.0 (JDK 17) |
| UI | Jetpack Compose (BOM 2024.11.00) + Material3 |
| DI | Hilt 2.56 |
| Identity | BouncyCastle 1.78.1 (software Ed25519) |
| Network | OkHttp 4.12.0 (+ DNS-over-HTTPS) |
| Wake Word | Porcupine Android 4.0.0 |
| VAD | Silero android-vad 2.0.10 |
| Camera | CameraX 1.3.1 |
| QR | ML Kit barcode-scanning 17.2.0 |
| Audio | Media3 ExoPlayer 1.2.1 |
| Persistence | DataStore Preferences (encrypted) |

## Project Structure

```
app/src/main/java/com/duq/android/
├── network/duq/       # DuqChatClient (operator), DuqNodeClient (node), DuqProtocol
├── auth/              # DeviceIdentityManager (Ed25519 software)
├── service/           # DuqListenerService (foreground WS), VoiceCommandProcessor, BootReceiver,
│                      #   DuqAccessibilityService, DuqVoiceInteractionService(+Session)
├── audio/             # AudioRecorder, VoiceActivityDetector (Silero), BeepPlayer, ChatAudioPlaybackManager
├── wakeword/          # WakeWordManager (Porcupine) + Factory
├── camera/            # CameraCapture (CameraX)
├── location/          # FusedLocationDataSource, LocationReporter
├── screen/            # ScreenCaptureManager, ScreenConsentActivity, ScreenRecorder (MediaProjection)
├── update/            # AppUpdater (self-update from channel)
├── logging/           # FileLogger, Logger
├── config/            # AppConfig (all timeouts/limits — single source, no scattered hardcode)
├── data/              # SettingsRepository (tokens/key seeds/gateway URL), model/
├── ui/                # Compose: MainScreen, PairingScreen(+VM), SettingsScreen, ConversationViewModel
└── di/                # AppModule (Hilt)
```

## Configuration

All endpoints, timeouts and limits live in `config/AppConfig.kt` (single source of truth).

- Backend: `https://on-za-menya.online`
- Gateway WS: `wss://on-za-menya.online/duq/ws` (overridable in Settings)
- STT: `/stt/v1/audio/transcriptions`

Wake-word sensitivity (`AppConfig.WAKE_WORD_SENSITIVITY`) and VAD silence timeout
(`AppConfig.VAD_SILENCE_TIMEOUT_MS`) are tunable there.

## CI/CD

`.github/workflows/android.yml` — on push to `master`/`main`:

1. `assembleRelease` + unit tests
2. Publish APK to the VPS update channel (retry ×3, soft-fail)
3. Create a GitHub **prerelease** `build-<run_number>` with the APK
4. Notify Telegram, upload artifacts

## Debugging

```bash
adb logcat | grep -E "Duq|WakeWord|VoiceActivity"
adb shell pm clear com.duq.android   # reset app data (re-pair afterwards)
```

## License

Private project — © 2026 Danny-sth

## Related Projects

- [duq-gateway](https://github.com/Danny-sth/duq-gateway) — backend API gateway
- [not-that-duq](https://github.com/Danny-sth/not-that-duq) — core AI agent
