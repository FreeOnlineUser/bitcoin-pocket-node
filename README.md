# Bitcoin Pocket Node

**Bitcoin node in your pocket in under an hour.**

Turn any Android phone into a fully-validating Bitcoin full node. No server dependency, no ongoing tethering. Your phone becomes a sovereign Bitcoin node.

📖 **[Project overview and how chainstate copy works](https://freeonlineuser.github.io/bitcoin-pocket-node/)**

## ✅ Proven

- **Two proven bootstrap paths:** sync from home node (under 1 hour) or download from internet (3-6 hours, on-chain only)
- **Phone-to-phone sharing (built, untested on second device):** scan a QR code, get a full node. No servers, no accounts
- **3 Bitcoin implementations:** Core 30, Core 29.3, Knots 29.3. One-tap switching, same chainstate. Universal BIP 110 signaling toggle
- **No thermal load:** phone shows no sign of load or overheat during normal operation
- ~26 GB total disk with Lightning (11 GB chainstate + 2 GB pruned blocks + 13 GB block filters), ~13 GB without
- **Pure Kotlin Electrum server** with wallet tracking: balance, transactions, UTXOs all served from your own pruned node
- **Built-in Lightning wallet** powered by LDK: send, receive, BOLT12 offers, open/close channels, peer browser, QR codes
- **LNDHub API** for external Lightning wallets (BlueWallet)
- **P2P port** exposed on localhost:8333 for Neutrino-compatible wallets
- **Proactive prune recovery** re-downloads missed blocks on startup when node was offline
- **Full Tor integration** with one-tap toggle: routes bitcoind P2P, Lightning peers, and all HTTP calls through embedded Arti SOCKS proxy. Your ISP sees only Tor traffic. No Orbot needed.

## Screenshots

*Running on a Pixel 7 Pro with GrapheneOS*

**First run**

<table>
<tr>
<td align="center" width="33%"><strong>Setup screen</strong></td>
<td align="center" width="33%"><strong>Setup checklist</strong></td>
<td align="center" width="33%"><strong>Version picker</strong></td>
</tr>
<tr>
<td><img src="docs/images/00-source-picker.jpg" width="200" alt="Choose snapshot source"></td>
<td><img src="docs/images/01-checklist.jpg" width="200" alt="Setup checklist all green"></td>
<td><img src="docs/images/11-version-picker.png" width="200" alt="Choose Bitcoin implementation"></td>
</tr>
</table>

**Node + On-chain wallet**

<table>
<tr>
<td align="center" width="25%"><strong>Node dashboard</strong></td>
<td align="center" width="25%"><strong>Electrum server</strong></td>
<td align="center" width="25%"><strong>BlueWallet connected</strong></td>
<td align="center" width="25%"><strong>BlueWallet wallet</strong></td>
</tr>
<tr>
<td><img src="docs/images/02-node-dashboard.jpg" width="200" alt="Node dashboard fully synced"></td>
<td><img src="docs/images/03-electrum-server.jpg" width="200" alt="Electrum server running with tracked wallets"></td>
<td><img src="docs/images/04-bluewallet-connected.jpg" width="200" alt="BlueWallet connected to localhost"></td>
<td><img src="docs/images/04-bluewallet-wallet.jpg" width="200" alt="BlueWallet wallet view"></td>
</tr>
</table>

**Lightning Wallet**

<table>
<tr>
<td align="center" width="25%"><strong>Dashboard</strong></td>
<td align="center" width="25%"><strong>Lightning wallet</strong></td>
<td align="center" width="25%"><strong>Watchtower + Seed</strong></td>
<td align="center" width="25%"><strong>Peer browser</strong></td>
</tr>
<tr>
<td><img src="docs/images/dashboard-home.png" width="200" alt="Dashboard with power modes"></td>
<td><img src="docs/images/lightning-wallet.png" width="200" alt="Lightning send/receive"></td>
<td><img src="docs/images/lightning-watchtower.png" width="200" alt="Watchtower active"></td>
<td><img src="docs/images/peer-browser.png" width="200" alt="Browse Lightning peers"></td>
</tr>
</table>

## How It Works

Three bootstrap paths. Pick whichever suits your situation:

### ⚡ Path 1: Sync from Your Home Node (under 1 hour)
1. App connects to your home node (Umbrel, Start9, any Bitcoin node) via SSH
2. Briefly stops bitcoind, archives chainstate + block index + block filters
3. Downloads the archive (~24 GB with Lightning filters), extracts, starts bitcoind
4. **Full node at chain tip.** Includes block filters for Lightning if your node has them

### 📱 Path 2: Copy from a Nearby Phone (under 1 hour)
1. A friend with Pocket Node opens Share, which shows a QR code
2. You scan it (or visit the URL on any browser to download the app first)
3. Chainstate + block filters transfer directly over WiFi, phone to phone
4. **No home node needed.** Zero accounts, zero servers, just two phones on the same network

### 🔒 Path 3: Download from Internet (AssumeUTXO) (3-6 hours, on-chain only)
1. Download a UTXO snapshot (~9 GB) from utxo.download
2. App loads it via `loadtxoutset` (cryptographically verified by Bitcoin Core)
3. Phone syncs forward from the snapshot height (~30 min to load, 2-5 hours to reach tip)
4. Background validation confirms everything independently from genesis

> **Note:** AssumeUTXO does not build block filter indexes, so Lightning is not available until background validation completes. Good for on-chain use (BlueWallet, Electrum) while validation runs.

See [Direct Chainstate Copy](docs/direct-chainstate-copy.md) for a detailed comparison.

## Version Selection

3 Bitcoin implementations with one-tap switching: Core 30, Core 29.3, Knots 29.3. Universal BIP 110 signaling toggle.

| Implementation | Size | Policy |
|---|---|---|
| **Bitcoin Core 30** | 8.6 MB | Permissive: larger OP_RETURN data allowed |
| **Bitcoin Core 29.3** | 8 MB | Standard relay rules, BIP 110 compatible (v72t's port) |
| **Bitcoin Knots 29.3** | 12 MB | Restrictive: filters non-standard transactions |

All three share the same chainstate format. Switch without re-syncing. Tap "Change" on the dashboard, confirm, and the node restarts with the new binary.

**BIP 110** ([bip110.dev](https://bip110.dev/)) temporarily limits arbitrary data embedding at the consensus level. The universal toggle enables version bit 4 signaling and peer preference for reduced data carriers on Core 29.3 and Knots. Built from Dathon Ohm's [reference implementation](https://github.com/bitcoinknots/bitcoin/compare/29.x-knots...dathonohm:bitcoin:uasf-modified-bip9) with a 55% activation threshold.

See [Version Selection Design](docs/VERSION-SELECTION.md) and [BIP 110 Research](docs/BIP110-RESEARCH.md) for details.

## Features

- **3 Bitcoin implementations** with one-tap switching: Core 30, Core 29.3, Knots 29.3. BIP 110 signaling toggle on Core 29.3 and Knots
- **Two proven bootstrap paths:** home node or internet download (phone-to-phone built, untested)
- **Pure Kotlin Electrum server** purpose-built for pruned nodes: the only Electrum server that works with `prune=2048`. Balances from the UTXO set, transaction history persisted forever (survives pruning), unsolicited notifications push new transactions to BlueWallet in real time
- **Built-in Lightning node** powered by LDK (send, receive, channels, peer browser, seed backup/restore with automatic fund recovery)
- **LNDHub API** on localhost:3000 for external wallet connectivity (BlueWallet)
- **P2P port** exposed on localhost:8333 for Neutrino-compatible wallets
- **Wallet birthday recovery:** automatic fund discovery on seed restore via UTXO scan with live progress, instant restore for wallets with saved birthday
- **Home node watchtower** with automatic channel protection via LDK-to-LND bridge over Tor
- **Full Tor privacy** with one toggle: bitcoind peers, Lightning peers (with stream isolation), HTTP API calls, and watchtower all route through embedded Arti SOCKS proxy. 🧅 indicators on all screens when active
- **BOLT12 support:** send to offers, create reusable offers, variable-amount offers
- **QR codes:** generate on receive, scan with camera on send (CameraX + ZXing, no Google Play)
- **Sovereign price discovery** using UTXOracle (BTC/USD from on-chain data, no exchange APIs)
- **Mempool viewer** with fee estimates, projected blocks, and transaction search
- **Wallet tracking** with history recovery from mempool.space, transaction hex caching, and gap limit discovery
- **BlueWallet integration** tested: balance display, transaction history, pull-to-refresh all working on pruned node
- **Snapshot validation** checks block hash before loading, auto-redownloads if wrong
- **Non-blocking snapshot load** with progress tracking
- **Network-aware sync** with automatic power mode detection
- **VPN-aware networking:** Detects actual connection type behind VPN (cellular vs WiFi)
- **Data budgets** for WiFi and cellular
- **Power modes:** Max Data, Low Data, Away Mode with burst sync for mobile efficiency
- **Auto data mode:** detects WiFi/cellular and charging state, adjusts automatically
- **Auto-start on boot**
- **Secure node pairing** with restricted SFTP account (no access to your bitcoin data)
- **Setup checklist** with auto-detection of completed steps
- **Live dashboard** showing block height, sync progress, peers, mempool, disk usage
- **Partial mempool** (50 MB) with persistence across restarts (survives nightly reboot)
- **Embedded Tor** (Arti 0.39.0) with one-tap toggle: routes bitcoind P2P, Lightning peers (stream-isolated), HTTP API calls, and watchtower through SOCKS proxy. No Orbot dependency. Persists across force-kill. 🧅 indicators on notification, badges, peer browser, and connected peers dialog

## Snapshot Sources

### From Your Node (LAN)

#### Direct Chainstate Copy (fastest)
The app connects to your home node via SSH, briefly stops bitcoind, and copies:
- `chainstate/` (the UTXO set, ~11 GB)
- `blocks/index/` (block metadata, ~2 GB)
- `blocks/xor.dat` (block file obfuscation key)
- Tip block/rev files (latest block data)

Total transfer ~13 GB over LAN (~5 min). Node operational in under an hour including setup.

#### AssumeUTXO Snapshot
1. Generates a UTXO snapshot using `dumptxoutset rollback`
2. Downloads via SFTP over LAN (~5 min for 9 GB)
3. Loads via `loadtxoutset`

The app tries saved `pocketnode` SFTP credentials first. If a snapshot already exists on the server, no admin credentials are needed.

### From Internet
Download from `https://utxo.download/utxo-910000.dat` (9 GB). Same `loadtxoutset` flow, just a different download source. The snapshot is cryptographically verified against the block hash compiled into Bitcoin Core before loading.

**Note:** This path provides an on-chain node only. Lightning support requires block filters (~13 GB) which can be added later by copying from a home node via SSH.

## Architecture

```
┌───────────────────────────────────────────────────────┐
│                Android App (Kotlin)                   │
│                                                       │
│  ┌──────────┐  ┌───────────┐  ┌───────────┐          │
│  │Chainstate│  │  Network  │  │   Sync    │          │
│  │ Manager  │  │  Monitor  │  │ Controller│          │
│  └────┬─────┘  └─────┬─────┘  └─────┬─────┘          │
│       │              │              │                 │
│  ┌────┴──────────────┴──────────────┴──────────────┐  │
│  │  bitcoind (ARM64), user selects:                │  │
│  │  Core 30 | Core 29.3 | Knots 29.3              │  │
│  │  (BIP 110 toggle on Core 29.3 + Knots)         │  │
│  │  Foreground service, local RPC                  │  │
│  │  Tor mode: -proxy=127.0.0.1:9050 -onlynet=onion│  │
│  └────────────────┬────────────────────────────────┘  │
│                   │ RPC                               │
│      ┌────────────┼────────────┐                      │
│      │            │            │                      │
│  ┌───┴──────┐ ┌───┴──────┐ ┌──┴───────────┐          │
│  │ Electrum │ │ ldk-node │ │   UTXOracle  │          │
│  │  :50001  │ │(in-proc) │ │  price feed  │          │
│  └───┬──────┘ └───┬──────┘ └──────────────┘          │
│      │            │                                   │
│      │     ┌──────┴───────┐   ┌────────────────────┐  │
│      │     │  LNDHub API  │   │  Embedded Arti 🧅  │  │
│      │     │   :3000      │   │  SOCKS :9050       │  │
│      │     └──────┬───────┘   │  All traffic when  │  │
│      │            │           │  Tor enabled        │  │
│      │            │           └────────────────────┘  │
└──────┼────────────┼───────────────────────────────────┘
       │            │
  BlueWallet    BlueWallet
  (on-chain)    (Lightning)
```

## Security Model

### Node Pairing (SSH Setup)
When you pair with your home node, the app creates a restricted `pocketnode` user:

- **SFTP-only.** Cannot run commands, no shell access
- **Chroot jailed.** Can only see `/home/pocketnode/`, nothing else
- **Zero data access.** Cannot read your bitcoin data directory, wallet, configs, or logs
- **Root-owned copy scripts** bridge the gap, copying only snapshot files to the SFTP location

Admin SSH credentials are **never saved** (username is saved for pre-fill convenience). Always prompted, used once, discarded.

You can view the pocketnode credentials and **fully remove access** from the app at any time.

### Snapshot Verification
- Snapshots are verified against block hashes **compiled into the Bitcoin Core binary**
- The app also validates the snapshot file header before attempting to load
- A tampered or wrong-height snapshot is rejected before any data is used
- Background IBD independently validates everything from genesis (AssumeUTXO path)

### Android Security
- `network_security_config.xml` allows cleartext HTTP to `127.0.0.1` (local RPC) and `.onion` domains (Tor provides encryption)
- bitcoind runs as `libbitcoind.so` in `jniLibs/` for GrapheneOS W^X compliance
- No internet-facing ports. RPC and Electrum server are both localhost only
- When Tor enabled, all outbound connections route through embedded Arti SOCKS proxy with stream isolation

## Lightning Support

Built-in Lightning wallet powered by [LDK](https://lightningdevkit.org/) (ldk-node 0.7.0). Runs in-process, connects to your local bitcoind via RPC. No external apps needed.

### How It Works
1. Start your Bitcoin node and wait for sync
2. Open the Lightning wallet from the dashboard
3. Fund your on-chain wallet (receive bitcoin to the displayed address)
4. Browse peers (Most Connected, Largest, or search) and open a channel
5. Send and receive Lightning payments

### Architecture

```
bitcoind ← RPC → ldk-node (in-process)
                    │
            ┌───────┴────────┐
            │                │
      Built-in UI      LNDHub API (:3000)
      (send/receive/        │
       channels)       External wallets
                       (BlueWallet)
```

**Why LDK?** Earlier versions used Zeus with embedded LND, which required BIP 157/158 block filters and had a NODE_NETWORK service bit limitation with pruned nodes. LDK connects via RPC directly, so pruned nodes work natively. No service bit checks, no cross-app restrictions, no duplicate sync engine.

### External Wallet Support

The app runs an LNDHub-compatible API server on localhost:3000. Connect BlueWallet in LNDHub mode to use your Lightning node from another app on the same phone.

### Peer Discovery

Built-in peer browser using mempool.space API. Browse nodes by:
- **Most Connected:** highest channel count
- **Largest:** biggest total capacity
- **Search:** find nodes by name or pubkey

## Target Platform

- **OS:** Android 7+ (tested on GrapheneOS, EMUI, Samsung OneUI)
- **Hardware:** Any ARM64 device (tested on Pixel, Samsung, Huawei)
- **Default:** Bitcoin Core 29.3 (BIP 110 compatible, standard relay rules)
- **Also bundled:** Core 30, Knots 29.3 (user selects from dashboard, BIP 110 toggle on Core 29.3 + Knots)
- **AssumeUTXO heights:** 840k (upstream) + 880k, 910k (backported from Core 30)

## Building

### Prerequisites
- macOS or Linux build machine
- Android SDK + NDK r27
- JDK 17
- Bitcoin Core 29.3 source (with BIP 110 and chainparams patches)

### Build bitcoind for ARM64
See [docs/cross-compile-android.md](docs/cross-compile-android.md)

### Build ldk-node for ARM64
See [docs/ldk-node-android-build.md](docs/ldk-node-android-build.md). Covers the Rust cross-compile, UniFFI binding generation, and AAR packaging.

### Build the Android app
```bash
export ANDROID_HOME=/path/to/android-sdk
export JAVA_HOME=/path/to/jdk-17
./gradlew assembleDebug
```

### Install
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## App Structure

```
app/src/main/java/com/pocketnode/
├── service/
│   ├── BitcoindService.kt      # Foreground service managing bitcoind
│   ├── ElectrumService.kt      # Electrum server lifecycle management
│   ├── SyncController.kt       # Network-aware sync pause/resume
│   ├── BatteryMonitor.kt       # Battery level monitoring
│   ├── BootReceiver.kt         # Auto-start on device boot
│   └── WatchtowerManager.kt    # Watchtower service lifecycle
├── lightning/
│   ├── LightningService.kt     # ldk-node singleton wrapper (start/stop, state)
│   ├── PaymentManager.kt       # Send payments, routing fee budget, retry logic
│   ├── PaymentTracker.kt       # Live route tracking, hop display, attempt history
│   ├── ChannelManager.kt       # Channel open/close operations
│   ├── OnchainWallet.kt        # On-chain send/sweep, address management
│   ├── RecoveryManager.kt      # Seed backup/restore, recovery scan, chain state
│   ├── NodeDirectory.kt        # Peer directory and node info
│   ├── LndHubServer.kt         # LNDHub-compatible API server (:3000)
│   ├── WatchtowerBridge.kt     # LDK-to-LND watchtower push via Brontide
│   ├── WatchtowerNative.kt     # JNA bindings to native Rust watchtower client
│   ├── WalletRecoveryService.kt # Background wallet recovery
│   └── Bip39.kt                # Pure Kotlin BIP39 (mnemonic <-> entropy)
├── electrum/
│   ├── ElectrumServer.kt       # Electrum protocol TCP server
│   ├── ElectrumMethods.kt      # RPC method handlers (pruned-node tx fallbacks)
│   ├── AddressIndex.kt         # Descriptor wallet, UTXO cache, tx hex cache
│   ├── HistoryRecovery.kt      # mempool.space history recovery with gap limit
│   └── SubscriptionManager.kt  # Address/header subscription notifications
├── mempool/
│   ├── MempoolService.kt       # Local mempool monitoring
│   ├── GbtGenerator.kt         # Block template generation
│   ├── GbtResult.kt            # Block template result types
│   ├── MempoolEntry.kt         # Mempool transaction entry
│   ├── ProjectedBlockInfo.kt   # Projected block fee info
│   ├── ThreadAcceleration.kt   # CPFP/RBF acceleration detection
│   └── ThreadTransaction.kt    # Transaction thread grouping
├── power/
│   └── PowerModeManager.kt     # Max/Low/Away modes, burst sync, network holds
├── network/
│   └── NetworkMonitor.kt       # WiFi/cellular/VPN detection + data tracking
├── snapshot/
│   ├── ChainstateManager.kt    # AssumeUTXO snapshot flow
│   ├── SnapshotManager.kt      # Snapshot state management
│   ├── BlockFilterManager.kt   # Lightning block filter copy/remove
│   ├── NodeSetupManager.kt     # SSH setup + teardown
│   ├── NodeConnectionManager.kt # Remote node connection management
│   └── SnapshotDownloader.kt   # SFTP download with progress
├── share/
│   ├── ShareServer.kt          # Phone-to-phone chainstate sharing (server)
│   └── ShareClient.kt          # Phone-to-phone chainstate sharing (client)
├── ssh/
│   └── SshUtils.kt             # Shared SSH/SFTP utilities
├── rpc/
│   └── BitcoinRpcClient.kt     # Local bitcoind JSON-RPC
├── notification/
│   └── TransactionNotificationManager.kt # Transaction push notifications
├── tor/
│   ├── TorManager.kt           # Arti lifecycle, SOCKS proxy, persistent preference
│   └── TorAwareHttp.kt         # Routes OkHttp through SOCKS, .onion URL mapping
├── storage/
│   └── WatchListManager.kt     # Address watch list persistence
├── ui/
│   ├── PocketNodeApp.kt        # Navigation + top-level routing
│   ├── NodeStatusScreen.kt     # Main dashboard
│   ├── LightningScreen.kt      # Lightning management (channels, watchtower)
│   ├── SetupChecklistScreen.kt # Config mode setup wizard
│   ├── SnapshotSourceScreen.kt # Source picker
│   ├── SnapshotProgressScreen.kt # Snapshot load progress
│   ├── ChainstateCopyScreen.kt # Chainstate copy progress (4-step flow)
│   ├── ConnectWalletScreen.kt  # RPC / Electrum / LNDHub connection guide
│   ├── BlockFilterUpgradeScreen.kt # Lightning block filter management
│   ├── WatchtowerScreen.kt     # Home node watchtower setup
│   ├── DataUsageScreen.kt      # Data usage breakdown
│   ├── NetworkSettingsScreen.kt # Cellular/WiFi budgets
│   ├── NodeAccessScreen.kt     # View/remove node access
│   ├── NodeConnectionScreen.kt # Remote node connection setup
│   ├── NodeSetupScreen.kt      # Initial node setup
│   ├── NearbyNodeScreen.kt     # Discover nearby nodes
│   ├── ShareScreen.kt          # Phone-to-phone sharing UI
│   ├── InternetDownloadScreen.kt # HTTPS snapshot download
│   ├── PowerModeSelector.kt    # Three-segment power mode toggle + burst banner
│   ├── lightning/
│   │   ├── LightningPayScreen.kt   # Lightning Pay home (send/receive)
│   │   ├── SendPaymentScreen.kt    # Pay BOLT11/BOLT12, route tree display
│   │   ├── SendOnchainScreen.kt    # On-chain send/sweep
│   │   ├── ReceivePaymentScreen.kt # Generate invoices
│   │   ├── PaymentHistoryScreen.kt # Payment list
│   │   ├── OpenChannelScreen.kt    # Open channel to peer
│   │   ├── PeerBrowserScreen.kt    # Browse/search Lightning peers
│   │   ├── SeedBackupScreen.kt     # BIP39 seed view and restore
│   │   ├── QrCode.kt              # QR code generation (ZXing)
│   │   └── QrScannerScreen.kt     # Camera QR scanner (CameraX + ZXing)
│   ├── mempool/
│   │   ├── MempoolScreen.kt            # Mempool viewer
│   │   ├── MempoolViewModel.kt         # Mempool state management
│   │   ├── FeeEstimatePanel.kt         # Fee rate visualization
│   │   ├── TransactionSearchScreen.kt  # Transaction lookup
│   │   └── TransactionSearchViewModel.kt # Search state management
│   └── components/
│       ├── NetworkStatusBar.kt      # Sync status banner
│       ├── FairTradeCard.kt         # Fair trade info display
│       ├── OracleCard.kt            # UTXOracle price display
│       └── AdminCredentialsDialog.kt # SSH creds prompt
├── oracle/
│   └── UTXOracle.kt            # Sovereign price discovery from on-chain data
└── util/
    ├── ConfigGenerator.kt      # Mobile-optimized bitcoin.conf
    ├── BinaryExtractor.kt      # Version selection, 3 bundled bitcoind binaries
    ├── UpdateChecker.kt         # GitHub release update checker
    └── SetupChecker.kt         # Auto-detect completed setup steps
```

## Documentation

- [Build Guide](docs/build-android-arm64.md)
- [Cross-Compile Guide](docs/cross-compile-android.md)
- [ldk-node Android Build](docs/ldk-node-android-build.md)
- [Tor Integration](docs/tor-integration.md)
- [Chainparams Patch](docs/chainparams-patch.md)
- [Direct Chainstate Copy](docs/direct-chainstate-copy.md)
- [Snapshot Testing](docs/snapshot-testing.md)
- [Umbrel Integration](docs/umbrel-integration.md)
- [Block Filter Design](docs/BLOCK-FILTER-DESIGN.md)
- [Block Index Consistency](docs/BLOCK-INDEX-CONSISTENCY.md)
- [Version Selection Design](docs/VERSION-SELECTION.md)
- [BIP 110 Research](docs/BIP110-RESEARCH.md)
- [LDK Research](docs/LDK-RESEARCH.md)
- [Watchtower Mesh Design](docs/WATCHTOWER-MESH.md)
- [LDK-to-LND Watchtower Bridge](docs/LDK-WATCHTOWER-BRIDGE.md)
- [Desktop Port Design](docs/DESKTOP-PORT.md)
- [Power Modes Design](docs/POWER-MODES.md)
- [Pruned Node Risk Analysis](docs/PRUNED-NODE-RISK-ANALYSIS.md)
- [LDK Upstream Contribution](docs/LDK-UPSTREAM-CONTRIBUTION.md)
- [LDK Anchor Downgrade Bug](docs/LDK-ANCHOR-DOWNGRADE-BUG.md)
- [Ark Integration Plan](docs/ARK-INTEGRATION.md)
- [Built-in Tor Design](docs/BUILT-IN-TOR.md)
- [iOS Port Feasibility](docs/IOS-PORT.md)
- [Phone-to-Phone Sharing](docs/PHONE-TO-PHONE.md)

## Roadmap

- **LDK upstream contribution:** improving watchtower API in rust-lightning ChannelMonitor ([#813](https://github.com/lightningdevkit/ldk-node/issues/813)). Draft PR submitted.
- **Upstream PRs:** rust-lightning [#4453](https://github.com/lightningdevkit/rust-lightning/pull/4453) (justice tx API), ldk-node [#822](https://github.com/lightningdevkit/ldk-node/pull/822) (wallet birthday), rust-lightning [#4485](https://github.com/lightningdevkit/rust-lightning/issues/4485) (anchor downgrade bug)
- **Desktop port:** Same app on Linux, macOS, Windows via Compose Multiplatform. See [design doc](docs/DESKTOP-PORT.md)
- **iOS port:** Burst sync + watchtower + in-process LDK make iOS viable. See [feasibility analysis](docs/IOS-PORT.md)

## Tested On

| Device | SoC | OS | Result |
|--------|-----|----|--------|
| Pixel 9 | Tensor G4 | GrapheneOS | ✅ Full stack: chainstate copy, LDK Lightning, BIP 110, all features verified |
| Samsung Galaxy Z Fold | Snapdragon | Android | ✅ Dual-pane foldable layout working, IBD syncing |
| Huawei Mate 20 Lite | Kirin 710 | EMUI | ✅ Clean install, IBD syncing from genesis |
| Pixel 9 | Tensor G4 | GrapheneOS | ✅ Lightning send/receive verified, 7-day uptime confirmed by independent tester |

## Known Issues

- 16KB page alignment warning on GrapheneOS (cosmetic only)
- `getblockchaininfo` reports background validation progress, not snapshot chain tip (AssumeUTXO path only)

## License

MIT
