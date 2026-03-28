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
- [x] Network-aware sync with automatic power mode detection
- [x] VPN-aware networking: detects actual connection type behind VPN
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
- [x] Electrum server integration (pure Kotlin Electrum server, BlueWallet connects locally)

### Bitcoin Core Version Selection ✅
- [x] Bundle 4 implementations: Core 28.1 (13 MB), Core 30 (8.6 MB), Knots 29.3 (9 MB), Knots BIP 110 (9 MB)
- [x] User-selectable from dashboard with one-tap switching
- [x] Restart node with selected binary, no download needed
- [x] APK 81 MB with all 4 binaries
- [x] User controls which consensus rules they run: never auto-update
- [x] Policy differences shown in picker (neutral/permissive/restrictive/enforcement)
- [x] All 4 verified on phone: chainstate compatible across all versions, no reindex needed
- [x] Confirmation dialog with auto-restart when switching

### Lightning Phase 1: Block Filter Infrastructure ✅
Block filter support enables Lightning on pruned nodes. Filters are copied from a home node or another Bitcoin Pocket Node.

**Historical note:** The original approach used Zeus with embedded LND, which worked but had a fundamental limitation: our pruned bitcoind advertises `NODE_NETWORK_LIMITED` instead of `NODE_NETWORK`. LND's Neutrino requires `NODE_NETWORK` from peers, so it silently rejected our local node and fell back to internet peers. This meant two independent sync engines on the phone and no true local sovereignty. This limitation drove the decision to migrate to LDK (Phase 4), which connects to bitcoind via RPC instead of P2P, bypassing service bit checks entirely.

- [x] "Add Lightning Support" button on dashboard
- [x] Reuse existing SSH credentials from chainstate copy
- [x] Detect if donor already has block filters, skip build if so
- [x] If donor lacks filters: enable on donor, poll build progress via RPC, copy when ready, revert donor config
- [x] Copy `indexes/blockfilter/basic/` (781 files + LevelDB) to phone
- [x] Configure local bitcoind: `blockfilterindex=1` + `peerblockfilters=1` + `listen=1` + `bind=127.0.0.1`
- [x] Auto-restart node after filter install/remove
- [x] Unified atomic snapshot: stop donor, archive chainstate + filters together, restart
- [x] Revert listen/bind config when Lightning removed

### Additional Completed Features
- [x] Onboarding flow (SetupChecklistScreen with auto-detection)
- [x] Mempool viewer (fee estimates, projected blocks, transaction search)
- [x] Config mode / Run mode UX refinement
- [x] Auto-restart detection in foreground service (orphan bitcoind attach via RPC)
- [x] Foldable/landscape dual-pane mode (BoxWithConstraints 550dp threshold)
- [x] UTXOracle sovereign price discovery (BTC/USD from on-chain data)
- [x] Power modes: Max Data, Low Data, Away Mode with burst sync
- [x] Auto-start on boot (BootReceiver)
- [x] Live foreground notification (block height, peers, sync %, oracle price)
- [x] Persistent mempool across restarts (survives nightly reboot)
- [x] Config migration for existing installs (auto-adds new settings)
- [x] Pure Kotlin Electrum server (1,129 lines, no native dependencies)
- [x] BwtService renamed to ElectrumService across entire codebase
- [x] fdsan fix for GrapheneOS file descriptor sanitizer
- [x] Unified Knots binary with BIP 110 toggle (3 binaries, ~72 MB APK)
- [x] First-run setup screen
- [x] HTTPS download from utxo.download
- [x] Electrum server retry on boot (waits for bitcoind RPC)
- [x] Phone-to-phone node sharing (ShareServer, QR code, landing page, up to 2 concurrent)
- [x] In-app update checker (GitHub Releases API, APK download + install)
- [x] Release signing keystore
- [x] Seed backup info card ("What does my seed protect?")
- [x] IBD hold: forces Max mode during initial block download
- [x] Wallet hold: network stays active while Electrum client connected

### BIP-110 Universal Toggle ✅
- [x] Cross-compiled Bitcoin Core 29.3 with BIP 110 support (v72t's port)
- [x] Core 30 vanilla build (no BIP 110 patches needed for vanilla)
- [x] Universal `-signalbip110` toggle works on both Core 29.3 and Knots 29.3
- [x] 3 binaries, ~78 MB APK
- [x] All three implementations share chainstate, switch without re-syncing

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
- [x] Project website (features, screenshots, download, docs)
- [ ] Storage management (prune depth configuration)
- [ ] Peer management UI
- [ ] Beta testing on multiple Pixel devices
- [ ] F-Droid / APK distribution (no Google Play)
- [ ] Clean up old snapshot files on Umbrel

### Electrum Server: Pruned-Node Native
- [x] Descriptor wallet RPCs for balance and UTXOs (listunspent, getbalances, listtransactions)
- [x] Persistent transaction history (survives block pruning)
- [x] 3-source history merge (persisted + descriptor wallet + mempool.space)
- [x] History recovery from mempool.space with gap limit discovery (20 address gap)
- [x] Skip rescan for already-imported descriptors (fast restarts)
- [x] Batch JSON-RPC responses as JSON array (BlueWallet compatibility)
- [x] Transaction hex caching: proactive cache while blocks available, mempool.space fallback for pruned
- [x] Block hash retry for getrawtransaction on pruned nodes
- [x] zpub/ypub to xpub conversion for descriptor wallet import
- [x] Wallet-specific RPC endpoint (/wallet/pocketnode_electrum)
- [x] Prevent double ElectrumService start (EADDRINUSE fix)
- [x] Recovery UI: progress counter, rate limit backoff display, instant cancel
- [x] Exponential backoff on mempool.space rate limits (2s-30s, retry same address)
- [x] **BlueWallet tested:** balance, send, receive, confirm all working end-to-end
- [x] Unsolicited scripthash.subscribe notifications: push tx changes to BlueWallet in real time
- [x] 5-second tx poll: catches unconfirmed txs between blocks
- [x] Stub response for pruned/uncached vin txids (prevents BlueWallet crash)
- [x] Enriched decoderawtransaction with confirmations/blockhash/blocktime from history
- [x] Arti SOCKS proxy exposed for Java-side Tor routing (TorAwareHttp)
- [x] All HTTP calls route through Tor when enabled (mempool.space, peer browser, update checker)
- [x] Refresh wallet transactions on new blocks and every 5s poll cycle
- [ ] Lightning recovery helper: scantxoutset + mempool.space to find force-close txs on pruned nodes, feed raw tx data to LDK for sweep (closes biggest risk in PRUNED-NODE-RISK-ANALYSIS.md)

### Version Selection Enhancements
- [ ] Version compatibility matrix in UI
- [ ] Chainstate backup before version switch (safety net for future incompatible versions)

### Lightning Phase 2: Peer Channels
As the network matures, users open channels to arbitrary peers. Natural user behavior as confidence grows.

- [ ] Documentation for opening peer channels

### Lightning Phase 3: Watchtower ✅
LDK-to-LND watchtower bridge over Tor. Phone pushes encrypted justice blobs to any LND watchtower via native BOLT 8 Brontide protocol. Embedded Arti handles .onion connectivity.

See [Watchtower Design](docs/WATCHTOWER-MESH.md) and [LDK-to-LND Bridge](docs/LDK-WATCHTOWER-BRIDGE.md) for details.

- [x] Custom Brontide (BOLT 8) implementation with secp256k1 ECDH
- [x] LND wtwire protocol: CreateSession + StateUpdate
- [x] Embedded Arti (0.39.0) for direct .onion watchtower connection
- [x] Auto-push blobs after every payment with dynamic fee estimation
- [x] End-to-end verified against live LND tower on Umbrel

### Lightning Phase 4: LDK Migration ✅
In-process Lightning using ldk-node (Lightning Dev Kit): modular Lightning library with native Android bindings. Designed for mobile (constrained storage, intermittent connectivity).

LDK connects to bitcoind via RPC (not P2P), so pruned nodes work natively. No service bit checks, no cross-app issues, no duplicate sync engine. One bitcoind, one Lightning implementation, all in-process.

**Architecture:**
```
bitcoind ← RPC → ldk-node (in-process)
                    │
            ┌───────┴────────┐
            │                │
      Built-in UI      LNDHub API (:3000)
      (send/receive/        │
       channels)       External wallets
                       (BlueWallet, Zeus)
```

- [x] ldk-node 0.7.0 integration with bitcoind RPC backend (in-process, no cross-app issues)
- [x] Built-in Lightning wallet UI (send, receive, channels, payment history, peer browser)
- [x] Close/force-close channel UI with cooperative and emergency options
- [x] Peer discovery browser with mempool.space API (Most Connected, Largest, Lowest Fee, Search)
- [x] LNDHub-compatible localhost API (:3000) for external wallet apps (BlueWallet, Zeus)
- [x] Auto-start Lightning when bitcoind syncs (SharedPreference persistence)
- [x] Channel status indicators (Active/Ready/Pending) with outbound capacity display
- [x] **Seed backup & restore**: BIP39 mnemonic display (view 24 words), restore from existing mnemonic with smart backup matching
- [x] **Wallet birthday recovery**: automatic fund discovery on seed restore (see docs/LDK-SEED-RECOVERY.md)
  - Saves wallet creation height to `wallet_birthday` file for instant future restores
  - Fallback: `scantxoutset` scans UTXO set (~4 min on phone) with live progress indicator
  - Auto-restarts LDK from birthday height, balance appears in seconds
  - Proven: 110,628 sats recovered end-to-end on Pixel 9
- [ ] Pruned node recovery: auto-detect missing blocks, temporarily grow prune window, show recovery progress, shrink back when caught up
- [x] Watchtower bridge: LDK-to-LND watchtower protocol (see docs/LDK-WATCHTOWER-BRIDGE.md)
- [ ] **Seed Prism**: same 24 words viewed through multiple derivation paths simultaneously
  - BIP39 standard (PBKDF2 + BIP84) — compatible with BlueWallet, Electrum, Sparrow
  - LDK raw entropy — current KeysManager derivation
  - AEZEED (LND cipher seed) — compatible with Zeus, Zap, Blixt
  - Single `scantxoutset` with addresses from all derivations, shows funds per "lens"
  - Enables recovery regardless of which app originally created the wallet
  - Future: implement BIP39 standard derivation in our ldk-node fork for cross-wallet compatibility
- [ ] VLS (Validating Lightning Signer): phone holds signing keys, remote server runs always-online node

**What was built:**
- `LightningService.kt`: Singleton wrapping ldk-node. Start/stop, channel management, payments, on-chain wallet, observable StateFlow
- `LndHubServer.kt`: HTTP server on localhost:3000 implementing LNDHub protocol (auth, balance, invoices, payments, decode, getinfo)
- `LightningScreen.kt`: Status card, balances (on-chain + Lightning), channel list with tap-to-close, fund wallet
- `SendPaymentScreen.kt`: Paste BOLT11 invoice, pay
- `ReceivePaymentScreen.kt`: Enter amount, generate invoice, copy
- `PaymentHistoryScreen.kt`: Payment list with direction/amount/status
- `OpenChannelScreen.kt`: Peer node ID, address, amount input with validation
- `PeerBrowserScreen.kt`: Browse Lightning nodes by connectivity, capacity, fee rate, or search by name/pubkey
- `SeedBackupScreen.kt`: View 24-word BIP39 mnemonic, restore from seed with smart backup matching
- `Bip39.kt`: Pure Kotlin BIP39 implementation (entropy to mnemonic, mnemonic to entropy)
- `WatchtowerBridge.kt`: Drains justice blobs from ldk-node, encrypts, SSH tunnels to home node, pushes via Brontide
- `WatchtowerNative.kt`: JNA bindings to native Rust watchtower client (libldk_watchtower_client.so)

**Pruned node compatibility:** ldk-node uses `getblock` via RPC, no service bit checks. Works natively with pruned nodes for normal use. If the phone is offline longer than the prune window (~2 weeks at `prune=2048`), ldk-node can't fetch blocks it missed. Recovery: temporarily increase prune setting, let bitcoind re-download the gap blocks, ldk-node catches up, then shrink prune back to normal. User sees a "Recovering Lightning state..." screen with progress.

**Power modes:** Three data modes (Max/Low/Away) control sync behaviour. Low and Away use burst sync via `setnetworkactive` RPC. External wallets hold the network active while connected. Channel opens require Max mode. See [Power Modes Design](docs/POWER-MODES.md).

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
4. Bundle x86_64 bitcoind binaries (same version selection: Core 29.3, Core 30, Knots 29.3)
5. Single codebase, two platforms

**Estimated effort:** 2-3 weeks for a working desktop build with dashboard + chainstate copy + version selection.

### iOS Port
Burst sync + watchtower + in-process LDK accidentally made iOS viable. See [docs/IOS-PORT.md](docs/IOS-PORT.md) for full analysis.

Key insight: iOS BGProcessingTask gives several minutes when charging on WiFi (basically Max mode), and foreground catch-up only takes seconds for a pruned node. Watchtower covers channel safety while the app is suspended. No one has shipped a full node on iOS because everyone assumed continuous background execution was required. Burst sync removes that assumption.

Compose Multiplatform targets iOS (same shared UI as desktop port). bitcoind cross-compiles to ARM64. ldk-node has Swift bindings. Arti (Tor) compiles for iOS.

**Not building now.** Explore after Android is stable. Estimated effort: 13-19 weeks.

### Nice to Haves
- [ ] Business mode: point-of-sale UI with preset items and prices, tap to generate Lightning invoice, show QR to customer. For markets, cafes, anyone accepting Lightning in person.
- [ ] Demo mode: interactive walkthrough of all features with simulated data (no chainstate needed)
- [ ] Detect corrupted block index after long offline period, offer re-bootstrap
- [ ] Charging-aware sync (configurable)
- [ ] Doze mode handling
- [ ] Sync staleness nudge: gentle notification when node hasn't synced in a while. Watchtower active: 48h threshold. No watchtower: 12h threshold. Low pressure, just "Connect to WiFi when convenient to stay current."
- [x] Built-in Tor (Arti): direct .onion connection to home node watchtower, no SSH tunnel or Orbot needed
- [x] Tor for bitcoind: full network privacy via SOCKS proxy (`-proxy`, `-onlynet=onion`, `-dnsseed=0`)
- [x] Tor for HTTP calls: TorAwareHttp routes mempool.space, peer browser, and all API calls through SOCKS
- [x] Tor for LDK peer connections: all Lightning peers routed through SOCKS with stream isolation
- [x] One-tap Tor toggle: auto-restarts node, persists across force-kill, 🧅 indicators everywhere
- [ ] Tor for Rapid Gossip Sync: route RGS fetch through Arti (currently clearnet)
- [ ] Tor for HTTPS chainstate download: setup privacy
- [ ] Electrum hidden service: remote wallet access over Tor
- [ ] Non-technical setup documentation for everyday users
- [ ] Expanded device testing beyond Pixel line
- [ ] Block visualization: animated graphic showing stub creation → pruning → backfill
- [ ] Mempool home screen widget

### Tor for All Traffic ✅ (Phases 1-4)
One-tap Tor toggle routes all Pocket Node traffic through embedded Arti SOCKS proxy. Full network privacy: your ISP sees only Tor traffic.

See [Tor Integration](docs/tor-integration.md) for the full design document.

| Phase | Component | Status | Notes |
|-------|-----------|--------|-------|
| 1 | Arti SOCKS proxy service | ✅ | TorManager singleton, persistent preference, auto-start before bitcoind |
| 2 | bitcoind `-proxy` | ✅ | `-proxy=127.0.0.1:9050 -onlynet=onion -dnsseed=0` |
| 3 | HTTP calls through SOCKS | ✅ | TorAwareHttp, .onion URL mapping, 30s timeout |
| 4 | LDK peers via SOCKS | ✅ | ldk-node `set_tor_proxy()`, `tor_connect_outbound()` with stream isolation |
| 5 | Electrum hidden service | Future | Remote wallet access over Tor |

**UI features:** 🧅 on notification title, peer count badge, peer browser, connected peers dialog. Tor toggle auto-restarts bitcoind+LDK. Tor preference persists across force-kill. Connected Peers dialog shows node aliases from network graph.

### Upstream Contributions
- **rust-lightning [#4453](https://github.com/lightningdevkit/rust-lightning/pull/4453):** Justice transaction API improvements for watchtower use. Draft, waiting on review.
- **ldk-node [#822](https://github.com/lightningdevkit/ldk-node/pull/822):** Wallet birthday support for seed restore. Draft, waiting on review.

### Grant Applications
- **OpenSats:** Application submitted ($36k / 12 months)
- **HRF Bitcoin Development Fund:** Application submitted ($36k / 12 months)

### Hardening
- [ ] Block LDK startup until prune recovery confirms completion (not just triggered)
- [ ] Watchtower blob push retry loop with user alert when tower is unreachable
- [ ] "Offline too long" warning on startup when offline duration approaches prune window

### Performance
- [ ] Optimize stub file creation: only create stubs for files actually in index range (reduce 15 min pruning)
- [ ] Copy more block files (~3-4 blk/rev pairs) to cover full pruning window (reorg safety)
- [ ] Rust GBT native lib for mempool block projection performance

### Electrum Server / Wallet
- [x] Descriptor wallet support: zpub/ypub/xpub import with `importdescriptors`
- [ ] Taproot/P2TR output recognition
- [ ] Multisig support (comes with descriptor wallets)
- [ ] Remove `deprecatedrpc=create_bdb` dependency

### Channel Safety ✅
- [x] WAL checkpoint (TRUNCATE) after every channel/payment event: prevents channel state loss on process kill
- [x] WAL integrity check + TRUNCATE on startup: detects corrupt frames, clean state for LDK build
- [x] Static Channel Backup (SCB): saves peer+funding on open, recovery via peer reconnection
- [x] All auto-restarts disabled (orphan, sync watchdog): manual restart only
- [x] Gossip logs dropped: preserves logcat for crash debugging
- [x] Circuit breaker: 3 consecutive crashes disables auto-start
- [x] Recovery UI: lost channels card (SCB), missing on-chain funds guide (seed export)
- [x] Channel probe scanner: discover minimum channel sizes from .onion nodes
- [x] AdminReceiver: clean Lightning stop/restart via ADB broadcast
- [x] Event-driven network hold: hold on ChannelPending, release on ChannelReady/Closed

### Chainstate Copy
- [ ] XOR re-encoding: decode source obfuscation keys, re-encode with locally generated keys so every node is unique on disk
- [x] Phone-to-phone chainstate copy (WiFi Direct / hotspot)
- [x] Resume support: skip already-downloaded files on retry
- [x] Session progress tracking (POST /start-session, GET /progress)
- [x] QR scan: parses URL, JSON, and host:port formats
- [x] Cleartext HTTP for LAN connections
- [x] Safe service shutdown before receiving downloads

### Remote Node Sharing (Tor)
Share the Freedom page: two bootstrap options from the same screen.

- [ ] **Nearby (WiFi Direct)**: existing phone-to-phone, same room
- [ ] **Remote (Tor)**: sender generates .onion URL with Arti, shows QR code. Receiver scans QR on first launch, downloads chainstate directly from sender's phone over Tor. No server, no account, no middleman.
- [ ] Arti hidden service for file serving (lightweight, built on existing Arti integration)
- [ ] QR code contains .onion URL + auth token + expected block hash for verification
- [ ] Works at meetups, across countries, anywhere with internet
- [ ] Same verification as phone-to-phone (block hash check at known height)

---

## Technical Reference

### Infrastructure
- **Dev machine:** 2014 MacBook Pro 15" (i7-4870HQ, 16GB, Big Sur)
- **Test node:** Umbrel VM on Mac Mini (Bitcoin Knots 29.2.0, full unpruned, 820GB chain) at 10.0.1.127:9332
- **Target hardware:** Google Pixel 9 with GrapheneOS
- **Repo:** github.com/FreeOnlineUser/bitcoin-pocket-node

### bitcoind Configuration (Mobile)

```ini
server=1
prune=2048
listen=1
bind=127.0.0.1
maxconnections=8
maxmempool=50
persistmempool=1
blockreconstructionextratxn=10
dbcache=256
rpcbind=127.0.0.1
rpcallowip=127.0.0.1
rpcuser=pocketnode
rpcpassword=<generated>
```

**Why `persistmempool=1`?** Phones restart nightly (GrapheneOS auto-reboot). Without persistence, the node wakes up with an empty mempool and needs 30+ minutes to rebuild from peer relay. With it, mempool.dat is written on shutdown and reloaded on startup.

**Why no `blocksonly=1`?** Partial mempool (50MB cap) for fee estimation, payment detection, privacy cover traffic, and compact block reconstruction.

**Why Core 29.3 as default?** Includes BIP 110 consensus code (v72t's port from vanilla Core), standard relay rules, and universal `-signalbip110` toggle. Users who want the latest Core features (relaxed OP_RETURN) can switch to Core 30. Users who want stricter relay policy can switch to Knots 29.3. BIP 110 signaling works on both 29.3 binaries.
