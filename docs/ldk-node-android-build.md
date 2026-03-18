# Building ldk-node for Android (ARM64)

Cross-compile the custom ldk-node fork (with watchtower bridge, Tor SOCKS proxy, and UniFFI bindings) from macOS to `aarch64-linux-android`.

## Source

Fork: `https://github.com/FreeOnlineUser/ldk-node`, branch `watchtower-bridge`

Custom features over upstream ldk-node:
- **Watchtower bridge**: `watchtowerExportMonitors()` for LDK-to-LND monitor export
- **SOCKS proxy**: `set_tor_proxy()` routes Lightning peer connections through Tor
- **Cooperative close feerate**: `close_channel_with_target_feerate()` for anchor channels
- **PeerDetails.supportsAnchors**: exposed from InitFeatures for anchor detection
- **Wallet birthday**: block hash lookup for instant restore

## Prerequisites

- **Rust**: stable toolchain with `aarch64-linux-android` target
  ```bash
  rustup target add aarch64-linux-android
  ```
- **Android NDK r27**: `~/tools/android-sdk/ndk/27.2.12479018`
- **uniffi-bindgen**: pre-built at `ldk-node/bindings/uniffi-bindgen/target/debug/uniffi-bindgen`

## Environment

```bash
export ANDROID_NDK_HOME="$HOME/tools/android-sdk/ndk/27.2.12479018"
export CC_aarch64_linux_android="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android28-clang"
export AR_aarch64_linux_android="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-ar"
```

Also ensure `~/.cargo/config.toml` has:
```toml
[target.aarch64-linux-android]
linker = "/Users/joehey/tools/android-sdk/ndk/27.2.12479018/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android28-clang"
```

## Step 1: Build the native library

```bash
cd ~/ldk-node
cargo build --target aarch64-linux-android --release --features uniffi --lib
```

This takes ~5-10 minutes on a 2014 MacBook Pro, ~2.5 minutes on modern hardware.

**Critical**: the `uniffi` feature flag is required. Without it, the .so has no FFI symbols and Kotlin can't call anything.

## Step 2: Strip the binary

```bash
$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-strip \
  target/aarch64-linux-android/release/libldk_node.so
```

Reduces from ~60MB to ~21MB.

## Step 3: Generate Kotlin bindings

```bash
bindings/uniffi-bindgen/target/debug/uniffi-bindgen \
  generate bindings/ldk_node.udl \
  --language kotlin \
  --out-dir /tmp/ldk-kotlin
```

Output: `/tmp/ldk-kotlin/org/lightningdevkit/ldknode/ldk_node.kt` (~16,500 lines)

## Step 4: Install into the Android project

### Native library
```bash
cp target/aarch64-linux-android/release/libldk_node.so \
  ~/bitcoin-pocket-node/app/src/main/jniLibs/arm64-v8a/libldk_node.so
```

### Kotlin bindings
```bash
mkdir -p ~/bitcoin-pocket-node/app/src/main/java/org/lightningdevkit/ldknode
cp /tmp/ldk-kotlin/org/lightningdevkit/ldknode/ldk_node.kt \
  ~/bitcoin-pocket-node/app/src/main/java/org/lightningdevkit/ldknode/
```

### AAR (stripped)
The AAR at `app/libs/ldk-node-android-0.7.0-watchtower.aar` must have an empty `classes.jar` since the Kotlin bindings are now in app source. If rebuilding the AAR:

```bash
# Extract existing AAR
mkdir /tmp/aar && cd /tmp/aar
unzip ~/bitcoin-pocket-node/app/libs/ldk-node-android-0.7.0-watchtower.aar -d contents

# Replace with empty classes.jar
cd contents
jar cf classes.jar -C /dev/null . 2>/dev/null || echo "" > classes.jar

# Update native lib
mkdir -p jni/arm64-v8a
cp ~/ldk-node/target/aarch64-linux-android/release/libldk_node.so jni/arm64-v8a/

# Repackage
zip -r ~/bitcoin-pocket-node/app/libs/ldk-node-android-0.7.0-watchtower.aar \
  AndroidManifest.xml R.txt classes.jar jni/
```

## Key types in UniFFI bindings

| Rust type | Kotlin type | Notes |
|-----------|-------------|-------|
| `SocketAddress` | `String` | Typedef, pass as `"127.0.0.1:9050"` |
| `PublicKey` | `String` | Hex-encoded 33-byte pubkey |
| `NodeId` | `String` | Same as PublicKey |
| `NetworkGraph` | `NetworkGraph` (class) | Has `.node(nodeId)` for alias lookup |
| `PeerDetails` | `PeerDetails` (data class) | nodeId, address, isConnected, supportsAnchors, isPersisted |

## Troubleshooting

### "no FFI symbols" / unresolved references in Kotlin
You forgot `--features uniffi` in the cargo build.

### MutexGuard not Send across await
If you add new async code in `connection.rs` or similar, copy Mutex values to local variables before `.await` points. The MutexGuard can't be held across yield points in async code.

### Checksum mismatch at runtime
The .so and the Kotlin bindings are out of sync. Regenerate bindings from the same commit you built the .so from. The UniFFI checksums are compiled into both sides.

### "WalletKeysManager" vs "KeysManager"
ldk-node's `KeysManager` is a type alias for `WalletKeysManager`, not `lightning::sign::KeysManager`. Use `crate::types::KeysManager` in ldk-node code.
