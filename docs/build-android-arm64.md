# Building Bitcoin Core for Android ARM64

## Overview
Cross-compiles Bitcoin Core v28.1 for ARM64 Android using NDK r27 on macOS.

## Prerequisites
- macOS with Xcode Command Line Tools
- Homebrew packages: `cmake autoconf automake libtool pkg-config`
- Android NDK r27 (installed at `$ANDROID_SDK/ndk/27.2.12479018`)

## Key Challenges Solved

### 1. NDK Compiler Wrappers
The Bitcoin Core `depends` system expects `aarch64-linux-android-gcc` etc. The NDK provides clang-based compilers with a different naming convention. We created wrapper scripts in `ndk-wrappers/` that:
- Map `aarch64-linux-android-gcc` → `aarch64-linux-android28-clang`
- Fix `--target=aarch64-linux-android` → `--target=aarch64-linux-android28` (CMake passes target without API level, breaking CRT file lookup)
- Use bash arrays for proper argument quoting (important for libtool which passes args with spaces)

### 2. libevent pthread detection
CMake's FindThreads fails the `CMAKE_HAVE_LIBC_PTHREAD` test but falls back to `-pthread` flag. We pass `-DCMAKE_HAVE_LIBC_PTHREAD=ON` to skip the failing test.

### 3. Fuzz binary uses glibc-only `cookie_io_functions_t`
Must configure with `--disable-fuzz-binary` for Android builds.

## Build Steps

```bash
# Set environment
export ANDROID_NDK=/path/to/android-sdk/ndk/27.2.12479018
TOOLCHAIN=$ANDROID_NDK/toolchains/llvm/prebuilt/darwin-x86_64
WRAPDIR=/path/to/ndk-wrappers
export PATH="$WRAPDIR:$TOOLCHAIN/bin:/usr/local/opt/libtool/libexec/gnubin:$PATH"

# 1. Build depends (~20 min on i7-4870HQ)
cd src/depends
make HOST=aarch64-linux-android NO_QT=1 -j4

# 2. Generate configure
cd ..
./autogen.sh

# 3. Configure
DEPENDS_PREFIX=$(pwd)/depends/aarch64-linux-android
CONFIG_SITE=$DEPENDS_PREFIX/share/config.site \
./configure --prefix=$DEPENDS_PREFIX \
  --host=aarch64-linux-android \
  --disable-tests --disable-bench --disable-fuzz-binary \
  --with-gui=no --enable-reduce-exports

# 4. Build (~15 min)
make -j4

# 5. Strip binaries
llvm-strip src/bitcoind src/bitcoin-cli src/bitcoin-tx src/bitcoin-wallet
```

## Output
- `bitcoind` — 13MB stripped, ELF 64-bit ARM aarch64
- `bitcoin-cli` — 919KB stripped
- `bitcoin-tx` — 2.7MB stripped
- `bitcoin-wallet` — 7.7MB stripped

## Build Configuration
- Bitcoin Core v28.1
- NDK r27 (clang 18.0.3)
- API level 28 (Android 9.0+)
- Static linking for all dependencies (boost, libevent, BDB, SQLite, ZeroMQ, etc.)
- No Qt/GUI, no tests, no fuzz
