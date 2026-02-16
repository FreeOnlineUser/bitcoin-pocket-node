# Umbrel Integration Notes

## Node Details
- **Host:** 10.0.1.127 (Umbrel VM on Mac Mini — NOT a Raspberry Pi)
- **RPC Port:** 9332
- **Software:** Bitcoin Knots 29.2.0 (based on Core 29.x)
- **Chain:** mainnet, fully synced, unpruned (~820 GB)
- **Docker container:** `bitcoin-knots_app_1` (ghcr.io/retropex/umbrel-bitcoin-knots:1.2.5)
- **App proxy container:** `bitcoin-knots_app_proxy_1` — crashes if only bitcoind container restarted

## SFTP Account

A restricted `pocketnode` user is created by the app's setup wizard:
- **Username:** pocketnode
- **Auth:** password (stored in app SharedPreferences)
- **Shell:** `/usr/sbin/nologin` (SFTP-only)
- **Chroot:** `/home/pocketnode/`
- **Snapshots:** `/home/pocketnode/snapshots/`

The pocketnode user has **zero access** to the bitcoin data directory. Root-owned scripts handle file transfer.

## Snapshot Generation

The app generates snapshots via SSH using admin credentials:

```bash
# The copy script runs dumptxoutset inside the Docker container
docker exec bitcoin-knots_app_1 bitcoin-cli \
  -rpcport=43782 -rpcuser=umbrel -rpcpassword=<token> \
  -rpcclienttimeout=0 \
  dumptxoutset "/data/.bitcoin/utxo-910000.dat" rollback
```

**Important:**
- Use `"rollback"` not `"latest"` — latest dumps at chain tip, not AssumeUTXO height
- `-rpcclienttimeout=0` required — rollback + dump takes ~55 minutes
- Use `docker stop/start` not `docker compose` — compose requires env vars not available via SSH
- If only bitcoind container is restarted, also restart `bitcoin-knots_app_proxy_1`

## Snapshot Files on Umbrel

```
/home/pocketnode/snapshots/
├── utxo-910000.dat         # Correct snapshot (9.0 GB, height 910,000) ✅
├── utxo-910000-v2.dat      # Also correct (duplicate)
└── utxo-910000-wrong.dat   # OLD — from chain tip, not height 910k ❌
```

## App Flow: "Sync from Your Node"

1. **Try pocketnode first** — SFTP connect with saved credentials, check if `utxo-910000.dat` exists
2. **If snapshot exists** — skip to download (no admin creds needed)
3. **If not** — prompt for admin SSH credentials, generate snapshot via `dumptxoutset rollback`
4. **Download** — SFTP ~9 GB over LAN (~5 min at ~30 MB/s)
5. **Validate** — read snapshot header, verify block hash matches expected
6. **Load** — `loadtxoutset` RPC (~25 min, non-blocking with progress polling)

## Security Model

- Admin credentials **never saved** (username pre-filled for convenience)
- pocketnode credentials saved in SharedPreferences (shown in Node Access screen)
- pocketnode has no group access to bitcoin data dir
- Root-owned copy script bridges the gap
- User can remove all access from the app (requires admin creds to delete the account)

## Direct Chainstate Copy

The fastest bootstrap method — copy the node's database directly instead of using AssumeUTXO. This works with **any Bitcoin node** (Core or Knots, Umbrel or standalone), not just the Umbrel setup described here.

### Required Files

| File | Purpose | Size |
|------|---------|------|
| `chainstate/` | UTXO set (LevelDB) | ~12 GB |
| `blocks/index/` | Block metadata index (LevelDB) | ~2 GB |
| `blocks/xor.dat` | XOR obfuscation key (Knots/Core 28+) | 8 bytes |
| `blocks/blkNNNNN.dat` | Tip block data (latest file only) | ~130 MB |
| `blocks/revNNNNN.dat` | Tip undo data (matches blk file) | ~20 MB |

### XOR Block Obfuscation

Knots 29.2 (and Core 28+ via PR #28052) XOR-obfuscates all block files with an 8-byte key:

```
Location: $BITCOIN_DIR/blocks/xor.dat
Key (this Umbrel): 74fa298a07b80e7b
```

Core v28.1 reads this key natively. Without it, block data is garbled.

### Process

**Must stop bitcoind** for a consistent chainstate archive — LevelDB can't be safely copied while running.

```bash
# 1. Stop bitcoind (Umbrel Docker)
docker stop bitcoin-knots_app_1

# 2. Archive files
BITCOIN_DIR="/home/umbrel/umbrel/app-data/bitcoin-knots/data/bitcoin"

tar -cf /tmp/chainstate.tar -C "$BITCOIN_DIR" chainstate/
tar -cf /tmp/blocks-index.tar -C "$BITCOIN_DIR/blocks" index/
cp "$BITCOIN_DIR/blocks/xor.dat" /tmp/
cp "$BITCOIN_DIR/blocks/blk05087.dat" /tmp/   # latest tip block
cp "$BITCOIN_DIR/blocks/rev05087.dat" /tmp/

# 3. Restart immediately
docker start bitcoin-knots_app_1
docker start bitcoin-knots_app_proxy_1
```

Downtime: ~2 minutes (just long enough to create the tar archives).

### Snapshot Files on Umbrel (updated)

```
/home/pocketnode/snapshots/
├── utxo-910000.dat         # AssumeUTXO snapshot (9.0 GB) ✅
├── chainstate.tar          # Direct copy: UTXO set (~12 GB)
├── blocks-index.tar        # Direct copy: block index (~2 GB)
├── xor.dat                 # Block obfuscation key (8 bytes)
├── blk05087.dat            # Tip block data
└── rev05087.dat            # Tip undo data
```

### Non-Umbrel Nodes

This technique works with any Bitcoin node. Adjust the data directory path:

- **Bitcoin Core (default):** `~/.bitcoin/`
- **Bitcoin Knots (default):** `~/.bitcoin/`
- **Umbrel (Docker):** `/home/umbrel/umbrel/app-data/bitcoin-knots/data/bitcoin/`
- **Start9:** Check Embassy app data directory
- **myNode:** `/mnt/hdd/mynode/bitcoin/`

The key requirement is stopping bitcoind, copying the files, and restarting. The Docker vs systemd vs manual difference is just how you stop/start the process.

See [docs/direct-chainstate-copy.md](direct-chainstate-copy.md) for the full technical explanation and comparison with AssumeUTXO.

## Docker Notes

- Internal RPC port: 43782 (inside container)
- External RPC port: 9332 (mapped to host)
- Bitcoin data inside container: `/data/.bitcoin/`
- Bitcoin data on host: `/home/umbrel/umbrel/app-data/bitcoin-knots/data/bitcoin/`
