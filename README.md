# AgenC Android

Native Jetpack Compose remote control for AgenC coding sessions running on a
paired computer. The LLM, provider credentials, files, tools, and daemon stay
on the computer; the phone connects through the authenticated AgenC relay to
read and drive those sessions.

The app shares the backend and relay protocol used by
[AgenC iOS](https://github.com/tetsuo-ai/agenc-ios) and is paired with
[AgenC Core](https://github.com/tetsuo-ai/agenc-core).

## Product surface

- monochrome Home, Sessions, and Account shell with a smooth bottom navigator
  and haptic feedback;
- QR or eight-character code pairing with the running Core connector;
- searchable sessions, swipe-to-delete/archive, notification mute controls,
  and exact-session notification deep links;
- streaming chat with stable user→assistant grouping, animated entry/loading,
  jump-to-latest, long-press message actions, permission/elicitation sheets,
  and violet exact-token `@ledger` treatment;
- background relay/Core continuity through an eligible foreground service and
  global completion/attention delivery;
- account reconciliation between the phone bearer and the Core `/login`
  identity;
- Ledger Flex BLE association, Solana account discovery, saved default wallet,
  and physically approved typed `@ledger` SOL transfers.

Detailed behavior:

- [Remote control, identity, and notifications](docs/REMOTE_CONTROL.md)
- [Ledger Flex and `@ledger` security](docs/LEDGER_FLEX.md)
- [UX acceptance contract](docs/UX_BEHAVIOR.md)
- [Development, tests, installation, and logs](docs/DEVELOPMENT.md)

## Runtime topology

```text
Android app
  -> id.agenc.ag (account, pairing, short-lived relay ticket)
  -> signed relay room
  -> agenc remote on (computer connector)
  -> loopback agenc-core daemon
  -> daemon-owned sessions, tools, provider, and files
```

Relay frames use the portal `{ "t": "data" }` envelope. Inside it, Core speaks
JSON-RPC (`initialize`, `session.list`, `session.attach`, `message.send`,
`tool.approve`, `elicitation.respond`, and event notifications). A direct local
daemon endpoint exists for development, but production pairing uses the signed
relay room.

The Android client advertises two authenticated capabilities:

```text
portal.mobile.status.push.v1
portal.ledger.solana.sign.v1
```

The first receives cross-session status without attaching every transcript.
The second receives one typed Ledger action on a capable phone. Older Core
builds still support basic pairing/chat, but do not provide these newer paths.

## Source layout

```text
app/src/main/java/ai/tetsuo/agenc/
  MainActivity.kt        lifecycle, routes, notification deep links, Ledger recovery wiring
  AppConfig.kt           backend/relay endpoints and permission-mode wire values
  data/                  auth, secure/local state, session reducer, daemon/relay hub
  net/                   JSON-RPC connection and strict portal event adapter
  notif/                 foreground link service, local notifications, action receiver
  ledger/                BLE transport, Solana APDUs/accounts/transactions, controller
  model/                 domain, transcript, typed Ledger action/result models
  ui/Theme.kt            monochrome design tokens
  ui/screens/             Login, Pairing, Home, Sessions, Account, Ledger
  ui/chat/                chat, composer, Markdown, actions, permission/input sheets
app/src/test/             JVM contract and state-transition tests
branding/                 canonical AgenC SVG source and asset provenance
```

Bearer tokens and relay tickets use encrypted Android storage. Public session,
pairing labels, selected Ledger address/path, and recovery receipts use app-
private storage. Cloud backup and device-to-device extraction are disabled; a
new phone must authenticate and pair again. Private Ledger keys never enter the
app.

## Prerequisites

- a computer running a compatible AgenC Core daemon and `agenc remote on`;
- a valid AgenC account on both phone and Core;
- a configured Core provider/model/key for real agent answers;
- Android 8.0+ (API 26); Android SDK 35 and JDK 21 for development;
- optional Ledger Flex with the stock Solana app for `@ledger`.

If chat connects but the agent never answers, check Core first: the app does
not run the model and does not receive the provider API key.

## Build and verify

The repository uses Gradle 8.11.1, AGP 8.7.3, Kotlin 2.0.21, and Compose BOM
2024.12.01. `local.properties` is machine-local and must point at the Android
SDK; it is never committed.

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The debug APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install to an explicitly selected device when more than one emulator/phone is
available:

```sh
adb devices -l
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> shell am start -n ai.tetsuo.agenc/.MainActivity
```

See [development](docs/DEVELOPMENT.md) for package-scoped logs, manual
rehearsals, and the release-safety checklist.

## Permissions

| Permission | Use |
| --- | --- |
| Internet/network state | backend, relay, Core, and Solana RPC |
| Notifications | completion, attention, and foreground-link status |
| Foreground service / remote messaging | keep an authenticated paired Core link alive in background |
| Camera | optional pairing QR scan |
| Bluetooth scan/connect | Ledger Flex discovery and APDU transport |
| Legacy Bluetooth/location (API 30 and below) | platform BLE compatibility only |
| Vibration | navigation, switches, chat, and action feedback |

Camera and BLE are optional hardware features. The app requests sensitive
runtime permissions only when their feature is used.

## Release status

The current application id is `ai.tetsuo.agenc`, `minSdk` 26, `targetSdk` 35,
`versionName` `0.1`, and `versionCode` `1`. This repository currently has no
hosted CI workflow or signed-store release pipeline; local Gradle and device
gates are authoritative for debug APK changes. A distributable release must
bump version metadata and use the project-approved signing/AAB process.
