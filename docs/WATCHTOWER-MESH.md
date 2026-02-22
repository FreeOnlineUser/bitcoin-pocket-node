# Watchtower: Home Node Protection

## Concept

Your home node watches your phone's Lightning channels. When the phone is offline (sleeping, traveling, no signal), the home node detects breach attempts and broadcasts justice transactions on your behalf.

No mesh. No discovery protocol. No new infrastructure. Just your existing home node doing one more job.

## What Exists Today

LND ships with a complete watchtower implementation:

- **Server** (`watchtower.active=1`): watches channels for other nodes
- **Client** (`wtclient.active=1`): sends channel state to a watchtower

The phone already pairs with the home node via SSH during setup. The watchtower feature piggybacks on that existing relationship.

## How It Works

During the existing admin SSH setup (NodeSetupManager), one additional step:

1. Add `watchtower.active=1` to the home node's `lnd.conf`
2. Restart LND on the home node
3. Read the tower URI from `lncli tower info` (pubkey + .onion address)
4. On the phone, call `wtclient add <tower-uri>`

Done. Home node watches the phone's channels. No ongoing configuration needed.

## Reachability

The home node's watchtower listens on port 9911. The phone needs to reach it:

| Location | How it works |
|---|---|
| Home WiFi | Direct LAN connection (e.g. 10.0.1.127:9911) |
| Away from home | Tor .onion address (Umbrel already runs Tor) |

Umbrel nodes already expose LND services via Tor hidden services. The tower's .onion address is typically available without additional configuration.

## Implementation

### Changes to Existing Code

**NodeSetupManager.kt** (during admin SSH setup):
- Detect if home node runs LND
- Add `watchtower.active=1` to lnd.conf if not already present
- Restart LND
- Read tower info and save URI to SharedPreferences

**New: WatchtowerManager.kt** (in `snapshot/` package):
- On Lightning setup completion, add home node tower via LND wtclient API
- Store tower URI in SharedPreferences
- Dashboard status: "Protected by home node" or "No watchtower configured"

**NodeStatusScreen.kt** (dashboard):
- Watchtower status row showing protection state

**ConnectWalletScreen.kt** (Zeus setup guide):
- Note that watchtower protection is automatic when Lightning is set up via home node

### LND API Calls

All available via LND REST API on the home node:

```
# Get tower info (server side, during setup)
GET /v2/tower/info
→ { "pubkey": "02f1...", "uris": ["02f1...@abc.onion:9911"] }

# Add tower (client side, on phone)
POST /v2/watchtower/client
{ "pubkey": "02f1...", "address": "abc.onion:9911" }

# List towers (for dashboard status)
GET /v2/watchtower/client
→ { "towers": [...] }
```

## What Is NOT Being Built

- Mesh discovery
- Nostr integration
- Go daemon
- WireGuard provisioning
- CLN support
- Custom protocol

This is one config line on the home node and one API call on the phone.

## Future

If the user base grows and there's demand for mesh redundancy (multiple watchtowers from other users), Nostr-based discovery can be added later as a separate feature. The home node watchtower remains the primary, mesh peers become optional backups.

But that's a future decision, not a current one.
