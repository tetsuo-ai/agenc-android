# AgenC AI — Android

Native Jetpack Compose app to **control AgenC coding-agent sessions running on your computer, from
your phone, anywhere** — the Android counterpart of [agenc-ios](https://github.com/tetsuo-ai/agenc-ios).
Your Mac runs the agenc-core daemon; the app reaches *that machine's* sessions over the AgenC relay,
so the agent's files, environment, and tools stay on your computer while you drive it from your pocket.

It talks to the **same** backend (`id.agenc.ag`) and the **same** relay worker as the iOS app — a
phone paired on either platform reaches the same daemon.

## Architecture (mirrors the iOS app)

```
app/src/main/java/ai/tetsuo/agenc/
  AppConfig.kt        endpoints (backend, relay), permission modes  — port of BackendConfig/AppSettings
  model/Models.kt     domain types: EventMsg, TranscriptItem, SessionLifecycle, …  — port of Models.swift
  net/                WebSocket JSON-RPC client + the Portal event adapter (wire → EventMsg)
  data/               BackendClient (device-flow + pairing), AuthManager, SecureStore,
                      SessionStore (the reducer), DaemonHub (relay/daemon connection)
  ui/Theme.kt         dark ChatGPT-style palette (Brand + ChatPalette)  — port of Theme.swift
  ui/screens/         Login · Pairing · Sessions · Account
  ui/chat/            ChatScreen + tool/permission/elicitation sheets  — port of ChatThreadView
  MainActivity.kt     wires the data layer to the screens
```

The wire protocol is identical to iOS: relay frames are wrapped in a `{t:"data"}` envelope, the
daemon speaks agenc-core's app-server JSON-RPC (`initialize`, `agent.create`, `session.attach`,
`message.send`, `session.list`, …), and event notifications (`event.message_chunk`,
`event.session_event{event:{type,payload}}`, `event.agent_status`, …) are normalized into `EventMsg`.

> The LLM runs on **your computer**, not in the app. The agent only answers if the Mac's agenc-core
> daemon has a model + provider + key configured in `~/.agenc/config.toml` and `agenc remote on` is
> running. (See the iOS repo's CHANGELOG → "Mac-side requirement".)

## Build

Toolchain: JDK 21 · Android SDK (platform 35, build-tools 35) · Gradle 8.11.1 (wrapper) · AGP 8.7.3 ·
Kotlin 2.0.21 · Compose BOM 2024.12.01. `local.properties` points `sdk.dir` at the Android SDK.

```sh
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- `applicationId` / package: `ai.tetsuo.agenc` · `minSdk` 26 · `targetSdk` 35 · version `0.1` (1)
- `agenc://` deep link is registered for device pairing (matches the iOS URL scheme).
