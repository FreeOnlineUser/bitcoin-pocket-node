package com.pocketnode.tor

import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/**
 * HTTP utility that routes through the Arti SOCKS proxy when Tor is enabled.
 * Automatically maps clearnet domains to .onion equivalents where known.
 */
object TorAwareHttp {

    /** Known .onion mappings for privacy-critical services. */
    private val ONION_MAP = mapOf(
        "mempool.space" to "mempoolhqx4isw62xs7abwphsq7ldayuidyx2v2oethdhhj6mlo2r6ad.onion"
    )

    /**
     * Open a URL connection, routing through Tor if enabled.
     * .onion URLs are used automatically when available.
     */
    fun openConnection(url: String): HttpURLConnection {
        val torEnabled = TorManager.enabledFlow.value && TorManager.statusFlow.value == TorManager.TorStatus.RUNNING

        val targetUrl = if (torEnabled) toOnionUrl(url) else url
        val proxy = if (torEnabled) {
            Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", TorManager.SOCKS_PORT))
        } else {
            Proxy.NO_PROXY
        }

        return URL(targetUrl).openConnection(proxy) as HttpURLConnection
    }

    /**
     * Get the appropriate base URL for a service.
     * Returns .onion URL when Tor is running, clearnet otherwise.
     */
    fun baseUrl(clearnetUrl: String): String {
        val torEnabled = TorManager.enabledFlow.value && TorManager.statusFlow.value == TorManager.TorStatus.RUNNING
        return if (torEnabled) toOnionUrl(clearnetUrl) else clearnetUrl
    }

    /**
     * Replace clearnet domain with .onion equivalent.
     * HTTPS → HTTP for .onion (Tor provides encryption).
     */
    private fun toOnionUrl(url: String): String {
        var result = url
        for ((domain, onion) in ONION_MAP) {
            if (result.contains(domain)) {
                result = result.replace("https://$domain", "http://$onion")
                result = result.replace("http://$domain", "http://$onion")
            }
        }
        return result
    }

    /**
     * Open a connection, choosing the proxy by the target rather than by the
     * global Tor toggle:
     *  - a .onion host is unreachable without Tor, so it always routes through
     *    the Arti SOCKS proxy (and fails clearly if Tor isn't running);
     *  - a clearnet host routes through Tor only when Tor is running, so a
     *    public snapshot (utxo.download) isn't dragged through a slow circuit
     *    when the user hasn't opted into Tor.
     *
     * On Android, HttpURLConnection is OkHttp-backed and hands the hostname to
     * the SOCKS proxy for resolution, so .onion names resolve inside Tor (same
     * path openConnection() already uses for mempool).
     */
    fun openRouted(url: String): HttpURLConnection {
        val host = URL(url).host.lowercase()
        val isOnion = host.endsWith(".onion")
        val torRunning = TorManager.enabledFlow.value &&
            TorManager.statusFlow.value == TorManager.TorStatus.RUNNING
        if (isOnion && !torRunning) {
            throw java.io.IOException("Tor must be running to reach a .onion address")
        }
        val proxy = if (isOnion || torRunning) {
            Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", TorManager.SOCKS_PORT))
        } else {
            Proxy.NO_PROXY
        }
        return URL(url).openConnection(proxy) as HttpURLConnection
    }

    /**
     * Get a SOCKS proxy for use with OkHttp or other HTTP clients.
     * Returns Proxy.NO_PROXY if Tor is not running.
     */
    fun getProxy(): Proxy {
        val torEnabled = TorManager.enabledFlow.value && TorManager.statusFlow.value == TorManager.TorStatus.RUNNING
        return if (torEnabled) {
            Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", TorManager.SOCKS_PORT))
        } else {
            Proxy.NO_PROXY
        }
    }
}
