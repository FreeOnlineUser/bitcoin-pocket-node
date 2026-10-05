package com.pocketnode.lightning

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

class RpcRelayTest {

    private val prunedHash = "00000000000000000000aaaa" + "0".repeat(40)
    private val goodHash = "00000000000000000000bbbb" + "0".repeat(40)
    private val bigBlock = ByteArray(5_000_000) { (it % 251).toByte() }

    private lateinit var upstream: ServerSocket
    private lateinit var relay: RpcRelay
    private val reported = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        upstream = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        Thread {
            while (true) {
                val s = try { upstream.accept() } catch (_: Exception) { break }
                Thread { fakeBitcoind(s) }.start()
            }
        }.apply { isDaemon = true }.start()
        relay = RpcRelay(upstream.localPort) { reported.add(it) }.start()
    }

    @After
    fun tearDown() {
        relay.stop()
        upstream.close()
    }

    /** Answers one request per connection, like bitcoind behind our relay. */
    private fun fakeBitcoind(s: Socket) { s.use {
        val input = BufferedInputStream(s.getInputStream())
        val (_, body) = readHttp(input) ?: return
        val text = String(body)
        val out = s.getOutputStream()
        when {
            text.contains(prunedHash) -> {
                val err = """{"result":null,"error":{"code":-1,"message":"Block not available (pruned data)"},"id":1}"""
                out.write("HTTP/1.1 500 Internal Server Error\r\nContent-Type: application/json\r\nContent-Length: ${err.length}\r\n\r\n$err".toByteArray())
            }
            text.contains("chunked") -> {
                out.write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n".toByteArray())
                out.write("5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n".toByteArray())
            }
            text.contains(goodHash) -> {
                out.write("HTTP/1.1 200 OK\r\nContent-Length: ${bigBlock.size}\r\n\r\n".toByteArray())
                out.write(bigBlock)
            }
            else -> {
                val ok = """{"result":42,"error":null,"id":1}"""
                out.write("HTTP/1.1 200 OK\r\nContent-Length: ${ok.length}\r\n\r\n$ok".toByteArray())
            }
        }
        out.flush()
    } }

    private fun request(body: String) =
        "POST / HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Basic eDp5\r\nConnection: keep-alive\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n$body"

    @Test
    fun forwardsKeepAliveRequestsAndReportsOnlyPrunedGetblock() {
        Socket("127.0.0.1", relay.port).use { s ->
            val out = s.getOutputStream()
            val input = BufferedInputStream(s.getInputStream())

            out.write(request("""{"method":"getblockcount","params":[],"id":1}""").toByteArray()); out.flush()
            assertEquals("""{"result":42,"error":null,"id":1}""", String(readHttp(input)!!.second))

            out.write(request("""{"method":"getblock","params":["$goodHash",0],"id":2}""").toByteArray()); out.flush()
            assertArrayEquals(bigBlock, readHttp(input)!!.second)

            out.write(request("""{"method":"getblock","params":["$prunedHash",0],"id":3}""").toByteArray()); out.flush()
            assertTrue(String(readHttp(input)!!.second).contains("pruned data"))

            out.write(request("""{"method":"sendrawtransaction","params":["0200"],"id":4}""").toByteArray()); out.flush()
            assertEquals("""{"result":42,"error":null,"id":1}""", String(readHttp(input)!!.second))
        }
        Thread.sleep(100)
        assertEquals(listOf(prunedHash), reported.toList())
    }

    @Test
    fun passesChunkedResponsesThrough() {
        Socket("127.0.0.1", relay.port).use { s ->
            s.getOutputStream().write(request("""{"method":"chunked","params":[],"id":1}""").toByteArray())
            assertEquals("hello world", String(readHttp(BufferedInputStream(s.getInputStream()))!!.second))
        }
    }

    // Minimal HTTP reader for the test side (content-length and chunked).
    private fun readHttp(input: InputStream): Pair<Map<String, String>, ByteArray>? {
        val head = ByteArrayOutputStream()
        while (!head.toString(Charsets.ISO_8859_1.name()).endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0) return null
            head.write(b)
        }
        val headers = head.toString(Charsets.ISO_8859_1.name()).split("\r\n").drop(1)
            .filter { it.contains(':') }
            .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        headers["content-length"]?.toInt()?.let { n ->
            val buf = ByteArray(n); var off = 0
            while (off < n) { val r = input.read(buf, off, n - off); if (r < 0) break; off += r }
            return headers to buf
        }
        if (headers["transfer-encoding"] == "chunked") {
            val body = ByteArrayOutputStream()
            fun line(): String { val l = ByteArrayOutputStream(); while (true) { val b = input.read(); if (b == '\n'.code || b < 0) break; if (b != '\r'.code) l.write(b) }; return l.toString() }
            while (true) {
                val n = line().trim().toInt(16)
                if (n == 0) { line(); break }
                val buf = ByteArray(n); var off = 0
                while (off < n) { val r = input.read(buf, off, n - off); if (r < 0) break; off += r }
                body.write(buf); line()
            }
            return headers to body.toByteArray()
        }
        return headers to ByteArray(0)
    }
}
