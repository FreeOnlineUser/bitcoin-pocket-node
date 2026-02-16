# Bitcoin Core ARM64 Android Cross-Compilation Research

*Date: 2026-02-14*

## 1. Prior Art: ABCore

**ABCore** (originally by greenaddress, forked by nicola-pc) was an Android app that ran `bitcoind` directly on Android devices. Key details:

- **Status:** Repository appears to have been deleted/archived (both greenaddress/abcore and nicola-pc/ABCore return 404 as of Feb 2026)
- **Approach:** Used Bitcoin Core's `depends` system to cross-compile for Android ARM/ARM64
- **Architecture:** Kotlin/Java Android app that bundled the `bitcoind` binary, extracted it to app-private storage, and ran it as a child process
- **NDK:** Used Android NDK's standalone toolchain (clang-based) for cross-compilation
- **Last known activity:** ~2020-2021, targeting Bitcoin Core 0.21.x era

**Key lessons from ABCore:**
- The `depends` system is the correct path — it handles all dependencies (boost, libevent, openssl→wolfssl, etc.)
- Android's Bionic libc differences require careful handling (no `getifaddrs` on older API levels, different threading)
- The app ran bitcoind as a foreground service to avoid Android killing it
- Storage was the main UX challenge (chain data on internal storage)

## 2. Bitcoin Core Build System

### Build System Evolution
- **Pre-28.0:** Autotools (configure/make) with `depends` system for cross-compilation
- **28.0+ (late 2024):** Migrated to **CMake** build system (PR #30454)
- **29.0+ (2025):** CMake is the only build system, autotools fully removed
- **30.2 (current stable as of Feb 2026):** CMake-only

### The `depends` System
Bitcoin Core's `depends/` directory contains a self-contained cross-compilation framework:
- Downloads, verifies, and builds all dependencies from source
- Supports multiple host triplets including `aarch64-linux-android`
- Produces a toolchain file and prefix that CMake/autotools can consume
- **Android is an officially supported cross-compilation target** in the depends system

### Key files:
- `depends/hosts/android.mk` — Android-specific host configuration
- `depends/toolchain.cmake.in` — CMake toolchain template for cross-builds
- `depends/packages/` — Package recipes for all dependencies

## 3. Cross-Compilation Steps (Theoretical)

Based on Bitcoin Core's build system and depends documentation:

```bash
# 1. Set NDK path
export ANDROID_NDK=/path/to/android-ndk-r27c

# 2. Build depends for Android ARM64
cd depends
make HOST=aarch64-linux-android ANDROID_API_LEVEL=28 NO_QT=1 -j4

# 3. Configure Bitcoin Core with CMake
cd ..
cmake -B build \
  --toolchain depends/aarch64-linux-android/share/toolchain.cmake \
  -DBUILD_GUI=OFF \
  -DENABLE_WALLET=OFF

# 4. Build
cmake --build build -j4
```

### Critical Parameters:
- **HOST triplet:** `aarch64-linux-android` (ARM64, Android)
- **ANDROID_API_LEVEL:** Minimum 24 (Android 7.0), recommend 28+ (Android 9.0) for GrapheneOS compatibility
- **NO_QT=1:** No GUI needed for our use case
- **ENABLE_WALLET=OFF:** We're disabling wallet (per PLAN.md config)

## 4. Android API Level Considerations

- **GrapheneOS** supports Pixel 4+ (originally), currently Pixel 6+
- Pixel 6 shipped with Android 12 (API 31)
- GrapheneOS tracks recent Android versions closely
- **Recommendation:** Target API level 31 minimum, but compile with NDK API level 28 for maximum compatibility
- The NDK API level sets the *minimum* Android version the binary can run on

## 5. Known Challenges

### Bionic libc Differences
- No `getifaddrs()` before API 24 (we're fine targeting 28+)
- `pthread` differences (minor)
- No `locale` support (Bitcoin Core handles this)
- `mmap` behavior differences (relevant for LevelDB)

### Android-Specific Issues
- **SELinux:** May restrict execution of binaries from app-private storage
  - Solution: Ensure binary has correct context, or use `app_process` tricks
  - GrapheneOS may have additional restrictions
- **File descriptor limits:** Android has lower defaults than desktop Linux
- **Memory pressure:** Android aggressively kills background processes
  - Must use foreground service with notification
- **Storage:** Need to handle scoped storage (Android 11+)

### Build System Concerns
- CMake cross-compilation with NDK should work out of the box via depends
- The NDK includes its own sysroot, so no system library conflicts
- May need to set `-DANDROID_STL=c++_shared` or use static linking

## 6. Alternative Approaches Considered

### Termux
- Could run `bitcoind` in Termux (it has a package)
- **Rejected:** Not a standalone app experience, requires Termux knowledge

### Pre-built Binaries
- Bitcoin Core does NOT publish official Android ARM64 binaries
- Some third parties (e.g., Nix Bitcoin) may have them
- **Rejected:** We need to build ourselves for trust/verification

### Docker/Container
- Not applicable on Android (no Docker runtime)

## 7. Recommendations

1. **Use Bitcoin Core 30.2** (latest stable) with CMake build system
2. **Use NDK r27c** (LTS release, good compatibility)
3. **Target API level 28** (Android 9.0) as minimum
4. **Static linking** preferred over shared libs for simplicity
5. **Disable wallet, GUI, tests** to reduce build complexity
6. **Test on emulator first**, then real Pixel hardware

## 8. Next Steps

- [x] Research complete
- [ ] Install Android SDK command-line tools
- [ ] Install NDK r27c
- [ ] Clone Bitcoin Core 30.2
- [ ] Build depends for aarch64-linux-android
- [ ] Cross-compile bitcoind
- [ ] Test on ARM64 emulator
