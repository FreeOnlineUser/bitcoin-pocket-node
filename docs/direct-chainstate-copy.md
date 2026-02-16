# Direct Chainstate Copy

**The fastest way to bootstrap a Bitcoin full node on a phone: ~20 minutes from zero to chain tip.**

## Overview

Instead of downloading a UTXO snapshot and using AssumeUTXO (which requires ~3 hours of background validation), we copy the essential database files directly from an existing Bitcoin node. The phone starts as a fully-validated node at the chain tip — no background validation, no catch-up sync.

## Why It Works

A Bitcoin full node's state lives in a few key databases:

| Directory | Purpose | Size (mainnet, pruned) |
|-----------|---------|----------------------|
| `chainstate/` | The UTXO set (LevelDB) — every unspent output | ~12 GB |
| `blocks/index/` | Block metadata index (LevelDB) — where each block lives on disk | ~2 GB |
| `blocks/xor.dat` | 8-byte XOR key for block file obfuscation (Knots/Core 28+) | 8 bytes |
| `blocks/blkNNNNN.dat` | Actual block data (only tip blocks needed) | ~130 MB each |
| `blocks/revNNNNN.dat` | Undo data for reorgs (matches blk files) | ~20 MB each |

Copy these to the phone, and bitcoind sees a fully-synced node. No `loadtxoutset`, no background IBD.

## What Was Tried (and Failed)

### Attempt 1: Chainstate Only ❌
Copied just `chainstate/` without `blocks/index/`. bitcoind refused to start — it needs the block index to know where blocks are stored.

### Attempt 2: chainstate_snapshot Directory Swap ❌
Tried renaming the chainstate to `chainstate_snapshot` (the directory AssumeUTXO creates). bitcoind treats this as a background-validation state, not a primary chainstate.

### Attempt 3: Chainstate + Block Index ❌ (partial)
Copied `chainstate/` and `blocks/index/` but no block files. bitcoind started but complained about missing blk files — the index references them.

### Attempt 4: Chainstate + Index + Stub Files ✅
The winning combination. The block index references ~5000+ blk/rev files, but we only need real data for the tip blocks. Create empty stub files for everything else.

## The Knots XOR Discovery

Bitcoin Knots 29.2 (and Core 28+ via [PR #28052](https://github.com/bitcoin/bitcoin/pull/28052)) obfuscates block files using an 8-byte XOR key stored in `blocks/xor.dat`.

```
# On Umbrel (Knots 29.2):
$ xxd /home/umbrel/umbrel/app-data/bitcoin-knots/data/bitcoin/blocks/xor.dat
00000000: 74fa 298a 07b8 0e7b                      t.)....{
```

**Key:** `74fa298a07b80e7b`

This key is applied cyclically to all bytes in blk*.dat and rev*.dat files. Bitcoin Core v28.1 reads `xor.dat` natively — no patching needed. Without this file, block data is garbage and the node can't validate anything.

## Stub File Requirement

The block index references every blk/rev file ever created (~5000+ files on a fully-synced node). On startup, bitcoind checks that all referenced files exist. They don't need real data — just existence.

```bash
# Create stub blk/rev files (blk00000.dat through blk05100.dat)
for i in $(seq 0 5100); do
  touch blocks/blk$(printf "%05d" $i).dat
  touch blocks/rev$(printf "%05d" $i).dat
done
```

**Important:** Set `checklevel=0` in `bitcoin.conf` so bitcoind doesn't try to verify block data in the stub files (they're empty).

### Pruning Behavior
After startup, bitcoind detects it has "blocks" it doesn't need (the stubs + old real blocks) and begins pruning. With 5000+ stub files, this takes ~15 minutes on a Pixel 7 Pro. This is optimizable — we could create fewer stubs or pre-set the pruning state.

## Step-by-Step Manual Process

### 1. Stop the source node (for consistent database state)

```bash
# Umbrel with Knots:
ssh umbrel@10.0.1.127
docker stop bitcoin-knots_app_1
```

### 2. Archive the required files

```bash
BITCOIN_DIR="/home/umbrel/umbrel/app-data/bitcoin-knots/data/bitcoin"

# Chainstate (~12 GB)
tar -cf /tmp/chainstate.tar -C "$BITCOIN_DIR" chainstate/

# Block index (~2 GB)
tar -cf /tmp/blocks-index.tar -C "$BITCOIN_DIR/blocks" index/

# XOR key (8 bytes)
cp "$BITCOIN_DIR/blocks/xor.dat" /tmp/xor.dat

# Tip block files (latest blk/rev pair)
# Find the highest-numbered blk file:
ls -1 "$BITCOIN_DIR/blocks"/blk*.dat | tail -1
# e.g., blk05087.dat — copy that and its rev pair
cp "$BITCOIN_DIR/blocks/blk05087.dat" /tmp/
cp "$BITCOIN_DIR/blocks/rev05087.dat" /tmp/
```

### 3. Restart the source node

```bash
docker start bitcoin-knots_app_1
# Also restart the proxy if needed:
docker start bitcoin-knots_app_proxy_1
```

### 4. Transfer to phone

```bash
# Push via ADB (~15 GB total, ~5 min over USB)
adb push /tmp/chainstate.tar /sdcard/Download/
adb push /tmp/blocks-index.tar /sdcard/Download/
adb push /tmp/xor.dat /sdcard/Download/
adb push /tmp/blk05087.dat /sdcard/Download/
adb push /tmp/rev05087.dat /sdcard/Download/
```

### 5. Deploy on phone

Extract into the bitcoind data directory, create stub files, and configure:

```
bitcoin.conf:
  prune=2048
  checklevel=0
```

### 6. Start bitcoind

Node starts at chain tip. Connects to peers, begins syncing any new blocks since the archive was created. Full node operational in minutes.

## How This Will Be Automated

The app will offer "Sync from your node" with two sub-options:

1. **Direct Copy** (fastest, ~20 min) — SSH to home node, stop bitcoind briefly, archive + transfer chainstate/index/xor/tip blocks, create stubs, start
2. **AssumeUTXO** (trustless, ~3 hours) — existing flow via `dumptxoutset rollback` + `loadtxoutset`

The direct copy flow will:
- Detect available space and estimate transfer size
- Handle docker stop/start automatically
- Create stub files programmatically
- Set appropriate bitcoin.conf options
- Monitor startup and pruning progress

## Comparison: Direct Copy vs AssumeUTXO

| | Direct Chainstate Copy | AssumeUTXO |
|---|---|---|
| **Total time** | ~20 minutes | ~3 hours |
| **Transfer size** | ~15 GB (chainstate + index + tip blocks) | ~9 GB (UTXO snapshot) |
| **Background validation** | None needed | Runs from genesis (~days to complete) |
| **Node state after setup** | Full node at tip, fully validated | Full node at tip, background-validating |
| **Trust model** | Trusts source node's chainstate | Trustless — block hash verified against binary |
| **Source requirements** | Any Bitcoin node (Core or Knots, full or pruned) | Node capable of `dumptxoutset rollback` |
| **Downtime on source** | ~2 min (stop for consistent archive) | ~55 min (rollback + dump) |
| **Requires source node stop** | Yes (briefly) | No |
| **Works with Knots** | ✅ (xor.dat copied) | ✅ |
| **Works with Core** | ✅ | ✅ |

## Trust Considerations

Direct chainstate copy trusts that the source node has a valid chainstate. If your source is your own home node that you've been running, this is equivalent trust to running the node yourself — you're just moving the validated state to a new device.

For users who want zero-trust bootstrapping, AssumeUTXO remains available — the snapshot hash is compiled into the Bitcoin Core binary itself.
