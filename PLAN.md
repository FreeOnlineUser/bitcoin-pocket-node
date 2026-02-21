# Bitcoin Pocket Node: Development Plan

## Completed Phases

### Phase 0: Research & Toolchain ✅
- [x] Cross-compile `bitcoind` for ARM64 Android (13MB stripped, NDK r27)
- [x] Set up Android SDK + NDK r27 on dev machine (Big Sur, i7-4870HQ)
- [x] Test bitcoind on real Pixel 7 Pro: RPC responds, peers connect
- [x] Generate UTXO snapshot from Umbrel (Knots 29.2.0, `dumptxoutset rollback`)
- [x] Patch chainparams with AssumeUTXO heights 880k, 910k (backported from Core 30)

### Phase 1: Proof of Concept ✅
- [x] Android app (Kotlin/Jetpack Compose) bundles bitcoind as `libbitcoind.so`
- [x] Foreground service starts/stops bitcoind
- [x] `loadtxoutset` via local RPC: 167M UTXOs loaded on Pixel 7 Pro
- [x] Node connects to P2P network and syncs forward from snapshot
- [x] Dashboard: block height, sync progress with ETA, peers, animated status dot

### Phase 2: Snapshot Manager ✅
- [x] SFTP download from personal node over LAN (~5 min for 9 GB)
- [x] Snapshot generation via SSH (`dumptxoutset rollback` on remote node)
- [x] Snapshot block hash validation before loading (auto-redownload if wrong)
- [x] Non-blocking loadtxoutset with progress polling (tails debug.log)
- [x] Early completion detection (skip re-download if already loaded)
- [x] "Try pocketnode first": check for existing snapshot with saved SFTP creds
- [x] Download from HTTPS URL (utxo.download)

### Phase 3: Smart Syncing ✅
- [x] Network-aware sync: auto-pauses on cellular, resumes on WiFi
- [x] VPN support: WireGuard/VPN treated as connected
- [x] Foreground service with persistent notification
- [x] Data usage tracking with WiFi/cellular budgets
- [x] Auto-start on app launch (SharedPreferences flag)

### Phase 4: Node Setup & Security ✅
- [x] SSH setup wizard: creates restricted `pocketnode` SFTP account
- [x] Platform-agnostic detection (Docker vs native, not hardcoding Umbrel/Start9)
- [x] Root-owned copy scripts: pocketnode never has data dir access
- [x] Admin credentials never saved (username pre-filled from SharedPreferences)
- [x] View/remove node access from app
- [x] Setup checklist (Config mode) with auto-detection of completed steps
- [x] `network_security_config.xml`: cleartext HTTP to localhost only
- [x] GrapheneOS W^X compliance: bitcoind in `jniLibs/` → `nativeLibraryDir`

### Phase 5: Wallet RPC Interface ✅
- [x] Localhost RPC endpoint for external wallet apps
- [x] Connection instructions with copy buttons (ConnectWalletScreen)
- [x] Electrum server integration (BWT runs alongside bitcoind, BlueWallet connects locally)

### Bitcoin Core Version Selection ✅
- [x] Bundle 4 implementations: Core 28.1 (13 MB), Core 30 (8.6 MB), Knots 29.3 (9 MB), Knots BIP 110 (9 MB)
- [x] User-selectable from dashboard with one-tap switching
- [x] Restart node with selected binary, no download needed
- [x] APK 81 MB with all 4 binaries
- [x] User controls which consensus rules they run: never auto-update
- [x] Policy differences shown in picker (neutral/permissive/restrictive/enforcement)
- [x] All 4 verified on phone: chainstate compatible across all versions, no reindex needed
- [x] Confirmation dialog with auto-restart when switching

### Lightning Phase 1: Sovereign Foundation ✅
Pruned Bitcoin Core on the phone with BIP 157/158 block filters. Zeus with embedded LND handles Lightning. Full sovereign stack proven and operational.

- [x] "Add Lightning Support" button on dashboard
- [x] Reuse existing SSH credentials from chainstate copy
- [x] Detect if donor already has block filters, skip build if so
- [x] If donor lacks filters: enable on donor, poll build progress via RPC, copy when ready, revert donor config
- [x] Copy `indexes/blockfilter/basic/` (781 files + LevelDB) to phone
- [x] Configure local bitcoind: `blockfilterindex=1` + `peerblockfilters=1` + `listen=1` + `bind=127.0.0.1`
- [x] Auto-restart node after filter install/remove
- [x] Unified atomic snapshot: stop donor, archive chainstate + filters together, restart
- [x] Revert listen/bind config when Lightning removed

**Proven on device:** Zeus v0.12.2 synced to chain at block 937,527. `127.0.0.1` as only Neutrino peer. Full stack: bitcoind → block filters → Zeus Neutrino → Lightning wallet, all localhost.

### Additional Completed Features
- [x] Onboarding flow (SetupChecklistScreen with auto-detection)
- [x] Mempool viewer (fee estimates, projected blocks, transaction search)
- [x] Config mode / Run mode UX refinement
- [x] Auto-restart detection in foreground service (orphan bitcoind attach via RPC)
- [x] Foldable/landscape dual-pane mode (BoxWithConstraints 550dp threshold)
- [x] UTXOracle sovereign price discovery (BTC/USD from on-chain data)
- [x] Battery saver (pauses sync when unplugged below 50%)
- [x] Auto-start on boot (BootReceiver)
- [x] Live foreground notification (block height, peers, sync %, oracle price)

### Technical Risks (Resolved)
- [x] **bitcoind ARM64 compilation**: Works with NDK r27 clang wrappers
- [x] **Android background process limits**: Foreground service handles it
- [x] **Storage**: 9 GB snapshot + 2 GB pruned chain, clear UX about requirements
- [x] **RAM**: `dbcache=256` fine on Pixel 7 Pro (12 GB RAM)
- [x] **Thermal throttling**: Phone stays cool during loadtxoutset and sync
- [x] **GrapheneOS W^X**: `nativeLibraryDir` is the only executable path
- [x] **Cleartext HTTP on Android**: `network_security_config.xml` for localhost RPC

---

## Roadmap

### Phase 6: Polish & Release
- [ ] Storage management (prune depth configuration)
- [ ] Peer management UI
- [ ] Beta testing on multiple Pixel devices
- [ ] F-Droid / APK distribution (no Google Play)
- [ ] Clean up old snapshot files on Umbrel

### Version Selection Enhancements
- [ ] Version compatibility matrix in UI
- [ ] Chainstate backup before version switch (safety net for future incompatible versions)

### Lightning Phase 2: Peer Channels
As network matures, users open channels to arbitrary peers beyond Olympus. No new code from us: natural user behavior as confidence grows.

- [ ] LAN exposure toggle for Zeus on separate device (same network)
- [ ] Documentation for opening peer channels

### Lightning Phase 3: Watchtower Mesh
Phones watch each other's Lightning channels. Uses LND's built-in watchtower (server + client), which ships today. The only new code is auto-discovery and app integration.

See [Watchtower Mesh Design](docs/WATCHTOWER-MESH.md) for the full design document.

**Core idea:** Every Pocket Node phone runs as both watchtower server and client. A lightweight discovery service (Nostr relay) lets phones find each other. Zero configuration for the user.

**What's already built (LND):**
- `watchtower.active=1` enables server mode
- `wtclient.active=1` enables client mode
- `lncli wtclient add <pubkey@host>` adds peers
- Encrypted blob storage (server learns nothing about channel details)
- ~256 bytes per channel state update per peer

**What we build:**
- [ ] Zeus watchtower config support (feature request or direct config injection)
- [ ] Nostr-based peer discovery (publish/fetch watchtower URIs via relay)
- [ ] WatchtowerManager.kt: auto-enable, auto-discover, auto-add peers
- [ ] Dashboard card: "Watching N peers / Watched by N peers"
- [ ] Client-only mode without Tor (full mesh requires Tor for reachability)

**Estimated effort:** 2-3 weeks for MVP (client-only, Nostr discovery, dashboard card).

### Lightning Phase 4: LDK Migration
Replace Zeus embedded LND with LDK (Lightning Dev Kit): modular Lightning library with native Android bindings. Designed for mobile (constrained storage, intermittent connectivity).

- [ ] LDK integration with native Android bindings
- [ ] LDK uses local bitcoind RPC as chain source (already available)
- [ ] Justice transaction fee incentives
- [ ] VLS (Validating Lightning Signer): phone holds signing keys, remote server runs always-online node

### Desktop Port (Compose Multiplatform)
Same app, same experience, phone or desktop. Using Jetpack Compose Multiplatform to share UI code between Android and desktop (Linux, macOS, Windows).

See [Desktop Port Design](docs/DESKTOP-PORT.md) for the full design document.

**Shared code (no changes needed):**
- All Compose UI screens (dashboard, setup checklist, version picker, mempool, etc.)
- BitcoinRpcClient.kt (JSON-RPC over localhost)
- SshUtils.kt (SFTP/SSH operations)
- UTXOracle.kt (price discovery)
- ChainstateManager.kt / BlockFilterManager.kt (copy logic)
- BinaryExtractor.kt (version selection)
- ConfigGenerator.kt (bitcoin.conf generation)

**Platform-specific replacements:**
- BitcoindService.kt (Android foreground service) → simple process manager
- BatteryMonitor.kt → removed (no battery constraints)
- NetworkMonitor.kt → simplified (no cellular/metered detection)
- BootReceiver.kt → OS-specific autostart (systemd, launchd, startup folder)
- Notification system → desktop notifications or tray icon

**Desktop advantages:**
- `dbcache=2048`+ (vs 256 MB on phone)
- Full 300 MB mempool (vs 50 MB cap)
- Higher `maxconnections` (serve the network)
- No thermal throttling, no battery saver
- NVMe storage for fast validation
- Sustained CPU for IBD if needed

**Approach:**
1. Add Compose Multiplatform to existing project (shared `commonMain` module)
2. Move UI + business logic to shared module
3. Android and desktop targets with platform-specific service layers
4. Bundle x86_64 bitcoind binaries (same version selection: Core 28.1, Core 30, Knots, BIP 110)
5. Single codebase, two platforms

**Estimated effort:** 2-3 weeks for a working desktop build with dashboard + chainstate copy + version selection.

### Nice to Haves
- [ ] Detect corrupted block index after long offline period, offer re-bootstrap
- [ ] Charging-aware sync (configurable)
- [ ] Doze mode handling
- [ ] Tor integration for private peer connections
- [ ] Non-technical setup documentation for everyday users
- [ ] Expanded device testing beyond Pixel line
- [ ] Block visualization: animated graphic showing stub creation → pruning → backfill
- [ ] Mempool home screen widget

### Performance
- [ ] Optimize stub file creation: only create stubs for files actually in index range (reduce 15 min pruning)
- [ ] Copy more block files (~3-4 blk/rev pairs) to cover full pruning window (reorg safety)
- [ ] Rust GBT native lib for mempool block projection performance

### BWT / Wallet
- [ ] Descriptor wallet support: replace `importmulti` with `importdescriptors`
- [ ] Taproot/P2TR output recognition
- [ ] Multisig support (comes with descriptor wallets)
- [ ] Remove `deprecatedrpc=create_bdb` dependency
- [ ] BWT fork maintenance and modernization

### Chainstate Copy
- [ ] XOR re-encoding: decode source obfuscation keys, re-encode with locally generated keys so every node is unique on disk
- [ ] Phone-to-phone chainstate copy (WiFi Direct / hotspot)

---

## Technical Reference

### Infrastructure
- **Dev machine:** 2014 MacBook Pro 15" (i7-4870HQ, 16GB, Big Sur)
- **Test node:** Umbrel VM on Mac Mini (Bitcoin Knots 29.2.0, full unpruned, 820GB chain) at 10.0.1.127:9332
- **Target hardware:** Google Pixel 7 Pro with GrapheneOS
- **Repo:** github.com/FreeOnlineUser/bitcoin-pocket-node

### bitcoind Configuration (Mobile)

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

**Why v28.1 as default?** Core 30 changed OP_RETURN policy. v28.1 with patched AssumeUTXO heights gives snapshot flexibility while preserving standard transaction relay. Users can switch to Core 30, Knots, or Knots BIP 110 from the dashboard.
