# Chainparams Patch. AssumeUTXO Entries

## Overview
Added AssumeUTXO snapshots at heights 880,000 and 910,000 to Bitcoin Core's mainnet chainparams (in addition to the existing 840,000 entry from upstream v28.1).

This enables faster initial sync for Pocket Node users via UTXO snapshot loading.

## Patched File
`src/kernel/chainparams.cpp`. mainnet `m_assumeutxo_data` section.

**Note:** The `src/` directory is gitignored (it's a full Bitcoin Core checkout). This document records the patch for reproducibility.

## Added Entries

### Height 880,000
```cpp
{
    .height = 880'000,
    .hash_serialized = AssumeutxoHash{uint256{"dbd190983eaf433ef7c15f78a278ae42c00ef52e0fd2a54953782175fbadcea9"}},
    .m_chain_tx_count = 1145604538,
    .blockhash = consteval_ctor(uint256{"000000000000000000010b17283c3c400507969a9c2afd1dcf2082ec5cca2880"}),
},
```

### Height 910,000
```cpp
{
    .height = 910'000,
    .hash_serialized = AssumeutxoHash{uint256{"4daf8a17b4902498c5787966a2b51c613acdab5df5db73f196fa59a4da2f1568"}},
    .m_chain_tx_count = 1226586151,
    .blockhash = consteval_ctor(uint256{"0000000000000000000108970acb9522ffd516eae17acddcb1bd16469194a821"}),
},
```

## Rebuild
After patching, rebuild with:
```bash
cd src
PATH="/path/to/ndk-wrappers:$PATH" make -j4
```
Only the chainparams object file recompiles and binaries relink (~30 seconds).

## Date
2026-02-14
