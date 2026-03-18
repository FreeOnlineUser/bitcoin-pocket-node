# Watchtower Design

## What's Built

Bitcoin Pocket Node uses a custom LDK-to-LND watchtower bridge. The phone runs ldk-node and pushes encrypted justice blobs to an LND watchtower over Tor, using the native BOLT 8 (Brontide) protocol.

See `docs/LDK-WATCHTOWER-BRIDGE.md` for the full technical design.

### Current Architecture

```
Phone (ldk-node)              Tor (.onion)              Home Node (LND)
├── WatchtowerBridge.kt  ──────────────────────────>  LND Watchtower
│   ├── Drains state blobs from ldk-node               ├── Encrypted blob store
│   ├── Encrypts as JusticeKitV0                       ├── Watches every block
│   └── Pushes via Brontide (BOLT 8)                   └── Broadcasts justice tx
└── Embedded Arti (Tor client)
```

**Key components:**
- **ldk-node fork** (`FreeOnlineUser/ldk-node`, branch `watchtower-bridge`): custom persistence that captures channel state for watchtower updates
- **ldk-watchtower-client** (`FreeOnlineUser/ldk-watchtower-client`): Rust library implementing BOLT 8 Brontide handshake + LND wtwire protocol
- **Embedded Arti (0.39.0)**: in-process Tor client for .onion connectivity, no Orbot dependency
- **Auto-push**: blobs sent after every payment, dynamic fee estimation from local bitcoind

### Setup

The watchtower URI (pubkey@onion:9911) is configured once during Lightning setup. The phone connects directly to the tower's .onion address via embedded Arti — no SSH, no VPN, no port forwarding.

Any LND watchtower is compatible. Common sources:
- Another Pocket Node user running in Max mode (future: decentralised network)
- Home node running LND (Umbrel, Start9, RaspiBlitz, myNode etc.)
- Public community watchtowers

## Power Mode Integration

Watchtower safety is maintained across all power modes:

- **Max Data**: continuous monitoring, blobs pushed immediately
- **Low Data**: watchtower covers 15-minute gaps between bursts. Blobs pushed during each burst.
- **Away Mode**: watchtower covers 60-minute gaps. This is the primary safety net: even with hourly syncs, the watchtower is watching continuously from the home node.

Channel opens require Max Data mode to ensure reliable funding confirmation monitoring.

## Decentralised Watchtower Network

### The Problem with a Single Watchtower

A single home watchtower has a single point of failure:
- Home internet goes down
- Node crashes or corrupts
- Power outage
- ISP blocks Tor

If the watchtower is offline at the exact moment a breach occurs, the justice transaction doesn't get broadcast.

### Concept: Decentralised Pocket Node Watchtower Network

Pocket Node users in Max mode (plugged in, on WiFi) can opt in to running a watchtower server directly on their phone. No home node required. Every Pocket Node becomes both a watchtower client and server, creating a peer-to-peer protection network.

**How it works:**
- A Pocket Node in Max mode runs an LND-compatible watchtower server via Tor hidden service
- Other Pocket Node users connect and push encrypted justice blobs
- Blobs are encrypted: the tower can only use them if a breach is detected on-chain. It cannot steal funds or learn channel balances.
- Users push blobs to multiple peer towers for redundancy. Any one tower can broadcast the justice tx.

**Marketplace:**

The app includes a "Browse Watchtowers" screen. Available towers publish to a Nostr relay. Users pick 2-3 towers, pay the fixed rate, done. No manual URI exchange needed.

```
Available Towers
├── Tower A - 98.5% uptime - 1000 sats/yr
├── Tower B - 95.2% uptime - 1000 sats/yr
├── Tower C - 99.1% uptime - 1000 sats/yr (free tier)
└── 47 towers available
```

Fixed rate (1000 sats/year) for all towers. No price competition, no haggling. Users pick based on uptime reputation, not cost. Keeps the UX simple: tap, pay, protected.

Uptime reputation builds from client attestations over time. New towers start with no history.

**All discovery traffic routes through embedded Arti (Tor).** The entire watchtower flow is Tor end-to-end: marketplace queries, tower connections, blob pushes, payments. ISP sees nothing. This also avoids regional relay issues (e.g. high latency to US/EU relays from Australia).

**Discovery:**
1. **Nostr relay via Tor** (primary): query existing public relays for watchtower events (custom kind). Standard relays, no special infrastructure. Towers publish a replaceable event with their .onion address and uptime stats.
2. **Manual exchange**: share tower URIs via QR code for direct peering
3. **DNS seeds**: hardcoded community towers as bootstrap fallback

**Trust model:**
- Watchtower blobs reveal nothing about your channels (encrypted until breach)
- A malicious watchtower can refuse to act, but cannot steal funds
- Multiple towers provide redundancy: you only need one honest tower online during a breach
- No KYC, no accounts, just pubkey-based identity

### Architecture

```
Phone A (Max mode, plugged in)              Phone B (Low/Away mode, mobile)
├── ldk-node (own channels)                 ├── ldk-node (own channels)
├── Watchtower server (opt-in)  <────────── ├── Push blobs to Phone A
│   ├── Encrypted blob store                ├── Push blobs to Phone C
│   ├── Watches every block                 └── Any tower can punish breach
│   └── Broadcasts justice tx
└── Tor hidden service

Phone C (Max mode, plugged in)
├── ldk-node (own channels)
├── Watchtower server (opt-in)  <────────── Phone B also pushes here
└── Tor hidden service
```

**The key insight:** a Pocket Node in Max mode is already running bitcoind continuously, syncing every block. Adding watchtower server capability is lightweight: it just stores encrypted blobs and checks each new block for breaches. The phone is already doing the hard work.

**Max mode requirement:** watchtower servers only run in Max mode. In Low or Away mode the phone has gaps between syncs, which defeats the purpose of watching for breaches. When the mode switches away from Max, the server pauses (existing clients will rely on other towers).

**New components needed:**
- Watchtower server implementation in the app (LND wtwire protocol, same as what our client already speaks)
- Blob storage manager (encrypted blobs on device, prunable after channel close)
- Tower discovery (Nostr relay or manual URI exchange)
- Multi-tower blob distribution in WatchtowerBridge
- Tower health monitoring (detect offline towers, find replacements)
- Dashboard toggle: "Run watchtower for others" with storage usage indicator

### Watchtower as a Service

The watchtower is the only part of the Pocket Node stack that isn't fully sovereign. Everything else — bitcoind, LDK, Electrum server, Tor — runs locally. You're trusting someone else's uptime for channel protection.

Paid watchtower service solves this by making it self-sustaining:

- Pocket Nodes in Max mode opt in as paid watchtowers
- Clients pay a small Lightning fee per blob stored (microsats)
- Payment happens over the same Lightning channels the watchtower is protecting
- No external service, no subscription, no account — just peer-to-peer Lightning payments

**Economics:**
- Tower operators earn sats passively for keeping their phone plugged in
- Clients pay tiny amounts for peace of mind (a few sats per channel update)
- Max mode users have a direct incentive to stay online
- The network self-scales: more users = more towers = more redundancy
- **Dev fee:** 50% of each watchtower fee goes to the project. Sustainable open source funding baked into the protocol, no donations or grants needed long-term

**Pricing:** 1000 sats/year per client. Cheap enough that nobody thinks twice, sustainable enough to incentivise operators. One Lightning payment extends coverage by a year. 100 clients = 100k sats/year passive income for keeping your phone plugged in.

**Subscription model:**
- Tower stores `expiry_timestamp` per client session
- Each StateUpdate checks if subscription is active
- 30-day warning before expiry: "Watchtower subscription expires soon. Renew for 1000 sats?"
- On expiry: tower stops accepting new blobs, but existing blobs stay stored (no mid-channel protection gap)
- Renewal is a single Lightning payment that extends the timestamp by a year
- The payment itself is the receipt, no accounts or billing

**Free tier:** reciprocal watching (I watch yours, you watch mine) could remain as a no-cost option alongside paid towers. Community/altruistic towers can set their fee to zero.

## Revised Revenue Model (March 2026)

After further discussion, the paid-per-blob model adds too much complexity for unclear benefit. Watchtower blobs are tiny (~300 bytes each), freeloading isn't a real problem, and the enforcement/billing logic is heavy.

**New approach: free towers, paid directory.**

Watchtower service itself stays **free and altruistic**, exactly how LND towers already work. No payment protocol between client and tower, no billing, no kicking users. Anyone can run a tower and offer it to others. This matches Bitcoin's ethos: infrastructure as a public good.

The value is in **discovery and curation**. Finding reliable towers, knowing which are online, comparing uptime. That's the hard problem nobody's solved.

**Directory model:**
- Tower operators register in the Pocket Tower Directory (pubkey, .onion address, capacity)
- Directory monitors uptime, tracks reliability over time
- Pocket Node users browse the directory from the app
- **Free tier:** one default community tower, basic directory access
- **Paid tier:** full directory with uptime stats, multi-tower selection, reliability scores
- Payment via Lightning, subscription or one-time

**Why this works better:**
- No custom tower software needed: stock LND towers are compatible
- No payment channels between client and tower
- No enforcement headaches (kicking, blocking, grace periods)
- Revenue comes from a service people actually need (discovery) not artificial scarcity
- Easier to build: directory is just an API + health checks
- Tower operators have zero overhead: just run LND with watchtower enabled

**Implementation options:**
- Simple API server (could run on Umbrel as an app)
- Nostr relay with custom event kinds (decentralised, censorship-resistant)
- Hardcoded community list as bootstrap fallback

The 50% dev fee concept from the original model can apply to directory subscription revenue instead.

## Priority

1. **Current**: LDK-to-LND watchtower bridge over Tor (working, shipped)
2. **Next**: multi-tower support (push blobs to 2-3 towers instead of one)
3. **Future**: watchtower directory service, tower health monitoring, paid directory tier
