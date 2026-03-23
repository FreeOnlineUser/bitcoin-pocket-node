# PowerModeManager Architecture

620 lines, one class, many responsibilities. This document maps every moving part, how they interlock, and where the known bugs live.

## State (all in companion object, survives re-creation)

| Flow/Field | Type | Default | What it controls |
|---|---|---|---|
| `_modeFlow` | `MutableStateFlow<Mode>` | `Mode.LOW` | Current power mode. Loaded from `power_mode` pref in `init`. |
| `_autoEnabled` | `MutableStateFlow<Boolean>` | `false` | Whether auto-detect is active. Loaded from `power_mode_auto` pref. |
| `_burstStateFlow` | `MutableStateFlow<BurstState>` | `IDLE` | Current burst state: IDLE, SYNCING, WAITING. |
| `_nextBurstFlow` | `MutableStateFlow<Long>` | `0` | Epoch ms of next burst. 0 = not scheduled. |
| `_walletConnectedFlow` | `MutableStateFlow<Boolean>` | `false` | True while external wallet (BlueWallet) is connected. |
| `_initialSyncHold` | `MutableStateFlow<Boolean>` | `false` | True during IBD. Blocks `setNetworkActive(false)`. |
| `channelHoldingNetwork` | `@Volatile Boolean` | `false` | True when channel open is holding network. |
| `_channelHoldFlow` | `MutableStateFlow<Boolean>` | `false` | UI-observable version of above. |
| `getLdkHeight` | `(() -> Long)?` | `null` | Callback to get LDK block height. Set by LightningService. |

## Instance State

| Field | Type | Purpose |
|---|---|---|
| `burstJob` | `Job?` | The running burst cycle coroutine. |
| `autoDetectJob` | `Job?` | The auto-detect monitoring coroutine. |
| `initialSyncJob` | `Job?` | The IBD polling coroutine (checks every 30s). |
| `walletIndicatorJob` | `Job?` | 10s delay before hiding wallet indicator. |
| `rpc` | `BitcoinRpcClient?` | RPC client, set by `setRpc()`. |
| `activeScope` | `CoroutineScope?` | Scope for launching coroutines. |
| `walletHoldingNetwork` | `Boolean` | True when wallet session is holding. |
| `networkHoldCount` | `Int` | Reference-counted network holds (channel opens). |

## Modes

| Mode | Behavior | Burst Interval | Peers |
|---|---|---|---|
| **MAX** | Continuous connection, network always on | None | 8 |
| **LOW** | Burst every 15 min, network off between | 15 min | 8 |
| **AWAY** | Burst every 60 min, network off between | 60 min | 8 |

Note: peer counts are all 8 (constants exist but aren't differentiated).

## Entry Points (who calls what, and when)

### Startup Sequence (BitcoindService)

```
BitcoindService.onCreate()
  → pmm = PowerModeManager.getInstance(context)
  → pmm.setMode(modeFlow.value, scope)          // applies saved mode
  → pmm.setRpc(rpcClient)                        // may start burst cycle
  → pmm.startAutoIfEnabled(network, battery, scope)  // starts auto-detect if enabled
  → pmm.startInitialSyncHold(scope, rpc)         // if IBD detected
```

**Known race:** `setRpc()` can start burst cycling BEFORE `startInitialSyncHold()` is called. The IBD check in `setRpc()` (added March 24) mitigates this but doesn't eliminate the ordering dependency.

### setRpc(client)
- Saves RPC client
- If mode != MAX and no burst running and no IBD hold:
  - Checks `getblockchaininfo` for IBD
  - If not IBD: starts burst cycle via `applyMode()`
  - If IBD: skips (logs and returns)

### setMode(mode, scope, isAuto)
- Updates `_modeFlow`
- Saves to SharedPreferences (`power_mode`)
- If not auto: also saves to `power_mode_manual` (for revert on auto-disable)
- Cancels existing burst job
- If not auto: clears wallet + channel holds
- Launches `applyMode(mode)`

### applyMode(mode)
- MAX: `setNetworkActive(true)`, no burst
- LOW: `startBurstCycle(15 min)`
- AWAY: `startBurstCycle(60 min)`

### startBurstCycle(intervalMs)
- Cancels existing burst job
- Loop: `doBurst()` → wait interval → repeat

### doBurst() (the core burst logic)
1. Lock `burstMutex`
2. Snapshot current block height
3. `setNetworkActive(true)`
4. Wait up to 30s for at least 1 peer
5. If no peers: `setNetworkActive(false)`, abort
6. Wait 2s for headers
7. If new blocks: download, wait up to 2 min
8. If LDK running: wait up to 30s for LDK to catch up
9. `setNetworkActive(false)` (unless MAX mode)
10. Unlock mutex

**Known bug:** Error handler (catch block) calls `setNetworkActive(false)` without checking IBD hold or mode.

## Network Holds (things that keep the network on)

### 1. IBD Hold (`_initialSyncHold`)
- Set by `startInitialSyncHold()`
- Cancels burst job, calls `setNetworkActive(true)`
- Polls `getblockchaininfo` every 30s
- When `initialblockdownload` becomes false: clears hold, calls `applyMode()` to resume burst
- **Guard:** `setNetworkActive(false)` checks this flag and blocks if true

### 2. Wallet Hold (`walletHoldingNetwork`)
- Set by `onWalletSessionStarted()` (Electrum client connects)
- Cancels burst job, calls `setNetworkActive(true)`
- Cleared by `onWalletSessionEnded()` (all wallets disconnect)
- 10s grace period before hiding UI indicator
- After release: `applyMode()` resumes burst

### 3. Channel Hold (`networkHoldCount`, reference-counted)
- `holdNetwork()`: increments count, first hold activates network
- `releaseNetworkHold()`: decrements count, last release resumes burst
- Used by LightningService on ChannelPending/ChannelReady/ChannelClosed events

### 4. Auto-detect blocks downgrade when held
- `startAutoDetection()` skips mode downgrade if `walletHoldingNetwork || channelHoldingNetwork`
- Does NOT check `_initialSyncHold` (potential issue)

## Auto-Detect

### suggestMode(network, battery) → Mode
```
battery < 20% && !charging → AWAY
WiFi + charging → MAX
WiFi + battery → LOW
Cellular + charging → LOW
Cellular + battery → AWAY
Offline → AWAY
```

**Note:** No path to MAX on cellular. Manual MAX on cellular WILL be overridden if auto-detect is on.

### startAutoDetection()
- Combines network + battery state flows
- Skips first emission (don't override saved mode at boot)
- On change: if suggested != current, calls `setMode(suggested, isAuto=true)`
- Respects wallet/channel holds (won't downgrade while held)

## SharedPreferences Keys

| Key | File | Type | Purpose |
|---|---|---|---|
| `power_mode` | `pocketnode_prefs` | String | Current mode (MAX/LOW/AWAY) |
| `power_mode_auto` | `pocketnode_prefs` | Boolean | Auto-detect enabled |
| `power_mode_manual` | `pocketnode_prefs` | String | Last manually-set mode (for revert) |

## Known Bugs and Race Conditions

### 1. Startup ordering (FIXED March 24)
`setRpc()` starts burst before `startInitialSyncHold()`. During IBD, burst runs, syncs ~50 blocks, then kills network. Fix: `setRpc()` now checks `getblockchaininfo` directly.

### 2. Default mode is LOW before prefs load
`_modeFlow` starts as `Mode.LOW` (line 42). Prefs load in `init` (line 130). If anything reads `_modeFlow` before `init` completes (unlikely but possible with companion object statics), it gets LOW instead of saved mode.

### 3. Auto-detect doesn't respect IBD hold
`startAutoDetection()` checks `walletHoldingNetwork || channelHoldingNetwork` but NOT `_initialSyncHold`. Auto could theoretically override mode during IBD (though unlikely since auto is off by default).

### 4. Burst error handler kills network during IBD
`catch` block at line ~464 calls `setNetworkActive(false)` unconditionally. Now partially fixed: `setNetworkActive` checks `_initialSyncHold`. But the burst still aborts and doesn't retry.

### 5. networkHoldCount can drift
`holdNetwork()` increments, `releaseNetworkHold()` decrements. If a hold path fails to release (exception, missed event), count stays elevated forever. Network never returns to burst mode.

### 6. Multiple callers to applyMode()
`setMode()`, `endInitialSyncHold()`, `onWalletSessionEnded()`, `releaseNetworkHold()`, `setRpc()` all call `applyMode()`. Each one cancels and restarts the burst job. If two fire close together, the second cancels the first's burst mid-run.

## Callers Map

```
BitcoindService.onCreate()
  ├── setMode()
  ├── setRpc() → may call applyMode()
  ├── startAutoIfEnabled() → may start autoDetectJob
  └── startInitialSyncHold() → cancels burst, starts IBD poll

LightningService (channel events)
  ├── holdNetwork() → on ChannelPending
  └── releaseNetworkHold() → on ChannelReady / ChannelClosed

ElectrumService (wallet sessions)
  ├── onWalletSessionStart callback → onWalletSessionStarted()
  └── onWalletSessionEnd callback → onWalletSessionEnded()

UI (NodeStatusScreen / PowerModeSelector)
  ├── setMode() → user picks mode
  ├── setAutoEnabled() → user toggles auto
  └── triggerBurst() → manual burst button

Auto-detect coroutine
  └── setMode(suggested, isAuto=true) → on network/battery change
```

## Suggested Refactor (future)

1. **State machine:** Replace the web of boolean flags and jobs with an explicit state machine: IBD → SYNCED_MAX → SYNCED_BURST → WALLET_HOLD → CHANNEL_HOLD. Each state defines what's allowed.

2. **Single `applyMode()` entry:** Route all mode changes through one gated method that checks all holds before applying. No direct `setNetworkActive()` calls from burst/hold/auto-detect.

3. **Startup coordinator:** Enforce ordering: load prefs → check IBD → set mode → start auto-detect. Currently scattered across BitcoindService with timing dependencies.

4. **Separate burst manager:** Extract burst cycling into its own class. PowerModeManager decides WHEN to burst, BurstManager handles HOW.

5. **Tests:** Integration test that simulates IBD → sync complete → burst cycling → wallet connect → channel open → mode switch. Currently untestable without a real bitcoind.
