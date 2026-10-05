package com.pocketnode.lightning

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * A pass-through relay between LDK and bitcoind's RPC port that notices when
 * bitcoind answers a getblock with "pruned data".
 *
 * LDK syncs several listeners (channel manager, on-chain wallet, sweeper,
 * channel monitors) from the oldest one's position, and we can't see each
 * one's height from the bindings. When the oldest sits below the prune
 * height, LDK asks for a block bitcoind no longer has, and the sync retries
 * forever. This relay reports exactly which block that was, so the prune feed
 * can fetch it from peers, and answers getblock for blocks the feed holds.
 *
 * Everything else is forwarded unchanged in both directions (claims and justice
 * transactions are broadcast over this path); the relay only reads a copy of
 * getblock requests and their error replies. Plain threads, no coroutines:
 * it starts before node.start(), see LightningService.start.
 */
class RpcRelay(
    private val upstreamPort: Int,
    private val upstreamHost: String = "127.0.0.1",
    /** Raw bytes of a block bitcoind has pruned, if the prune feed holds a copy. */
    private val cachedBlock: (blockHash: String) -> ByteArray? = { null },
    private val onPrunedBlock: (blockHash: String) -> Unit
) {
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))

    /** Local port LDK should connect to instead of bitcoind's. */
    val port: Int get() = server.localPort

    @Volatile private var running = true

    fun start(): RpcRelay {
        Thread({
            while (running) {
                val client = try { server.accept() } catch (_: Exception) { break }
                Thread({ serve(client) }, "rpc-relay-conn").apply { isDaemon = true }.start()
            }
        }, "rpc-relay").apply { isDaemon = true }.start()
        return this
    }

    fun stop() {
        running = false
        try { server.close() } catch (_: Exception) {}
    }

    // An exception escaping a plain thread kills the whole app, bitcoind included.
    // A peer resetting the socket is routine (LDK drops its connections when the
    // node stops), so any I/O failure just ends this connection.
    private fun serve(client: Socket) = try {
        relay(client)
    } catch (_: java.io.IOException) {
    } catch (e: Exception) {
        android.util.Log.w("RpcRelay", "Connection ended: $e")
    }

    private fun relay(client: Socket) {
        client.use { c ->
            val cin = BufferedInputStream(c.getInputStream())
            val cout = c.getOutputStream()
            while (running) {
                val request = readMessage(cin, isResponse = false) ?: return
                val blockHash = getblockHash(request.body)

                // A block the prune feed copied out before bitcoind pruned it again.
                val cached = blockHash?.let { cachedBlock(it) }
                if (cached != null) {
                    cout.write(blockResponse(request.body, cached))
                    cout.flush()
                    if (request.closeAfter) return
                    continue
                }

                val response = Socket(upstreamHost, upstreamPort).use { up ->
                    up.soTimeout = 300_000
                    val upOut = up.getOutputStream()
                    upOut.write(request.raw)
                    upOut.flush()
                    readMessage(BufferedInputStream(up.getInputStream()), isResponse = true)
                } ?: return

                cout.write(response.raw)
                cout.flush()

                if (blockHash != null && isPrunedError(response.body)) onPrunedBlock(blockHash)
                if (response.closeAfter || request.closeAfter) return
            }
        }
    }

    private class Message(val raw: ByteArray, val body: ByteArray, val closeAfter: Boolean)

    /**
     * Read one HTTP/1.x message. [raw] is the exact bytes received (forwarded
     * as-is); [body] is the decoded body, for inspection only. Null on EOF.
     */
    private fun readMessage(input: InputStream, isResponse: Boolean): Message? {
        val head = ByteArrayOutputStream()
        // Headers end at CRLFCRLF.
        var matched = 0
        while (matched < 4) {
            val b = input.read()
            if (b < 0) return null
            head.write(b)
            matched = when {
                (matched == 0 || matched == 2) && b == '\r'.code -> matched + 1
                (matched == 1 || matched == 3) && b == '\n'.code -> matched + 1
                b == '\r'.code -> 1
                else -> 0
            }
        }
        val headBytes = head.toByteArray()
        val lines = String(headBytes, Charsets.ISO_8859_1).split("\r\n")
        val startLine = lines.firstOrNull() ?: return null
        val headers = lines.drop(1).filter { it.contains(':') }.associate {
            it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim()
        }
        val http10 = startLine.contains("HTTP/1.0")
        val connection = headers["connection"]?.lowercase()
        var closeAfter = connection == "close" || (http10 && connection != "keep-alive")

        val raw = ByteArrayOutputStream()
        raw.write(headBytes)
        val body: ByteArray
        val length = headers["content-length"]?.toLongOrNull()
        when {
            headers["transfer-encoding"]?.lowercase()?.contains("chunked") == true -> {
                body = readChunked(input, raw)
            }
            length != null -> {
                body = readExactly(input, length.toInt())
                raw.write(body)
            }
            isResponse -> {
                // No length: the body runs to EOF, so the connection ends with it.
                body = input.readBytes()
                raw.write(body)
                closeAfter = true
            }
            else -> body = ByteArray(0)
        }
        return Message(raw.toByteArray(), body, closeAfter)
    }

    private fun readExactly(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) throw java.io.EOFException("Body ended after $off of $n bytes")
            off += r
        }
        return buf
    }

    private fun readLine(input: InputStream, raw: OutputStream): String {
        val line = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) throw java.io.EOFException("EOF in chunk header")
            raw.write(b)
            if (b == '\n'.code) break
            if (b != '\r'.code) line.write(b)
        }
        return String(line.toByteArray(), Charsets.ISO_8859_1)
    }

    private fun readChunked(input: InputStream, raw: OutputStream): ByteArray {
        val decoded = ByteArrayOutputStream()
        while (true) {
            val size = readLine(input, raw).substringBefore(';').trim().toInt(16)
            if (size == 0) {
                // Trailers, then a blank line.
                while (readLine(input, raw).isNotEmpty()) {}
                return decoded.toByteArray()
            }
            val chunk = readExactly(input, size)
            raw.write(chunk)
            decoded.write(chunk)
            readLine(input, raw)  // CRLF after the chunk
        }
    }

    private fun getblockHash(body: ByteArray): String? {
        if (body.isEmpty() || body.size > 4096) return null
        val text = String(body, Charsets.UTF_8)
        if (!text.contains("\"getblock\"")) return null
        return try {
            val json = org.json.JSONObject(text)
            if (json.optString("method") != "getblock") null
            else json.optJSONArray("params")?.optString(0)?.takeIf { it.length == 64 }
        } catch (_: Exception) { null }
    }

    /** The reply bitcoind would give to getblock <hash> 0, built from the raw block. */
    private fun blockResponse(requestBody: ByteArray, block: ByteArray): ByteArray {
        val id = try { org.json.JSONObject(String(requestBody, Charsets.UTF_8)).opt("id") } catch (_: Exception) { null }
        val idJson = when (id) {
            null, org.json.JSONObject.NULL -> "null"
            is String -> org.json.JSONObject.quote(id)
            else -> id.toString()
        }
        val body = ByteArrayOutputStream(block.size * 2 + 64)
        body.write("{\"result\":\"".toByteArray())
        body.write(toHex(block))
        body.write("\",\"error\":null,\"id\":$idJson}".toByteArray())
        val head = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size()}\r\n\r\n"
        return head.toByteArray() + body.toByteArray()
    }

    private fun toHex(bytes: ByteArray): ByteArray {
        val digits = "0123456789abcdef".toByteArray()
        val out = ByteArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xff
            out[2 * i] = digits[v ushr 4]
            out[2 * i + 1] = digits[v and 0x0f]
        }
        return out
    }

    private fun isPrunedError(body: ByteArray): Boolean {
        if (body.size > 4096) return false  // a real block, not an error
        val text = String(body, Charsets.UTF_8)
        return text.contains("pruned data", ignoreCase = true)
    }
}
