# Competitive Multi-Path Payments with Gossip-Based Release

> **Status:** Distant future concept / research
> **Author:** Brad
> **Source:** [Claude artifact](https://claude.ai/public/artifacts/44367608-14a4-4735-8c4a-cc4eff90c22a)

## Problem

Lightning payments can hang indefinitely when an intermediate hop becomes unresponsive. One slow or malicious hop has effective veto power over payment completion, locking liquidity on all routes until on-chain CLTV timeouts expire (potentially hours to days). MPP makes this worse: all shards must succeed, so one hung shard blocks the entire payment.

## Proposed Solution

Three interlocking changes that make multi-path routing genuinely competitive:

1. **Merkle payment hashing** — racing routes, first shard wins (k-of-n threshold)
2. **PTLC adaptor signatures** — cryptographically clean threshold proofs
3. **`payment_settled` gossip** — losing routes release immediately on signal

Each component is backwards compatible and independently deployable.

## How It Works

Alice pays Dave 1000 sats, split into 4 shards of 250 sats (threshold: 1-of-4):

```
Shard 1: Alice → Bob → Carol → Dave (carries r1)
Shard 2: Alice → Eve → Frank → Dave (carries r2)
Shard 3: Alice → Grace → Hub → Dave (carries r3)
Shard 4: Alice → Ivan → Jane → Dave (carries r4)
```

Shard 2 arrives first. Dave settles it and broadcasts `payment_settled` gossip. All other shards release HTLCs within seconds. Alice's liquidity restored immediately.

## Key Properties

- **No more stuck payments:** losing paths race, gossip signal provides fast exit
- **k-of-n threshold:** not all shards need to succeed
- **HTLC release in seconds**, not CLTV expiry hours
- **Privacy preserved:** gossip reveals only that a payment hash completed, not amount/sender/receiver
- **Backwards compatible:** old nodes fall back to existing behavior

## Relationship to Pocket Node

This concept was born from experiencing the stuck payment problem firsthand: a 20k sat payment that our UI reported as "failed" (30s timeout) but actually succeeded via LDK's retry mechanism. The sender had no way to know the payment was still in-flight, and could have double-paid.

Competitive MPP would solve this at the protocol level rather than working around it in wallet UX.

## Dependencies

- BOLT 12 (TLV-extensible invoices) — deployed
- Taproot channels (BOLT PR #995) — for PTLC upgrade path
- Complementary to BOLT PR #1044 (Attributable Failures) and channel jamming proposals

## Open Questions

- Fee model for racing overhead (4x base fees if n=4, k=1)
- Gossip propagation latency in partitioned networks
- HTLC version ships first (minor colluding-node detection tradeoff until PTLCs)

## BOLT PR Breakdown

Maps to four independently mergeable PRs:
1. Merkle Payment Hashing (BOLT 12 TLV extension)
2. Shard-Aware Payment Construction (BOLT 11/implementation)
3. `payment_settled` Gossip Message (BOLT 7)
4. HTLC Release on Settlement Signal (BOLT 2)
