# Tor Integration Plan

Route all Pocket Node traffic through Tor. One toggle: "Route all traffic through Tor."

## Current State

- **Arti 0.39.0** already embedded (Rust Tor implementation)
- **Watchtower** already connects via Tor to .onion watchtowers
- **bitreq SOCKS5** (the proxy path for HTTP/RGS/scoring calls) is **merged upstream** as corepc [#533](https://git.rust-bitcoin.org/rust-bitcoin/corepc/pulls/533) (rust-bitcoin, 2026-07-23), the first of our upstream PRs to land. The app currently builds against our bitreq fork; adopting the released crate is also what unblocks the upstream ldk-node TorConfig IP-leak fix ([#834](https://github.com/lightningdevkit/ldk-node/issues/834)), which is waiting on a bitreq crates.io release (≥ 0.3.8) carrying #533.
- Everything else is clearnet

## Architecture

```
┌─────────────────────────────────────────────┐
│  Pocket Node App                            │
│                                             │
│  ┌─────────┐  ┌─────────┐  ┌────────────┐  │
│  │bitcoind │  │  LDK    │  │ HTTP calls │  │
│  │ -proxy  │  │ peers   │  │ (updates,  │  │
│  └────┬────┘  └────┬────┘  │  mempool)  │  │
│       │            │       └─────┬──────┘  │
│       │            │             │          │
│       └────────┬───┴─────────────┘          │
│                │                            │
│         ┌──────▼──────┐                     │
│         │ Arti SOCKS5 │                     │
│         │ 127.0.0.1   │                     │
│         │ :9050       │                     │
│         └──────┬──────┘                     │
│                │                            │
└────────────────┼────────────────────────────┘
                 │
            Tor Network
```

## Every External Call in the App

Complete inventory of code that talks to the internet:

### bitcoind (P2P network)
- **What:** Peer connections, block/tx relay
- **Where:** Managed by bitcoind itself, args in `BitcoindService.kt` line ~210
- **Current:** Direct TCP to clearnet peers
- **Tor change:** Add `-proxy=127.0.0.1:9050` arg. Optionally `-onlynet=onion`.
- **Effort:** Trivial (one arg)

### LDK Rapid Gossip Sync
- **What:** Downloads Lightning network graph snapshot on startup
- **Where:** `LightningService.kt:27` — `RGS_URL = "https://rapidsync.lightningdevkit.org/snapshot"`
- **Current:** Direct HTTPS
- **Tor change:** Route through SOCKS proxy. No .onion available, uses Tor exit node.
- **Effort:** Small (ldk-node config)

### LDK peer connections
- **What:** TCP connections to Lightning peers (e.g. ACINQ)
- **Where:** `LightningService.kt` — `node.connect()` calls
- **Current:** Direct TCP to peer IP:port
- **Tor change:** Route through SOCKS5. Prefer .onion address when available.
- **Known .onion peers:**
  - ACINQ: `of7husrflx7sforh3fw6yqlpwstee3wg5imvvmkp4bz6rbjxtg5nljad.onion:9735`
  - Most major routing nodes publish .onion in their node announcement
- **Effort:** Moderate (custom transport layer or ldk-node SOCKS support)

### mempool.space: History Recovery (PRIVACY CRITICAL)
- **What:** Fetches transaction history for tracked wallet addresses on pruned node
- **Where:** `HistoryRecovery.kt:23` — `DEFAULT_API_BASE = "https://mempool.space/api"`
- **Called from:** `AddressIndex.kt:505` — `recoverMissingHistory()`
- **Current:** Direct HTTPS to mempool.space. Sends your addresses to them.
- **Tor .onion:** `http://mempoolhqx4isw62xs7abwphsq7ldayuidyx2v2oethdhhj6mlo2r6ad.onion/api`
- **Tor change:** Replace BASE_URL with .onion, route through SOCKS proxy
- **Effort:** Small
- **Priority:** HIGH. This is the biggest privacy leak in the app.

### mempool.space: Transaction Hex Fetch (PRIVACY CRITICAL)
- **What:** Fetches raw transaction data for pruned blocks (Electrum needs tx hex)
- **Where:** `AddressIndex.kt:625` — fetches hex from mempool.space for txids
- **Current:** Direct HTTPS. Reveals which specific transactions you care about.
- **Tor .onion:** Same as above
- **Tor change:** Same URL swap + SOCKS proxy
- **Effort:** Small
- **Priority:** HIGH. Reveals your transaction interest.

### mempool.space: Peer Browser
- **What:** Lightning node directory (most connected, largest, lowest fee, search)
- **Where:** `NodeDirectory.kt:22` — `BASE_URL = "https://mempool.space/api/v1/lightning"`
- **Current:** Direct HTTPS
- **Tor .onion:** `http://mempoolhqx4isw62xs7abwphsq7ldayuidyx2v2oethdhhj6mlo2r6ad.onion/api/v1/lightning`
- **Tor change:** URL swap + SOCKS proxy
- **Effort:** Small
- **Priority:** Medium. Reveals interest in specific Lightning nodes.

### mempool.space: Anchor Support Check
- **What:** Checks if a Lightning peer supports anchor channels (feature bit 23)
- **Where:** `PeerBrowserScreen.kt:249` — enriches node data
- **Current:** Direct HTTPS via NodeDirectory
- **Tor change:** Same as peer browser (shared NodeDirectory code)
- **Effort:** Free (comes with NodeDirectory change)

### GitHub: Update Checker
- **What:** Checks for new app releases
- **Where:** `UpdateChecker.kt:22` — `RELEASES_URL = "https://api.github.com/repos/FreeOnlineUser/bitcoin-pocket-node/releases/latest"`
- **Current:** Direct HTTPS to GitHub API
- **Tor .onion:** None. GitHub has no .onion. Uses Tor exit node.
- **Tor change:** Route HttpURLConnection through SOCKS proxy
- **Effort:** Small
- **Priority:** Low. Reveals you run Pocket Node, but no financial info.

### GitHub: Avatar Image
- **What:** Loads project avatar in About screen
- **Where:** `NodeStatusScreen.kt:1798`, `LightningPayScreen.kt:405` — `"https://github.com/FreeOnlineUser.png"`
- **Current:** Coil image loader, direct HTTPS
- **Tor change:** Configure Coil OkHttp client with SOCKS proxy
- **Effort:** Small
- **Priority:** Low. Cosmetic.

### utxo.download: Snapshot Download
- **What:** Downloads UTXO snapshot for internet bootstrap path
- **Where:** `InternetDownloadScreen.kt:63` — `"https://utxo.download/utxo-910000.dat"`
- **Current:** Direct HTTPS (9 GB download)
- **Tor .onion:** Unknown. Likely no .onion. Would use exit node.
- **Tor change:** Route through SOCKS proxy. WARNING: 9 GB over Tor is very slow.
- **Effort:** Small
- **Priority:** Low. One-time setup, and user chose to download from internet.

### UTXOracle: YouTube/utxo.live links
- **What:** Opens external URLs in browser (not API calls, just link taps)
- **Where:** `OracleCard.kt:404,414`
- **Current:** Opens system browser
- **Tor change:** None needed (opens in user's browser, not our code)
- **Effort:** None

### Watchtower (ALREADY DONE)
- **What:** Pushes justice blobs to LND watchtower
- **Where:** `WatchtowerBridge.kt`, `WatchtowerNative.kt`
- **Current:** Already routes through Arti to .onion watchtower
- **Tor change:** None
- **Effort:** Done ✅

## Implementation Plan

### Phase 1: Arti SOCKS Proxy Service (foundation)

**New class: `TorManager.kt`**
```kotlin
object TorManager {
    private const val SOCKS_PORT = 9050
    
    fun isEnabled(context: Context): Boolean
    fun setEnabled(context: Context, enabled: Boolean)
    fun start(): Boolean  // Start Arti as SOCKS5 on 127.0.0.1:9050
    fun stop()
    fun isRunning(): Boolean
    fun getSocksProxy(): Proxy  // java.net.Proxy for HttpURLConnection
}
```

**What changes:**
- Current: Arti starts per-connection for watchtower only (`WatchtowerNative.kt`)
- New: Arti starts as persistent SOCKS5 proxy when Tor mode enabled
- Watchtower still uses its dedicated Arti connection (proven, don't break it)

**Effort:** Small. Arti natively supports SOCKS5 proxy mode.

### Phase 2: bitcoind through Tor (biggest privacy win)

**File:** `BitcoindService.kt` ~line 210

**Change:** Add args when Tor enabled:
```kotlin
if (TorManager.isEnabled(this)) {
    args.add("-proxy=127.0.0.1:9050")
    // Optional: Tor-only mode
    // args.add("-onlynet=onion")
    // args.add("-dnsseed=0")
}
```

**Notes:**
- Bitcoin has thousands of Tor peers
- DNS seeding doesn't work over Tor, but bitcoind has hardcoded .onion seeds
- Mixed mode (clearnet + Tor) is default. User can opt into Tor-only.
- Slight sync latency increase

**Effort:** Trivial.

### Phase 3: HTTP calls through Tor (privacy-critical API calls)

**New utility class: `TorAwareHttp.kt`**
```kotlin
object TorAwareHttp {
    // Known .onion mappings
    private val ONION_MAP = mapOf(
        "mempool.space" to "mempoolhqx4isw62xs7abwphsq7ldayuidyx2v2oethdhhj6mlo2r6ad.onion",
    )
    
    fun openConnection(url: String, context: Context): HttpURLConnection {
        val torEnabled = TorManager.isEnabled(context)
        val targetUrl = if (torEnabled) toOnionUrl(url) else url
        return URL(targetUrl).openConnection(
            if (torEnabled) TorManager.getSocksProxy()
            else Proxy.NO_PROXY
        ) as HttpURLConnection
    }
    
    private fun toOnionUrl(url: String): String {
        var result = url
        for ((domain, onion) in ONION_MAP) {
            result = result.replace("https://$domain", "http://$onion")
        }
        return result
    }
}
```

**Files to update (replace direct HttpURLConnection with TorAwareHttp):**

| File | Line | What |
|------|------|------|
| `HistoryRecovery.kt` | 23 | `DEFAULT_API_BASE` → use TorAwareHttp |
| `AddressIndex.kt` | 625 | tx hex fetch → use TorAwareHttp |
| `NodeDirectory.kt` | 22 | `BASE_URL` → use TorAwareHttp |
| `UpdateChecker.kt` | 22 | `RELEASES_URL` → use TorAwareHttp |

**Effort:** Small-moderate. ~4 files, same pattern each time.

### Phase 4: LDK peers via .onion (full Lightning privacy)

**Files:** `LightningService.kt`, `NodeDirectory.kt`, `PeerBrowserScreen.kt`

**Changes:**
1. `NodeDirectory.kt`: Parse both clearnet and .onion from mempool.space `sockets` field
2. `PeerBrowserScreen.kt`: Show .onion badge, prefer .onion when Tor enabled
3. `LightningService.kt`: Connect to .onion address through SOCKS when Tor enabled
4. `LightningService.kt`: Configure RGS fetch through SOCKS proxy

**Challenge:** ldk-node's `connect()` takes a `SocketAddress`. Need to route TCP through SOCKS5. Options:
- Custom `TcpStream` wrapper that SOCKS-proxies
- Patch ldk-node to accept SOCKS proxy config
- Use ldk-node's transport abstraction if available

**Effort:** Moderate. The TCP-through-SOCKS is the main challenge.

### Phase 5: Electrum hidden service (future)

**What:** Expose Electrum server as .onion for remote access
**Why:** Access your phone's Electrum server from another device
**Where:** Arti hidden service API
**Effort:** Hard. Arti's hidden service support is still maturing.
**Status:** Future, not in first Tor release.

## UI Design

### Settings
- **"Tor Network"** section
- Main toggle: "Route all traffic through Tor"
- When enabled: **Tor only** by default (`onlynet=onion`). No clearnet connections. Safe for dissidents, journalists, activists.
- Sub-option: "Allow clearnet" (opt-in for users in safe countries who want faster sync and more peers)
- Status: Connected / Connecting / Off
- Circuit info: number of hops, uptime

**Design rationale:** The people who need Tor the most are the ones least able to evaluate "prefer" vs "only." One clearnet connection leaks their IP. Tor-only is the only safe default. Users in safe countries can opt into clearnet for performance.

### Dashboard
- Small 🧅 onion icon next to peer count when Tor active
- Peer count label: "4 peers (Tor)" or "4 peers (mixed)"

### Peer Browser
- .onion badge on Tor-reachable nodes
- When Tor enabled, show .onion address instead of IP
- Filter option: "Tor-reachable only"

## Privacy Matrix

| Component | Without Tor | With Tor |
|-----------|-------------|----------|
| bitcoind peers | ISP sees all peer IPs | Hidden |
| Lightning peers | ISP sees peer IPs | Hidden |
| mempool.space queries | mempool.space sees your IP + addresses | IP hidden |
| GitHub update check | GitHub sees your IP | IP hidden |
| Snapshot download | utxo.download sees your IP | IP hidden |
| On-chain transactions | Public on blockchain | Public on blockchain (Tor doesn't help) |
| Watchtower | Already Tor ✅ | Already Tor ✅ |

## Power Modes and Tor

Tor stays running across all power modes. Arti proxy persists independently of bitcoind's lifecycle.

| Power Mode | Tor Behavior |
|------------|-------------|
| **Max** | Persistent connection, circuits stay warm. Best Tor experience. |
| **Burst** | bitcoind cycles on/off but Arti stays up. Circuits remain warm between bursts. No cold-start penalty on each cycle. |
| **Low** | Minimal network activity. Arti idles, circuits maintained. Negligible overhead. |

**Key design decision:** Do NOT tear down Arti when bitcoind stops. Keep the proxy alive so circuits are ready when bitcoind restarts. This avoids the 3-10 second circuit setup on every burst cycle.

### Idle Data Usage

Arti's idle overhead when no app traffic is flowing:
- **Directory consensus:** ~1-2 MB every 1-3 hours (cached to disk, survives restarts)
- **Circuit keepalive:** ~1 KB/min per circuit (padding cells)
- **Prebuilt circuits:** 2-3 idle
- **Total idle cost:** ~2-5 MB/day

Negligible next to block sync or RGS downloads.

## Performance Impact

- **bitcoind sync:** +50-200% latency per peer. Acceptable for pruned catch-up (few blocks). Avoid for IBD.
- **Lightning payments:** +1-3s per hop. Most users won't notice for simple sends.
- **API calls:** +1-3s per request. Fine for background checks.
- **Battery:** Modest increase from Tor circuit maintenance. Arti is efficient.
- **Bandwidth:** No overhead (Tor adds ~500 bytes per cell, negligible).

## Risks

- Tor exit nodes can see unencrypted HTTP (mitigated: use .onion where available, HTTPS elsewhere)
- Tor congestion causes timeouts (mitigated: generous timeouts, retry logic)
- Some Lightning peers may reject Tor connections (mitigated: fall back or warn user)
- Arti is relatively young (mitigated: battle-tested in our watchtower for months)

## Dependencies

- Arti 0.39.0 (already in the app via libldk_watchtower_client.so)
- No new native libraries
- No new Android permissions (Tor uses standard TCP sockets)
- May need to expose Arti SOCKS from native lib for Java-side use (JNI/JNA addition to WatchtowerNative)
