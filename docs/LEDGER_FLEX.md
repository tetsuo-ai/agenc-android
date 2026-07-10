# Ledger Flex and `@ledger`

Android is the Ledger execution boundary for remote AgenC transfers. Core sends
one typed request; the app chooses the saved source wallet, opens the stock
Solana app on a Ledger Flex over BLE, builds the transfer, and requires physical
approval on the device. The private key never leaves the Ledger.

Version 1 supports native SOL transfers on Solana mainnet only. It does not
support SPL tokens, swaps, staking, arbitrary programs, devnet, or a source
wallet supplied by the model.

## Associate a Ledger

1. Open Account → Ledger Flex.
2. Unlock the Flex and enable Bluetooth.
3. Grant Android's nearby-device/Bluetooth permission when prompted.
4. Select the device. The app reconnects through the native BLE transport.
5. Android reads the running Ledger app. If necessary it closes the other app,
   opens `Solana` through BOLOS/DMK-compatible commands, reconnects after the
   expected session reset, and verifies that Solana is active.
6. Public Solana accounts are derived and listed. Select one as AgenC's default
   source wallet.

The remembered BLE address/name and selected public path/address are non-secret
metadata. Once associated, reopening Ledger connects directly to that device;
it does not scan again unless the association is forgotten.

**Forget Ledger** removes AgenC's saved device and default wallet so another
Ledger can be chosen. It does not touch the private key, funds, apps, or
Android's OS-level Bluetooth bond.

## Account discovery

Solana derivation space is infinite, so the UI never claims to list “all”
accounts. It scans a bounded, deterministic union of the common families:

```text
44'/501'/N'/0'
44'/501'/N'
44'/501'/0'/N'
```

The first page includes at least ten legacy bare accounts. **Load more** grows
the common-family scan up to 25 indexes. Exact index lookup covers indexes from
0 through 1,000,000 for wallets outside the fast page. Duplicate paths such as
`0/0` are removed. Unsupported paths are never accepted from external input.

Balances are display metadata. Selecting the default wallet atomically saves
both its path and derived address before an `@ledger` request can auto-forward.

## Requesting a remote transfer

The current root human message on Core must contain `@ledger` as an exact token
and specify an unambiguous recipient and SOL amount. Example intent:

```text
@ledger send 0.05 SOL to <solana-address>
```

Core converts SOL to exact integer lamports and emits one action:

```json
{
  "type": "ledger_solana_transfer_v1",
  "source": "agenc-core",
  "targetCapability": "portal.ledger.solana.sign.v1",
  "network": "mainnet-beta",
  "intentId": "ledger_...",
  "responseNonce": "core-generated-256-bit-base64url-challenge",
  "to": "Base58SolanaRecipient",
  "lamports": "50000000",
  "note": "optional reason",
  "expiresAt": "2026-07-10T12:10:00.000Z"
}
```

Android advertises `portal.ledger.solana.sign.v1` only on its authenticated raw
portal connection. The action parser rejects unknown source/type/capability/
network fields, malformed or non-32-byte addresses, non-canonical amounts,
unsafe ids/challenges, expired requests, and expiries more than 15 minutes in
the future. Core currently issues a 10-minute TTL.

The action cannot choose `from`. Android binds the request to the default
wallet selected locally and to the current pairing/host.

## Physical review and broadcast

The irreversible sequence is deliberately ordered:

```text
persist exact request + selected source
  -> atomically set hardwareReviewStarted
  -> build legacy System Program transfer using latest blockhash
  -> send SIGN_MESSAGE APDUs to the Ledger
  -> human checks source, recipient, and amount on the Flex and approves/rejects
  -> persist signature + exact signed transaction bytes + last-valid height
  -> broadcast those exact bytes via Solana RPC
  -> persist and return a typed terminal receipt
```

`hardwareReviewStarted` is a monotonic one-shot fence committed before the
first signing APDU. If the app/process dies after that boundary, it will not
show a second signing prompt for the same intent. It recovers persisted signed
bytes or fails closed.

The app never rebuilds and re-signs after an ambiguous RPC response. Retry
rebroadcasts the exact bytes already persisted. Safe abandonment requires both:

- the last-valid block height has passed; and
- the same RPC reports that the persisted signature is absent.

Until both facts are available, the UI stays in an uncertain/recovery state.

## Typed receipt

Submitted:

```json
{
  "type": "ledger_solana_transfer_receipt_v1",
  "intentId": "ledger_...",
  "responseNonce": "the-original-challenge",
  "status": "submitted",
  "network": "mainnet-beta",
  "to": "Base58SolanaRecipient",
  "lamports": "50000000",
  "from": "Base58SelectedLedgerWallet",
  "signature": "Base58TransactionSignature"
}
```

Cancelled:

```json
{
  "type": "ledger_solana_transfer_receipt_v1",
  "intentId": "ledger_...",
  "responseNonce": "the-original-challenge",
  "status": "cancelled",
  "network": "mainnet-beta",
  "to": "Base58SolanaRecipient",
  "lamports": "50000000",
  "from": "Base58SelectedLedgerWallet",
  "reason": "rejected_on_device"
}
```

Receipts are strict JSON objects: no unknown fields, JSON-number amount, invalid
reason, malformed public key, or malformed signature is accepted. Core binds
the challenge and every transfer field to the pending action before showing a
nonce-free result to the model.

`submitted` means Solana RPC received the exact signed transaction. It does
not prove confirmation or finality. Core v1 always exposes
`confirmed: false`, and the violet chat receipt says submitted/awaiting
confirmation rather than finalized. The signature opens Solscan for independent
observation.

## Durable inbox and replay safety

Android persists a FIFO capability inbox of at most eight actions across
sessions. A tombstone is recorded with every accepted intent and retained
through its original expiry. Replayed relay/Core frames therefore cannot create
a second signing prompt after the action leaves the visible queue.

Only one action owns the active hardware slot. Terminal state is append-only:
an exact replay is idempotent, while a conflicting same-intent update fails
closed. A delayed cancellation cannot replace `submitted`.

If transport fails after a receipt exists, the app first archives the exact
host/session/request/intent/challenge-bound record and only then releases the
hardware slot. The receipt outbox is intentionally uncapped in production;
each record is small public metadata, and losing unacknowledged financial
evidence is worse than gradual storage growth.

Core may answer that an elicitation is no longer pending because of timeout,
restart, cancellation, or a previously received reply. That response is
inconclusive—it is not a digest acknowledgement of this receipt. Android keeps
and retries the exact archived record until the live correlated response path
proves settlement. A durable acknowledgement journal is future work.

## Logout and host changes

Every action/receipt carries its pairing host binding.

- Unsigned old-host actions are removed, but intent tombstones remain until
  expiry.
- A valid terminal receipt is archived before logout or switching Macs.
- Signing, broadcasting, ambiguous broadcast recovery, or an invalid terminal
  record blocks the exit.
- Persistence failure aborts logout/host switch and asks the user to stay
  paired and retry.
- A receipt from the prior Mac is never rebound or sent to the new host.

Cloud backup and device-to-device transfer are disabled for this state. A new
phone must authenticate, pair, associate the Ledger, and select a wallet again.

## Signer boundary

The Mac-side standalone `wallet-cli` is not invoked by this flow. Core only
routes and validates the typed action/receipt. Android's native BLE/APDU client
and the physically approved Ledger are the only signer; Android is the only
broadcaster. Adding another signer path would require a separate protocol and
threat review.

## Release safety

`Demo.ENABLED` must remain `false`. The opt-in demo script contains a fixed
public recipient and, when enabled, signs and broadcasts a real mainnet SOL
transfer. It is for controlled recorded walkthroughs only and must never be
enabled in a production/debug artifact distributed to testers.

No routine automated test sends a transaction. A safe hardware rehearsal is:
receive a real typed request, review it on the Flex, reject on-device, verify a
typed cancellation in Core, and confirm no broadcast. A mainnet submit test
moves real funds and requires explicit human intent.

## Source map

| Concern | Source |
| --- | --- |
| APDU/Solana app | `ledger/SolanaLedgerApp.kt` |
| BLE transport | `ledger/LedgerBleTransport.kt` |
| account, signing, broadcast state | `ledger/LedgerController.kt` |
| transaction and RPC primitives | `ledger/Solana.kt` |
| strict receipt/result models | `model/LedgerTransferReceipt.kt` |
| action parsing | `net/PortalEventAdapter.kt` |
| durable inbox/fence/outbox | `data/Prefs.kt`, `data/DaemonHub.kt` |
| UI | `ui/screens/LedgerScreen.kt`, `ui/chat/MessageRows.kt` |
