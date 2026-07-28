# LDK Upstream Contribution: Watchtower Justice TX API

## Status: Active on Forgejo — Option-2 foundation pushed, awaiting review

- **PR:** https://git.rust-bitcoin.org/lightningdevkit/rust-lightning/pulls/4453
  (rust-lightning moved PR development off GitHub to its self-hosted Forgejo;
  GitHub is now a mirror. Issues and the git tree stay on GitHub.)
- **Umbrella issue:** https://github.com/lightningdevkit/ldk-node/issues/813
- **Branch head:** `d964e3f80` (rebased onto current main)

## Background

TheBlueMatt responded to issue #813 on 2026-03-01 asking if we'd pick up
abandoned PR #2552 (improving the watchtower API in ChannelMonitor). Two
previous pickup attempts by other contributors had failed.

tnull (Elias Rohrer, ldk-node lead) asked us to coordinate with @enigbe who
also plans to work on watchtower support. We offered our draft as a starting
point and deferred on direction.

## Design (Matt's direction, 2026-07-05: "Option 2")

After review, Matt settled the design on a **retention-list** approach rather
than the earlier update-relative API:

- A **not-rotated retention list** of revoked counterparty commitments lives
  on the monitor (`FundingScope`, TLV-serialized, odd/optional so old
  monitors read empty). Per-funding-scope tracking supports splicing.
- **`get_pending_justice_txs`** is sourced from that list.
- **`mark_justice_persisted`** drains entries once a watchtower has stored them.
- **ChainMonitor gating** provides crash-safety (block new monitor updates
  while a justice write is in flight).
- A **`WatchtowerPersist` trait** with an async wrapper carries the
  operational complexity (backoff, multi-tower, hung towers) in the impl,
  keeping the core crash-safe and synchronous.

This superseded our earlier `sign_initial_justice_tx` /
`sign_justice_txs_from_update` update-relative API, which Matt moved away from.

## Current State

- Matt granted write access on the Forgejo instance 2026-07-27.
- The **4-commit rebased Option-2 foundation** was pushed 2026-07-28: make the
  persister storage idempotent; add the retention list on `FundingScope`; add
  `mark_justice_persisted` to drain it; source `get_pending_justice_txs` from
  the list plus a serialize/reload crash-recovery test. CI is green apart from
  one unrelated network-flaky DNS test in a crate we don't touch.
- **Follow-up commits, held pending review of the foundation:** the
  `WatchtowerPersist` trait itself, the ChainMonitor gating, and a retention
  on/off gate so non-watchtower nodes don't accumulate.

## Downstream (this app)

Pocket Node is the consumer of this API via `ldk-watchtower-client` (Rust-side,
retention ON). Until the `WatchtowerPersist` trait stage lands upstream, the
app keeps its own hand-rolled justice-tx tracking.

## Links

- PR (Forgejo): https://git.rust-bitcoin.org/lightningdevkit/rust-lightning/pulls/4453
- Umbrella issue: https://github.com/lightningdevkit/ldk-node/issues/813
- Original abandoned PR: https://github.com/lightningdevkit/rust-lightning/pull/2552
- Our ldk-node fork: https://github.com/FreeOnlineUser/ldk-node/tree/watchtower-bridge-v2
- Our watchtower client: https://github.com/FreeOnlineUser/ldk-watchtower-client
