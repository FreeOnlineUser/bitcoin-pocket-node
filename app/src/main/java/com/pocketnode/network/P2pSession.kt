package com.pocketnode.network

import com.pocketnode.tor.TorManager
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * One outbound Bitcoin P2P connection: connect, version/verack handshake,
 * then message exchange. Answers pings and other peers' getheaders itself
 * so callers only see the messages they asked for.
 */
internal class P2pSession(
    private val host: String,
    private val port: Int,
    private val torActive: Boolean
) : AutoCloseable {

    companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 15_000
        // Blend in with the dominant implementation: a distinct UA on
        // short-lived connections would fingerprint a wallet-carrying node.
        const val USER_AGENT = "/Satoshi:29.0.0/"

        const val NODE_NETWORK = 1L
        const val NODE_COMPACT_FILTERS = 1L shl 6
        const val NODE_NETWORK_LIMITED = 1L shl 10
    }

    private val socket: Socket = if (torActive) {
        Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", TorManager.SOCKS_PORT)))
    } else {
        Socket()
    }
    private lateinit var input: DataInputStream
    private lateinit var output: OutputStream

    /** Service bits the peer advertised in its version message. */
    var peerServices: Long = 0
        private set

    fun connect(startHeight: Int) {
        val target = if (torActive) {
            InetSocketAddress.createUnresolved(host, port)
        } else {
            InetSocketAddress(host, port)
        }
        socket.connect(target, CONNECT_TIMEOUT_MS)
        socket.soTimeout = READ_TIMEOUT_MS
        input = DataInputStream(socket.getInputStream().buffered())
        output = socket.getOutputStream()

        Wire.sendMsg(output, "version", versionPayload(startHeight))
        var gotVersion = false
        var gotVerack = false
        var guard = 0
        while (!(gotVersion && gotVerack)) {
            if (guard++ > 20) throw IOException("handshake: too many messages")
            val (cmd, payload) = Wire.readMsg(input)
            when (cmd) {
                "version" -> {
                    gotVersion = true
                    if (payload.size >= 12) {
                        peerServices = ByteBuffer.wrap(payload, 4, 8)
                            .order(ByteOrder.LITTLE_ENDIAN).long
                    }
                    Wire.sendMsg(output, "verack", ByteArray(0))
                }
                "verack" -> gotVerack = true
                "ping" -> Wire.sendMsg(output, "pong", payload)
                else -> {} // wtxidrelay, sendaddrv2, etc.
            }
        }
    }

    fun send(command: String, payload: ByteArray) = Wire.sendMsg(output, command, payload)

    /**
     * Read until a message matching [wanted] arrives; answers pings and
     * peer getheaders along the way. Returns null after [maxOther]
     * unrelated messages or a read timeout.
     */
    fun await(vararg wanted: String, maxOther: Int = 50, maxLen: Int = 8_000_000): Pair<String, ByteArray>? {
        var other = 0
        while (other < maxOther) {
            val (cmd, payload) = try {
                Wire.readMsg(input, maxLen)
            } catch (_: java.net.SocketTimeoutException) {
                return null
            }
            when {
                cmd in wanted -> return cmd to payload
                cmd == "ping" -> Wire.sendMsg(output, "pong", payload)
                cmd == "getheaders" -> Wire.sendMsg(output, "headers", byteArrayOf(0))
                else -> other++
            }
        }
        return null
    }

    override fun close() {
        try { socket.close() } catch (_: Exception) {}
    }

    private fun versionPayload(startHeight: Int): ByteArray {
        val ua = USER_AGENT.toByteArray()
        val buf = ByteBuffer.allocate(86 + ua.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(Wire.PROTOCOL_VERSION)
        buf.putLong(0L)                          // services: none, we are a leech
        buf.putLong(System.currentTimeMillis() / 1000)
        buf.putLong(0L); buf.put(ByteArray(16)); buf.putShort(0)  // addr_recv (unused by peers)
        buf.putLong(0L); buf.put(ByteArray(16)); buf.putShort(0)  // addr_from
        buf.putLong(Random.nextLong())           // nonce
        buf.put(ua.size.toByte())                // varstr length (< 0xfd)
        buf.put(ua)
        buf.putInt(startHeight)
        buf.put(0)                               // relay=false: no tx invs (BIP 37)
        return buf.array()
    }
}
