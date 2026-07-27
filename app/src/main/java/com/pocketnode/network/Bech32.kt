package com.pocketnode.network

/**
 * Bech32/Bech32m address decoding (BIP 173 / BIP 350), just enough to turn
 * a mainnet segwit address into its scriptPubKey for filter matching.
 */
internal object Bech32 {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private const val BECH32_CONST = 1L
    private const val BECH32M_CONST = 0x2bc830a3L

    /** Decode a mainnet segwit address to its scriptPubKey, or null if invalid. */
    fun addressToScriptPubKey(address: String): ByteArray? {
        val lower = address.lowercase()
        if (address != lower && address != address.uppercase()) return null // mixed case
        if (!lower.startsWith("bc1")) return null

        val pos = lower.lastIndexOf('1')
        if (pos < 1 || pos + 7 > lower.length || lower.length > 90) return null
        val hrp = lower.substring(0, pos)
        if (hrp != "bc") return null

        val data = IntArray(lower.length - pos - 1)
        for (i in data.indices) {
            val c = CHARSET.indexOf(lower[pos + 1 + i])
            if (c < 0) return null
            data[i] = c
        }

        val version = data[0]
        if (version > 16) return null
        val expected = if (version == 0) BECH32_CONST else BECH32M_CONST
        if (polymod(hrpExpand(hrp) + data) != expected) return null

        val program = convertBits(data.copyOfRange(1, data.size - 6)) ?: return null
        if (program.size < 2 || program.size > 40) return null
        if (version == 0 && program.size != 20 && program.size != 32) return null

        // scriptPubKey: OP_n <push len> <program>
        val opN = if (version == 0) 0x00 else 0x50 + version
        val script = ByteArray(2 + program.size)
        script[0] = opN.toByte()
        script[1] = program.size.toByte()
        program.copyInto(script, 2)
        return script
    }

    private fun hrpExpand(hrp: String): IntArray {
        val out = IntArray(hrp.length * 2 + 1)
        for (i in hrp.indices) {
            out[i] = hrp[i].code shr 5
            out[hrp.length + 1 + i] = hrp[i].code and 31
        }
        out[hrp.length] = 0
        return out
    }

    private fun polymod(values: IntArray): Long {
        val gen = longArrayOf(0x3b6a57b2L, 0x26508e6dL, 0x1ea119faL, 0x3d4233ddL, 0x2a1462b3L)
        var chk = 1L
        for (v in values) {
            val top = chk shr 25
            chk = ((chk and 0x1ffffffL) shl 5) xor v.toLong()
            for (i in 0 until 5) {
                if ((top shr i) and 1L != 0L) chk = chk xor gen[i]
            }
        }
        return chk
    }

    /** 5-bit groups to 8-bit bytes, no padding allowed at the end. */
    private fun convertBits(data: IntArray): ByteArray? {
        var acc = 0
        var bits = 0
        val out = ArrayList<Byte>()
        for (value in data) {
            if (value < 0 || value shr 5 != 0) return null
            acc = (acc shl 5) or value
            bits += 5
            while (bits >= 8) {
                bits -= 8
                out.add(((acc shr bits) and 0xFF).toByte())
            }
        }
        if (bits >= 5 || ((acc shl (8 - bits)) and 0xFF) != 0) return null
        return out.toByteArray()
    }
}
