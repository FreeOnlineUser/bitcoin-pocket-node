package com.pocketnode.util

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Locates the bitcoind binary from the app's native library directory.
 *
 * The binary is packaged as libbitcoind.so in jniLibs/arm64-v8a/ and
 * Android extracts it to nativeLibraryDir, where execution is permitted
 * (even on GrapheneOS with W^X enforcement).
 */
object BinaryExtractor {

    private const val TAG = "BinaryExtractor"

    /**
     * Get the bitcoind binary path from the native library directory.
     * @return File pointing to the bitcoind binary (executable).
     */
    fun extractIfNeeded(context: Context): File {
        val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
        val bitcoind = File(nativeLibDir, "libbitcoind.so")

        if (bitcoind.exists() && bitcoind.canExecute()) {
            Log.i(TAG, "bitcoind found at: ${bitcoind.absolutePath} (${bitcoind.length()} bytes)")
            return bitcoind
        }

        // Fallback: check old location (files/bin/)
        val legacyBin = File(context.filesDir, "bin/bitcoind")
        if (legacyBin.exists() && legacyBin.canExecute()) {
            Log.w(TAG, "Using legacy binary at: ${legacyBin.absolutePath}")
            return legacyBin
        }

        throw RuntimeException("bitcoind binary not found in nativeLibraryDir: ${nativeLibDir.absolutePath}")
    }
}
