# Cross-Compiling bitcoind for Android (ARM64)

Build bitcoind from source for `aarch64-linux-android` on macOS.

## Prerequisites

- Android NDK r27 at `~/tools/android-sdk/ndk/27.2.12479018`
- CMake (brew install cmake)
- Standard build tools (make, git)

## Environment

```bash
export NDK=~/tools/android-sdk/ndk/27.2.12479018
export STRIP=$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-strip
```

## Step 1: Build Dependencies

Bitcoin Core needs Boost (headers only) and libevent (static library).
Deps are cached at `~/tools/android-cross-deps/android-deps/` for reuse.

### Boost (header-only)

```bash
cd /private/tmp
curl -sLO https://archives.boost.io/release/1.81.0/source/boost_1_81_0.tar.gz
tar xzf boost_1_81_0.tar.gz boost_1_81_0/boost
mkdir -p /private/tmp/android-deps/include
cp -r boost_1_81_0/boost /private/tmp/android-deps/include/
```

### Boost CMake config (required for Core 30+)

Core 30+ uses `find_package(Boost CONFIG)` which needs a proper CMake config file.
Core 29.3 uses `Boost_INCLUDE_DIR` directly and doesn't need this.

```bash
mkdir -p /private/tmp/android-deps/lib/cmake/Boost-1.81.0

cat > /private/tmp/android-deps/lib/cmake/Boost-1.81.0/BoostConfig.cmake << 'EOF'
set(Boost_VERSION "1.81.0")
set(Boost_VERSION_MAJOR 1)
set(Boost_VERSION_MINOR 81)
set(Boost_VERSION_PATCH 0)
set(Boost_FOUND TRUE)
if(NOT TARGET Boost::headers)
  add_library(Boost::headers INTERFACE IMPORTED)
  set_target_properties(Boost::headers PROPERTIES
    INTERFACE_INCLUDE_DIRECTORIES "${CMAKE_CURRENT_LIST_DIR}/../../../include"
  )
endif()
EOF

cat > /private/tmp/android-deps/lib/cmake/Boost-1.81.0/BoostConfigVersion.cmake << 'EOF'
set(PACKAGE_VERSION "1.81.0")
if(PACKAGE_FIND_VERSION_MAJOR EQUAL 1)
  if(PACKAGE_FIND_VERSION_MINOR LESS_EQUAL 81)
    set(PACKAGE_VERSION_COMPATIBLE TRUE)
    if(PACKAGE_FIND_VERSION_MINOR EQUAL 81)
      set(PACKAGE_VERSION_EXACT TRUE)
    endif()
  endif()
endif()
EOF
```

### libevent (static)

```bash
cd /private/tmp
curl -sLO https://github.com/libevent/libevent/releases/download/release-2.1.12-stable/libevent-2.1.12-stable.tar.gz
tar xzf libevent-2.1.12-stable.tar.gz
cd libevent-2.1.12-stable
mkdir build-android && cd build-android

cmake .. \
  -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-24 \
  -DANDROID_STL=c++_shared \
  -DEVENT__DISABLE_OPENSSL=ON \
  -DEVENT__DISABLE_TESTS=ON \
  -DEVENT__DISABLE_SAMPLES=ON \
  -DEVENT__DISABLE_BENCHMARK=ON \
  -DEVENT__LIBRARY_TYPE=STATIC \
  -DCMAKE_INSTALL_PREFIX=/private/tmp/android-deps \
  -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
  -DCMAKE_HAVE_LIBC_PTHREAD=1 \
  -DCMAKE_USE_PTHREADS_INIT=1 \
  -DTHREADS_FOUND=TRUE

make -j4
make install
```

**Note:** The `CMAKE_HAVE_LIBC_PTHREAD`, `CMAKE_USE_PTHREADS_INIT`, and `THREADS_FOUND`
flags are required to work around FindThreads failing on Android cross-compile.
`CMAKE_POLICY_VERSION_MINIMUM=3.5` is needed for libevent 2.1.12's older CMake files.

### Save deps persistently

```bash
mkdir -p ~/tools/android-cross-deps
cp -r /private/tmp/android-deps ~/tools/android-cross-deps/
```

For future builds, copy back if `/private/tmp` was cleared:
```bash
cp -r ~/tools/android-cross-deps/android-deps /private/tmp/
```

## Step 2: Clone and Patch Source

### Clone

**Core 29.4** (vanilla, the default `libbitcoind_core.so` since v0.37):
```bash
git clone --depth 1 --branch v29.4 https://github.com/bitcoin/bitcoin.git bitcoin-core-v29.4
```
Same cmake flags as the Core 29.3 section below, plus two patches: fdsan, and the
mainnet assumeutxo entry for height 910,000 copied verbatim from Core v30.0
(stock 29.x only knows 840,000 and 880,000, and the app's snapshot is 910,000).
`~/tools/bitcoind-build/build-v29.4.sh` on the build mini does the whole thing.

> **Do not ship the v72t `29.3+UASF-BIP110` build again.** It is not dormant:
> it requires every block in [961,632, 963,648) to signal bit 4
> (`bad-version-reduced_data`), so it rejected mainnet block 961,640 and
> stopped following the chain. v0.35 and v0.36 shipped it as the default.

**Core 29.3 + BIP-110** (v72t's port, historical, see warning above):
```bash
git clone --depth 1 --branch "29.3+UASF-BIP110" https://github.com/v72t/bitcoin.git bitcoin-core-bip110
```

**Knots 29.3 + BIP-110** (dathonohm's):
```bash
git clone --depth 1 --branch "29.3.knots20260210+UASF-BIP110" https://github.com/dathonohm/bitcoin.git bitcoin-knots-bip110
```

**Core 30.3** (vanilla, `libbitcoind_v30.so` since v0.38): `~/tools/bitcoind-build/build-v30.3.sh`,
Core 30 flags below, fdsan patch only.

**Core 31.1** (vanilla, `libbitcoind_v31.so` since v0.38):
```bash
git clone --depth 1 --branch v31.1 https://github.com/bitcoin/bitcoin.git bitcoin-core-v31.1
```
Same flags as Core 30 below plus `-DENABLE_EXTERNAL_SIGNER=OFF`, fdsan patch only
(31 already carries assumeutxo data for 910,000 and 935,000).
`~/tools/bitcoind-build/build-v31.1.sh` on the build mini does the whole thing.

**Core 30.0** (vanilla, no BIP-110):
```bash
git clone --depth 1 --branch v30.0 https://github.com/bitcoin/bitcoin.git bitcoin-core-v30
```

### Patch: fdsan fix (required for all, GrapheneOS compatibility)

In `src/bitcoind.cpp`, add after the `#include <compat/compat.h>` line:
```cpp
#ifdef __ANDROID__
#include <dlfcn.h>
#endif
```

At the start of `AppInit()`:
```cpp
#ifdef __ANDROID__
    {
        using fdsan_fn = void (*)(unsigned);
        auto set_level = reinterpret_cast<fdsan_fn>(
            dlsym(RTLD_DEFAULT, "android_fdsan_set_error_level"));
        if (set_level) set_level(0);
    }
#endif
```

### Patch: -signalbip110 toggle (BIP-110 builds only, NOT Core 30)

Makes BIP-110 signaling opt-in via command line flag instead of always-on.

**src/init.cpp** changes:

1. Remove `NODE_UASF_REDUCED_DATA` from default `g_local_services`:
```cpp
// Before:
ServiceFlags g_local_services = ServiceFlags(NODE_NETWORK_LIMITED | NODE_WITNESS | NODE_UASF_REDUCED_DATA);
// After:
ServiceFlags g_local_services = ServiceFlags(NODE_NETWORK_LIMITED | NODE_WITNESS);
```

2. Register the arg in `SetupServerArgs()`:
```cpp
argsman.AddArg("-signalbip110", "Signal support for BIP 110 (Reduced Data) via service flag and version bit (default: 0)", ArgsManager::ALLOW_ANY, OptionsCategory::OPTIONS);
```

3. Conditionally set the service flag (after arg parsing, near other service flag checks):
```cpp
if (args.GetBoolArg("-signalbip110", false)) {
    g_local_services = ServiceFlags(g_local_services | NODE_UASF_REDUCED_DATA);
}
```

**src/net_processing.cpp**: Make peer filtering conditional on signaling:
```cpp
// Before:
if (pfrom.ExpectServicesFromConn() && !(nServices & NODE_UASF_REDUCED_DATA)) {
// After:
if (pfrom.ExpectServicesFromConn() && (m_connman.GetLocalServices() & NODE_UASF_REDUCED_DATA) && !(nServices & NODE_UASF_REDUCED_DATA)) {
```

**src/protocol.h**: Revert `SeedsServiceFlags` to standard (so non-signaling nodes connect to all seeds):
```cpp
// Before:
constexpr ServiceFlags SeedsServiceFlags() { return ServiceFlags(NODE_NETWORK | NODE_WITNESS | NODE_UASF_REDUCED_DATA); }
// After:
constexpr ServiceFlags SeedsServiceFlags() { return ServiceFlags(NODE_NETWORK | NODE_WITNESS); }
```

## Step 3: Build bitcoind

### Core 29.3 + BIP-110

```bash
cd bitcoin-core-bip110
mkdir build-android && cd build-android

cmake .. \
  -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-24 \
  -DANDROID_STL=c++_shared \
  -DBUILD_DAEMON=ON \
  -DBUILD_CLI=OFF \
  -DBUILD_TX=OFF \
  -DBUILD_UTIL=OFF \
  -DBUILD_GUI=OFF \
  -DBUILD_TESTS=OFF \
  -DBUILD_BENCH=OFF \
  -DBUILD_FUZZ_BINARY=OFF \
  -DENABLE_WALLET=OFF \
  -DWITH_ZMQ=OFF \
  -DWITH_USDT=OFF \
  -DCMAKE_FIND_ROOT_PATH="/private/tmp/android-deps" \
  -DCMAKE_FIND_ROOT_PATH_MODE_INCLUDE=BOTH \
  -DCMAKE_FIND_ROOT_PATH_MODE_LIBRARY=BOTH \
  -DBoost_INCLUDE_DIR=/private/tmp/android-deps/include \
  -DCMAKE_HAVE_LIBC_PTHREAD=1 \
  -DCMAKE_USE_PTHREADS_INIT=1 \
  -DTHREADS_FOUND=TRUE

make -j4 bitcoind
```

### Core 30.0 (vanilla)

Core 30 requires two additional flags:
- `-DCMAKE_PREFIX_PATH` for Boost CMake config discovery
- `-DENABLE_IPC=OFF` to disable Cap'n Proto multiprocess (not needed)

```bash
cd bitcoin-core-v30
mkdir build-android && cd build-android

cmake .. \
  -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-24 \
  -DANDROID_STL=c++_shared \
  -DBUILD_DAEMON=ON \
  -DBUILD_CLI=OFF \
  -DBUILD_TX=OFF \
  -DBUILD_UTIL=OFF \
  -DBUILD_GUI=OFF \
  -DBUILD_TESTS=OFF \
  -DBUILD_BENCH=OFF \
  -DBUILD_FUZZ_BINARY=OFF \
  -DENABLE_WALLET=OFF \
  -DENABLE_IPC=OFF \
  -DWITH_ZMQ=OFF \
  -DWITH_USDT=OFF \
  -DCMAKE_FIND_ROOT_PATH="/private/tmp/android-deps" \
  -DCMAKE_FIND_ROOT_PATH_MODE_INCLUDE=BOTH \
  -DCMAKE_FIND_ROOT_PATH_MODE_LIBRARY=BOTH \
  -DCMAKE_PREFIX_PATH="/private/tmp/android-deps" \
  -DCMAKE_HAVE_LIBC_PTHREAD=1 \
  -DCMAKE_USE_PTHREADS_INIT=1 \
  -DTHREADS_FOUND=TRUE

make -j4 bitcoind
```

### Knots 29.3 + BIP-110

Same cmake flags as Core 29.3. Apply both fdsan and signalbip110 patches.

## Step 4: Strip and Install

```bash
$STRIP build-android/bin/bitcoind -o build-android/bin/bitcoind-stripped
```

Copy to app with the correct library name:

| Source | Install as |
|--------|-----------|
| Core 29.3 + BIP-110 | `libbitcoind_core.so` |
| Core 30.0 | `libbitcoind_v30.so` |
| Knots 29.3 + BIP-110 | `libbitcoind_knots.so` |

```bash
cp build-android/bin/bitcoind-stripped \
   ~/bitcoin-pocket-node/app/src/main/jniLibs/arm64-v8a/<library-name>
```

Binary must be named `lib*.so` for Android's native library extraction.

## Expected Output

| Binary | Stripped Size |
|--------|-------------|
| Core 29.3 + BIP-110 | ~8 MB |
| Core 30.0 | ~8 MB |
| Knots 29.3 + BIP-110 | ~12 MB |

Knots is larger due to additional policy code.

## Key Differences Between Versions

| | Core 29.3 | Core 30 | Knots 29.3 |
|---|---|---|---|
| BIP-110 code | Yes (v72t port) | No | Yes (dathonohm) |
| `-signalbip110` flag | Yes (patched) | N/A | Yes (patched) |
| `ENABLE_IPC` | N/A | Must disable | N/A |
| Boost find method | `Boost_INCLUDE_DIR` | CMake CONFIG | `Boost_INCLUDE_DIR` |
| OP_RETURN policy | Standard | Relaxed | Restrictive |

## Important Notes

- `/private/tmp` is NOT persistent on macOS. Save deps to `~/tools/android-cross-deps/`.
- The `depends/` system does NOT support Android cross-compile natively. Use CMake directly with the NDK toolchain.
- `ENABLE_WALLET=OFF` is correct. Pocket Node uses LDK/BDK for wallet, not bitcoind's wallet.
- `c++_shared` STL is required. The app already ships `libc++_shared.so`.
- Build time: ~5-10 minutes per binary on 2014 MacBook Pro (after deps are ready).
- Deps build time: ~5 minutes (libevent + Boost headers).
- Always apply fdsan fix to ALL binaries (GrapheneOS crashes without it).
- Gradle clean required when swapping .so files: `./gradlew clean assembleRelease`

## Rebuilding libldk_node.so (reproducibly)

`app/src/main/jniLibs/arm64-v8a/libldk_node.so` is built from the ldk-node fork,
branch `watchtower-bridge-v2` (FreeOnlineUser/ldk-node). Since `9d2598b` the branch
commits a `Cargo.lock` pinned to the dependency versions of the March 2026 build, so
a rebuild changes only what the source changes. (Commit `9e329df` in this repo says
the library came from `4ba0bab`; it was built from `9d2598b`, the genesis-wallet
guard backported from upstream `d0ed6a3`.)

```bash
rustup target add aarch64-linux-android
~/tools/build-ldk-android.sh   # cargo build --profile release-smaller --features uniffi --target aarch64-linux-android
cp ~/ldk-node/target/aarch64-linux-android/release-smaller/libldk_node.so app/src/main/jniLibs/arm64-v8a/
# Kotlin bindings, from the same library:
cd ~/ldk-node && cargo run --manifest-path bindings/uniffi-bindgen/Cargo.toml generate \
  bindings/ldk_node.udl --language kotlin --out-dir /tmp/ldk-bindgen \
  --lib-file target/aarch64-linux-android/release-smaller/libldk_node.so
cp /tmp/ldk-bindgen/org/lightningdevkit/ldknode/ldk_node.kt \
  app/src/main/java/org/lightningdevkit/ldknode/ldk_node.kt
```

Check before shipping: the exported `uniffi_*` symbol set should match the old
library (`llvm-readelf --dyn-syms`), and the `ldk_node.kt` diff should contain only
the intended API change. The APK loads `libldk_node.so` from `jniLibs`; the copies
inside `app/libs/*.aar` are not used.
