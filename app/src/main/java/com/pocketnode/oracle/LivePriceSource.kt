package com.pocketnode.oracle

/**
 * A price from transactions not yet buried 6 blocks deep. Only the internal
 * edition provides one (see [Edition]); the public edition returns null.
 */
interface LivePriceSource {
    /** How far back the sample reaches, for display. */
    val windowMinutes: Int

    /** Outputs counted in the last estimate, for display. */
    val sampleOutputs: Int

    /** A fresh estimate, or null if there isn't enough data yet. */
    suspend fun update(oracle: UTXOracle, tipHeight: Int): OracleResult?

    fun reset()
}
