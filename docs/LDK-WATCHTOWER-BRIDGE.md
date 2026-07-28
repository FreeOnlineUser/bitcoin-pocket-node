# LDK-to-LND Watchtower Bridge

## Problem

Bitcoin Pocket Node uses ldk-node for Lightning. The phone goes offline (sleep, airplane mode, nightly restart). If a channel counterparty broadcasts a revoked commitment transaction while the phone is offline, the user loses funds unless a watchtower detects and punishes the cheater.

LND has a mature watchtower system. Many users already run an LND watchtower on their home node (Umbrel, Start9, etc.). But LND's watchtower speaks its own protocol, and LDK has no built-in watchtower client. These two systems have never talked to each other.

**We built the bridge.**

## Architecture

```
Phone (ldk-node)                          Home Node (Umbrel)
┌──────────────────────┐                  ┌─────────────────────┐
│                      │                  │                     │
│  ldk-node (forked)   │   SSH tunnel     │  LND Watchtower     │
│    │                 │ ──────────────>  │  (wtserver:9911)    │
│    ├── WatchtowerPer-│                  │    │                │
│    │   sister wraps  │   Brontide       │    ├── Encrypted    │
│    │   all Persist   │   (BOLT 8)       │    │   blob store   │
│    │   callbacks     │                  │    │                │
│    │                 │                  │  bitcoind            │
│  WatchtowerBridge.kt │                  │    │                │
│    │                 │                  │    ├── Watches      │
│    ├── Drains blobs  │                  │    │   every block  │
│    │   from ldk-node │                  │    │                │
│    ├── Encrypts as   │                  │    └── Broadcasts   │
│    │   JusticeKitV0  │                  │        justice tx   │
│    │                 │                  │                     │
│    └── Pushes via    │                  └─────────────────────┘
│       native Brontide│
│       (Rust .so)     │
└──────────────────────┘
```

## How It Works

### 1. Real-time Capture (ldk-node fork)

We forked ldk-node and added `WatchtowerPersister` -- a wrapper around the existing `MonitorUpdatingPersister`. It intercepts every `persist_new_channel` and `update_persisted_channel` callback without changing any existing behavior.

When a new channel state arrives, it extracts:
- Counterparty commitment transactions (via `counterparty_commitment_txs_from_update`)
- Revocation data from subsequent updates
- CSV delay (brute-force matched against P2WSH script, since `to_broadcaster_delay` is private)

These are stored as pending `WatchtowerCommitment` entries, deduplicated by txid.

**Only 2 files changed in ldk-node itself** (`types.rs` and `builder.rs`). All watchtower logic lives in `src/watchtower.rs`.

### 2. Blob Construction

When commitments are drained (via `watchtower_drain_justice_blobs()`), they're packaged into LND's `JusticeKitV0` format:
- Sweep address (42 bytes padded)
- Revocation pubkey (33 bytes)
- Local delay pubkey (33 bytes)
- CSV delay (4 bytes)
- to-local revocation signature (64 bytes)
- to-remote pubkey (33 bytes)
- to-remote signature (64 bytes)

Encrypted with XChaCha20-Poly1305:
- Key: revoked commitment txid (32 bytes)
- Nonce: random 24 bytes
- Output: `[24-byte nonce][encrypted 274 bytes][16-byte MAC]`
- Hint: first 16 bytes of txid (tower's lookup key)

### 3. Wire Protocol (Custom Brontide)

LND's watchtower uses Brontide (BOLT 8 Noise_XK with secp256k1 ECDH). No existing Rust library supports this -- the `snow` framework uses Curve25519 and hashes the wrong protocol name (`"Noise_XK_25519_ChaChaPoly_SHA256"` instead of `"Noise_XK_secp256k1_ChaChaPoly_SHA256"`), causing MAC mismatches.

We wrote a complete Brontide implementation from scratch in `src/brontide.rs`:
- **Noise_XK 3-act handshake** with secp256k1 ECDH (libsecp256k1's `SharedSecret::new()` already computes `SHA256(compressed_point)`, matching LND)
- **ChaCha20-Poly1305** CipherState with nonce counter and HKDF key rotation at 1000 messages
- **SymmetricState** with `mix_key`, `mix_hash`, `encrypt_and_hash`, `decrypt_and_hash`
- **Encrypted message transport** with length-prefixed framing per BOLT 8

Verified byte-for-byte against BOLT 8 Appendix A test vectors.

### 4. Tower Communication

Wire message types 600-607 (LND's `wtwire` package):
1. **Init** (600/601): Feature negotiation, exchanged immediately after handshake
2. **CreateSession** (602/603): Blob type, max updates, sweep fee rate
3. **StateUpdate** (604/605): Hint + encrypted blob for each revoked state
4. **DeleteSession** (606/607): Clean up when channel closed

### 5. Phone-side Integration

`WatchtowerBridge.kt` orchestrates the Kotlin side:
- Drains justice blobs from ldk-node via UniFFI
- Encrypts with XChaCha20-Poly1305 (pure Kotlin HChaCha20 implementation)
- SSH tunnels to Umbrel (port 9911 is inside Docker, not exposed to host)
- Pushes via `libldk_watchtower_client.so` (native Rust, JNA bindings)

Auto-drain is hooked into `LightningService.handleEvents()` after `ChannelReady` and `ChannelClosed` events.

Fallback: when the tower is unreachable, encrypted blobs are saved locally to `filesDir/watchtower_blobs/<hint>.blob` for later retry.

## How the Tower Catches Cheaters

1. Tower watches every new block
2. For each transaction, computes the 16-byte hint from the txid
3. If a hint matches a stored blob, decrypts using the full txid as the key
4. Reconstructs the justice transaction from the decrypted data
5. Broadcasts the justice transaction, sweeping the cheater's funds

The phone never needs to be online for punishment. The tower handles it autonomously.

## Implementation Status

### Complete

- **ldk-node fork** (`FreeOnlineUser/ldk-node`, branch `watchtower-bridge-v2`)
  - `WatchtowerPersister` wrapping `MonitorUpdatingPersister` -- intercepts all Persist callbacks
  - `watchtower_list_monitors()`, `watchtower_export_monitors()`, `watchtower_drain_justice_blobs()` on Node
  - `watchtower_set_sweep_address()` for configuring where swept funds go
  - UniFFI bindings for Kotlin
  - 30 tests passing (27 existing + 3 watchtower)
  - Cross-compiled to ARM64 (25 MB .so), AAR built

- **ldk-watchtower-client** (`FreeOnlineUser/ldk-watchtower-client`)
  - Custom BOLT 8 Brontide transport (`brontide.rs`) -- full Noise_XK with secp256k1
  - Wire protocol (`wire.rs`) -- message types 600-607, Init/CreateSession/StateUpdate
  - Justice blob construction (`blob.rs`) -- JusticeKitV0, XChaCha20-Poly1305 encryption
  - C FFI layer (`ffi.rs`) -- `wtclient_connect`, `wtclient_send_backup`, `wtclient_disconnect`
  - Cross-compiled to ARM64 (1 MB .so)

- **App integration** (`bitcoin-pocket-node`)
  - Local AAR replaces Maven ldk-node dependency
  - `WatchtowerBridge.kt` -- drain, encrypt, tunnel, push
  - `WatchtowerNative.kt` -- JNA bindings to native .so
  - `WatchtowerScreen.kt` + `WatchtowerManager.kt` -- UI and auto-detection
  - Auto-detect watchtower during SSH copy flows
  - Auto-drain after channel events

- **Verified against live LND tower on Umbrel**
  - Brontide handshake: working
  - Init exchange: working
  - CreateSession: tower responds correctly (returned TemporaryFailure code 40 -- capacity, not protocol error)

### Remaining

1. **Resolve tower capacity** -- check Umbrel watchtower config for session limits
2. **StateUpdate push** -- send actual encrypted justice blobs after CreateSession succeeds
3. **`sign_to_local_justice_tx()`** -- call during persist callbacks for complete revocation signatures
4. **End-to-end test** -- fund LDK wallet, open channel, verify blobs captured and pushed
5. **Tag v0.8-alpha** -- watchtower milestone

## Security

- **Signing keys never leave the phone.** Justice blobs contain pre-signed transactions, not private keys
- **Tower learns nothing about channels** until a breach occurs (hint is just 16 bytes of a txid)
- **SSH tunnel** provides encrypted transport to home node
- **XChaCha20-Poly1305** encrypts blobs at rest (on tower and in local fallback)
- **Same trust model as running LND** -- your home node, your tower

## Ecosystem Impact

This is the **first cross-implementation watchtower bridge** in the Lightning ecosystem. No one has connected LDK to LND's watchtower protocol before. The ldk-node changes are designed to be clean enough to PR upstream, benefiting any LDK-based mobile wallet that wants to use existing LND tower infrastructure.

**Upstream relationship:** the justice-tx primitive this bridge relies on is being standardized upstream as rust-lightning [#4453](https://git.rust-bitcoin.org/lightningdevkit/rust-lightning/pulls/4453) (Matt's Option-2 design: a not-rotated retention list of revoked counterparty commitments on the monitor, `get_pending_justice_txs` sourced from it, `mark_justice_persisted` to drain it, plus a `WatchtowerPersist` trait with ChainMonitor gating for crash-safety). The foundation is pushed and awaiting review on the rust-lightning Forgejo; the trait and gating are held follow-ups. Pocket Node is the downstream consumer and keeps this hand-rolled bridge until the `WatchtowerPersist` trait lands, at which point the capture path migrates onto it. See `LDK-UPSTREAM-CONTRIBUTION.md` for the full upstream status.

## Repositories

- **ldk-node fork**: https://github.com/FreeOnlineUser/ldk-node/tree/watchtower-bridge-v2
- **Watchtower client**: https://github.com/FreeOnlineUser/ldk-watchtower-client
- **Bitcoin Pocket Node**: https://github.com/FreeOnlineUser/bitcoin-pocket-node

## References

- [BOLT 8: Encrypted and Authenticated Transport](https://github.com/lightning/bolts/blob/master/08-transport.md)
- LND watchtower: `lnd/watchtower/` (blob, wtwire, wtclient, wtserver)
- LND justice kit: `lnd/watchtower/blob/justice_kit.go`
- LDK ChannelMonitor: `lightning::chain::channelmonitor::ChannelMonitor`
- LDK Persist trait: `lightning::chain::chainmonitor::Persist`
