# LDK (Lightning Dev Kit) Research for Bitcoin Pocket Node

**Date:** 2025-02-19  
**Context:** Evaluating Lightning integration for the Pocket Node Android app (ARM64, GrapheneOS Pixel) which already runs Bitcoin Core v28.1 + BWT Electrum server.

---

## Table of Contents
1. [What is LDK](#1-what-is-ldk)
2. [Required Interfaces](#2-required-interfaces)
3. [How Our Existing Stack Helps](#3-how-our-existing-stack-helps)
4. [Language Bindings](#4-language-bindings)
5. [ldk-node vs Raw LDK](#5-ldk-node-vs-raw-ldk)
6. [Lightning Features We'd Get](#6-lightning-features-wed-get)
7. [Resource Requirements](#7-resource-requirements)
8. [Existing Examples](#8-existing-examples)
9. [Integration Plan](#9-integration-plan)
10. [Challenges and Risks](#10-challenges-and-risks)

---

## 1. What is LDK

LDK (Lightning Development Kit) is an **open-source Lightning Network implementation written in Rust**, maintained by Spiral (a Block/Square initiative). Unlike CLN or LND which are standalone daemons you run as separate programs, LDK is a **library**. it compiles directly into your app.

### What LDK Provides (you get this for free)
- **Lightning protocol state machine**. all the complex rules for opening/closing channels, routing payments, handling HTLCs (the building blocks of Lightning payments)
- **On-chain punishment logic**. if a channel partner tries to cheat by broadcasting an old state, LDK handles the response
- **Routing/pathfinding**. finding a path through the Lightning network to reach the recipient
- **Invoice creation and parsing**. BOLT11 (classic) and BOLT12 (offers, the newer format)
- **Channel management**. the full lifecycle from open → operate → close
- **Peer-to-peer messaging**. the Lightning gossip protocol

### What YOU Must Provide (the integrator's job)
- **Blockchain data source**. LDK needs to know what's happening on-chain (new blocks, confirmed transactions)
- **Transaction broadcaster**. when LDK needs to put a transaction on-chain, it calls your code to do it
- **Key management**. where private keys live and how signing happens
- **Persistence/storage**. channel state must be saved reliably (losing this = losing funds)
- **Fee estimation**. what fee rate to use for on-chain transactions
- **Network graph storage**. the map of the Lightning network for routing
- **Logger**. where log messages go

This modular design is LDK's superpower: you plug in your own implementations of each piece, or use the defaults they provide.

---

## 2. Required Interfaces

Here's each interface LDK needs, in plain terms:

### Chain Data Provider (`chain::Listen` or `chain::Confirm`)
- **What it does:** Feeds LDK information about new blocks and transactions
- **Two modes:**
  - `Listen`. you give it full blocks as they arrive (works great with a local bitcoind)
  - `Confirm`. you tell it about specific confirmed/unconfirmed transactions (works with Electrum/Esplora)
- **Our case:** We have local bitcoind AND BWT Electrum. we can use either approach

### Transaction Broadcaster (`BroadcasterInterface`)
- **What it does:** When LDK creates a transaction (channel open, close, justice tx), it hands it to your broadcaster to send to the network
- **Implementation:** Call bitcoind's `sendrawtransaction` RPC

### Key Manager (`SignerProvider` / `NodeSigner` / `EntropySource`)
- **What it does:** Manages all cryptographic keys. node identity, channel keys, on-chain keys
- **Default available:** LDK provides `KeysManager` which derives everything from a single 32-byte seed
- **Custom option:** You could use a hardware security module or Android Keystore for extra security

### Persister (`KVStore`)
- **What it does:** Saves channel state, network graph, and scorer data
- **Critical:** If you lose channel state after a payment, you could lose funds. This must be rock-solid.
- **Options:** SQLite database, filesystem, or custom (cloud backup, etc.)

### Fee Estimator (`FeeEstimator`)
- **What it does:** Returns fee rates (in sat/kw) for different confirmation targets
- **Implementation:** Call bitcoind's `estimatesmartfee` RPC

### Network Graph (`NetworkGraph`)
- **What it does:** Stores the map of all Lightning channels and nodes for routing
- **Two sync methods:**
  - **P2P gossip**. learn about the network from peers (bandwidth-heavy, slow on mobile)
  - **Rapid Gossip Sync (RGS)**. download a compressed snapshot from a server (much better for mobile)
- **Our case:** RGS is the clear winner for a phone

### Logger (`Logger`)
- **What it does:** Receives log messages from LDK internals
- **Implementation:** Route to Android's logcat or a file

---

## 3. How Our Existing Stack Helps

This is where Pocket Node has a **massive advantage** over typical mobile Lightning wallets. Most mobile wallets must rely on external servers. we have everything locally.

| LDK Need | Our Stack | How It Helps |
|---|---|---|
| **Chain data** | Bitcoin Core v28.1 (local) | Direct RPC calls: `getblock`, `getbestblockhash`, `getblockchaininfo`. No trust in third parties. |
| **Tx broadcasting** | Bitcoin Core (local) | `sendrawtransaction` RPC. instant, private, no external server needed |
| **Fee estimation** | Bitcoin Core (local) | `estimatesmartfee` RPC. based on our own mempool, not someone else's guess |
| **Block notifications** | Bitcoin Core ZMQ or polling | ZMQ `hashblock`/`rawtx` notifications, or poll `getbestblockhash` |
| **Electrum data** | BWT Electrum server | Could use Electrum protocol for `Confirm`-style chain monitoring (transaction-level rather than block-level) |

### What We Still Need to Build
- **Persistence layer**. SQLite database on Android for channel state
- **Key management**. generate and securely store a Lightning seed (could derive from existing wallet seed)
- **Networking**. TCP connections to Lightning peers (Android can do this, but needs careful handling of background execution)
- **Gossip/routing**. Rapid Gossip Sync is recommended even though we have bandwidth for P2P, because it's dramatically faster to bootstrap

### The Big Win
Most LDK mobile apps use Esplora (a remote block explorer API) as their chain source because they don't have a local node. We skip that entirely. **our chain data is local, private, and trust-minimised**. This is the gold standard for a Lightning node.

---

## 4. Language Bindings

LDK's core is Rust. Here are the paths to get it into our Kotlin/Android app:

### Option A: ldk-node Kotlin bindings (RECOMMENDED)
- **Package:** `org.lightningdevkit:ldk-node-android` on Maven Central
- **How it works:** Uses [UniFFI](https://github.com/mozilla/uniffi-rs/) (Mozilla's tool for generating language bindings from Rust) to create native Kotlin APIs
- **What you get:** A `.aar` file containing the native `.so` libraries for ARM64 (and other architectures) plus Kotlin wrapper classes
- **Gradle:**
  ```kotlin
  dependencies {
      implementation("org.lightningdevkit:ldk-node-android:LATEST_VERSION")
  }
  ```
- **Maturity:** Actively maintained, published to Maven Central, used in production apps

### Option B: Raw LDK Java/Kotlin bindings
- **Package:** `org.lightningdevkit:ldk-java` 
- **How it works:** JNI bindings generated from the Rust code, giving you access to all low-level LDK interfaces
- **What you get:** Full control over every component, but you write a LOT more code
- **Trade-off:** Maximum flexibility, maximum effort

### Option C: Rust directly via JNI
- Write the Lightning logic in Rust, expose a custom JNI interface to Kotlin
- Most complex, only makes sense if you're already deep in Rust

### Recommendation
**Option A (ldk-node-android)** is the clear winner for us. It gives us a high-level API in Kotlin, handles all the internal wiring, and already supports Bitcoin Core RPC as a chain source.

---

## 5. ldk-node vs Raw LDK

This is the key architectural decision.

### ldk-node (Higher-Level Wrapper)

**What it is:** A ready-to-go Lightning node library built on top of raw LDK + BDK (Bitcoin Dev Kit). It makes all the "boring" decisions for you and exposes a simple API.

**Pros:**
- ~50 lines of code to get a working Lightning node
- Built-in on-chain wallet (via BDK)
- Built-in persistence (SQLite or filesystem)
- Built-in key management
- Handles background tasks automatically (channel monitoring, peer reconnection)
- **Supports Bitcoin Core RPC as a chain source**. perfect for us
- Supports Electrum and Esplora as chain sources too
- Official Kotlin/Android bindings published to Maven Central
- Rapid Gossip Sync built in
- BOLT11 and BOLT12 (offers) payment support
- Liquidity management via LSPS2 (Lightning Service Provider protocol)

**Cons:**
- Less control over individual components
- Comes with its own on-chain wallet (BDK). we already have wallet functionality, so there's potential overlap
- Some design choices are opinionated (storage format, key derivation)

**Example code (Kotlin):**
```kotlin
val builder = Builder()
builder.setNetwork(Network.SIGNET)
builder.setChainSourceBitcoindRpc(
    "http://127.0.0.1:8332",
    "rpc_user",
    "rpc_password"
)
builder.setGossipSourceRgs(
    "https://rapidsync.lightningdevkit.org/testnet/snapshot"
)

val node = builder.build()
node.start()

// Open a channel
node.openChannel(nodeId, nodeAddr, 100000u, null, null)

// Pay an invoice
node.bolt11Payment().send(invoice, null)
```

### Raw LDK (Low-Level Library)

**What it is:** The core Lightning protocol implementation. You wire everything together yourself.

**Pros:**
- Total control over every component
- Can reuse our existing wallet instead of BDK's
- Can implement custom persistence, custom key management
- Smaller binary if you only include what you need

**Cons:**
- **Dramatically more code**. the ldk-sample (reference implementation) is thousands of lines just for basic functionality
- Must implement and maintain: chain monitoring, peer management, background processing, channel persistence, routing, event handling
- Must handle all the edge cases yourself
- Higher risk of bugs in integration code (and bugs = lost funds in Lightning)

### Verdict: Use ldk-node

For Pocket Node, **ldk-node is the right choice**. Here's why:

1. **We're adding Lightning to an existing app, not building a Lightning company.** We want it to work, not to be a research project.
2. **ldk-node already supports bitcoind RPC**. our main advantage (local node) works out of the box.
3. **The Kotlin bindings are production-ready**. no JNI wrangling needed.
4. **The BDK wallet overlap is manageable**. ldk-node's wallet handles Lightning-specific funds (channel opens/closes), while our existing wallet handles regular on-chain transactions. They can share the same seed.
5. **Development time:** weeks with ldk-node vs months with raw LDK.

---

## 6. Lightning Features We'd Get

### Core Features (available now in ldk-node)

| Feature | Description |
|---|---|
| **Channel management** | Open, close (cooperative & force), and manage Lightning channels |
| **BOLT11 payments** | Send and receive standard Lightning invoices (the QR codes everyone uses today) |
| **BOLT12 offers** | The next-gen invoice format. reusable payment codes, no expiry, better privacy |
| **Spontaneous payments** | Send payments without an invoice (keysend) |
| **Multi-path payments** | Split a payment across multiple channels for larger amounts |
| **On-chain wallet** | Integrated Bitcoin wallet for funding channels and receiving channel closes |
| **HODL invoices** | Hold incoming payments until you decide to accept or reject them |

### Routing & Network
- **Rapid Gossip Sync**. download the network graph quickly instead of learning it from peers over hours
- **Probabilistic routing**. LDK uses a "scorer" that learns which routes work well over time
- **Private channels**. channels that aren't announced to the network (better privacy)

### Liquidity Features
- **LSPS2 (JIT channels)**. request inbound liquidity from a Lightning Service Provider. When someone tries to pay you and you don't have enough inbound capacity, an LSP can open a channel to you on-the-fly.
- **Dual-funded channels**. both sides contribute funds when opening a channel (if the peer supports it)

### What This Means Practically
Brad could:
1. Fund a Lightning channel from the Pocket Node's on-chain wallet
2. Pay Lightning invoices (buy stuff, pay for services)
3. Receive Lightning payments (generate invoices, share with others)
4. Use BOLT12 offers for a static "pay me" address
5. Route payments through the Lightning network

---

## 7. Resource Requirements

### Memory (RAM)
- **LDK node process:** ~30-80 MB depending on network graph size and number of channels
- **Network graph (full):** ~50-100 MB in memory for mainnet (tens of thousands of channels)
- **With Rapid Gossip Sync:** Graph is loaded from a compressed snapshot, same memory but faster startup
- **Per channel:** ~1-2 KB of state per channel (negligible)
- **Total realistic estimate:** ~50-100 MB additional RAM on top of what bitcoind already uses

### Storage
- **Channel state database:** <1 MB for typical usage (even with dozens of channels)
- **Network graph on disk:** ~50-100 MB (SQLite or file)
- **Scorer data:** ~1-5 MB (routing probability data)
- **Total:** ~100-200 MB additional disk space

### CPU
- **Startup:** Brief spike loading graph + syncing chain state
- **Steady state:** Minimal. Lightning operations are cryptographic but lightweight
- **Pathfinding:** Brief spike when computing payment routes (milliseconds)
- **No mining or heavy computation**. Lightning is lightweight by design

### Network/Bandwidth
- **Rapid Gossip Sync:** ~5-10 MB download periodically (compressed graph updates)
- **Peer connections:** Minimal bandwidth for channel operations
- **P2P gossip (if used):** Can be 50-100+ MB/month. this is why we'd use RGS instead

### Compared to What We Already Run
Bitcoin Core v28.1 on the Pixel already uses:
- ~500+ MB RAM for bitcoind
- ~600+ GB disk for the blockchain
- Significant CPU for initial sync

**Lightning adds maybe 10-15% more RAM and trivial disk space.** The Pixel can handle it.

---

## 8. Existing Examples

### Apps Using LDK on Android

| App | Description | Relevance |
|---|---|---|
| **LDK Node Demo** | Official demo app from LDK team | Direct reference implementation for Android |
| **Monday Wallet** | Mobile Lightning wallet using ldk-node | Shows production ldk-node on Android |
| **Mutiny Wallet** | Used LDK (via WASM, but their mobile app used native LDK) | Demonstrated LDK works for consumer wallets (note: Mutiny shut down in 2024) |
| **Cash App** | Block/Square's payment app uses LDK for Lightning | Proves LDK at massive scale (though not open-source) |
| **Cake Wallet** | Added Lightning support via LDK | Shows LDK integration into an existing wallet app |
| **Blue Wallet** | Uses LDK for self-custodial Lightning | Popular wallet, validates the approach |

### Key Repositories

- **[ldk-node](https://github.com/lightningdevkit/ldk-node)**. the library we'd use, includes Kotlin bindings
- **[ldk-sample](https://github.com/lightningdevkit/ldk-sample)**. reference Rust implementation showing raw LDK usage with bitcoind
- **[rust-lightning](https://github.com/lightningdevkit/rust-lightning)**. the core protocol library underneath ldk-node

### Relevant to Our Use Case
The ldk-sample project is especially interesting because it **uses Bitcoin Core RPC** for chain data. exactly like we would. It demonstrates the `bitcoind_rpc` chain source that ldk-node also supports.

---

## 9. Integration Plan

### Phase 0: Research & Prototype (1-2 weeks)
- [x] Research LDK options (this document)
- [ ] Set up a testnet/signet environment on the Pixel
- [ ] Build a minimal ldk-node "hello world" in Kotlin that connects to the local bitcoind
- [ ] Verify ARM64 native library works on GrapheneOS

### Phase 1: Core Lightning Module (2-4 weeks)
- [ ] Add `ldk-node-android` dependency to the app
- [ ] Create `LightningService` class that:
  - Configures ldk-node Builder with local bitcoind RPC URL
  - Sets up Rapid Gossip Sync
  - Manages node lifecycle (start/stop with the app)
  - Handles persistence directory on Android storage
- [ ] Generate/derive Lightning seed (ideally from existing wallet mnemonic using a separate derivation path)
- [ ] Implement basic event handling (channel opened, payment received, etc.)

### Phase 2: Channel Management UI (2-3 weeks)
- [ ] "Open Channel" screen. enter peer node ID, amount, confirm
- [ ] Channel list view. show open channels, capacity, balance
- [ ] "Close Channel" button (cooperative close)
- [ ] Channel backup/export functionality

### Phase 3: Payments (2-3 weeks)
- [ ] "Pay Invoice". scan/paste a BOLT11 invoice, confirm, send
- [ ] "Receive Payment". generate BOLT11 invoice with amount, show QR code
- [ ] Payment history view
- [ ] BOLT12 offers support (static payment codes)

### Phase 4: Liquidity & UX (2-4 weeks)
- [ ] LSP integration for inbound liquidity (LSPS2)
- [ ] Automatic channel management (suggest opens/closes based on usage)
- [ ] Fee management UI
- [ ] Watchtower integration (for safety when the phone is offline)

### Phase 5: Hardening (ongoing)
- [ ] Channel state backup to external storage / cloud
- [ ] Handle Android lifecycle properly (background execution, Doze mode)
- [ ] Force-close recovery procedures
- [ ] Testing with real mainnet funds (small amounts first!)

### Architecture in the App

```
┌──────────────────────────────────────────┐
│           Pocket Node Android App         │
│                                           │
│  ┌─────────────┐    ┌──────────────────┐ │
│  │  On-chain    │    │   Lightning      │ │
│  │  Wallet UI   │    │   Wallet UI      │ │
│  └──────┬──────┘    └────────┬─────────┘ │
│         │                    │            │
│  ┌──────┴──────┐    ┌───────┴──────────┐ │
│  │  Existing    │    │   ldk-node       │ │
│  │  Wallet      │    │   (Kotlin)       │ │
│  │  Logic       │    │                  │ │
│  └──────┬──────┘    └───────┬──────────┘ │
│         │                    │            │
│         │         ┌─────────┴──────────┐ │
│         │         │  ldk-node-android   │ │
│         │         │  (native .so)       │ │
│         │         └─────────┬──────────┘ │
│         │                    │            │
│  ┌──────┴────────────────────┴──────────┐│
│  │        Bitcoin Core v28.1 (RPC)       ││
│  │        + BWT Electrum Server          ││
│  └───────────────────────────────────────┘│
└──────────────────────────────────────────┘
```

---

## 10. Challenges and Risks

### 🔴 Critical Challenges

#### Channel State Loss = Lost Funds
- If the app data is wiped or the database corrupts and you lose channel state, your channel funds are at risk
- **Mitigation:** Regular encrypted backups of channel state to external storage. ldk-node uses SQLite which is robust, but we need backup procedures.
- **Static Channel Backups (SCB):** LDK supports these. they allow recovery by force-closing all channels, getting funds back on-chain. Not ideal but prevents total loss.

#### Mobile Uptime & Background Execution
- Lightning nodes ideally run 24/7. A phone doesn't.
- If your channel partner force-closes while you're offline, you have a time window (typically 144 blocks = ~1 day) to respond
- **Android kills background processes aggressively**, especially on GrapheneOS
- **Mitigation:** 
  - Use a foreground service with notification to keep the process alive
  - Implement a watchtower service (see below)
  - Accept that mobile Lightning works best for spending, not routing/forwarding

#### Watchtowers
- A watchtower is a service that watches the blockchain on your behalf while you're offline
- If your channel partner broadcasts an old state, the watchtower can submit the punishment transaction for you
- **Options:** Run your own (on a server), use a third-party watchtower service
- **ldk-node status:** Watchtower support is not built into ldk-node yet. this would need external tooling

### 🟡 Significant Challenges

#### Inbound Liquidity
- To **receive** Lightning payments, someone else needs to have funds in a channel pointed at you
- Simply opening a channel only gives you **outbound** capacity (you can send but not receive)
- **Solutions:**
  - Use an LSP (Lightning Service Provider) that opens channels to you. ldk-node supports LSPS2 for this
  - Open channels with well-connected nodes
  - Submarine swaps (on-chain → Lightning)
  - Loop In services

#### Network Connectivity
- Lightning peers need to reach your node. On mobile behind NAT, you can't accept incoming connections easily
- **Mitigation:** You connect outbound to peers. Most Lightning wallets work this way. Not ideal for routing, but fine for a personal wallet.

#### Fee Management
- Channel opens/closes require on-chain transactions with fees
- During high-fee periods, force-closes can be expensive
- Anchor outputs (supported by LDK) help by allowing fee bumping after broadcast

#### Graph Sync on Mobile
- The Lightning network graph is large. On mobile, we'd use Rapid Gossip Sync which downloads compressed snapshots
- This means trusting the RGS server to give you an accurate graph (though you can verify signatures)
- **Alternative:** Since we have a full node, we could potentially do P2P gossip, but it's bandwidth-heavy

### 🟢 Manageable Challenges

#### Dual Wallet Situation
- ldk-node comes with its own BDK-based on-chain wallet
- Our app already has an on-chain wallet
- **Approach:** Use ldk-node's wallet for Lightning-specific on-chain operations (channel opens/closes) and our existing wallet for general use. Fund the Lightning wallet by sending to its address from the main wallet.

#### Binary Size
- The ldk-node native library adds ~10-20 MB to the APK (ARM64)
- Manageable for our use case

#### Testing
- Lightning has complex edge cases (force closes during payments, concurrent HTLCs, etc.)
- Start on signet/testnet, graduate to small mainnet amounts
- LDK is production-tested (Cash App, etc.) so the library itself is solid. our integration is the risk

---

## Summary & Recommendation

### Use ldk-node with bitcoind RPC chain source

**Why:**
1. **Simplest path to Lightning**. high-level Kotlin API, published on Maven Central
2. **Leverages our unique advantage**. local bitcoind RPC = no external trust, private, fast
3. **Production-proven**. LDK powers Cash App's Lightning at scale
4. **Active development**. ldk-node is Spiral's priority project, actively maintained
5. **Good mobile story**. Rapid Gossip Sync, LSPS2 liquidity, designed for resource-constrained environments

**Key config for our app:**
```kotlin
builder.setChainSourceBitcoindRpc(
    "http://127.0.0.1:8332",  // local bitcoind
    rpcUser,
    rpcPassword
)
builder.setGossipSourceRgs(
    "https://rapidsync.lightningdevkit.org/snapshot"
)
builder.setStorageDirPath(context.filesDir.resolve("lightning").absolutePath)
```

**Estimated effort:** 8-14 weeks for a functional Lightning wallet integrated into Pocket Node, starting from Phase 1.

**Biggest risk:** Mobile uptime and channel state safety. Mitigated by watchtowers, SCB backups, and using Lightning primarily for spending (not as a routing node).

---

## Key Links

- **ldk-node repo:** <https://github.com/lightningdevkit/ldk-node>
- **ldk-node docs:** <https://docs.rs/ldk-node>
- **ldk-node Android package:** <https://central.sonatype.com/artifact/org.lightningdevkit/ldk-node-android>
- **LDK site:** <https://lightningdevkit.org>
- **rust-lightning (core):** <https://github.com/lightningdevkit/rust-lightning>
- **LDK Discord:** <https://discord.gg/5AcknnMfBw>
- **ldk-sample (reference):** <https://github.com/lightningdevkit/ldk-sample>
