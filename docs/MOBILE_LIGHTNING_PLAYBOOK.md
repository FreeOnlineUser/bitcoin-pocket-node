# Mobile Lightning Playbook

Hard-won techniques for running LDK on Android. Each one came from losing sats, debugging crashes, or discovering undocumented behavior.

## State Protection

### WAL Checkpoint (prevents channel loss)
SQLite writes to a Write-Ahead Log before the main DB file. If Android kills the process before the WAL is merged, LDK reads stale state on next start. Channel_manager says no channel, monitor says yes. LDK archives the monitor. Channel gone.

**Fix:** Force `PRAGMA wal_checkpoint(TRUNCATE)` after every channel/payment event and before every `build()`. One line of SQL prevents channel loss.

```kotlin
db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
```

TRUNCATE mode merges WAL into the main file then deletes the WAL entirely. No stale reads possible.

### Integrity Check on Startup
Before checkpointing, run `PRAGMA integrity_check` to detect corrupt WAL frames from mid-write crashes. Log the result. Better to know the state is bad than to silently read garbage.

### No Auto-Restarts
Never call `stop()` then `start()` programmatically. Every automatic restart risks a race between state persistence and the new session reading. Sync watchdog, orphan detection, reconnection logic: none of these should restart LDK. Show an error, let the user decide.

## Recovery

### Static Channel Backup (SCB)
On channel open: save peer pubkey, funding txid/vout, peer addresses, capacity. On channel close: remove. Simple JSON file.

On channel loss: connect to the peer. LDK sends "no such channel" error. Peer detects mismatch, force-closes. Funds return after ~144 blocks (~24 hours).

Proven to work: Start9 HQ force-closed within seconds of reconnection.

### Why Full State Backups Don't Work
- **Full restore before build():** `ReadFailed`. LDK can't deserialize state from a different session context.
- **Monitor-only restore before build():** Same `ReadFailed`.
- **Monitor injection after start():** LDK archives orphan monitors (no matching channel_manager).
- **broadcastHolderCommitmentTxns:** Only works on active monitors, not archived.
- **Stale monitor injection causes native crash:** "Failed to process events" SIGABRT in libldk_node.so.

SCB is the only reliable recovery path.

### Missing On-Chain Funds
BDK's bitcoind chain source doesn't support `full_scan`. It only sees addresses the wallet has already generated. After a restart, historical UTXOs at older addresses are invisible.

**Fix:** Export seed to BlueWallet (BIP84 compatible), sweep all funds to a new deposit address from the app.

## Logging

### Drop Gossip Logs
LDK gossip generates 36k+ channel updates on startup (~7 MB). Android logcat ring buffer is 256 KB. Gossip wipes all important logs before they can be read.

**Fix:** `LogLevel.GOSSIP -> {}` in the custom LogWriter. Drop them entirely. They have zero diagnostic value for a mobile node.

### Circuit Breaker
After N consecutive starts without a clean stop, disable auto-start. User must start manually. Prevents crash loops from consuming battery and flooding logs.

## Networking

### Power Modes
- **Max Data:** Continuous connection, 8 peers. WiFi + charging.
- **Low Data:** Burst every 15 min. WiFi + battery.
- **Away Mode:** Burst every 60 min. Cellular or low battery.

Auto-detect based on WiFi/charging/battery state. Wallet Hold keeps network active while external wallet is connected.

### Burst Sync
In Low/Away modes, bring up networking for a burst, sync blocks, exchange channel state, then disconnect. LDK disconnects peers between bursts automatically.

Key timing: 30s peer wait on cold start (15s too short). `setMode()` must come before `setRpc()` or burst won't trigger.

### Lightning vs Bitcoin Bandwidth
Lightning gossip: ~5-10 KB/hr (free). bitcoind block downloads: 14-102 MB/burst. Lightning peers persist independently of bitcoind P2P.

## Tor

### Full Routing
All four connection types through Arti SOCKS5:
1. Bitcoin P2P (`-proxy=127.0.0.1:9050`)
2. HTTP/API calls (via bitreq SOCKS5 fork)
3. Lightning peers (`tor_connect_outbound()` in ldk-node)
4. Watchtower (Brontide over SOCKS5)

### Tor Doesn't Make You Anonymous
Node pubkey is permanent and linkable across sessions. On-chain footprint visible. Tor hides your IP, not your identity.

### Timeout Adjustment
Default 5s HTTP timeouts fail through Tor. Bumped to 30s globally. Should be conditional on TorConfig in future.

### Idle Cost
~2-5 MB/day for directory consensus + circuit keepalive.

## Channel Management

### Peer Minimum Discovery
Three-tier system from probe results:
- **Exact:** Peer rejected with specific amount in error message.
- **Floor:** Peer disconnected without error at amount X (minimum is >= X).
- **Ceiling:** Peer accepted channel at amount X (minimum is <= X).

Cached in SharedPreferences, shared via phone-to-phone, shown in peer browser "Smallest" tab.

### Anchor Channels
Zero-fee commitments eliminate fee disagreements that cause force-closes. Essential for mobile. LDK enables by default. Check feature bit 23 from mempool.space API.

Downside: require on-chain funds for CPFP on force-close.

### Cooperative Close Fee
Use minimum feerate (253 sat/kw) for cooperative close. Widens acceptable range. Mobile nodes shouldn't insist on stale fee estimates. Anchor channels make this unnecessary for new channels.

## Data Usage

### NetworkMonitor Singleton
Was instantiated 5x, inflating data usage ~5x. Use `getInstance(context)` pattern.

### App-Only Traffic
`TrafficStats.getUidRxBytes(myUid)` not `getTotalRxBytes()`. The latter counts ALL phone traffic.

## Seed & Wallet

### BIP39 Standard Derivation
`fromBip39Mnemonic` for standard BIP39/BIP84 compatibility. Works with BlueWallet and any BIP84 wallet. Legacy `keys_seed` wallets use `fromSeedPath` (mnemonic backup incomplete).

### Wallet Birthday
Block height at wallet creation. Enables instant recovery (skip scanning blocks before birthday). Stored in `wallet_birthday` file, included in seed backup.

### Electrum Server
Pure Kotlin, no external dependency. scantxoutset for confirmed UTXOs, descriptor wallet for unconfirmed. Gap limit discovery (stop after 20 empty). Unsolicited scripthash notifications for real-time balance updates in BlueWallet.

## Build & Deploy

### Force-Stop Before Install
Always `adb shell am force-stop` before `adb install`. Prevents orphan bitcoind processes.

### Never `adb uninstall`
Wipes app data including wallet. Use `install -r` to update in place.

### GrapheneOS Blocks Downgrades
`INSTALL_FAILED_VERSION_DOWNGRADE` even with `-d` flag. Always increment versionCode.

## Channel Open UX

### Async Channel Negotiation
`openChannel()` returns success when negotiation starts, not when the peer accepts. The channel appears in `listChannels()` within 6ms. Rejection arrives 350-700ms later. Checking too early falsely reports success.

**Fix:** Wait 3 seconds, polling `handleEvents()` every 500ms. Then check `listChannels()`. If channel survived, peer accepted. If gone, peer rejected. Drain events during wait to capture the close reason.

### updateState() Wipes Transient Fields
`updateState()` creates a new `LightningState()` every 10 seconds. Constructor defaults reset `lastChannelError` to null. Any field not explicitly carried forward gets silently wiped.

**Rule:** When adding fields to state objects, always carry them through `updateState()`.

### Anchor Downgrade on Rejection
LDK retries rejected channel opens with downgraded channel type (removing anchors), even when the rejection was about amount/policy, not channel type. Causes double-rejection in logs and wasted bandwidth. Upstream bug in `maybe_downgrade_channel_features`.

### Never Delete Channel State on Timeout
A sync watchdog that deleted SQLite after 120s with no new block destroyed a live 100k sat channel. Bitcoin blocks can take 30+ minutes. Always compare against bitcoind height, never use elapsed time alone.

## Force-Close Broadcast

### Fire-and-Forget Problem
LDK's `BroadcasterInterface` drops failed broadcasts with no retry. If `sendrawtransaction` fails (network off during burst mode), the commitment tx is gone. `rebroadcast_pending_claims()` only handles sweeps, not the commitment tx itself.

### Commitment Tx Rebroadcast
Call `broadcastHolderCommitmentTxns()` on startup and periodically when pending close funds exist. This iterates closed channel monitors and re-broadcasts the latest holder commitment. Safety net for fire-and-forget failures.

## Android-Specific

### UniFFI Tokio Runtime Context Leak
When `node.start()` is called from a Kotlin coroutine context, UniFFI's JNI bridge leaks a tokio runtime handle to the calling thread. LDK borrows that runtime instead of creating its own. The reactor is not driven from the sync thread, causing all `.await` calls to hang forever.

**Fix:** Start LDK from a plain `Thread`, never from `withContext(Dispatchers.IO)` or `runBlocking`.

### Electrum History Bug
Client-side transaction cache showed stale history after wallet operations. Resolved by clearing the persisted tx history file on significant state changes.

---

*Every entry here cost us something to learn. Don't repeat the mistakes.*
