# Phone-to-Phone Node Sharing

## Overview

A Pocket Node user can onboard others by sharing their validated chainstate directly from phone to phone. No home server, no internet download, no trust in third parties. One phone at a meetup can bootstrap a whole table.

## How It Works

### Sender (existing Pocket Node user)

1. Taps "Share My Node" on the dashboard
2. Phone enables a lightweight file server on the local network
3. Screen shows: QR code with connection details + simple instructions
4. Server stays active until toggled off or app backgrounded

### Receiver (new user)

1. Installs Pocket Node APK (shared via nearby share, QR link, or USB)
2. Connects to sender's WiFi hotspot (or same WiFi network)
3. Scans QR code from sender's screen (or enters IP manually)
4. Normal chainstate copy flow runs, pulling from the sender's phone
5. Full node in under an hour

### Connection Options

| Scenario | How | Speed |
|----------|-----|-------|
| Hotspot | Sender creates WiFi hotspot, receiver joins | ~20-40 MB/s |
| Same WiFi | Both on same network at meetup/cafe | ~20-40 MB/s (depends on router) |
| WiFi Direct | P2P connection, no router needed | ~20-40 MB/s |

**Hotspot is the primary path.** It works everywhere, requires no infrastructure, and the sender controls the network. WiFi Direct is a future option but adds API complexity.

## What Gets Shared

Same data as a home node chainstate copy:

- **Chainstate** (~11 GB): the validated UTXO set
- **Block index**: so the receiver's bitcoind knows what's been validated
- **XOR obfuscation key** + recent block files
- **Block filters** (optional, ~13 GB): if sender has them, receiver gets filter index for Neutrino wallets (Zeus). Not needed for Lightning (LDK uses RPC).

The receiver's phone validates everything locally after copy. The sender's chainstate is a starting point, not a trust relationship.

## Architecture

### File Server

A lightweight HTTP server running in-process on the sender's phone. No SSH, no SFTP account creation, no credentials.

```
Sender Phone                              Receiver Phone
┌──────────────────────┐                  ┌──────────────────────┐
│ Pocket Node          │                  │ Pocket Node          │
│ ├── bitcoind         │   HTTP/WiFi      │ ├── Setup Wizard     │
│ ├── ShareServer      │ <─────────────── │ ├── Downloads chain  │
│ │   ├── /chainstate  │                  │ │   state + filters  │
│ │   ├── /blockindex  │                  │ ├── bitcoind starts  │
│ │   └── /filters     │                  │ └── Full node ✅     │
│ └── QR code display  │                  │                      │
└──────────────────────┘                  └──────────────────────┘
```

**Why HTTP instead of SFTP:**
- No account creation needed on sender's phone
- No SSH key exchange
- Simpler implementation (OkHttp or raw ServerSocket)
- Read-only by design (just serves files)
- Receiver's existing download code works with minimal changes

### Security

- Server only binds to the local network interface (not accessible from internet)
- Read-only: only serves chainstate files, nothing else on the phone
- No authentication needed (the physical proximity IS the trust model)
- Server auto-stops when sharing is toggled off or app goes to background
- Optional: PIN displayed on sender screen, entered on receiver (prevents drive-by downloads on open WiFi)

### QR Code Contents

```
http://192.168.43.1:8432
```

Receiver scans, app parses URL (also accepts JSON and host:port), auto-connects, shows node info.

## Meetup Flow

Realistic scenario at a Bitcoin meetup:

1. **Brad** has Pocket Node running on his Pixel, synced to chain tip
2. Opens "Share My Node", turns on hotspot
3. QR code appears on screen
4. **Alice** installs the APK (Brad sends via nearby share)
5. Alice opens app, connects to Brad's hotspot
6. Scans QR code, chainstate copy starts
7. under an hour later: Alice has a full node
8. **Bob** sees this, installs APK, also scans Brad's QR
9. Brad is now serving two downloads simultaneously
10. Later: Alice can share with others the same way

**With Lightning:** Alice walks away with a full node AND Lightning-ready in under an hour. LDK Lightning works without block filters. Set up a wallet, open a channel, start paying.

**Viral loop:** every new user becomes a potential sender. The network grows without any central infrastructure.

## Multi-Receiver

Maximum 2 simultaneous transfers. This keeps download times short (under 1 hourutes each), and those 2 can immediately start sharing with others. Exponential spread: 1→2→4→8.

The sender's screen shows anonymous transfer progress so they know when it's safe to leave:

```
📡 Sharing Node
├── Transfer 1 - 67%
├── Transfer 2 - 12%
└── 2 active
```

No device names, no identifiers. The sender just sees how many transfers are active and how far along they are. Once all complete (or no one is connected), they can safely toggle off and leave. If a transfer is interrupted, the receiver can resume from another sender or switch to the internet download path.

## APK Distribution

At a meetup without internet, the APK itself needs to get to new phones:

- **Nearby Share** (Android built-in): tap to send the APK file
- **QR code link**: if there's any internet, link to GitHub releases
- **USB transfer**: plug in, copy APK
- **Local HTTP**: the share server could also serve the APK itself at `/apk`

The share server serves the APK directly at `/apk`. Receiver opens a browser link from the QR code, downloads and installs the same version the sender is running. One QR code gives you everything: the app, the chainstate, and the block filters.

## Implementation

### New Files

- `ShareServer.kt`: HTTP file server, binds to local interface, serves chainstate/filters/APK
- `ShareScreen.kt`: QR code display, connection status, active transfers, toggle

### Changes to Existing Code

- `NodeStatusScreen.kt`: "Share My Node" button (only shown when synced to tip)
- `SetupChecklistScreen.kt` / setup flow: "Scan QR from nearby node" option alongside existing SSH/HTTPS paths
- `SnapshotDownloader.kt`: add HTTP download source (currently SFTP only)

### Status: Shipped

All implemented and tested end-to-end. ShareServer serves files, ShareClient downloads with resume support, QR scanning works, session progress tracking shows sender-side progress. Also works with the pocket-node-relay for remote sharing over LAN.

## Power Mode Interaction

Sharing should require Max Data mode (continuous network, full resources). If the sender switches to Low or Away, sharing pauses with a note: "Switch to Max to continue sharing."

## Community Stats

After a successful transfer, the sender gets a prompt: "Share this transfer anonymously to the community counter?" If they tap OK, a +1 is queued locally and pushed to a public counter when internet is available. No device info, no IPs, no timestamps. Just a count of how many nodes have been bootstrapped phone-to-phone.

**Anti-spoofing:** each increment includes a hash of `chainstate_hash + block_height + random_salt`. The counter server deduplicates by chainstate hash + height (one count per unique pair per day). To spoof, you'd need a valid chainstate hash, which means running a real node.

This gives the project a public metric: "X nodes shared peer-to-peer" without compromising anyone's privacy.

## Future

- **WiFi Direct**: no hotspot needed, phones connect peer-to-peer
- **Chain of trust display**: "Your chainstate came from Brad's Pixel, which copied from his Umbrel" (provenance, not trust)
- **Meetup mode**: dedicated full-screen UI optimized for standing around a table, large QR code, progress visible from across the room
