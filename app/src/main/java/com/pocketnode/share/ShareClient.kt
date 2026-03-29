package com.pocketnode.share

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * HTTP client for downloading chainstate from a nearby ShareServer.
 * Used in the setup flow as an alternative to SSH-based chainstate copy.
 */
class ShareClient(private val context: Context) {

    companion object {
        private const val TAG = "ShareClient"
    }

    data class ShareInfo(
        val version: String,
        val chainHeight: Int,
        val hasFilters: Boolean,
        val activeTransfers: Int,
        val maxConcurrent: Int
    )

    data class DownloadState(
        val phase: String = "Connecting...",
        val currentFile: String = "",
        val fileProgress: Int = 0,       // 0-100 for current file
        val totalProgress: Int = 0,      // 0-100 overall
        val bytesDownloaded: Long = 0,
        val totalBytes: Long = 0,
        val filesCompleted: Int = 0,
        val totalFiles: Int = 0
    )

    private val _state = MutableStateFlow(DownloadState())
    val stateFlow: StateFlow<DownloadState> = _state

    /**
     * Fetch server info. Returns null on error.
     */
    suspend fun getInfo(host: String, port: Int = ShareServer.PORT): ShareInfo? = withContext(Dispatchers.IO) {
        try {
            val conn = URL("http://$host:$port/info").openConnection() as HttpURLConnection
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            if (conn.responseCode != 200) return@withContext null

            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            ShareInfo(
                version = json.getString("version"),
                chainHeight = json.getInt("chainHeight"),
                hasFilters = json.getBoolean("hasFilters"),
                activeTransfers = json.getInt("activeTransfers"),
                maxConcurrent = json.getInt("maxConcurrent")
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get info from $host: ${e.message}")
            null
        }
    }

    /**
     * Fetch and merge peer channel minimums from the share server.
     * Merges with local data (keeps the larger value if both exist).
     */
    suspend fun fetchPeerLimits(host: String, port: Int = ShareServer.PORT): Int = withContext(Dispatchers.IO) {
        try {
            val conn = URL("http://$host:$port/peer-limits").openConnection() as HttpURLConnection
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            if (conn.responseCode != 200) return@withContext 0

            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val prefs = context.getSharedPreferences("peer_channel_limits", Context.MODE_PRIVATE)
            val editor = prefs.edit()
            var merged = 0
            json.keys().forEach { key ->
                try {
                    val value = json.get(key)
                    when (value) {
                        is Number -> {
                            val remote = value.toLong()
                            val local = prefs.getLong(key, -1L)
                            if (remote > 0 && remote > local) {
                                editor.putLong(key, remote)
                                merged++
                            }
                        }
                        is Boolean -> {
                            editor.putBoolean(key, value)
                            merged++
                        }
                    }
                } catch (_: Exception) {}
            }
            editor.apply()
            Log.i(TAG, "Merged $merged peer limits from $host")
            merged
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch peer limits from $host: ${e.message}")
            0
        }
    }

    /**
     * Download all chainstate files from the share server.
     * Stops bitcoind is assumed to already be stopped (fresh install).
     */
    suspend fun downloadChainstate(
        host: String,
        port: Int = ShareServer.PORT,
        includeFilters: Boolean = true
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            _state.value = DownloadState(phase = "Fetching file list...")

            // Get manifest
            val manifestConn = URL("http://$host:$port/manifest").openConnection() as HttpURLConnection
            manifestConn.connectTimeout = 10_000
            manifestConn.readTimeout = 30_000
            if (manifestConn.responseCode != 200) {
                val error = manifestConn.inputStream.bufferedReader().readText()
                _state.value = _state.value.copy(phase = "Error: $error")
                return@withContext false
            }

            val manifest = JSONObject(manifestConn.inputStream.bufferedReader().readText())
            val files = manifest.getJSONArray("files")
            val totalSize = manifest.getLong("totalSize")

            // Filter out block filters if not wanted
            data class FileEntry(val path: String, val size: Long, val lastModified: Long)
            val downloadList = mutableListOf<FileEntry>()
            for (i in 0 until files.length()) {
                val file = files.getJSONObject(i)
                val path = file.getString("path")
                val size = file.getLong("size")
                val modified = file.optLong("lastModified", 0)
                if (!includeFilters && path.startsWith("indexes/")) continue
                downloadList.add(FileEntry(path, size, modified))
            }

            val filteredTotal = downloadList.sumOf { it.size }
            var bytesDownloaded = 0L

            // Announce session to sender for progress tracking
            try {
                val sessionConn = URL("http://$host:$port/start-session").openConnection() as HttpURLConnection
                sessionConn.requestMethod = "POST"
                sessionConn.doOutput = true
                sessionConn.setRequestProperty("Content-Type", "application/json")
                sessionConn.connectTimeout = 5_000
                sessionConn.readTimeout = 5_000
                sessionConn.outputStream.use { it.write("""{"totalFiles":${downloadList.size}}""".toByteArray()) }
                sessionConn.responseCode // trigger the request
                sessionConn.disconnect()
                Log.i(TAG, "Announced session: ${downloadList.size} files")
            } catch (e: Exception) {
                Log.w(TAG, "Could not announce session: ${e.message}")
                // Non-fatal, sender just won't show progress
            }

            _state.value = DownloadState(
                phase = "Downloading ${downloadList.size} files...",
                totalFiles = downloadList.size,
                totalBytes = filteredTotal
            )

            val bitcoinDir = File(context.filesDir, "bitcoin")

            for ((index, entry) in downloadList.withIndex()) {
                if (!coroutineContext.isActive) return@withContext false

                val path = entry.path
                val size = entry.size
                val targetFile = File(bitcoinDir, path)
                targetFile.parentFile?.mkdirs()

                // Resume support: skip files with matching size and date
                // Always re-download chainstate (small, must be consistent with blocks)
                val isChainstate = path.startsWith("chainstate/")
                val remoteModified = entry.lastModified
                val sameDate = remoteModified == 0L || targetFile.lastModified() >= remoteModified
                if (!isChainstate && targetFile.exists() && targetFile.length() == size && sameDate) {
                    bytesDownloaded += size
                    _state.value = _state.value.copy(
                        filesCompleted = index + 1,
                        bytesDownloaded = bytesDownloaded
                    )
                    continue
                }

                _state.value = _state.value.copy(
                    currentFile = path,
                    fileProgress = 0,
                    filesCompleted = index
                )

                // Download file
                val fileConn = URL("http://$host:$port/file/$path").openConnection() as HttpURLConnection
                fileConn.connectTimeout = 10_000
                fileConn.readTimeout = 60_000
                
                if (fileConn.responseCode != 200) {
                    Log.e(TAG, "Failed to download $path: HTTP ${fileConn.responseCode}")
                    _state.value = _state.value.copy(phase = "Error downloading $path")
                    return@withContext false
                }

                fileConn.inputStream.use { input ->
                    targetFile.outputStream().use { output ->
                        val buffer = ByteArray(65536)
                        var fileDownloaded = 0L
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            if (!coroutineContext.isActive) return@withContext false
                            output.write(buffer, 0, read)
                            fileDownloaded += read
                            bytesDownloaded += read

                            // Update progress
                            val filePercent = if (size > 0) ((fileDownloaded * 100) / size).toInt() else 100
                            val totalPercent = if (filteredTotal > 0) ((bytesDownloaded * 100) / filteredTotal).toInt() else 0
                            _state.value = _state.value.copy(
                                fileProgress = filePercent,
                                totalProgress = totalPercent,
                                bytesDownloaded = bytesDownloaded
                            )
                        }
                    }
                }
            }

            _state.value = _state.value.copy(
                phase = "Download complete",
                totalProgress = 100,
                filesCompleted = downloadList.size
            )

            Log.i(TAG, "Chainstate download complete: ${downloadList.size} files, $bytesDownloaded bytes")

            // Notify sender that download is complete
            try {
                val completeConn = URL("http://$host:$port/complete").openConnection() as HttpURLConnection
                completeConn.requestMethod = "POST"
                completeConn.doOutput = true
                completeConn.connectTimeout = 5_000
                completeConn.outputStream.use { it.write("{}".toByteArray()) }
                completeConn.responseCode
                completeConn.disconnect()
            } catch (_: Exception) {}

            true
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${e.message}", e)
            _state.value = _state.value.copy(phase = "Error: ${e.message}")
            false
        }
    }
}
