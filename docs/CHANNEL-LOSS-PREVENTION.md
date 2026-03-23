# Channel Loss Prevention

## What happened (March 20, 2026)

During a rebase of our ldk-node fork from `watchtower-bridge` to `watchtower-bridge-v2` (on upstream/main), the channel_manager state did not survive. LDK started with a fresh empty manager, found a monitor with no matching channel entry, and archived it as stale. The channel disappeared from the app.

Channel `0a962150881c` with ~90k sats was lost. The monitor was recovered from backup, injected back into SQLite, and a force-close commitment tx was broadcast. Funds are recovering via on-chain timelock.

## Root cause

LDK requires channel_manager and channel monitors to be consistent. If channel_manager is missing or reset, monitors become orphaned and get archived. The rebase replaced the ldk-node .so binary, which changed internal state handling. The existing SQLite was incompatible with the new binary's expectations, resulting in a fresh channel_manager being created on startup.

## What we got wrong initially

We initially blamed a "flat-file vs SQLite storage format change." This was incorrect. We forked ldk-node in February 2026, well after SQLite became the default (mid-2023). We were always on SQLite. The flat-file monitors only existed as backup copies we wrote ourselves. The actual cause was channel_manager state loss during the rebase.

## Protections now in place

### StateBackupManager (built March 20)
- Rolling SQLite state backups in 3 rotating slots
- Backs up: channel_manager, monitors, output_sweeper, wallet descriptors (~50 KB)
- Health check: blocks backup when state degrades (prevents overwriting good backups with empty state)
- Auto-restore on startup: if 0 monitors but backup has channels, injects before LDK build
- Triggered on: startup, channel events, every 5 minutes

### Legacy monitor fallback
- If all 3 SQLite backup slots are corrupted, falls back to flat-file monitor backups in `lightning_backup/monitors/`
- Derives SQLite key from channel_id filename (XOR trick for funding outpoint)

### Process rules
- Never rebuild the ldk-node .so or rebase without backing up the phone's SQLite first
- Always `adb pull` the ldk_node_data.sqlite before any LDK version change
- Test LDK version changes on a wallet with no live channels first
- Never run `adb uninstall` (wipes app data). Always `adb install -r`

## Still needed

### Static Channel Backup (SCB)
Store counterparty pubkey + funding outpoint for each open channel. On restore, connect to the peer and request force-close. Does not go stale because it contains no revocable state, just enough info to find your counterparty and get your money back.

- On channel open: save counterparty pubkey, funding txid/vout, node addresses
- On channel close: remove entry
- Export to external storage (Downloads, share sheet)
- Restore flow: read SCB, connect to each peer, request force-close
- Works with seed restore: seed recovers on-chain, SCB recovers channel funds

### Pre-update backup
Automatically snapshot state before any APK install via the in-app updater. If the update goes wrong, state can be restored from the pre-update snapshot.

### Backup verification
After restore, confirm channel_manager and monitors are consistent before starting LDK. Check that every monitor has a matching channel entry in the manager.

## What we should have done

The rebase onto upstream/main was unnecessary for the live wallet. We could have:
1. Bumped versionCode on the old fork and kept shipping
2. Developed the rebase on a separate branch, tested on an empty wallet
3. Only switched to the new codebase after closing the channel or verifying the migration

The phone doesn't care about git history. It only checks that versionCode goes up. The simplest path was right there: keep the old binary running, increment the version number, and take our time with the migration.

## Hard rules (from March 7 + March 20 failures)

1. Never auto-delete wallet/channel state based on time. Compare against external source of truth.
2. Never run `adb uninstall`. Always `adb install -r`.
3. Back up channel_manager AND monitors. Monitor alone cannot restore a channel.
4. Test destructive operations mentally first: "What data does this delete? Can it be recovered?"
5. Channel state backups go stale fast. SCB is the only safe external backup for Lightning channels.
6. If LDK starts with 0 channels but monitors exist, something went wrong. Do not proceed normally.

## Disaster Recovery: Full Seed + Channel Restore

If you lose app data completely (uninstall, device loss, data corruption):

### Prerequisites
- Your 24-word seed (mnemonic)
- Channel monitor backups (from `lightning_backup/monitors/` or StateBackupManager)
- The app installed on any phone with bitcoind synced

### Steps

1. **Restore seed:** Use the app's seed restore flow. This creates a fresh BDK wallet with standard BIP39 derivation and sets the wallet birthday from your backup.

2. **Wait for block replay:** The bitcoind chain source replays all blocks from the wallet birthday to the current tip. BDK discovers on-chain UTXOs at initially revealed addresses (receive and change paths). This takes a few minutes.

3. **Verify on-chain balance:** Confirm your on-chain funds appear. If UTXOs at higher address indices are missing, use BlueWallet (import same seed) to sweep them to the app's deposit address.

4. **Inject channel monitors:** If you have monitor backups, inject them into the SQLite database before starting Lightning:
   - Table: `ldk_node_data`
   - Namespace: `monitors` (primary), `""` (secondary)
   - Key: `{funding_txid_rpc_order}_{vout}`
   - Value: raw monitor binary

5. **Start Lightning:** LDK detects monitors with no matching channel_manager, force-closes the channels. The commitment transaction broadcasts.

6. **Wait for timelock:** Anchor channels have a ~144 block CSV delay (~24 hours). After confirmation + timelock, funds become spendable on-chain.

### Limitations
- **bitcoind chain source only scans addresses BDK has revealed.** If you used many addresses, some on-chain UTXOs may not appear. Import your seed into BlueWallet (or any BIP84 wallet) to find and sweep them.
- **Lightning balance is lost if you have no monitor backup.** The seed alone only recovers on-chain funds. Lightning channel funds require the channel monitor data.

## Update: March 23, 2026

**Monitor injection is deprecated.** Injecting stale monitors from a different LDK session causes native crashes ("Failed to process events" SIGABRT in libldk_node.so). The correct recovery path is now SCB (Static Channel Backup): connect to the peer, they detect the state mismatch, force-close from their side. Proven to work.

See `MOBILE_LIGHTNING_PLAYBOOK.md` for the current consolidated playbook.
