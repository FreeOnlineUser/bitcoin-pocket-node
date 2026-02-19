# Bitcoin Pocket Node — Development Plan

## Phase 0: Research & Toolchain ✅

- [x] Cross-compile `bitcoind` for ARM64 Android (13MB stripped, NDK r27)
- [x] Set up Android SDK + NDK r27 on dev machine (Big Sur, i7-4870HQ)
- [x] Test bitcoind on real Pixel 7 Pro — RPC responds, peers connect
- [x] Generate UTXO snapshot from Umbrel (Knots 29.2.0, `dumptxoutset rollback`)
- [x] Patch chainparams with AssumeUTXO heights 880k, 910k (backported from Core 30)

## Phase 1: Proof of Concept ✅

- [x] Android app (Kotlin/Jetpack Compose) bundles bitcoind as `libbitcoind.so`
- [x] Foreground service starts/stops bitcoind
- [x] `loadtxoutset` via local RPC — 167M UTXOs loaded on Pixel 7 Pro
- [x] Node connects to P2P network and syncs forward from snapshot
- [x] Dashboard: block height, sync progress with ETA, peers, animated status dot

## Phase 2: Snapshot Manager ✅

- [x] SFTP download from personal node over LAN (~5 min for 9 GB)
- [x] Snapshot generation via SSH (`dumptxoutset rollback` on remote node)
- [x] Snapshot block hash validation before loading (auto-redownload if wrong)
- [x] Non-blocking loadtxoutset with progress polling (tails debug.log)
- [x] Early completion detection (skip re-download if already loaded)
- [x] "Try pocketnode first" — check for existing snapshot with saved SFTP creds
- [ ] Download from HTTPS URL (utxo.download)
- [ ] Load from local file (USB/SD/Downloads)

## Phase 3: Smart Syncing ✅

- [x] Network-aware sync — auto-pauses on cellular, resumes on WiFi
- [x] VPN support — WireGuard/VPN treated as connected
- [x] Foreground service with persistent notification
- [x] Data usage tracking with WiFi/cellular budgets
- [x] Auto-start on app launch (SharedPreferences flag)
- [ ] Charging-aware sync (configurable)
- [ ] Doze mode handling
- [ ] Detect when behind prune window → offer re-bootstrap

## Phase 4: Node Setup & Security ✅

- [x] SSH setup wizard — creates restricted `pocketnode` SFTP account
- [x] Platform-agnostic detection (Docker vs native, not hardcoding Umbrel/Start9)
- [x] Root-owned copy scripts — pocketnode never has data dir access
- [x] Admin credentials never saved (username pre-filled from SharedPreferences)
- [x] View/remove node access from app
- [x] Setup checklist (Config mode) with auto-detection of completed steps
- [x] `network_security_config.xml` — cleartext HTTP to localhost only
- [x] GrapheneOS W^X compliance — bitcoind in `jniLibs/` → `nativeLibraryDir`

## Phase 5: Wallet RPC Interface

- [ ] Localhost RPC endpoint for external wallet apps
- [ ] Connection instructions / QR code for supported wallets
- [ ] Electrum server integration (optional, stretch goal)

## Phase 6: Polish & Release

- [ ] Onboarding flow
- [ ] Storage management (prune depth configuration)
- [ ] Peer management UI
- [ ] Mempool viewer
- [ ] Config mode / Run mode UX refinement
- [ ] Beta testing on multiple Pixel devices
- [ ] F-Droid / APK distribution (no Google Play)
- [ ] Auto-restart detection in foreground service
- [ ] Clean up old snapshot files on Umbrel

## Technical Risks (Resolved)

1. ~~**bitcoind ARM64 compilation**~~ ✅ Works with NDK r27 clang wrappers
2. ~~**Android background process limits**~~ ✅ Foreground service handles it
3. ~~**Storage**~~ ✅ 9 GB snapshot + 2 GB pruned chain — clear UX about requirements
4. ~~**RAM**~~ ✅ `dbcache=256` fine on Pixel 7 Pro (12 GB RAM)
5. ~~**Thermal throttling**~~ ✅ Phone stays cool during loadtxoutset and sync
6. ~~**GrapheneOS W^X**~~ ✅ `nativeLibraryDir` is the only executable path
7. ~~**Cleartext HTTP on Android**~~ ✅ `network_security_config.xml` for localhost RPC

## Infrastructure

- **Dev machine:** 2014 MacBook Pro 15" (i7-4870HQ, 16GB, Big Sur)
- **Test node:** Umbrel VM on Mac Mini (Bitcoin Knots 29.2.0, full unpruned, 820GB chain) at 10.0.1.127:9332
- **Target hardware:** Google Pixel 7 Pro with GrapheneOS
- **Repo:** github.com/FreeOnlineUser/bitcoin-pocket-node

## bitcoind Configuration (Mobile)

```ini
server=1
prune=2048
listen=0
maxconnections=4
maxmempool=50
blockreconstructionextratxn=10
dbcache=256
rpcbind=127.0.0.1
rpcallowip=127.0.0.1
rpcuser=pocketnode
rpcpassword=<generated>
disablewallet=1
```

**Why no `blocksonly=1`?** Partial mempool (50MB cap) for fee estimation, payment detection, privacy cover traffic, and compact block reconstruction.

**Why v28.1 instead of Core 30.x?** Core 30 changed OP_RETURN policy. v28.1 with patched AssumeUTXO heights gives us snapshot flexibility while preserving standard transaction relay behavior.

## Nice to Haves / Backlog

### Performance
- [ ] Optimize stub file creation — only create stubs for files actually in index range (reduce 15 min pruning)
- [ ] Copy more block files (~3-4 blk/rev pairs) to cover full pruning window (reorg safety)

### BWT / Wallet
- [ ] Descriptor wallet support — replace `importmulti` with `importdescriptors`
- [ ] Taproot/P2TR output recognition
- [ ] Multisig support (comes with descriptor wallets)
- [ ] Remove `deprecatedrpc=create_bdb` dependency

### Chainstate Copy
- [ ] XOR re-encoding — after chainstate copy, decode both block index (xor.dat key) and chainstate (embedded obfuscation key at `\x0e\x00obfuscate_key`) from source node, re-encode with locally generated keys. Every node unique on disk from first boot.
  - Must run after transfer, before bitcoind first starts
  - Only LevelDB values are obfuscated, not keys
  - Need to handle all LevelDB layers (SST files, WAL logs) — safest to read entire DB with old key, write fresh DB with new key
  - Chainstate is ~11GB, re-encoding adds a few minutes — needs progress indicator
  - Verify a sample of entries after re-encoding before first start
  - **Requires bundling a LevelDB library** (Java/Kotlin impl or JNI with C++ lib) — biggest practical hurdle
- [ ] Phone-to-phone chainstate copy (WiFi Direct / hotspot)

### Networking
- [ ] Tor integration for private peer connections

### UX
- [ ] **Foldable dual-pane mode** — detect unfolded state via `WindowInfoTracker`/`FoldingFeature`. When unfolded, show dashboard on the left and live mempool screen on the right. Bitcoin command center mode.
- [ ] Non-technical setup documentation for everyday users
- [ ] Expanded device testing beyond Pixel line

### Bitcoin Core Version Selection
- [ ] Bundle multiple Bitcoin Core versions in the APK (v28.1, v29, etc.)
- [ ] User-selectable in settings — pick which version to run
- [ ] Restart node with selected binary, no download needed
- [ ] ~20MB per binary, 3-4 versions keeps APK under 100MB
- [ ] User controls which consensus rules they run — never auto-update
- [ ] Note policy differences (e.g. v30 OP_RETURN changes)
- [ ] **Version compatibility matrix** — built-in table showing which versions can safely switch between each other
  - Green: safe to swap both directions (e.g. 28.0 <-> 28.1)
  - Yellow: forward OK but downgrade needs reindex (e.g. 28 -> 29 OK, 29 -> 28 risky)
  - Red: incompatible, requires full reindex or re-copy from donor
  - Matrix displayed before confirming version change
- [ ] **v28.1 baseline chainstate** — keep a frozen copy of the 28.1 chainstate as backup (~11 GB)
  - Two chainstates max on disk: active + 28.1 backup (~24 GB total)
  - Forward upgrades always safe (28.1 -> any newer version)
  - Downgrade = restore 28.1 backup, then fast-forward on target version (minutes, not reindex)
  - No reindex ever needed — just restore and catch up
  - Created from initial donor copy, never modified
- [ ] **Downgrade warning** — "This will restore v28.1 chainstate and catch up to chain tip. Continue?"
- [ ] Populate matrix from Bitcoin Core release notes (chainstate format changes are documented)

### Lightning (Zeus Integration via Neutrino)

**Concept:** Enable Zeus wallet to connect to Pocket Node's bitcoind for sovereign Lightning chain validation. No new Lightning code in our app — Zeus handles Lightning, we provide the trusted chain backend via BIP 157/158 compact block filters.

**The Sovereign Phone Stack:**
- **Pocket Node** — full validating Bitcoin node (trust anchor)
- **BlueWallet** — on-chain wallet via our Electrum server (BWT, port 50001)
- **Zeus** — Lightning wallet via our bitcoind Neutrino (BIP 157/158, port 8333)

**Setup Flow:**
- [ ] "Enable Lightning" toggle in settings with warning: "Requires ~5 GB additional data from your source node"
- [ ] Reuse existing SSH credentials from chainstate copy (no new login needed)
- [ ] SSH to donor node, add `blockfilterindex=1` to `bitcoin.conf`
- [ ] Restart donor's bitcoind, monitor index build via RPC (`getindexinfo`)
- [ ] Once index built, copy `indexes/blockfilter/basic/` directory to phone
- [ ] Check for XOR key in filter index directory — copy alongside if present (same pattern as chainstate xor.dat)
- [ ] Revert donor's `bitcoin.conf` immediately after copy (remove `blockfilterindex=1`)
- [ ] Restart donor's bitcoind — donor back to original state, completely out of the picture
- [ ] Configure local bitcoind: `blockfilterindex=1` + `peerblockfilters=1`
- [ ] Restart local bitcoind — now serves compact block filters

**Standalone Operation:**
- Historical filters copied from donor, new block filters built locally as blocks arrive
- Filter index doesn't need block data to serve — only to build. Pre-built = works on pruned node
- Phone never needs donor again after copy (same as chainstate copy)
- Zeus connects to localhost, gets filters, fully sovereign Lightning

**Implementation Notes:**
- [ ] Filter index lives in `indexes/blockfilter/basic/` (separate LevelDB from chainstate)
- [ ] Verify XOR obfuscation: own key vs shared with blocks index
- [ ] Can be done during initial chainstate copy or as separate "Lightning upgrade" download later
- [ ] If donor already has `blockfilterindex=1` enabled, skip the enable/build/revert — just copy
- [ ] Progress UI: index build can take hours on donor, need monitoring and status display
- [ ] Test with Umbrel and Start9 (different file paths, permissions)

**Future Enhancements:**
- [ ] VLS (Validating Lightning Signer) — phone holds signing keys, remote server runs always-online Lightning node. Even server operator can't steal funds.
- [ ] Partnership with sovereign data center for LSP/VLS infrastructure
- [ ] LAN exposure toggle for Zeus on separate device (same network)

### Maintenance
- [ ] BWT fork maintenance and modernization

### Visuals
- [ ] Block visualization — animated graphic showing stub creation → pruning → backfill (explainer for chainstate copy technique)

### Mempool Integration Notes
- **Rust GBT lib skipped** — using Kotlin fallback for block projection. If performance is an issue on phone, cross-compile the Rust native lib (`app/src/main/rust/gbt/`) with `aarch64-linux-android` NDK target.
- **Widget skipped** — mempool home screen widget from pocket-mempool repo can be added later.
