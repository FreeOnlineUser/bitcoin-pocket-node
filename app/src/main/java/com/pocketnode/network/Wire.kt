package com.pocketnode.network

import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Bitcoin P2P wire-format primitives shared by HeadersClient and SpvFetcher.
 */
internal object Wire {
    val MAGIC_BYTES = byteArrayOf(0xF9.toByte(), 0xBE.toByte(), 0xB4.toByte(), 0xD9.toByte())
    const val PROTOCOL_VERSION = 70016

    fun sendMsg(output: OutputStream, command: String, payload: ByteArray) {
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        header.put(MAGIC_BYTES)
        val cmd = ByteArray(12)
        command.toByteArray().copyInto(cmd)
        header.put(cmd)
        header.putInt(payload.size)
        header.put(dsha256(payload), 0, 4)
        output.write(header.array())
        output.write(payload)
        output.flush()
    }

    /** maxLen guards runaway peers; blocks legitimately reach ~4 MB. */
    fun readMsg(input: DataInputStream, maxLen: Int = 8_000_000): Pair<String, ByteArray> {
        val header = ByteArray(24)
        input.readFully(header)
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(4); buf.get(magic)
        if (!magic.contentEquals(MAGIC_BYTES)) throw IOException("bad magic")
        val cmdBytes = ByteArray(12); buf.get(cmdBytes)
        val command = String(cmdBytes).trimEnd { it.code == 0 }
        val length = buf.int
        if (length < 0 || length > maxLen) throw IOException("bad payload length $length")
        buf.int // checksum: payload integrity is TCP's job for our purposes
        val payload = ByteArray(length)
        input.readFully(payload)
        return command to payload
    }

    fun readVarInt(data: ByteArray, offset: Int): Pair<Long, Int> {
        val first = data[offset].toInt() and 0xFF
        return when {
            first < 0xFD -> first.toLong() to 1
            first == 0xFD -> {
                val v = ((data[offset + 1].toInt() and 0xFF) or
                    ((data[offset + 2].toInt() and 0xFF) shl 8)).toLong()
                v to 3
            }
            first == 0xFE -> {
                var v = 0L
                for (i in 0 until 4) v = v or ((data[offset + 1 + i].toLong() and 0xFF) shl (8 * i))
                v to 5
            }
            else -> {
                var v = 0L
                for (i in 0 until 8) v = v or ((data[offset + 1 + i].toLong() and 0xFF) shl (8 * i))
                v to 9
            }
        }
    }

    fun dsha256(data: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(md.digest(data))
    }

    fun dsha256(data: ByteArray, offset: Int, length: Int): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(data, offset, length)
        return md.digest(md.digest())
    }

    /** Display hex (big-endian) to internal byte order (little-endian). */
    fun hexToInternal(hex: String): ByteArray {
        val bytes = ByteArray(32)
        for (i in 0 until 32) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            bytes[31 - i] = ((hi shl 4) or lo).toByte()
        }
        return bytes
    }

    /** Internal byte order to display hex. */
    fun internalToHex(bytes: ByteArray): String =
        bytes.reversedArray().joinToString("") { "%02x".format(it) }
}
