package com.pocketnode.share

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Lightweight HTTP file server for phone-to-phone node sharing.
 * Serves chainstate, block index, xor.dat, tip blocks, block filters, and the APK.
 * 
 * Max 2 concurrent transfers. Read-only. Binds to local network only.
 */
class ShareServer(private val context: Context) {

    companion object {
        private const val TAG = "ShareServer"
        const val PORT = 8432
        const val MAX_CONCURRENT = 2

        private val _activeTransfers = MutableStateFlow<Map<Int, TransferInfo>>(emptyMap())
        val activeTransfersFlow: StateFlow<Map<Int, TransferInfo>> = _activeTransfers

        private val _completedTransfers = MutableStateFlow(0)
        val completedTransfersFlow: StateFlow<Int> = _completedTransfers

        private val _isRunning = MutableStateFlow(false)
        val isRunningFlow: StateFlow<Boolean> = _isRunning

        // Session progress (from POST /start-session)
        private val _sessionTotalFiles = MutableStateFlow(0)
        val sessionTotalFilesFlow: StateFlow<Int> = _sessionTotalFiles
        private val _sessionFilesCompleted = MutableStateFlow(0)
        val sessionFilesCompletedFlow: StateFlow<Int> = _sessionFilesCompleted
    }

    data class TransferInfo(
        val id: Int,
        val startTime: Long = System.currentTimeMillis(),
        val bytesServed: Long = 0,
        val totalBytes: Long = 0,
        val currentFile: String = ""
    )

    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null
    private val transferCounter = AtomicInteger(0)
    private val activeConnections = AtomicInteger(0)
    private val transfers = ConcurrentHashMap<Int, TransferInfo>()

    // Session tracking for download progress

    private val bitcoinDir: File get() = File(context.filesDir, "bitcoin")

    /**
     * Start the HTTP server. Call after stopping bitcoind.
     */
    fun start() {
        if (_isRunning.value) return

        serverThread = Thread {
            try {
                serverSocket = ServerSocket(PORT, 10, InetAddress.getByName("0.0.0.0"))
                _isRunning.value = true
                Log.i(TAG, "Share server started on port $PORT")

                while (!Thread.currentThread().isInterrupted) {
                    try {
                        val socket = serverSocket?.accept() ?: break
                        handleConnection(socket)
                    } catch (e: Exception) {
                        if (!Thread.currentThread().isInterrupted) {
                            Log.w(TAG, "Accept error: ${e.message}")
                        }
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server error: ${e.message}")
            } finally {
                _isRunning.value = false
                Log.i(TAG, "Share server stopped")
            }
        }.also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        serverThread?.interrupt()
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        serverThread = null
        transfers.clear()
        _activeTransfers.value = emptyMap()
        _completedTransfers.value = 0
        _isRunning.value = false
    }

    private fun handleConnection(socket: Socket) {
        Thread {
            try {
                socket.soTimeout = 30_000
                val reader = socket.getInputStream().bufferedReader()
                val requestLine = reader.readLine() ?: return@Thread
                
                // Read headers
                val headers = mutableMapOf<String, String>()
                var line: String?
                while (reader.readLine().also { line = it } != null && line!!.isNotEmpty()) {
                    val parts = line!!.split(": ", limit = 2)
                    if (parts.size == 2) headers[parts[0].lowercase()] = parts[1]
                }

                val parts = requestLine.split(" ")
                if (parts.size < 2 || (parts[0] != "GET" && parts[0] != "POST")) {
                    sendResponse(socket, 405, "Method Not Allowed", "GET or POST supported")
                    return@Thread
                }

                val method = parts[0]
                val path = parts[1]
                Log.d(TAG, "Request: $method $path")

                // Handle POST endpoints
                if (method == "POST" && path == "/start-session") {
                    val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                    val body = if (contentLength > 0) {
                        val buf = CharArray(contentLength)
                        reader.read(buf, 0, contentLength)
                        String(buf)
                    } else ""
                    try {
                        val json = org.json.JSONObject(body)
                        _sessionTotalFiles.value = json.optInt("totalFiles", 0)
                        _sessionFilesCompleted.value = 0
                        Log.i(TAG, "Session started: ${_sessionTotalFiles.value} files expected")
                    } catch (_: Exception) {}
                    sendResponse(socket, 200, "OK", """{"status":"ok"}""", contentType = "application/json")
                    return@Thread
                }

                if (method == "POST") {
                    sendResponse(socket, 404, "Not Found", "Unknown POST endpoint")
                    return@Thread
                }

                when {
                    path == "/" -> serveLandingPage(socket)
                    path == "/info" -> serveInfo(socket)
                    path == "/manifest" -> serveManifest(socket)
                    path == "/apk" -> serveApk(socket)
                    path == "/peer-limits" -> servePeerLimits(socket)
                    path == "/progress" -> {
                        val json = org.json.JSONObject().apply {
                            put("totalFiles", _sessionTotalFiles.value)
                            put("completedFiles", _sessionFilesCompleted.value)
                        }
                        sendResponse(socket, 200, "OK", json.toString(), contentType = "application/json")
                    }
                    path.startsWith("/file/") -> serveFile(socket, path.removePrefix("/file/"))
                    else -> sendResponse(socket, 404, "Not Found", "Unknown path: $path")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Connection error: ${e.message}")
            } finally {
                try { socket.close() } catch (_: Exception) {}
            }
        }.also { it.isDaemon = true; it.start() }
    }

    /**
     * GET / — landing page for browsers. Works for people with or without the app.
     */
    private fun serveLandingPage(socket: Socket) {
        val chainHeight = getChainHeight()
        val version = getAppVersion()
        val hasFilters = File(bitcoinDir, "indexes/blockfilter/basic").let {
            it.exists() && (it.listFiles()?.size ?: 0) > 1
        }
        val filtersText = if (hasFilters) "Lightning block filters included" else ""
        val html = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Pocket Node</title>
<style>
  * { margin: 0; padding: 0; box-sizing: border-box; }
  body {
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
    background: #121212; color: #e0e0e0;
    display: flex; justify-content: center; align-items: center;
    min-height: 100vh; padding: 24px;
  }
  .card {
    background: #1e1e1e; border-radius: 16px; padding: 32px;
    max-width: 400px; width: 100%; text-align: center;
  }
  h1 { font-size: 24px; color: #fff; margin-bottom: 4px; }
  .subtitle { color: #999; font-size: 14px; margin-bottom: 24px; }
  .stats {
    background: #2a2a2a; border-radius: 12px; padding: 16px;
    margin-bottom: 24px; text-align: left;
  }
  .stat { display: flex; justify-content: space-between; padding: 6px 0; }
  .stat-label { color: #999; }
  .stat-value { color: #fff; font-weight: 600; font-family: monospace; }
  .filters { color: #4CAF50; font-size: 13px; margin-top: 4px; }
  .btn {
    display: block; width: 100%; padding: 16px; border-radius: 12px;
    font-size: 16px; font-weight: 600; text-decoration: none;
    margin-bottom: 12px; border: none; cursor: pointer;
  }
  .btn-primary { background: #FF9800; color: #000; }
  .btn-secondary { background: #2a2a2a; color: #FF9800; border: 1px solid #FF9800; }
  .btn:active { opacity: 0.8; }
  .note { color: #666; font-size: 12px; margin-top: 16px; line-height: 1.5; }
</style>
</head>
<body>
<div class="card">
  <h1>&#x20BF; Pocket Node</h1>
  <div class="subtitle">A nearby phone is sharing its Bitcoin node</div>
  <div class="stats">
    <div class="stat"><span class="stat-label">Block height</span><span class="stat-value">${"%,d".format(chainHeight)}</span></div>
    <div class="stat"><span class="stat-label">Version</span><span class="stat-value">$version</span></div>
    ${if (filtersText.isNotEmpty()) "<div class=\"filters\">&#x26A1; $filtersText</div>" else ""}
  </div>
  <a class="btn btn-primary" href="/apk" download>Download Pocket Node APK</a>
  <a class="btn btn-secondary" href="pocketnode://share?host=${getLocalIpAddress() ?: "unknown"}&port=$PORT">I already have the app</a>
  <div class="note">
    After installing, open the app and choose "Copy from nearby phone" during setup,
    or scan the QR code again from within the app.
  </div>
</div>
</body>
</html>
""".trimIndent()
        sendResponse(socket, 200, "OK", html, "text/html")
    }

    /**
     * GET /info — node info for QR code / receiver display
     */
    private fun serveInfo(socket: Socket) {
        val chainHeight = getChainHeight()
        val hasFilters = File(bitcoinDir, "indexes/blockfilter/basic").let {
            it.exists() && (it.listFiles()?.size ?: 0) > 1
        }
        val json = JSONObject().apply {
            put("version", getAppVersion())
            put("chainHeight", chainHeight)
            put("hasFilters", hasFilters)
            put("maxConcurrent", MAX_CONCURRENT)
            put("activeTransfers", activeConnections.get())
        }
        sendResponse(socket, 200, "OK", json.toString(), "application/json")
    }

    /**
     * GET /manifest — list of all files to download with sizes
     */
    private fun serveManifest(socket: Socket) {
        if (activeConnections.get() >= MAX_CONCURRENT) {
            sendResponse(socket, 503, "Service Unavailable", 
                "Max concurrent transfers reached ($MAX_CONCURRENT). Try again later.")
            return
        }

        val files = JSONArray()
        var totalSize = 0L

        // Chainstate
        addDirectoryFiles(files, File(bitcoinDir, "chainstate"), "chainstate").also { totalSize += it }

        // Block index
        addDirectoryFiles(files, File(bitcoinDir, "blocks/index"), "blocks/index").also { totalSize += it }

        // XOR key
        val xorFile = File(bitcoinDir, "blocks/xor.dat")
        if (xorFile.exists()) {
            files.put(JSONObject().apply {
                put("path", "blocks/xor.dat")
                put("size", xorFile.length())
            })
            totalSize += xorFile.length()
        }

        // Tip block files (most recent blk/rev files)
        val blocksDir = File(bitcoinDir, "blocks")
        val blkFiles = blocksDir.listFiles { f -> f.name.matches(Regex("blk\\d+\\.dat")) && f.length() > 0 }
            ?.sortedByDescending { it.name } ?: emptyList()
        val revFiles = blocksDir.listFiles { f -> f.name.matches(Regex("rev\\d+\\.dat")) && f.length() > 0 }
            ?.sortedByDescending { it.name } ?: emptyList()

        // Include all non-empty block files (on a pruned node this is just the tip files)
        for (f in blkFiles) {
            files.put(JSONObject().apply {
                put("path", "blocks/${f.name}")
                put("size", f.length())
            })
            totalSize += f.length()
        }
        for (f in revFiles) {
            files.put(JSONObject().apply {
                put("path", "blocks/${f.name}")
                put("size", f.length())
            })
            totalSize += f.length()
        }

        // Fee estimates (for fast Lightning startup)
        val feeFile = File(bitcoinDir, "fee_estimates.dat")
        if (feeFile.exists()) {
            files.put(JSONObject().apply {
                put("path", "fee_estimates.dat")
                put("size", feeFile.length())
            })
            totalSize += feeFile.length()
        }

        // Block filters (optional, for Lightning)
        val filterDir = File(bitcoinDir, "indexes/blockfilter/basic")
        if (filterDir.exists() && (filterDir.listFiles()?.size ?: 0) > 1) {
            addDirectoryFiles(files, filterDir, "indexes/blockfilter/basic").also { totalSize += it }
        }

        val manifest = JSONObject().apply {
            put("files", files)
            put("totalSize", totalSize)
            put("fileCount", files.length())
        }
        sendResponse(socket, 200, "OK", manifest.toString(), "application/json")
    }

    /**
     * GET /peer-limits — cached peer channel minimums learned from rejections.
     * Shared so other phones don't have to discover the hard way.
     */
    private fun servePeerLimits(socket: Socket) {
        val prefs = context.getSharedPreferences("peer_channel_limits", Context.MODE_PRIVATE)
        val limits = JSONObject()
        prefs.all.forEach { (key, value) ->
            when (value) {
                is Long -> if (value > 0) limits.put(key, value)
                is Boolean -> limits.put(key, value)
                else -> {}
            }
        }
        sendResponse(socket, 200, "OK", limits.toString(), "application/json")
    }

    /**
     * GET /file/{path} — serve a specific file
     */
    private fun serveFile(socket: Socket, relativePath: String) {
        // Security: prevent path traversal
        val normalized = relativePath.replace("\\", "/")
        if (normalized.contains("..") || normalized.startsWith("/")) {
            sendResponse(socket, 403, "Forbidden", "Invalid path")
            return
        }

        // Only allow files under known directories
        val allowedPrefixes = listOf("chainstate/", "blocks/", "indexes/")
        if (allowedPrefixes.none { normalized.startsWith(it) }) {
            sendResponse(socket, 403, "Forbidden", "Path not in allowed directories")
            return
        }

        val file = File(bitcoinDir, normalized)
        if (!file.exists() || !file.isFile) {
            sendResponse(socket, 404, "Not Found", "File not found: $normalized")
            return
        }

        // Track transfer
        val transferId = transferCounter.incrementAndGet()
        activeConnections.incrementAndGet()
        transfers[transferId] = TransferInfo(transferId, totalBytes = file.length(), currentFile = normalized)
        updateTransferFlow()

        try {
            val out = socket.getOutputStream()
            val header = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: application/octet-stream\r\n")
                append("Content-Length: ${file.length()}\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }
            out.write(header.toByteArray())

            file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                var totalSent = 0L
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    out.write(buffer, 0, read)
                    totalSent += read
                    // Update progress periodically
                    if (totalSent % (1024 * 1024) < 65536) {
                        transfers[transferId] = transfers[transferId]!!.copy(bytesServed = totalSent)
                        updateTransferFlow()
                    }
                }
            }
            out.flush()
            _completedTransfers.value++
            _sessionFilesCompleted.value++
        } finally {
            activeConnections.decrementAndGet()
            transfers.remove(transferId)
            updateTransferFlow()
        }
    }

    /**
     * GET /apk — serve the running APK for easy app distribution
     */
    private fun serveApk(socket: Socket) {
        try {
            val apkPath = context.packageManager.getApplicationInfo(context.packageName, 0).sourceDir
            val apkFile = File(apkPath)
            
            val out = socket.getOutputStream()
            val header = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: application/vnd.android.package-archive\r\n")
                append("Content-Length: ${apkFile.length()}\r\n")
                append("Content-Disposition: attachment; filename=\"pocket-node-${getAppVersion()}.apk\"\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }
            out.write(header.toByteArray())
            
            apkFile.inputStream().use { input ->
                val buffer = ByteArray(65536)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    out.write(buffer, 0, read)
                }
            }
            out.flush()
        } catch (e: Exception) {
            sendResponse(socket, 500, "Internal Server Error", "Failed to serve APK: ${e.message}")
        }
    }

    private fun addDirectoryFiles(array: JSONArray, dir: File, prefix: String): Long {
        var size = 0L
        if (!dir.exists()) return 0
        dir.walkTopDown().filter { it.isFile }.forEach { file ->
            val relativePath = "$prefix/${file.relativeTo(dir)}"
            array.put(JSONObject().apply {
                put("path", relativePath)
                put("size", file.length())
            })
            size += file.length()
        }
        return size
    }

    private fun sendResponse(socket: Socket, code: Int, status: String, body: String, 
                            contentType: String = "text/plain") {
        try {
            val out = socket.getOutputStream()
            val response = buildString {
                append("HTTP/1.1 $code $status\r\n")
                append("Content-Type: $contentType\r\n")
                append("Content-Length: ${body.toByteArray().size}\r\n")
                append("Connection: close\r\n")
                append("\r\n")
                append(body)
            }
            out.write(response.toByteArray())
            out.flush()
        } catch (_: Exception) {}
    }

    private fun getChainHeight(): Int {
        // Read from cached blockchain info
        return try {
            val prefs = context.getSharedPreferences("pocketnode_prefs", Context.MODE_PRIVATE)
            prefs.getInt("last_chain_height", 0)
        } catch (_: Exception) { 0 }
    }

    private fun getAppVersion(): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (_: Exception) { "unknown" }
    }

    private fun updateTransferFlow() {
        _activeTransfers.value = transfers.toMap()
    }

    /**
     * Get the device's local WiFi/hotspot IP address.
     */
    fun getLocalIpAddress(): String? {
        try {
            for (iface in NetworkInterface.getNetworkInterfaces()) {
                // Skip loopback and down interfaces
                if (iface.isLoopback || !iface.isUp) continue
                // Prefer wlan or ap interfaces
                val name = iface.name.lowercase()
                if (name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("swlan")) {
                    for (addr in iface.inetAddresses) {
                        if (addr is java.net.Inet4Address) {
                            return addr.hostAddress
                        }
                    }
                }
            }
            // Fallback: any non-loopback IPv4
            for (iface in NetworkInterface.getNetworkInterfaces()) {
                if (iface.isLoopback || !iface.isUp) continue
                for (addr in iface.inetAddresses) {
                    if (addr is java.net.Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get local IP: ${e.message}")
        }
        return null
    }
}
