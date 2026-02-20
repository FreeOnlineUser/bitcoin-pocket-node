# BIP 110 Research -- Reduced Data Temporary Softfork

## What Is It

BIP 110 ("Reduced Data Temporary Softfork") proposes temporarily limiting data
fields at the **consensus level** to combat inscription/arbitrary data abuse on
Bitcoin. It's a direct response to Core v30 relaxing OP_RETURN limits.

**Author:** Dathon Ohm (builds on Luke Dashjr's mailing list proposal)
**Status:** Draft (assigned 2025-12-03)
**License:** BSD-3-Clause

## The Rules (temporary, 1-year deployment)

1. scriptPubKeys max 34 bytes (OP_RETURN max 83 bytes)
2. OP_PUSHDATA/witness elements max 256 bytes
3. Spending undefined witness versions invalid
4. Taproot annex invalid
5. Control blocks max 257 bytes (128 script leaves)
6. OP_SUCCESS* invalid in Tapscripts
7. OP_IF/OP_NOTIF invalid in Tapscripts

Pre-activation UTXOs are exempt. Rules expire after 1 year.

## Current Implementation Status

**Reference implementation exists** by Dathon Ohm (BIP author):

- Branch: `dathonohm:bitcoin:uasf-modified-bip9` (based on Knots 29.x)
- Compare: https://github.com/bitcoinknots/bitcoin/compare/29.x-knots...dathonohm:bitcoin:uasf-modified-bip9
- BIP status: Draft (assigned 2025-12-03)
- Signaling started Dec 1, 2025 (version bit 4)
- 55% activation threshold (1109/2016 blocks per difficulty period)
- max_activation_height: block 965664 (~Sep 2026)
- Expiry: 52416 blocks (~1 year) after activation
- Service flag: `NODE_UASF_REDUCED_DATA` (bit 27)
- Umbrel offers this as an option via retropex's Knots package
- Website: https://bip110.dev/
- Controversial -- Jameson Lopp, Peter Todd pushed back on the ML

## What Knots Already Does (Policy Level)

Knots v29.3 already has **restrictive relay policy** (NOT consensus):

- `datacarrier=true` but `permitbaredatacarrier=false` (default)
- `datacarriersize=83` (83 byte OP_RETURN limit)
- `datacarrierfullcount=true` (applies size limit to ALL datacarrier methods)
- `datacarriercost` multiplier (makes data more expensive in fee calc)
- `-corepolicy` flag relaxes all of the above to match Core defaults

These are **mempool/relay rules only** -- Knots still validates blocks containing
data that violates these policies because they're not consensus rules.

## What BIP 110 Would Add

BIP 110 moves restrictions from relay policy to **consensus enforcement**:
- Blocks violating BIP 110 rules would be **rejected as invalid**
- This is a soft fork -- old nodes still accept compliant blocks
- Requires activation via version bit signaling (bit 4 proposed)

## Relevance to Bitcoin Pocket Node

BIP 110 is very relevant to our app for several reasons:

1. **Our users care about this debate** -- people running their own nodes on
   phones are exactly the audience that wants policy choice
2. **Version selection is our differentiator** -- offering Core v28.1 (neutral),
   Core v30 (permissive), and Knots (restrictive policy) already covers the
   spectrum at the relay level
3. **When/if BIP 110 gets implemented**, we can add it as a 4th binary option
4. **Knots' existing policy restrictions** already give users most of what
   BIP 110 promises at the mempool level

## Practical Approach for the App

### Now (shipped)
- **Core 28.1** -- neutral baseline, pre-controversy
- **Core 30** -- permissive OP_RETURN policy
- **Knots 29.3** -- restrictive relay policy (closest to BIP 110 spirit)

### Future (when available)
- **Knots + BIP 110** -- consensus-level enforcement when Luke ships it

### What We Can Do Today
Knots already enforces most of BIP 110's spirit at the relay/mempool level.
The key difference is consensus vs policy -- Knots won't mine blocks with
inscription data, but it will still validate them if another miner does.

We could expose Knots' policy flags in the UI (advanced settings) to let users
tune the restrictiveness:
- `datacarrier` on/off
- `datacarriersize` (default 83)
- `datacarrierfullcount` on/off
- `acceptnonstddatacarrier` on/off

## Key Quotes from the BIP

> "Bitcoin should do one thing, and do it well. By rejecting data storage,
> this BIP liberates Bitcoin developers from endless scope creep."

> "The fee for a data storage transaction still goes only to the miner...
> but the burden of storing the data falls on all node operators, who never
> received even a part of the fee."

## References

- BIP: https://github.com/bitcoin/bips/blob/master/bip-0110.mediawiki
- Discussion: https://groups.google.com/g/bitcoindev/c/nOZim6FbuF8
- PR: https://github.com/bitcoin/bips/pull/2017
