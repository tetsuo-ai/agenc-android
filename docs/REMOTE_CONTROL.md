# Remote control, identity, and notifications

This is the Android contract for pairing with AgenC Core, driving daemon-owned
sessions, and staying useful when the Activity is backgrounded.

## Pair and connect

1. Sign in on the computer with `agenc login` and start `agenc remote on`.
2. Sign in on Android.
3. Scan the QR or enter its eight-character code. The code is normalized and
   never submitted until it has all eight characters.
4. The backend claims the single-use pairing and issues a short-lived client
   relay ticket for the same random pairing room as the computer.
5. Android opens the relay WebSocket, sends authenticated `initialize`, and
   begins session/status synchronization.

The pairing field uses readable monochrome text, no outline, and no blinking
caret in the centered `XXXX-XXXX` presentation. Camera scanning is implemented
inside `PairingScreen` with CameraX and ML Kit. The `agenc://pair?c=...` deep
link prefills but does not silently submit a code.

Production uses the signed relay. The direct loopback daemon URL is a local
development surface only.

## Connection ownership

`DaemonHub` is the single connection and session-state owner. It serializes
connect attempts, applies exponential reconnect, refreshes metadata, hydrates
transcripts after attach, and prevents stale sockets from publishing state
after a host/ticket change.

When the Activity is visible, it owns normal connection liveness. When the app
moves to background, `BackgroundLinkService` starts only when all conditions
are true:

- app is backgrounded;
- account is signed in;
- a computer is paired.

The foreground service uses Android's `remoteMessaging` type, immediately
posts a silent low-priority AgenC notification, and keeps the existing hub
alive. It does not duplicate auth, session, or chat state. Returning to the
Activity stops the service without deliberately disconnecting the socket.

The service observes relay-ticket version changes and asks the graph to
reconfigure through the same single-flight path. Its watchdog observes state;
it does not flatten the hub's reconnect backoff into an aggressive retry loop.

## Attached events versus global status

Android advertises:

```text
portal.mobile.status.push.v1
```

Most chat events require `session.attach` and belong to one transcript. The
status capability is different: Core may send `event.agent_status` for any
daemon session on the authenticated physical connection, even if Android has
not attached that chat. This is what makes background completion delivery
scale to hundreds of sessions.

`AgentStatusDeliveryTracker`:

- deduplicates status event ids;
- remembers the active/completed turn for each session;
- ignores a delayed `running` frame for a turn already completed;
- prevents a replayed terminal frame from posting a second notification;
- bounds its in-memory event/session history.

Status replay never substitutes for transcript hydration. Opening a
notification attaches/opens the exact session and loads its conversation.

## Notification policy

The app separates operational connection state from user alerts:

- **Background Core connection** — silent ongoing foreground-service
  notification, shown only while the background link is eligible.
- **Completion** — optional local notification after an authoritative terminal
  status.
- **Needs attention** — permission or user-input request while backgrounded.
- **Error** — terminal failure where the notification policy permits it.

Completion delivery is suppressed when any of these are true:

- app is foregrounded;
- completion notifications are disabled;
- the session is muted;
- current time falls in configured quiet hours;
- the turn duration is below the optional minimum threshold.

A missing duration is accepted when the minimum is zero because a reconnect
may receive the terminal frame without replaying its start. Notifications for
the chat currently visible in the foreground are suppressed.

Tapping a notification opens `MainActivity` with a private app PendingIntent
and routes to that session. Permission/attention actions carry only the
request/session identifiers needed to settle the pending daemon request. The
notification receiver is not exported.

All notification and launcher assets derive from the AgenC mark; Android
small-notification icons remain single-color as required by the platform.

## Permission settlement

The phone supports allow once, allow for session, deny, and typed input replies.
Interactive RPCs are sent immediately and Core dispatches them preemptively so
they cannot wait behind the turn they are unblocking.

Allow once:

```json
{
  "scope": "once"
}
```

Allow all tools for the current session:

```json
{
  "scope": "session",
  "allowAllToolsForSession": true
}
```

The second operation is intentionally stronger than an ordinary session
approval cache. A compatible Core promotes that one live daemon session to
`bypassPermissions` before releasing the blocked request and rolls back if
settlement fails. It does not affect another session, remove sandbox
boundaries, or persist as a global account preference.

If Android cannot determine whether Core received a reply, it reports the
failure instead of claiming success. Ledger receipts add their own durable
reconciliation rules; see [Ledger Flex](LEDGER_FLEX.md).

## Account identity linking

The phone account bearer and the Core `/login` identity are related but
separate authentication facts. Android computes one display snapshot:

1. If both sides expose stable account ids, those ids must match.
2. During migration from older phone builds, an exact normalized email/handle
   match may link records that lack an id.
3. Conflicting stable ids create a visible runtime mismatch. Android never
   replaces the phone account with the Core record.
4. Core's plan/tier is used only when the runtime marks it verified; otherwise
   the phone's authenticated tier remains authoritative.

Logout clears the phone session and old-host unsigned work only after Ledger
host-exit safety checks pass. It never logs the user out of Core implicitly.

## Session lifecycle

- Home summarizes connection/session state and offers a new remote session.
- Sessions supports search over large histories, live lifecycle state, mute,
  open, and swipe-to-delete.
- Delete calls Core's session termination/archive path first and removes the
  local projection only after success.
- Opening a chat attaches and hydrates its transcript. Loading and row entry
  are animated instead of replacing the screen abruptly.
- The local session id map preserves stable Android navigation while Core
  returns daemon ids.

## Compatibility

| Core | Android | Behavior |
| --- | --- | --- |
| old | new | Basic pairing/chat; no global status, all-tools promotion, or typed Ledger action |
| new | old | Existing attachment-bound chat remains compatible; unadvertised capabilities are unused |
| new | new | Full background status, account reconciliation, permission, and Ledger paths |

Core should be deployed/restarted before a build that relies on the new
capabilities. Provider credentials remain on the computer and are never copied
into Android.

## Privacy and local data

Bearer and relay-ticket secrets live in encrypted storage. App-private
preferences contain public session/account metadata, notification settings,
the current pairing id, and non-secret Ledger recovery records. Android backup
and device-transfer extraction are disabled so a restored phone cannot inherit
an authenticated pairing or financial recovery state. A new phone must sign in
and pair explicitly.
