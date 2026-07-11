# Development, verification, and release safety

## Toolchain

| Component | Version |
| --- | --- |
| JDK used by Gradle | 21 recommended (source/JVM target 17) |
| Gradle wrapper | 8.11.1 |
| Android Gradle Plugin | 8.7.3 |
| Kotlin | 2.0.21 |
| Compile/target SDK | 35 |
| Minimum SDK | 26 |
| Compose BOM | 2024.12.01 |

Set `sdk.dir` in the machine-local `local.properties`. Never commit that file,
Android SDK paths, IDE metadata, signing keys, API keys, device serials, or
logs.

## Local gates

Run from the repository root:

```sh
./gradlew --no-daemon testDebugUnitTest testReleaseUnitTest lintDebug lintRelease assembleRelease
git diff --check
```

The JVM suite covers the protocol contracts, state machines, and public-release defaults:

- connection-attempt single flight;
- agent-status deduplication and terminal ordering;
- identity link/mismatch behavior;
- notification and background-service policies;
- `@ledger` exact-token styling;
- strict action/receipt/result parsing;
- durable inbox, tombstone, hardware-fence, receipt monotonicity, and outbox;
- host-exit/logout rules;
- capability response correlation;
- Ledger account derivation and auto-forward gates;
- persistent chat receipt behavior.

Lint warnings about dependency freshness or Compose micro-optimizations may be
reviewed separately, but a PR must have zero lint errors. Android 12+ data
extraction rules explicitly exclude all app state from backup/device transfer.

The hosted Android CI workflow runs the same test/lint/release-build gate for pull requests and
pushes to `main`. The PR should still record any physical phone, relay, notification, or Ledger
verification that CI cannot perform.

## APK and device installation

Build output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Choose the destination explicitly when an emulator and phone are both present:

```sh
adb devices -l
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> shell am force-stop ai.tetsuo.agenc
adb -s <serial> shell am start -n ai.tetsuo.agenc/.MainActivity
```

Record the artifact digest in the PR when handing a specific APK to a tester:

```sh
shasum -a 256 app/build/outputs/apk/debug/app-debug.apk
```

Do not commit the APK or reports under `app/build/`.

## Logs

Clear and capture only the selected device:

```sh
adb -s <serial> logcat -c
APP_PID=$(adb -s <serial> shell pidof ai.tetsuo.agenc | tr -d '\r')
adb -s <serial> logcat --pid="$APP_PID" -v threadtime
```

For a cold-start crash where the pid disappears, use tag/package filtering and
stop capture promptly. Before attaching logs to an issue/PR, remove bearer
tokens, relay tickets, pairing codes, public wallet addresses when not needed,
device identifiers, session text, and provider errors that might contain
credentials. The app must never log private Ledger material; none exists
outside the device.

## Manual remote-control matrix

- [ ] login completes and `01 AgenC account` refreshes the exact `/v1/auth/me`
      display name while showing a photo slot and only `FREE`; no email is rendered
- [ ] QR and manual code both pair with `agenc remote on`
- [ ] Core with a configured provider answers a phone message
- [ ] live chunks stream smoothly; reopening hydrates without a top-of-list flash
- [ ] long answer enables jump-to-latest and manual scroll disables tail follow
- [ ] permission allow once settles once
- [ ] allow for session prevents later tool prompts in only that session
- [ ] deny and custom elicitation reply reach Core without deadlock
- [ ] background service appears only while signed in + paired + backgrounded
- [ ] completion notification arrives for a non-open session
- [ ] tapping the notification opens that exact session
- [ ] visible foreground session does not produce a duplicate notification
- [ ] mute, quiet hours, completion toggle, and duration threshold suppress correctly
- [ ] `02 Connected Core` distinguishes Checking, Offline, Unavailable, Not
      logged in, Linked, Mismatch, and Unverified
- [ ] Core `/login` with the same account links; conflicting ids show mismatch
      without replacing the hosted account profile
- [ ] failed Core identity refresh says Unavailable, never Not logged in
- [ ] swipe delete waits for Core and keeps the row on failure

## Manual Ledger rehearsal

Before touching the device, verify:

```text
Demo.ENABLED == false
network == mainnet-beta
the selected default source wallet is expected
the Core and Android builds both implement the typed v1 protocol
```

Safe no-broadcast rehearsal:

1. Pair Android and Core.
2. Associate/unlock a Ledger Flex; let Android open Solana.
3. Select a default account.
4. Send an exact, unambiguous `@ledger` SOL request from the root chat.
5. Confirm Android shows the expected source, recipient, and amount.
6. Reject on the physical Ledger.
7. Verify Core receives a typed `cancelled` receipt and the chat shows a violet
   cancellation row.
8. Verify no signed-byte record/broadcast remains.

A successful submit rehearsal moves real mainnet funds. Run it only for an
explicit human-approved recipient and amount, verify the Flex clear-sign
screen, and record that `submitted` is still not confirmation/finality.

Also rehearse recovery without signing again:

- background/foreground around a pending request;
- disconnect after hardware review and before receipt reply;
- process restart with persisted exact signed bytes;
- Core restart/`not pending` response retaining the outbox record;
- logout and host switch while unsigned, terminal, and critical states exist.

## Release checklist

- [ ] `Demo.ENABLED` is `false`
- [ ] demo destination and amount are empty/zero
- [ ] release artifact contains no bundled daemon cookie or credential override
- [ ] backup and device-transfer extraction remain disabled
- [ ] Gradle tests, lint, assemble, and diff check pass
- [ ] remote-control manual matrix completed on a physical phone
- [ ] safe Ledger rejection rehearsal completed on a physical Flex
- [ ] no unintended mainnet transaction was signed or sent
- [ ] launcher/adaptive/monochrome/notification assets show the AgenC mark
- [ ] versionCode/versionName bumped for any distributable release
- [ ] release APK is aligned, signed, and verified with the approved long-lived key
- [ ] APK checksum and signing-certificate fingerprint are published with the release
- [ ] no signing material or signing password is committed

The first public build uses `versionName 0.1.0` and `versionCode 1`. GitHub Actions runs tests,
lint, and an unsigned release build for every pull request and push to `main`. Distribution signing
is intentionally separate: the long-lived key and passwords remain outside the repository, and the
verified signed APK is published as a GitHub Release asset together with its checksum. The project
does not currently define Play Store signing.
