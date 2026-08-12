package com.pocketnode.util

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Manages Bitcoin implementation binaries.
 *
 * Supports multiple Bitcoin Core releases bundled as native libraries.
 * User selects which version to run; the selected binary is used by BitcoindService.
 *
 * Binaries are packaged as lib*.so in jniLibs/arm64-v8a/. This naming is required
 * because Android only extracts files matching lib*.so from the APK to nativeLibraryDir.
 * That directory has the execute permission needed to run binaries -- other app directories
 * (filesDir, cacheDir) are mounted noexec on modern Android and GrapheneOS.
 */
object BinaryExtractor {

    private const val TAG = "BinaryExtractor"
    private const val PREFS_NAME = "pocketnode_prefs"
    private const val KEY_BITCOIN_VERSION = "bitcoin_version"

    /**
     * Available Bitcoin implementations. Both are Bitcoin Core releases that
     * follow mainnet consensus; they differ only in relay / OP_RETURN policy.
     */
    enum class BitcoinVersion(
        val libraryName: String,
        val displayName: String,
        val versionString: String,
        val description: String,
        val policyStance: String
    ) {
        CORE(
            "libbitcoind_core.so",
            "Bitcoin Core",
            "29.3",
            "Reference implementation. Standard relay rules and OP_RETURN limits.",
            "Standard -- default relay policy"
        ),
        CORE_30(
            "libbitcoind_v30.so",
            "Bitcoin Core",
            "30.0",
            "Latest release. Relaxed OP_RETURN data size limits.",
            "Permissive -- larger OP_RETURN data allowed"
        );

        companion object {
            val DEFAULT = CORE

            fun fromName(name: String): BitcoinVersion {
                return try {
                    // Legacy selections: retired Knots builds and the old Core
                    // naming both fall back to the current Core default.
                    when (name) {
                        "KNOTS", "KNOTS_BIP110", "CORE_28_1" -> CORE
                        else -> valueOf(name)
                    }
                } catch (_: IllegalArgumentException) {
                    DEFAULT
                }
            }
        }
    }

    /**
     * Check if a specific version's binary is bundled in this APK.
     */
    fun isAvailable(context: Context, version: BitcoinVersion): Boolean {
        val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
        val binary = File(nativeLibDir, version.libraryName)
        return binary.exists() && binary.canExecute()
    }

    /**
     * Get all available (bundled) versions.
     */
    fun availableVersions(context: Context): List<BitcoinVersion> {
        return BitcoinVersion.entries.filter { isAvailable(context, it) }
    }

    /**
     * Get the user's selected Bitcoin version.
     */
    fun getSelectedVersion(context: Context): BitcoinVersion {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_BITCOIN_VERSION, null) ?: return BitcoinVersion.DEFAULT
        val version = BitcoinVersion.fromName(name)
        // Fall back to default if selected version isn't available
        return if (isAvailable(context, version)) version else BitcoinVersion.DEFAULT
    }

    /**
     * Set the user's selected Bitcoin version.
     */
    fun setSelectedVersion(context: Context, version: BitcoinVersion) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BITCOIN_VERSION, version.name)
            .apply()
        Log.i(TAG, "Selected Bitcoin version: ${version.displayName} ${version.versionString}")
    }

    /**
     * Get the bitcoind binary for the currently selected version.
     * Falls back through: selected version -> default version -> legacy name.
     */
    fun extractIfNeeded(context: Context): File {
        val selected = getSelectedVersion(context)
        return getBinary(context, selected)
    }

    /**
     * Get the bitcoind binary for a specific version.
     */
    fun getBinary(context: Context, version: BitcoinVersion): File {
        val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
        val binary = File(nativeLibDir, version.libraryName)

        if (binary.exists() && binary.canExecute()) {
            Log.i(TAG, "bitcoind [${version.displayName} ${version.versionString}] at: ${binary.absolutePath} (${binary.length()} bytes)")
            return binary
        }

        // Fallback: legacy single-binary name (pre-version-selection)
        val legacy = File(nativeLibDir, "libbitcoind.so")
        if (legacy.exists() && legacy.canExecute()) {
            Log.w(TAG, "Using legacy binary at: ${legacy.absolutePath}")
            return legacy
        }

        throw RuntimeException("bitcoind binary not found for ${version.displayName} ${version.versionString}")
    }
}
