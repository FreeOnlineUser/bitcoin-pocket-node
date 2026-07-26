package com.pocketnode.network

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for HeadersClient wire-format helpers.
 * The P2P socket path is exercised on-device; these cover the pure
 * parsing functions where an off-by-one silently corrupts a probe.
 */
class HeadersClientTest {

    @Test
    fun `varint single byte`() {
        val (value, size) = HeadersClient.readVarInt(byteArrayOf(0x7F), 0)
        assertEquals(127L, value)
        assertEquals(1, size)
    }

    @Test
    fun `varint zero`() {
        val (value, size) = HeadersClient.readVarInt(byteArrayOf(0x00), 0)
        assertEquals(0L, value)
        assertEquals(1, size)
    }

    @Test
    fun `varint uint16 - 2000 headers per message`() {
        // 2000 = 0xD007 little-endian after the 0xFD marker
        val data = byteArrayOf(0xFD.toByte(), 0xD0.toByte(), 0x07)
        val (value, size) = HeadersClient.readVarInt(data, 0)
        assertEquals(2000L, value)
        assertEquals(3, size)
    }

    @Test
    fun `varint uint32`() {
        val data = byteArrayOf(0xFE.toByte(), 0x01, 0x00, 0x01, 0x00)
        val (value, size) = HeadersClient.readVarInt(data, 0)
        assertEquals(65537L, value)
        assertEquals(5, size)
    }

    @Test
    fun `varint respects offset`() {
        val data = byteArrayOf(0x55, 0x55, 0x0A)
        val (value, size) = HeadersClient.readVarInt(data, 2)
        assertEquals(10L, value)
        assertEquals(1, size)
    }

    @Test
    fun `hexToInternal reverses byte order`() {
        val genesis = "000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f"
        val internal = HeadersClient.hexToInternal(genesis)
        assertEquals(32, internal.size)
        // Display hex is big-endian; internal order is reversed, so the
        // last display byte (0x6f) becomes the first internal byte
        assertEquals(0x6F.toByte(), internal[0])
        assertEquals(0xE2.toByte(), internal[1])
        // Leading display zeros land at the end
        assertEquals(0x00.toByte(), internal[31])
        assertEquals(0x00.toByte(), internal[30])
    }

    @Test
    fun `hexToInternal round trips`() {
        val hex = "000000000000000000014866d8dbba3ae0792c254f5e1784ed2ac48099b11119"
        val internal = HeadersClient.hexToInternal(hex)
        val back = internal.reversedArray().joinToString("") { "%02x".format(it) }
        assertEquals(hex, back)
    }
}
