# Snapshot Testing Log

## Test 1: dumptxoutset with "latest" mode ❌ (2026-02-14)

**Command:** `dumptxoutset utxo-910000.dat latest`
**Result:** Dumped at current tip (block 936,505) not at AssumeUTXO height
**File size:** 8.8 GB

**loadtxoutset response:**
```
error code: -32603
Unable to load UTXO snapshot: assumeutxo block hash in snapshot metadata not recognized
```

**Root cause:** `"latest"` dumps at whatever block the node is currently at. The snapshot must be at one of the hardcoded AssumeUTXO heights (840000, 880000, or 910000).

**Fix:** Use `"rollback"` mode: `dumptxoutset utxo-910000.dat rollback`

## Test 2: dumptxoutset with "rollback" mode ✅ (2026-02-14)

**Command:** `dumptxoutset utxo-910000-correct.dat rollback`
**Result:** Successfully dumped at height 910,000
**File size:** 9.0 GB
**Block hash:** `0000000000000000000108970acb9522ffd516eae17acddcb1bd16469194a821`
**Rollback time:** ~55 minutes (undoing ~26,500 blocks)

## Test 3: loadtxoutset on Pixel 7 Pro ✅ (2026-02-14)

**Result:** SUCCESS. 167,821,142 UTXOs loaded
```json
{
  "coins_loaded": 167821142,
  "tip_hash": "0000000000000000000108970acb9522ffd516eae17acddcb1bd16469194a821",
  "base_height": 910000
}
```

**Timeline:**
- Snapshot deserialization + LevelDB write: ~15 minutes
- UTXO hash verification: ~10 minutes
- Total loadtxoutset time: ~25 minutes

**Post-load state:**
- Syncing forward from block 910,000
- Background validation running from genesis
- Phone stays cool

## Test 4: Wrong snapshot loaded by app ❌ (2026-02-15)

**Problem:** App downloaded old `utxo-910000.dat` (8.8 GB, from Test 1) instead of correct one
**Error:** `assumeutxo block hash in snapshot metadata not recognized (hash: ...13b045599e022...)`
**Block in snapshot:** 936,505 (confirmed via mempool.space API)

**Root cause:** Old file was at `/home/pocketnode/snapshots/utxo-910000.dat`, correct file was at `utxo-910000-correct.dat`

**Fix:**
1. Renamed files on Umbrel so correct snapshot is at expected path
2. Added snapshot block hash validation to app. reads header, compares against expected hash
3. Auto-deletes and re-downloads if hash doesn't match

## Test 5: End-to-end from app with validation ✅ (2026-02-15)

**Flow:** App → SFTP download (9 GB, ~5 min LAN) → loadtxoutset → success
**Snapshot validated** before loading (block hash matches expected)
**loadtxoutset completed**. `chainstate_snapshot` directory created, background validation running

## Key Learnings

1. **Always use `"rollback"` mode** for dumptxoutset. `"latest"` produces snapshots at arbitrary heights
2. **Validate snapshot block hash** before loading. read bytes 11-42 of snapshot file header
3. **The file name is meaningless**. it's the embedded metadata that matters
4. **Transfer times:** 9 GB over LAN SFTP ~5 min, USB ADB ~5 min
5. **loadtxoutset blocks for ~25 min**. must be non-blocking in app (fire and poll)
6. **`chainstate_snapshot` dir** appearing = loadtxoutset completed
7. **Don't aggressively delete `chainstate_snapshot`**. wait for RPC confirmation before treating as stale
8. **Android blocks cleartext HTTP**. need `network_security_config.xml` for localhost RPC
9. **Knots and Core produce identical block hashes**. same consensus, same chain

## Snapshot Header Format

```
Offset  Size  Description
0       4     Magic: "utxo"
4       1     CompactSize marker: 0xff
5       2     Version (LE): 0x0002
7       4     Network magic: 0xf9beb4d9 (mainnet)
11      32    Block hash (LE. reverse for display)
43+     ...   Coin count, UTXO data
```

Read block hash: `file[11:43]` reversed to hex = block hash string.

## Test 6: Direct Chainstate Copy ✅ (2026-02-16)

**The big one.** Bypass AssumeUTXO entirely. copy the chainstate database directly from Umbrel to the phone.

### Process

1. **Stop Umbrel bitcoind** for consistent database state:
   ```bash
   ssh umbrel@10.0.1.127
   docker stop bitcoin-knots_app_1
   ```

2. **Archive required files** from the Bitcoin data directory:
   ```bash
   BITCOIN_DIR="/home/umbrel/umbrel/app-data/bitcoin-knots/data/bitcoin"
   
   # Chainstate. the UTXO set (~12 GB)
   tar -cf /tmp/chainstate.tar -C "$BITCOIN_DIR" chainstate/
   
   # Block index. block metadata (~2 GB)
   tar -cf /tmp/blocks-index.tar -C "$BITCOIN_DIR/blocks" index/
   
   # XOR key. block file obfuscation (8 bytes)
   cp "$BITCOIN_DIR/blocks/xor.dat" /tmp/xor.dat
   
   # Tip block + undo files (latest blk/rev pair)
   cp "$BITCOIN_DIR/blocks/blk05087.dat" /tmp/
   cp "$BITCOIN_DIR/blocks/rev05087.dat" /tmp/
   ```

3. **Restart Umbrel:**
   ```bash
   docker start bitcoin-knots_app_1
   docker start bitcoin-knots_app_proxy_1
   ```

4. **Deploy to phone** via ADB with stub blk/rev files for all historical blocks

5. **Configure bitcoin.conf** with `checklevel=0` (stub files have no real block data)

6. **Start bitcoind**

### The XOR Discovery

Bitcoin Knots 29.2 obfuscates block files with an 8-byte XOR key in `blocks/xor.dat`:
```
Key: 74fa298a07b80e7b
```

Without this file, block data is unreadable. Bitcoin Core v28.1 supports reading `xor.dat` natively (PR #28052), so no special handling needed. just copy the file.

### Stub File Requirement

The block index references ~5000+ blk/rev files. On startup, bitcoind checks: "Are all referenced blk files present?" They don't need real data. empty files satisfy the check. But ~5000 stub files means the initial prune pass takes ~15 minutes.

### Result

🟠 **SUCCESS**. Height 936,822, 4 peers connected, at chain tip instantly.

- No AssumeUTXO, no background validation
- No `chainstate_snapshot` directory. this IS the primary chainstate
- Full node operational in ~20 minutes (archive + transfer + startup + pruning)
- Pruning completed after ~15 minutes (removing 5000+ stub files)
- Node continued syncing new blocks normally after pruning

### Key Insight

This is fundamentally different from AssumeUTXO. With direct copy:
- The phone has a **fully validated chainstate** from the source node
- There is **no background IBD**. nothing to catch up on
- The node is a **real full node at tip** from the moment it starts

See [docs/direct-chainstate-copy.md](direct-chainstate-copy.md) for the full technical writeup.

## Hardware Performance (Pixel 7 Pro, Tensor G2)

- bitcoind startup: ~2 seconds
- Peer connections: 2-4 peers within 30 seconds
- Header sync: ~936k headers in ~10 minutes
- loadtxoutset: ~25 minutes for 167M UTXOs
- RPC responsiveness: <100ms locally
- Thermal: phone stays cool throughout
