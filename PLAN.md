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

- [x] **bitcoind ARM64 compilation**: Works with NDK r27 clang wrappers
- [x] **Android background process limits**: Foreground service handles it
- [x] **Storage**: 9 GB snapshot + 2 GB pruned chain, clear UX about requirements
- [x] **RAM**: `dbcache=256` fine on Pixel 7 Pro (12 GB RAM)
- [x] **Thermal throttling**: Phone stays cool during loadtxoutset and sync
- [x] **GrapheneOS W^X**: `nativeLibraryDir` is the only executable path
- [x] **Cleartext HTTP on Android**: `network_security_config.xml` for localhost RPC

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

### Bitcoin Core Version Selection ✅
- [x] Bundle 4 implementations: Core 28.1 (13 MB), Core 30 (8.6 MB), Knots 29.3 (9 MB), Knots BIP 110 (9 MB)
- [x] User-selectable from dashboard with one-tap switching
- [x] Restart node with selected binary, no download needed
- [x] APK 81 MB with all 4 binaries
- [x] User controls which consensus rules they run: never auto-update
- [x] Policy differences shown in picker (neutral/permissive/restrictive/enforcement)
- [x] All 4 verified on phone: chainstate compatible across all versions, no reindex needed
- [x] Confirmation dialog with auto-restart when switching
- [ ] **Version compatibility matrix** in UI (all current versions are compatible, future versions may diverge)
- [ ] **Chainstate backup** before version switch (safety net for future incompatible versions)

### Lightning: Sovereign Mobile Lightning Node

**Concept:** Make a phone a genuinely sovereign Lightning node using existing standards. No new protocol primitives required. Every component exists today: the innovation is composing them into an architecture that works on mobile.

**The Sovereign Phone Stack:**
- **Pocket Node**: full validating Bitcoin node (trust anchor)
- **BlueWallet**: on-chain wallet via our Electrum server (BWT, port 50001)
- **Zeus**: Lightning wallet via our bitcoind Neutrino (BIP 157/158, port 8333)

#### Phase 1: Sovereign Foundation ✅
Pruned Bitcoin Core on the phone with BIP 157/158 block filters. Zeus with embedded LND handles Lightning. Full sovereign stack proven and operational.

**Block Filter Setup Flow:** ✅
- [x] "Add Lightning Support" button on dashboard
- [x] Reuse existing SSH credentials from chainstate copy
- [x] Detect if donor already has block filters, skip build if so
- [x] If donor lacks filters: enable on donor, poll build progress via RPC, copy when ready, revert donor config
- [x] Copy `indexes/blockfilter/basic/` (781 files + LevelDB) to phone
- [x] Filter index LevelDB has no obfuscation (`f_obfuscate` defaults false)
- [x] Configure local bitcoind: `blockfilterindex=1` + `peerblockfilters=1` + `listen=1` + `bind=127.0.0.1`
- [x] Auto-restart node after filter install/remove
- [x] Unified atomic snapshot: stop donor, archive chainstate + filters together, restart
- [x] Revert listen/bind config when Lightning removed

**Standalone Operation:** ✅
- Historical filters copied from donor, new block filters built locally as blocks arrive
- Filter index doesn't need block data to serve: only to build. Pre-built = works on pruned node
- Phone never needs donor again after copy
- Zeus connects to `127.0.0.1` via Neutrino, fully sovereign Lightning

**Proven on device:**
- Zeus v0.12.2 synced to chain, block height 937,527
- `127.0.0.1` as only Neutrino peer (bypasses GrapheneOS DNS blocks)
- Full stack: bitcoind → block filters → Zeus Neutrino → Lightning wallet, all localhost

#### Phase 2: Peer Channels
As network matures, users open channels to arbitrary peers beyond Olympus. No new code from us: natural user behavior as confidence grows.

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
