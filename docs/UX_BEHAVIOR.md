# UX behavior and acceptance contract

The UI is deliberately monochrome: black surfaces, white typography, restrained
gray hierarchy, and violet reserved for the explicit Ledger route/result. This
page records the interaction behavior that should not regress during future
visual work.

## Brand and system chrome

- Launcher, adaptive/monochrome launcher, in-app wordmark, and notification
  assets derive from the AgenC SVG source in `branding/`.
- Android notification icons remain a single-color mask; the system supplies
  tint/background.
- Status/navigation bars follow the dark shell and respect safe drawing/IME
  insets.
- Generic decorative icons do not substitute for the AgenC mark.

## Login and pairing

- Login uses the same black/white onboarding family as the app shell.
- Pairing supports CameraX/ML Kit QR scan, deep-link prefill, and manual
  eight-character code.
- The centered code is readable on its container, uses no white outline, and
  hides the blinking caret that would sit between `XXXX-XXXX`.
- Scan/connect actions provide haptic confirmation; an incomplete code cannot
  submit.
- Loading, success, and error state remain on the same screen without a hard
  visual replacement.

## Home, Sessions, Account

- Home surfaces paired/connection state, active work, recent sessions, and a
  primary new-session action.
- Sessions has a sticky search field suitable for hundreds of items. Search
  filters the current projection without changing Core state.
- Swiping a session exposes delete/archive. Android calls Core first, shows a
  confirmation/failure path, and removes local state only after success.
- Session rows expose lifecycle and mute state without bright card chrome.
- Account starts with `01 AgenC account` (photo slot, display name only, and Free
  plan; never email or unavailable helper copy), followed immediately by `02 Connected Core` (machine, connection,
  linked/not-logged-in/mismatch state, and Refresh). Notifications, Delivery,
  Hardware wallet, and Security remain distinct numbered sections.
- Core identity never overwrites the hosted account card. Switches, adjusters,
  Refresh, and actions use semantic haptic cues without generic decorative icons.

## Bottom navigation

- Home, Sessions, and Account share one stable bottom bar.
- Selection is a restrained tint/indicator transition, not a floating gray
  square above a generic icon.
- Content changes with a 180 ms `FastOutSlowIn` crossfade.
- Re-selecting the current destination does not replay navigation haptics.
- A new destination produces a selection tick; pressed/disabled feedback is
  visible without shifting layout.
- Opening chat preserves the selected tab, Sessions query, and saveable scroll
  state underneath so Back returns to the exact shell state.

## Chat layout

- User input starts a visual turn: the user message is positioned with reading
  runway above the composer, followed by the agent response below it.
- A newly sent user row enters with a short fade/vertical reveal (disabled when
  reduced motion is requested).
- Opening an existing session positions the list at the tail before revealing
  the transcript, then staggers the latest rows; long histories must not flash
  at the top and jump suddenly.
- Streaming follows the tail while the user remains near the bottom. Manual
  upward drag disables auto-follow.
- When content continues below the viewport, a centered down-arrow appears.
  Tapping it animates to the latest anchor and re-enables tail follow.
- The composer has stable geometry across keyboard/typing/connection states.
  Its trailing command morphs among send, stop, wait, and voice instead of
  swapping in a generic detached button.
- Send/stop/voice/wait and permission actions use matching confirm/reject/action
  haptics.

## Message actions and thinking

- Copy is not a persistent label on every message.
- Long-press opens native Copy and, where supported, Edit actions. TalkBack
  exposes the same long-click semantics.
- Only eligible text user turns can be edited. Voice transcripts and restored
  history without a stable editable ordinal remain copy-only.
- Edit mode reuses the composer with an explicit cancel/submit state.
- Tool cards/dividers are collapsed from the normal reading flow. While a turn
  is processing, three monochrome animated dots are the only thinking label.
  Completed reasoning leaves no stale “Thinking…” text.

## Permission and input sheets

- Pending permission and elicitation requests use blocking bottom sheets with
  full-width monochrome actions.
- A blocking request cannot be swipe-dismissed into an indeterminate Core
  state; the user must allow, deny, cancel, or answer through a supported path.
- Allow once and Allow for session are visually distinct. The session option
  maps to Core's explicit `allowAllToolsForSession` contract.
- Delivery failure remains visible and must not claim Core received the choice.

## Ledger treatment

- Exact case-insensitive `@ledger` token boundaries are violet in composer and
  sent user text. `email@ledger`, `@ledger_wallet`, and `@ledgered` are ordinary
  text.
- Ledger review clearly locks the phone-selected source wallet and shows
  recipient/amount before the user reaches device approval.
- The Flex remains the trusted clear-sign display.
- Terminal chat rows are persistent and violet. Submitted displays
  “Submitted to Solana · awaiting confirmation”; it never says finalized.
- Long-press copies the signature when present or the structured result when
  cancelled.

## Motion and accessibility

- Standard navigation/message transitions are short (approximately 180 ms),
  smooth, and cancel/restart safely when state changes quickly.
- Reduced-motion settings skip nonessential entry animations and use direct
  positioning for long transcript hydration.
- Touch targets, content descriptions, semantics, contrast, safe areas, and
  keyboard navigation must remain intact when visual tokens change.
- Haptics supplement visible state; they are never the only confirmation.

## Manual visual checklist

- [ ] pairing placeholder/text are readable and borderless; no centered cursor
- [ ] QR scan returns a valid code and closes the overlay
- [ ] Home/Sessions/Account crossfade without a flash or gray icon square
- [ ] switches, nav, send/stop, permission, delete, and Ledger actions vibrate
- [ ] Sessions search handles a long list; delete failure preserves the row
- [ ] chat opens at the tail without flashing old content
- [ ] new user message enters above the response and away from the composer
- [ ] long streamed answer exposes/reuses the jump-to-latest control
- [ ] long-press shows copy/edit only when eligible
- [ ] thinking is animated dots only
- [ ] permission session approval settles later tools without another prompt
- [ ] exact `@ledger` and terminal receipt are violet; submitted is unconfirmed
- [ ] launcher and system notification show the AgenC mark correctly
