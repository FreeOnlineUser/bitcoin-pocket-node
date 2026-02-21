# Watchtower Mesh: Peer Channel Protection

## Summary

Every Pocket Node phone automatically watches other Pocket Node phones' Lightning channels. Zero configuration. If your phone goes offline and a counterparty broadcasts an old channel state, another Pocket Node user's phone broadcasts the justice transaction on your behalf.

## Why This Matters

Lightning channels are vulnerable when nodes go offline. If your counterparty publishes a revoked commitment transaction while your phone is off (charging overnight, rebooting, out of service), you lose funds. The standard solution is a watchtower: an always-on server that monitors the chain and broadcasts justice transactions for you.

The problem: most Lightning mobile users have no watchtower at all. They trust that counterparties won't cheat while they sleep. Running your own watchtower defeats the purpose of a phone node (you'd need a server anyway).

The Pocket Node answer: phones watch each other. Collectively, the network of Pocket Node users is always online even though individual phones come and go.

## What Already Exists

LND ships with a complete watchtower implementation. Both server and client are built in:

**Watchtower server** (watch someone else's channels):
```
# lnd.conf
watchtower.active=1
```
Exposes: `lncli tower info` returns pubkey + listener URI.

**Watchtower client** (send your channel state to watchtowers):
```
# lnd.conf
wtclient.active=1
```
Commands: `lncli wtclient add <pubkey@host>`, `lncli wtclient towers`, `lncli wtclient stats`.

The watchtower protocol is encrypted. The watchtower server learns nothing about channel details (amounts, counterparties) until a breach actually occurs. It only stores encrypted blob data that becomes useful when it sees a matching revoked commitment on-chain.

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                    Discovery Service                     │
│        (Nostr relay, simple API, or DNS records)         │
│                                                         │
│   Stores: watchtower pubkey + onion address per node    │
│   Simple read/write. No logic. Replaceable.             │
└───────────┬─────────────────────────┬───────────────────┘
            │ register                │ fetch peers
            │                        │
     ┌──────┴──────┐          ┌──────┴──────┐
     │  Phone A    │          │  Phone B    │
     │  (server +  │◄────────►│  (server +  │
     │   client)   │  watch   │   client)   │
     └─────────────┘          └─────────────┘
```

Every phone runs both roles:
1. **Server**: watches other phones' channels (costs nothing, minimal storage)
2. **Client**: sends encrypted channel state updates to peer watchtowers

## Implementation Plan

### Phase 1: Enable Watchtower in LND Config

**Status: needs Zeus support or direct config injection**

Zeus's embedded LND generates its own `lnd.conf` via `writeLndConfig()` in `LndMobileUtils.ts`. As of now, it does not include watchtower settings. Two paths forward:

**Option A: Zeus feature request (clean)**
Ask Zeus to add `watchtower.active` and `wtclient.active` as user-toggleable settings. This is a small PR. Zeus already exposes Neutrino peer config, fee estimator, and protocol options.

**Option B: Direct config append (works today)**
After Zeus writes its `lnd.conf`, Pocket Node appends watchtower lines before LND starts. The app already knows Zeus's LND directory location. Risk: Zeus could overwrite on restart. Mitigation: re-append on every Zeus start detected.

**Option C: LND REST/gRPC API (runtime)**
LND's `wtclient` and `watchtower` sub-servers may be controllable via API calls after LND starts, bypassing config entirely. Needs investigation of which RPC calls Zeus's LND build includes.

**Recommendation:** Start with Option A (Zeus PR). Fall back to Option B for a working prototype while waiting for upstream merge.

### Phase 2: Discovery Service

The simplest possible service for phones to find each other's watchtowers.

#### Option 1: Nostr Relay (preferred)

Watchtower registration as Nostr events on a public relay. No server to run.

```json
{
  "kind": 38333,
  "tags": [
    ["d", "<watchtower-pubkey>"],
    ["uri", "<pubkey>@<onion-address>:9911"],
    ["app", "pocket-node"],
    ["v", "1"]
  ],
  "content": ""
}
```

- Phones publish their watchtower info as replaceable Nostr events (kind 38333, arbitrary choice in parameterized replaceable range)
- Other phones query the relay for events with tag `["app", "pocket-node"]`
- Nostr relays are free, public, redundant, and censorship-resistant
- Events are signed by a per-device Nostr keypair (generated on first run)
- Stale entries naturally expire (relays prune old events, or set expiration tag)
- Multiple relays for redundancy (query 3, publish to 3)

**Why Nostr fits:**
- No server to build, host, or maintain
- Decentralized by default (relay goes down, use another)
- Spam-resistant (valid Nostr events only, can add PoW requirement)
- Already part of the Bitcoin ecosystem

#### Option 2: Simple REST API (fallback)

A single endpoint if Nostr adds too much complexity for v1:

```
POST /api/v1/watchtower
  { "pubkey": "02f1...", "uri": "02f1...@abc123.onion:9911" }

GET /api/v1/watchtowers
  Returns: [{ "pubkey": "...", "uri": "...", "last_seen": "..." }, ...]
```

Hosted on a simple VPS. No auth needed (watchtower pubkeys are meant to be public). Rate limit by IP. Prune entries not refreshed in 7 days.

This is a centralization point but a temporary one. Migrate to Nostr once proven.

#### Option 3: DNS TXT Records

Encode watchtower URIs as DNS TXT records under a dedicated subdomain:

```
_watchtower.pocketnode.org TXT "02f1...@abc123.onion:9911"
```

Simple, cacheable, works everywhere. But requires manual updates or a dynamic DNS API, and doesn't scale well past a few hundred entries.

**Recommendation:** Nostr for v1. It's the right fit for a Bitcoin-native, server-less discovery mechanism. REST API as a parallel fallback for reliability.

### Phase 3: App Integration (Pocket Node Side)

New Kotlin code in the Pocket Node app:

#### WatchtowerManager.kt

```kotlin
class WatchtowerManager(private val context: Context) {

    // On Lightning setup completion:
    fun enableWatchtower() {
        // 1. Ensure watchtower lines in LND config
        // 2. Restart LND if needed
        // 3. Get local tower info (pubkey + URI)
        // 4. Publish to discovery service
        // 5. Fetch peer watchtowers
        // 6. Add peers via wtclient
    }

    // Periodic (on app start, daily):
    fun refreshPeers() {
        // 1. Re-publish own tower info (keep-alive)
        // 2. Fetch current peer list
        // 3. Add new peers, remove stale ones
        // 4. Log stats (towers watching us, towers we watch)
    }
}
```

#### UI: Watchtower Status Card

On the dashboard (or Lightning section):

```
⚡ Watchtower Mesh
Watching: 12 peers
Watched by: 8 peers
Last refresh: 2 hours ago
```

Tap for details: list of connected watchtowers, stats, manual refresh.

#### Tor Requirement

Watchtower URIs use `.onion` addresses for privacy. LND generates these automatically when Tor is enabled. This means:

- Tor must be running on the phone for watchtower server to be reachable
- Zeus already supports Tor (toggle in settings)
- Pocket Node would need to ensure Tor is enabled when watchtower mesh is active
- Without Tor: client-only mode (use others' watchtowers, but don't serve)

**For v1:** Support client-only mode without Tor. Full mesh (server + client) requires Tor enabled in Zeus. This avoids making Tor a hard dependency.

### Phase 4: Hardening (Post-MVP)

- **Reputation scoring:** Track which watchtowers are consistently online. Prefer reliable peers.
- **Redundancy target:** Ensure each phone has at least N watchtowers (e.g., 5). Alert if below threshold.
- **Fee incentives:** LND supports watchtower reward addresses. Could add optional sat bounties for successful justice transactions. (Current LND watchtowers are altruistic.)
- **Gossip-based discovery:** Instead of a central service, watchtower URIs could propagate through LN gossip messages (custom TLV). True peer-to-peer, no relay needed.
- **Rate limiting:** Limit how many watchtower peers each phone serves to prevent resource exhaustion on low-end devices.
- **Encrypted backup:** Use the watchtower mesh as an encrypted channel state backup network (SCB distribution).

## Privacy Properties

| What watchtower server learns | What it doesn't learn |
|---|---|
| That a peer exists and has channels | Channel balances or capacity |
| Encrypted justice transaction blobs | Counterparty identity |
| When a breach occurs (if it does) | Normal channel activity |
| Nothing if no breach ever happens | Wallet contents, transaction history |

The watchtower protocol is designed so servers store opaque data. They can only act on it when they observe a matching revoked commitment transaction on-chain.

## Storage and Resource Cost

Per watched peer:
- ~256 bytes per channel state update (encrypted blob)
- Typical: a few KB per peer total
- 100 peers = ~1 MB of watchtower data

Negligible on a phone with 128+ GB storage. CPU cost is near zero (just blob storage and chain monitoring that LND already does).

## Scope and Dependencies

| Component | Status | Effort |
|---|---|---|
| LND watchtower server | Ships with LND | Configuration only |
| LND watchtower client | Ships with LND | Configuration only |
| Zeus config support | Not yet exposed | Feature request / small PR |
| Nostr discovery | Protocol exists | ~1 week Kotlin code |
| WatchtowerManager.kt | New | ~1 week |
| Dashboard UI | New | ~2 days |
| Tor integration | Zeus supports it | Configuration guidance |

**Total estimated effort:** 2-3 weeks for a working MVP (client-only without Tor, Nostr discovery, dashboard card).

Full mesh with Tor server mode: additional 1-2 weeks depending on Zeus cooperation.

## The Grant Pitch

> Every Bitcoin Pocket Node phone automatically watches other Pocket Node phones' Lightning channels. When your phone is offline and a counterparty tries to cheat, another Pocket Node user's phone catches it and broadcasts the justice transaction. Zero configuration. No servers. No trusted third parties. Phones protect each other.

This turns a network of mobile nodes from isolated individuals into a resilient collective. The more people run Pocket Node, the safer everyone's channels become. That's a network effect worth funding.
