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
