package com.pocketnode.network

/**
 * BIP 158 basic block filter matching (Golomb-coded sets, P=19, M=784931).
 * Used to decide whether a deferred block can contain wallet outputs
 * before spending ~2 MB to download it.
 */
internal object Bip158 {
    private const val P = 19
    private const val M = 784931L

    /**
     * True if any of [scripts] may be in the block whose basic filter is
     * [filterBytes] (from a cfilter message) and whose hash is [blockHashInternal].
     * False positives are possible (~1 in 784931 per script); false negatives
     * are not, assuming an honest filter. A lying filter only delays a
     * confirmation until the full block arrives on WiFi.
     */
    fun matches(filterBytes: ByteArray, blockHashInternal: ByteArray, scripts: List<ByteArray>): Boolean {
        if (scripts.isEmpty() || filterBytes.isEmpty()) return false
        val (n, varIntSize) = Wire.readVarInt(filterBytes, 0)
        if (n == 0L) return false

        val k0 = leLong(blockHashInternal, 0)
        val k1 = leLong(blockHashInternal, 8)
        val f = n * M

        val targets = scripts.map { mapToRange(sipHash24(k0, k1, it), f) }.sorted()

        val reader = BitReader(filterBytes, varIntSize)
        var value = 0L
        var targetIdx = 0
        try {
            for (i in 0 until n) {
                var quotient = 0L
                while (reader.readBit() == 1) quotient++
                value += (quotient shl P) or reader.readBits(P)

                while (targetIdx < targets.size && targets[targetIdx] < value) targetIdx++
                if (targetIdx == targets.size) return false
                if (targets[targetIdx] == value) return true
            }
        } catch (_: IndexOutOfBoundsException) {
            return false // malformed filter: treat as no match, block arrives on WiFi
        }
        return false
    }

    /** (hash * f) >> 64, unsigned 128-bit multiply-high plus low carry. */
    internal fun mapToRange(hash: Long, f: Long): Long {
        // Manual mulhi: split into 32-bit halves (Math.multiplyHigh needs API 33)
        val aHi = hash ushr 32; val aLo = hash and 0xFFFFFFFFL
        val bHi = f ushr 32; val bLo = f and 0xFFFFFFFFL
        val mid1 = aHi * bLo; val mid2 = aLo * bHi
        val carry = ((aLo * bLo ushr 32) + (mid1 and 0xFFFFFFFFL) + (mid2 and 0xFFFFFFFFL)) ushr 32
        return aHi * bHi + (mid1 ushr 32) + (mid2 ushr 32) + carry
    }

    internal fun sipHash24(k0: Long, k1: Long, data: ByteArray): Long {
        var v0 = k0 xor 0x736f6d6570736575L
        var v1 = k1 xor 0x646f72616e646f6dL
        var v2 = k0 xor 0x6c7967656e657261L
        var v3 = k1 xor 0x7465646279746573L

        val len = data.size
        val end = len - (len % 8)
        var i = 0
        while (i < end) {
            val m = leLong(data, i)
            v3 = v3 xor m
            repeat(2) {
                v0 += v1; v1 = java.lang.Long.rotateLeft(v1, 13); v1 = v1 xor v0; v0 = java.lang.Long.rotateLeft(v0, 32)
                v2 += v3; v3 = java.lang.Long.rotateLeft(v3, 16); v3 = v3 xor v2
                v0 += v3; v3 = java.lang.Long.rotateLeft(v3, 21); v3 = v3 xor v0
                v2 += v1; v1 = java.lang.Long.rotateLeft(v1, 17); v1 = v1 xor v2; v2 = java.lang.Long.rotateLeft(v2, 32)
            }
            v0 = v0 xor m
            i += 8
        }

        var last = (len.toLong() and 0xFF) shl 56
        for (j in 0 until (len % 8)) {
            last = last or ((data[end + j].toLong() and 0xFF) shl (8 * j))
        }
        v3 = v3 xor last
        repeat(2) {
            v0 += v1; v1 = java.lang.Long.rotateLeft(v1, 13); v1 = v1 xor v0; v0 = java.lang.Long.rotateLeft(v0, 32)
            v2 += v3; v3 = java.lang.Long.rotateLeft(v3, 16); v3 = v3 xor v2
            v0 += v3; v3 = java.lang.Long.rotateLeft(v3, 21); v3 = v3 xor v0
            v2 += v1; v1 = java.lang.Long.rotateLeft(v1, 17); v1 = v1 xor v2; v2 = java.lang.Long.rotateLeft(v2, 32)
        }
        v0 = v0 xor last
        v2 = v2 xor 0xFF
        repeat(4) {
            v0 += v1; v1 = java.lang.Long.rotateLeft(v1, 13); v1 = v1 xor v0; v0 = java.lang.Long.rotateLeft(v0, 32)
            v2 += v3; v3 = java.lang.Long.rotateLeft(v3, 16); v3 = v3 xor v2
            v0 += v3; v3 = java.lang.Long.rotateLeft(v3, 21); v3 = v3 xor v0
            v2 += v1; v1 = java.lang.Long.rotateLeft(v1, 17); v1 = v1 xor v2; v2 = java.lang.Long.rotateLeft(v2, 32)
        }
        return v0 xor v1 xor v2 xor v3
    }

    private fun leLong(data: ByteArray, offset: Int): Long {
        var v = 0L
        for (j in 0 until 8) v = v or ((data[offset + j].toLong() and 0xFF) shl (8 * j))
        return v
    }

    /** MSB-first bit reader over a byte array. */
    private class BitReader(private val data: ByteArray, startByte: Int) {
        private var bytePos = startByte
        private var bitPos = 0

        fun readBit(): Int {
            if (bytePos >= data.size) throw IndexOutOfBoundsException()
            val bit = (data[bytePos].toInt() shr (7 - bitPos)) and 1
            bitPos++
            if (bitPos == 8) { bitPos = 0; bytePos++ }
            return bit
        }

        fun readBits(count: Int): Long {
            var v = 0L
            repeat(count) { v = (v shl 1) or readBit().toLong() }
            return v
        }
    }
}
