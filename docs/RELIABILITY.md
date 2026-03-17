# Reliability Settings

Key parameters and design decisions that make Pocket Node work reliably on a phone.

## LDK Safety Buffers (rust-lightning)

These are upstream constants that give us safety margins:

| Constant | Value | Meaning |
|----------|-------|---------|
| `CLTV_CLAIM_BUFFER` | 36 blocks (~6h) | Time reserved to get commitment + HTLC tx confirmed before timeout |
| `MAX_BLOCKS_FOR_CONF` | 18 blocks (~3h) | Max blocks expected for a single tx to confirm |
| `LATENCY_GRACE_PERIOD_BLOCKS` | 3 blocks (~30min) | Extra headroom for chain sync latency |
| `HTLC_FAIL_BACK_BUFFER` | 39 blocks (~6.5h) | When to fail back an HTLC we can't resolve |
| `MIN_CLTV_EXPIRY_DELTA` | 48 blocks (~8h) | Minimum CLTV delta for forwarded HTLCs |

These buffers mean we can safely be a few blocks behind without risk. The chain monitor compares HTLC timeouts against the current known block height. If our chain tip is stale, we lose margin, but 36 blocks gives ~6 hours of slack.

## LDK-Node Chain Polling (ldk-node)

| Setting | Value | Purpose |
|---------|-------|---------|
| `CHAIN_POLLING_INTERVAL_SECS` | 2 seconds | How often ldk-node polls bitcoind RPC for new blocks |

This runs continuously in the background. It talks to bitcoind via **RPC only** (localhost). Does not require bitcoind to have P2P peers. As long as bitcoind is running and has chain data, LDK stays synced.

## Two Independent Peer Networks

This is the most important thing to understand:

- **bitcoind peers (B:N)**: P2P connections for downloading blocks. Controlled by `setnetworkactive`. Only needed for getting new blocks.
- **LDK Lightning peers (L:N)**: TCP connections to Lightning nodes. Managed independently by LDK. Needed for routing payments.

On Low mode with network paused: B:0 but L:5. Lightning payments still work because LDK maintains its own connections. bitcoind serves chain data from its local database via RPC regardless of P2P peer count.

**Lightning gossip bandwidth**: ~5-10 KB/hour. Essentially free to keep alive.

## Power Mode Settings

| Setting | Value | Purpose |
|---------|-------|---------|
| Low burst interval | 15 min | Time between burst sync cycles on Low mode |
| Away burst interval | 60 min | Time between burst sync cycles on Away mode |
| Burst sync timeout | 2 min | Max duration per burst cycle |
| Peer wait timeout | 30 sec | Wait for bitcoind peer connections during burst |
| Burst peer wait | 30 sec | Wait for LDK to sync after bitcoind catches up |

## Startup Order (Critical)

```
1. setMode(savedMode, serviceScope)  ← sets activeScope FIRST
2. setRpc(rpcClient)                  ← checks activeScope, starts burst cycling
```

`setRpc()` must be called AFTER `setMode()`. If reversed, `activeScope` is null and `setRpc()` returns early, burst cycling never starts.

## Channel Management

| Setting | Value | Purpose |
|---------|-------|---------|
| `forceCloseAvoidanceMaxFeeSatoshis` | 1000 sats | Max fee willing to pay to avoid force close |
| Cooperative close feerate | 253 sat/kw (1 sat/vB) | Minimum relay feerate for coop close |
| Pending close sync interval | 5 min | How often to call `syncWallets()` when funds are pending close |

## Routing Fee Budget

| Scenario | Formula | Floor |
|----------|---------|-------|
| Default | 0.5% of amount | 50 sats |
| Bumped (retry) | 2% of amount | 500 sats |
| `waitForPayment` timeout | 300 seconds | 5 minutes max wait |
| Go Back button delay | 60 seconds | Prevents premature abandonment |

## Network Holds (Reference Counted)

Network holds use an `AtomicInteger` counter. Multiple holds stack; network only releases when count hits 0.

Holds are acquired for:
- **Channel opens**: Hold during the entire open flow
- **Lightning sends**: Hold on SendPaymentScreen entry
- **Lightning receives**: Hold for 5 min after generating invoice (ensures LDK peers stay connected)
- **Cooperative close**: Hold during close negotiation
- **Channel auto-enable**: Any channel operation on Low/Away mode

## bitcoind Configuration

| Setting | Value | Purpose |
|---------|-------|---------|
| `prune` | 2048 | Retain ~2GB of recent blocks, sufficient for any realistic reorg |
| RPC bind | 127.0.0.1:8332 | Localhost only |
| `fdsan_error_level` | 0 | Required for GrapheneOS (fdsan kills process otherwise) |

## Receive Payment Flow

1. Generate BOLT11 invoice
2. Hold network open (if not Max mode)
3. Hide keyboard, scroll to show QR
4. Poll balance every 2s for 5 min
5. On receive: scroll to "Payment received!", release hold

LDK peers (L:5) handle the actual HTLC routing. The network hold keeps bitcoind connected so `syncWallets()` has a current chain tip for HTLC timeout safety checks. The 36-block CLTV buffer means being a few blocks behind is safe for simple receives.

## Send Payment Flow

1. Hold network open
2. `captureRouteHops()` — placeholder route
3. `sendPayment()` — LDK routes via Lightning peers
4. `waitForPayment()` — poll every 500ms, capture real route data
5. On success: poll up to 3s more for route data (LDK may not have processed `PaymentPathSuccessful` yet)
6. Display: prioritize SUCCEEDED path over last-in-list (LDK ordering is not guaranteed)
7. Failed attempts shown as one-liners, deduplicated by hop path
