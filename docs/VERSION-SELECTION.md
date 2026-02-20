# Bitcoin Core Version Selection

## Overview

Let users choose which Bitcoin implementation runs on their phone. Bundle multiple
pre-compiled ARM64 binaries in the APK. User selects in settings, node restarts
with the chosen binary. No downloads, no waiting.

## Why This Matters

The Bitcoin Core vs Knots debate (OP_RETURN policy, etc.) means people care deeply
about which consensus rules they run. Every other node platform ships one version.
We ship choice.

"Run YOUR node, YOUR rules, in your pocket."

## Bundled Versions

| Binary | Version | Size | Notes |
|--------|---------|------|-------|
| `libbitcoind_v28_1.so` | Bitcoin Core 28.1 | ~13 MB | Current default. Pre-OP_RETURN controversy. Universal acceptance. |
| `libbitcoind_v30.so` | Bitcoin Core 30.x | ~13 MB | New OP_RETURN policy (larger data allowed). |
| `libbitcoind_knots.so` | Bitcoin Knots 29.3 | ~9 MB | Restrictive OP_RETURN relay policy. Luke Dashjr's fork. |
| `libbitcoind_knots_bip110.so` | Bitcoin Knots (BIP 110) | ~9 MB | Consensus-level data restrictions. Signals version bit 4. |

Total APK impact: ~40 MB for 4 binaries (current APK is ~74 MB, would grow to ~104 MB).

## Architecture

### Binary Naming Convention
Android requires native libs to be named `lib*.so` in `jniLibs/arm64-v8a/`.
Each version gets a unique name:
```
jniLibs/arm64-v8a/
  libbitcoind_v28_1.so    # Bitcoin Core 28.1
  libbitcoind_v30.so      # Bitcoin Core 30.x
  libbitcoind_knots.so    # Bitcoin Knots
  libbwt.so               # BWT (unchanged)
  libc++_shared.so        # C++ runtime (unchanged)
```

### BinaryExtractor Changes
```kotlin
object BinaryExtractor {
    enum class BitcoinVersion(
        val libraryName: String,
        val displayName: String,
        val description: String
    ) {
        CORE_28_1("libbitcoind_v28_1.so", "Bitcoin Core 28.1",
            "Stable, pre-OP_RETURN changes. Universal acceptance."),
        CORE_30("libbitcoind_v30.so", "Bitcoin Core 30",
            "Latest Core release. Relaxed OP_RETURN policy."),
        KNOTS("libbitcoind_knots.so", "Bitcoin Knots",
            "Restrictive policy. Filters non-standard transactions.");

        companion object {
            val DEFAULT = CORE_28_1
        }
    }

    fun getBinary(context: Context, version: BitcoinVersion): File {
        val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
        val binary = File(nativeLibDir, version.libraryName)
        if (binary.exists() && binary.canExecute()) return binary

        // Fallback to current single-binary name during transition
        val legacy = File(nativeLibDir, "libbitcoind.so")
        if (legacy.exists() && legacy.canExecute()) return legacy

        throw RuntimeException("Binary not found: ${version.libraryName}")
    }
}
```

### User Preference Storage
```kotlin
// In SharedPreferences "pocketnode_prefs"
val KEY_BITCOIN_VERSION = "bitcoin_version"
// Stores BitcoinVersion.name (e.g., "CORE_28_1")
```

### Version Selection UI
New section on the dashboard or Settings screen:
- Current version displayed with colored badge
- Tap to open version picker dialog
- Each version shows: name, description, policy stance
- Warning before switching: "Node will restart. Chainstate is compatible."
- For incompatible downgrades: "Requires chainstate restore. ~5 min."

### Compatibility Matrix

| From \ To | Core 28.1 | Core 30 | Knots |
|-----------|-----------|---------|-------|
| Core 28.1 | -- | Safe | Safe |
| Core 30 | Safe* | -- | Safe* |
| Knots | Safe | Safe | -- |

*All versions use the same LevelDB chainstate format as of current releases.
Cross-compatibility is expected. Downgrade from v30 is safe because we run
pruned with checklevel=0.*

### Implementation Steps

1. **Rename current binary** from `libbitcoind.so` to `libbitcoind_v28_1.so`
2. **Update BinaryExtractor** with version enum and selection logic
3. **Add version preference** to SharedPreferences
4. **Build UI** -- version picker in Settings or dashboard card
5. **Wire BitcoindService** to use selected version from BinaryExtractor
6. **Cross-compile Core v30** and Knots for ARM64 (separate build sessions)
7. **Test version switching** on real device
8. **Add to README** with screenshots

### Phase 1 (Ship Now)
- Rename binary, update BinaryExtractor with enum
- Add version UI (even with only one binary available)
- "More versions coming soon" for unbuilt binaries
- Framework is ready for when new binaries are compiled

### Phase 2 (Build Binaries)
- Cross-compile Bitcoin Core v30 for ARM64
- Cross-compile Bitcoin Knots for ARM64
- Test chainstate compatibility between versions
- Ship multi-version APK

## Build Notes

Current build process (from docs/build-android-arm64.md):
- NDK r27, API level 24
- Static linking where possible
- Strip symbols (~13 MB final)
- Output as `lib*.so` for GrapheneOS W^X compliance

Each version needs its own source checkout and build. The NDK toolchain
and build flags are identical -- only the source repo/tag changes.
