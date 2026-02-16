# Android Emulator Testing Results

**Date:** 2026-02-14
**Host:** macOS Big Sur 11.7.10 (x86_64)

## Summary

**Emulator testing blocked** — cannot run ARM64 Android emulator on x86_64 macOS.

## What We Tried

1. **Android Emulator 36.4.9** (latest via sdkmanager) — crashes on Big Sur due to libc++ ABI incompatibility (`__ZTVNSt3__115basic_stringbufIcNS_11char_traitsIcEENS_9allocatorIcEEEE` symbol not found). Requires macOS 12+.

2. **Android Emulator 33.1.24** (older, compatible with Big Sur) — launches but refuses to run ARM64 AVD: `PANIC: Avd's CPU Architecture 'arm64' is not supported by the QEMU2 emulator on x86_64 host.`

3. Only ARM64 system image is installed (`system-images/android-34/google_apis/arm64-v8a`). An x86_64 system image wouldn't help since our binary is ARM64.

## Binary Verification

The bitcoind binary itself appears correctly built:

```
ELF 64-bit LSB pie executable, ARM aarch64, version 1 (SYSV),
dynamically linked, interpreter /system/bin/linker64, stripped
```

**Size:** 13.5 MB
**Dynamic dependencies:**
- `libz.so` (system)
- `libm.so` (system)
- `libc++_shared.so` (must be bundled from NDK)
- `libdl.so` (system)
- `libc.so` (system)

All companion binaries also built:
- `bitcoin-cli` (941 KB)
- `bitcoin-tx` (2.8 MB)
- `bitcoin-wallet` (8.1 MB)

## How to Test

### Option A: Physical Android Device (Recommended)
```bash
export ADB=/Users/joehey/tools/android-sdk/platform-tools/adb
export NDK=/Users/joehey/tools/android-sdk/ndk/27.2.12479018
export BUILD=/Users/joehey/clawd/projects/bitcoin-pocket-node/build/android-arm64

# Push binaries
$ADB push $BUILD/bitcoind /data/local/tmp/
$ADB push $BUILD/bitcoin-cli /data/local/tmp/
$ADB push $NDK/toolchains/llvm/prebuilt/darwin-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so /data/local/tmp/

# Make executable
$ADB shell chmod +x /data/local/tmp/bitcoind /data/local/tmp/bitcoin-cli

# Create config
$ADB shell "mkdir -p /data/local/tmp/bitcoin-data && cat > /data/local/tmp/bitcoin.conf << 'EOF'
regtest=1
server=1
rpcuser=test
rpcpassword=test
rpcallowip=127.0.0.1
rpcbind=127.0.0.1
EOF"

# Run with LD_LIBRARY_PATH for libc++_shared.so
$ADB shell "cd /data/local/tmp && LD_LIBRARY_PATH=/data/local/tmp ./bitcoind -conf=/data/local/tmp/bitcoin.conf -datadir=/data/local/tmp/bitcoin-data -daemon"

# Test RPC
$ADB shell "cd /data/local/tmp && LD_LIBRARY_PATH=/data/local/tmp ./bitcoin-cli -conf=/data/local/tmp/bitcoin.conf -datadir=/data/local/tmp/bitcoin-data getblockchaininfo"
```

### Option B: ARM-based Mac (M1/M2/M3)
The Android emulator on Apple Silicon natively runs ARM64 AVDs. Testing would work out of the box.

### Option C: Cloud ARM64 Instance
Use an AWS Graviton or Oracle ARM instance running Android in a container (e.g., via redroid).

## Deployment Note

When packaging for Umbrel/Android, remember to bundle `libc++_shared.so` from the NDK alongside the bitcoind binary:
```
ndk/27.2.12479018/toolchains/llvm/prebuilt/darwin-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so
```
