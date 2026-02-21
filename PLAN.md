# Bitcoin Pocket Node: Development Plan

## Phase 0: Research & Toolchain ✅

- [x] Cross-compile `bitcoind` for ARM64 Android (13MB stripped, NDK r27)
- [x] Set up Android SDK + NDK r27 on dev machine (Big Sur, i7-4870HQ)
- [x] Test bitcoind on real Pixel 7 Pro: RPC responds, peers connect
- [x] Generate UTXO snapshot from Umbrel (Knots 29.2.0, `dumptxoutset rollback`)
- [x] Patch chainparams with AssumeUTXO heights 880k, 910k (backported from Core 30)

## Phase 1: Proof of Concept ✅

- [x] Android app (Kotlin/Jetpack Compose) bundles bitcoind as `libbitcoind.so`
- [x] Foreground service starts/stops bitcoind
- [x] `loadtxoutset` via local RPC: 167M UTXOs loaded on Pixel 7 Pro
- [x] Node connects to P2P network and syncs forward from snapshot
- [x] Dashboard: block height, sync progress with ETA, peers, animated status dot

## Phase 2: Snapshot Manager ✅

- [x] SFTP download from personal node over LAN (~5 min for 9 GB)
- [x] Snapshot generation via SSH (`dumptxoutset rollback` on remote node)
- [x] Snapshot block hash validation before loading (auto-redownload if wrong)
- [x] Non-blocking loadtxoutset with progress polling (tails debug.log)
- [x] Early completion detection (skip re-download if already loaded)
- [x] "Try pocketnode first": check for existing snapshot with saved SFTP creds
- [x] Download from HTTPS URL (utxo.download)

## Phase 3: Smart Syncing ✅

- [x] Network-aware sync: auto-pauses on cellular, resumes on WiFi
- [x] VPN support: WireGuard/VPN treated as connected
- [x] Foreground service with persistent notification
- [x] Data usage tracking with WiFi/cellular budgets
- [x] Auto-start on app launch (SharedPreferences flag)
- [ ] Charging-aware sync (configurable)
- [ ] Doze mode handling

## Phase 4: Node Setup & Security ✅

- [x] SSH setup wizard: creates restricted `pocketnode` SFTP account
- [x] Platform-agnostic detection (Docker vs native, not hardcoding Umbrel/Start9)
- [x] Root-owned copy scripts: pocketnode never has data dir access
- [x] Admin credentials never saved (username pre-filled from SharedPreferences)
- [x] View/remove node access from app
- [x] Setup checklist (Config mode) with auto-detection of completed steps
- [x] `network_security_config.xml`: cleartext HTTP to localhost only
- [x] GrapheneOS W^X compliance: bitcoind in `jniLibs/` → `nativeLibraryDir`

## Phase 5: Wallet RPC Interface ✅

- [x] Localhost RPC endpoint for external wallet apps
- [x] Connection instructions / QR code for supported wallets (ConnectWalletScreen with copy buttons)
- [x] Electrum server integration (BWT runs alongside bitcoind, BlueWallet connects locally)

## Phase 6: Polish & Release

- [x] Onboarding flow (SetupChecklistScreen with auto-detection)
- [ ] Storage management (prune depth configuration)
- [ ] Peer management UI
- [x] Mempool viewer (fee estimates, projected blocks, transaction search)
- [x] Config mode / Run mode UX refinement (setup checklist vs dashboard)
- [ ] Beta testing on multiple Pixel devices
- [ ] F-Droid / APK distribution (no Google Play)
- [x] Auto-restart detection in foreground service (orphan bitcoind attach via RPC)
- [ ] Clean up old snapshot files on Umbrel

## Technical Risks (Resolved)

1. ~~**bitcoind ARM64 compilation**~~ ✅ Works with NDK r27 clang wrappers
2. ~~**Android background process limits**~~ ✅ Foreground service handles it
3. ~~**Storage**~~ ✅ 9 GB snapshot + 2 GB pruned chain: clear UX about requirements
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
- [ ] Detect corrupted block index after long offline period, offer re-bootstrap

### Performance
- [ ] Optimize stub file creation: only create stubs for files actually in index range (reduce 15 min pruning)
- [ ] Copy more block files (~3-4 blk/rev pairs) to cover full pruning window (reorg safety)

### BWT / Wallet
- [ ] Descriptor wallet support: replace `importmulti` with `importdescriptors`
- [ ] Taproot/P2TR output recognition
- [ ] Multisig support (comes with descriptor wallets)
- [ ] Remove `deprecatedrpc=create_bdb` dependency

### Chainstate Copy
- [ ] XOR re-encoding: after chainstate copy, decode both block index (xor.dat key) and chainstate (embedded obfuscation key at `\x0e\x00obfuscate_key`) from source node, re-encode with locally generated keys. Every node unique on disk from first boot.
  - Must run after transfer, before bitcoind first starts
  - Only LevelDB values are obfuscated, not keys
  - Need to handle all LevelDB layers (SST files, WAL logs): safest to read entire DB with old key, write fresh DB with new key
  - Chainstate is ~11GB, re-encoding adds a few minutes: needs progress indicator
  - Verify a sample of entries after re-encoding before first start
  - **Requires bundling a LevelDB library** (Java/Kotlin impl or JNI with C++ lib): biggest practical hurdle
- [ ] Phone-to-phone chainstate copy (WiFi Direct / hotspot)

### Networking
- [ ] Tor integration for private peer connections

### UX
- [x] **Foldable/landscape dual-pane mode**: BoxWithConstraints 550dp threshold, dashboard left + mempool right
- [ ] Non-technical setup documentation for everyday users
- [ ] Expanded device testing beyond Pixel line

### Bitcoin Core Version Selection
- [ ] Bundle multiple Bitcoin Core versions in the APK (v28.1, v29, etc.)
- [ ] User-selectable in settings: pick which version to run
- [ ] Restart node with selected binary, no download needed
- [ ] ~20MB per binary, 3-4 versions keeps APK under 100MB
- [ ] User controls which consensus rules they run: never auto-update
- [ ] Note policy differences (e.g. v30 OP_RETURN changes)
- [ ] **Version compatibility matrix**: built-in table showing which versions can safely switch between each other
  - Green: safe to swap both directions (e.g. 28.0 <-> 28.1)
  - Yellow: forward OK but downgrade needs reindex (e.g. 28 -> 29 OK, 29 -> 28 risky)
  - Red: incompatible, requires full reindex or re-copy from donor
  - Matrix displayed before confirming version change
- [ ] **v28.1 baseline chainstate**: keep a frozen copy of the 28.1 chainstate as backup (~11 GB)
  - Two chainstates max on disk: active + 28.1 backup (~24 GB total)
  - Forward upgrades always safe (28.1 -> any newer version)
  - Downgrade = restore 28.1 backup, then fast-forward on target version (minutes, not reindex)
  - No reindex ever needed: just restore and catch up
  - Created from initial donor copy, never modified
- [ ] **Downgrade warning**: "This will restore v28.1 chainstate and catch up to chain tip. Continue?"
- [ ] Populate matrix from Bitcoin Core release notes (chainstate format changes are documented)

### Lightning: Sovereign Mobile Lightning Node

**Concept:** Make a phone a genuinely sovereign Lightning node using existing standards. No new protocol primitives required. Every component exists today: the innovation is composing them into an architecture that works on mobile.

**The Sovereign Phone Stack:**
- **Pocket Node**: full validating Bitcoin node (trust anchor)
- **BlueWallet**: on-chain wallet via our Electrum server (BWT, port 50001)
- **Zeus**: Lightning wallet via our bitcoind Neutrino (BIP 157/158, port 8333)

#### Phase 1: Sovereign Foundation
Pruned Bitcoin Core on the phone with BIP 157/158 block filters. Zeus with embedded LND handles Lightning. Olympus LSP provides inbound liquidity via LSPS2 just-in-time channels. Users get a working sovereign Lightning experience without managing liquidity. Olympus can never steal funds: user holds keys and can exit to chain anytime. No watchtower needed: Olympus is a known, accountable entity.

**Block Filter Setup Flow:**
- [ ] "Enable Lightning" toggle in settings with warning: "Requires ~5 GB additional data from your source node"
- [ ] Reuse existing SSH credentials from chainstate copy (no new login needed)
- [ ] SSH to donor node, add `blockfilterindex=1` to `bitcoin.conf`
- [ ] Restart donor's bitcoind, monitor index build via RPC (`getindexinfo`)
- [ ] Once index built, copy `indexes/blockfilter/basic/` directory to phone
- [ ] Check for XOR key in filter index directory: copy alongside if present (same pattern as chainstate xor.dat)
- [ ] Revert donor's `bitcoin.conf` immediately after copy (remove `blockfilterindex=1`)
- [ ] Restart donor's bitcoind: donor back to original state, completely out of the picture
- [ ] If donor already has `blockfilterindex=1` enabled, skip enable/build/revert: just copy
- [ ] Configure local bitcoind: `blockfilterindex=1` + `peerblockfilters=1`
- [ ] Restart local bitcoind: now serves compact block filters
- [ ] Poll donor via SSH/RPC for build progress, notification when ready

**Standalone Operation:**
- Historical filters copied from donor, new block filters built locally as blocks arrive
- Filter index doesn't need block data to serve: only to build. Pre-built = works on pruned node
- Phone never needs donor again after copy (same as chainstate copy)
- Zeus connects to localhost, gets filters, fully sovereign Lightning

**Implementation Notes:**
- [ ] Filter index lives in `indexes/blockfilter/basic/` (separate LevelDB from chainstate)
- [ ] Verify XOR obfuscation: own key vs shared with blocks index
- [ ] Can be done during initial chainstate copy or as separate "Lightning upgrade" download later
- [ ] Progress UI: index build can take hours on donor, need monitoring and status display
- [ ] Test with Umbrel and Start9 (different file paths, permissions)

#### Phase 2: Peer Channels
As network matures, users open channels to arbitrary peers beyond Olympus: better routing, true decentralization, reduced LSP dependence. No new code from us: natural user behavior as confidence grows. Switching LSPs always possible because user owns the node.

- [ ] LAN exposure toggle for Zeus on separate device (same network)
- [ ] Documentation for opening peer channels

### Maintenance
- [ ] BWT fork maintenance and modernization

### Visuals
- [ ] Block visualization: animated graphic showing stub creation → pruning → backfill (explainer for chainstate copy technique)

### Mempool Integration Notes
- **Rust GBT lib skipped**: using Kotlin fallback for block projection. If performance is an issue on phone, cross-compile the Rust native lib (`app/src/main/rust/gbt/`) with `aarch64-linux-android` NDK target.
- **Widget skipped**: mempool home screen widget from pocket-mempool repo can be added later.

### Desktop Port (Future)

The fast-standup approach (chainstate copy + version selection) works on any platform, not just phones. A desktop companion tool would bring the same zero-to-full-node experience to Linux, macOS, and Windows.

**What carries over directly:**
- Chainstate + block index copy via SSH/SFTP (same SnapshotDownloader logic)
- Version selection (Core 28.1, Core 30, Knots, Knots BIP 110)
- UTXOracle sovereign price discovery
- BWT Electrum server for wallet connectivity
- Block filter index for Lightning/Neutrino support

**What changes:**
- x86_64 binaries instead of ARM64 (same NDK/CMake cross-compile, different target)
- No Android service layer -- simple process management (systemd, launchd, or background process)
- Could be CLI-first: `pocket-node setup --donor umbrel.local --version knots-bip110`
- Optional lightweight GUI (Electron, Tauri, or native)
- No battery/cellular constraints -- can run full mempool, more peers, no pruning

**Why it matters:**
- Lowers the barrier to running a node on ANY hardware, not just phones
- Same trust model: copy from your own node, verify locally
- "Mobile-first, desktop-portable" is a stronger grant story
- Desktop nodes can serve as donors for phone nodes (circular ecosystem)
- Fast disaster recovery: new machine to full node in minutes

**Approach:**
- Phase 1: CLI tool (Kotlin or Rust) with SFTP copy + bitcoind management
- Phase 2: Version selection + UTXOracle + BWT bundled
- Phase 3: Lightweight GUI wrapper

### Lightning Phase 3: Peer Watchtower Mesh + LDK (Future)
Replace Zeus embedded LND with LDK (Lightning Dev Kit by Block/Square): modular Lightning library with native Android bindings. Designed specifically for mobile: constrained storage, intermittent connectivity.

**Peer Watchtower Mesh:**
- Watchtower duty distributed across the user base cryptographically
- Phones watch each other: no trusted service, no always-on server, no single point of failure
- Counterparties learn nothing about channel details from watchtower data they hold
- Someone in the network is statistically always online
- Nodes earn fees for successfully broadcasting justice transactions
- Network effect: more users = stronger coverage = safer peer channels = less LSP dependence = more sovereignty

**Development path:**
- [ ] LDK integration with native Android bindings (replaces Zeus embedded LND)
- [ ] LDK uses our local bitcoind RPC as chain source (already available)
- [ ] Peer watchtower protocol: encrypted channel state backup distribution
- [ ] Justice transaction fee incentives
- [ ] VLS (Validating Lightning Signer): phone holds signing keys, remote server runs always-online node
- [ ] Partnership with sovereign data center for LSP/VLS infrastructure
