# Bitcoin Pocket Node - Mempool Explorer

This is Phase 1 of the mempool explorer feature for the Bitcoin Pocket Node Android app.

## What's Been Built

### 1. GBT Rust Library (JNI) ✅

**Location:** `app/src/main/rust/gbt/`

- Ported mempool.space's GBT (getblocktemplate) Rust algorithm from NAPI to JNI bindings
- Replaced `#[napi]` / `napi_derive` with `jni` crate bindings
- Replaced NAPI object passing with JNI struct marshalling
- Kept the core algorithm identical to upstream
- Build target: aarch64-linux-android (same toolchain as BWT)
- Output: libgbt.so

**Key files:**
- `src/lib.rs` - JNI bindings and main entry points
- `src/gbt.rs` - Core GBT algorithm (unchanged from upstream)
- `src/audit_transaction.rs` - Transaction analysis logic
- `src/thread_transaction.rs` - Input transaction format
- `src/thread_acceleration.rs` - Fee acceleration support
- `src/u32_hasher_types.rs` - Performance-optimized hash types
- `Cargo.toml` - Rust dependencies

### 2. Mempool Data Service (Kotlin) ✅

**Location:** `app/src/main/java/com/pocketnode/mempool/`

- `MempoolService.kt` - Background service that polls Bitcoin RPC
- Polls `getrawmempool true` every 10 seconds (when screen visible)
- Parses mempool data into ThreadTransaction format
- Calls GBT via JNI for block projections
- Provides fee rate histogram data
- Tracks watched transactions for confirmation

**Data classes:**
- `ThreadTransaction.kt` - JNI-compatible transaction format
- `ThreadAcceleration.kt` - Fee acceleration data
- `GbtResult.kt` - GBT algorithm results
- `MempoolEntry.kt` - Bitcoin RPC mempool data format

**JNI Integration:**
- `GbtGenerator.kt` - Kotlin wrapper for Rust GBT library

### 3. Mempool UI Screens (Compose) ✅

**Location:** `app/src/main/java/com/pocketnode/ui/mempool/`

**MempoolScreen** (`MempoolScreen.kt`):
- Mempool stats bar (tx count, total vMB, vB/s inflow)
- Projected blocks visualization using Canvas
- Color scheme: magenta(1-2) → purple(3-4) → blue(5-10) → green(11-20) → yellow(21-50) → orange(51-100) → red(100+) sat/vB
- Tap-to-show block details (planned)
- Fee rate histogram

**TransactionSearchScreen** (`TransactionSearchScreen.kt`):
- Search by 64-character TXID
- Shows: fee rate, vsize, fee, position in projected blocks, time in mempool
- "Watch" button to monitor for confirmation
- Input validation and error handling

**FeeEstimatePanel** (`FeeEstimatePanel.kt`):
- Next block / 30 min / 1 hour recommended fees
- Color-coded by priority (red/orange/green)

**ViewModels:**
- `MempoolViewModel.kt` - Main mempool screen state management
- `TransactionSearchViewModel.kt` - Transaction search functionality

### 4. Navigation ✅

**Location:** `app/src/main/java/com/pocketnode/MainActivity.kt`

- Added mempool explorer as main screen
- Navigation to transaction search screen
- Material 3 dark theme with Bitcoin orange accents

## Architecture

- **Bitcoin RPC Integration**: Ready for existing bitcoind RPC client
- **Compose UI**: Material 3 dark theme + Bitcoin orange accents
- **Service Architecture**: `MempoolService` extends existing foreground service pattern
- **StateFlow**: Reactive UI updates via ViewModel + StateFlow
- **JNI**: Efficient Rust ↔ Kotlin integration for GBT algorithm

## Build Configuration

- **Android**: minSdk 26, targetSdk 34
- **Kotlin**: 1.8 target with Compose
- **Rust**: NDK build with aarch64-linux-android target
- **Dependencies**: Compose, Coroutines, Serialization, Navigation

## Missing Integrations (TODO)

1. **Bitcoin RPC Client**: Placeholder calls need actual `BitcoinRpcClient` integration
2. **Fee Estimation**: `estimatesmartfee` RPC integration
3. **Block Confirmation**: Watch transaction confirmation via `getbestblockhash`/`getblock`
4. **Background Service**: Integration with existing Bitcoin node foreground service

## AGPL-3.0 Attribution

The GBT algorithm in `app/src/main/rust/gbt/src/` is derived from mempool.space:
https://github.com/mempool/mempool/tree/master/rust/gbt

Original code is AGPL-3.0 licensed. This derivative work maintains the same license.

## Building

1. **Rust Setup**: Install Rust Android targets
   ```bash
   rustup target add aarch64-linux-android
   ```

2. **Android Build**: Standard Android Studio build
   - The build system automatically compiles Rust → libgbt.so
   - JNI bindings link Kotlin ↔ Rust

3. **Testing**: Use Android emulator or device with API 26+

## Next Steps (Phase 2)

1. Integrate with actual Bitcoin RPC client
2. Add real-time fee estimation from `estimatesmartfee`
3. Implement confirmation tracking
4. Add transaction details modal/sheet
5. Performance optimization for large mempools
6. Add mempool analytics (tx/hour, fee trends, etc.)

The core algorithm and UI framework are complete and ready for Bitcoin RPC integration.