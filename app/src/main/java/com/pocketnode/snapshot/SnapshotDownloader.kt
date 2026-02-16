package com.pocketnode.snapshot

import android.content.Context
import android.os.StatFs
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.SftpProgressMonitor
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads a UTXO snapshot file over HTTP with progress reporting and resume support.
 * Designed for large files (7-10 GB) over LAN.
 */
class SnapshotDownloader(private val context: Context) {

    companion object {
        private const val TAG = "SnapshotDownloader"
        private const val BUFFER_SIZE = 256 * 1024 // 256 KB buffer for LAN speed
    }

    data class DownloadProgress(
        val state: DownloadState = DownloadState.IDLE,
        val bytesDownloaded: Long = 0,
        val totalBytes: Long = -1,
        val speedBytesPerSec: Long = 0,
        val error: String? = null
    ) {
        val progressFraction: Float
            get() = if (totalBytes > 0) (bytesDownloaded.toFloat() / totalBytes) else 0f

        val isComplete: Boolean
            get() = state == DownloadState.COMPLETE
    }

    enum class DownloadState {
        IDLE, DOWNLOADING, PAUSED, COMPLETE, ERROR
    }

    private val _progress = MutableStateFlow(DownloadProgress())
    val progress: StateFlow<DownloadProgress> = _progress

    @Volatile
    private var cancelled = false

    /**
     * Get the snapshot destination file path.
     */
    fun getSnapshotFile(): File {
        val bitcoinDir = File(context.filesDir, "bitcoin")
        bitcoinDir.mkdirs()
        return File(bitcoinDir, "utxo-snapshot.dat")
    }

    /**
     * Check available storage space.
     * @return available bytes
     */
    fun getAvailableSpace(): Long {
        val stat = StatFs(context.filesDir.absolutePath)
        return stat.availableBytes
    }

    /**
     * Download snapshot from a URL with resume support.
     *
     * @param downloadUrl Full URL to the snapshot file (e.g., http://umbrel.local:8080/utxo-snapshot.dat)
     * @return The local file path where the snapshot was saved, or null on failure
     */
    suspend fun download(downloadUrl: String): File? = withContext(Dispatchers.IO) {
        cancelled = false
        val destFile = getSnapshotFile()

        try {
            // Check existing partial download for resume
            val existingBytes = if (destFile.exists()) destFile.length() else 0L

            val url = URL(downloadUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000

            // Resume support
            if (existingBytes > 0) {
                conn.setRequestProperty("Range", "bytes=$existingBytes-")
                Log.i(TAG, "Resuming download from byte $existingBytes")
            }

            conn.connect()

            val responseCode = conn.responseCode
            val contentLength: Long
            val startOffset: Long

            when (responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    // Full download (server doesn't support range, or fresh start)
                    contentLength = conn.contentLengthLong
                    startOffset = 0
                    if (existingBytes > 0) {
                        // Server doesn't support resume, start over
                        destFile.delete()
                    }
                }
                HttpURLConnection.HTTP_PARTIAL -> {
                    // Resumed download
                    contentLength = existingBytes + conn.contentLengthLong
                    startOffset = existingBytes
                }
                else -> {
                    val error = "HTTP $responseCode: ${conn.responseMessage}"
                    _progress.value = DownloadProgress(DownloadState.ERROR, error = error)
                    return@withContext null
                }
            }

            // Storage space check
            val neededBytes = contentLength - (if (startOffset > 0) startOffset else 0)
            val available = getAvailableSpace()
            if (neededBytes > 0 && neededBytes > available) {
                val needed = neededBytes / (1024 * 1024 * 1024)
                val avail = available / (1024 * 1024 * 1024)
                _progress.value = DownloadProgress(
                    DownloadState.ERROR,
                    error = "Not enough storage. Need ${needed}GB, have ${avail}GB"
                )
                return@withContext null
            }

            _progress.value = DownloadProgress(
                DownloadState.DOWNLOADING,
                bytesDownloaded = startOffset,
                totalBytes = contentLength
            )

            val raf = RandomAccessFile(destFile, "rw")
            raf.seek(startOffset)

            val buffer = ByteArray(BUFFER_SIZE)
            val inputStream = conn.inputStream
            var totalRead = startOffset
            var lastSpeedCheck = System.currentTimeMillis()
            var bytesAtLastCheck = totalRead

            inputStream.use {
                while (isActive && !cancelled) {
                    val read = inputStream.read(buffer)
                    if (read == -1) break

                    raf.write(buffer, 0, read)
                    totalRead += read

                    // Update progress every 512KB
                    val now = System.currentTimeMillis()
                    val elapsed = now - lastSpeedCheck
                    if (elapsed >= 500) {
                        val speed = ((totalRead - bytesAtLastCheck) * 1000) / elapsed
                        _progress.value = DownloadProgress(
                            DownloadState.DOWNLOADING,
                            bytesDownloaded = totalRead,
                            totalBytes = contentLength,
                            speedBytesPerSec = speed
                        )
                        lastSpeedCheck = now
                        bytesAtLastCheck = totalRead
                    }
                }
            }

            raf.close()
            conn.disconnect()

            if (cancelled) {
                _progress.value = DownloadProgress(DownloadState.PAUSED, bytesDownloaded = totalRead, totalBytes = contentLength)
                return@withContext null
            }

            _progress.value = DownloadProgress(DownloadState.COMPLETE, bytesDownloaded = totalRead, totalBytes = contentLength)
            Log.i(TAG, "Download complete: ${destFile.absolutePath} (${totalRead} bytes)")
            destFile
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            _progress.value = DownloadProgress(DownloadState.ERROR, error = e.message ?: "Download failed")
            null
        }
    }

    fun cancel() {
        cancelled = true
    }

    fun reset() {
        cancelled = false
        _progress.value = DownloadProgress()
    }

    /**
     * Download snapshot via SFTP from the restricted pocketnode account.
     *
     * @param host SFTP host
     * @param port SFTP port (usually 22)
     * @param username SFTP username (pocketnode)
     * @param password SFTP password
     * @param remotePath Remote path to snapshot file (e.g. /snapshots/utxo-snapshot.dat)
     * @return The local file path where the snapshot was saved, or null on failure
     */
    suspend fun downloadSftp(
        host: String,
        port: Int,
        username: String,
        password: String,
        remotePath: String = "/snapshots/utxo-snapshot.dat",
        destinationFile: File? = null
    ): File? = withContext(Dispatchers.IO) {
        cancelled = false
        val destFile = destinationFile ?: getSnapshotFile()

        try {
            val jsch = JSch()
            val session = jsch.getSession(username, host, port)
            session.setPassword(password)
            session.setConfig("StrictHostKeyChecking", "no")

            Log.i(TAG, "SFTP connecting to $host:$port...")
            session.connect(15_000)

            val channel = session.openChannel("sftp") as ChannelSftp
            channel.connect(15_000)

            // Get remote file size
            val attrs = channel.stat(remotePath)
            val totalBytes = attrs.size

            // Check storage
            val available = getAvailableSpace()
            val existingBytes = if (destFile.exists()) destFile.length() else 0L
            val neededBytes = totalBytes - existingBytes
            if (neededBytes > 0 && neededBytes > available) {
                val needed = neededBytes / (1024 * 1024 * 1024)
                val avail = available / (1024 * 1024 * 1024)
                _progress.value = DownloadProgress(DownloadState.ERROR,
                    error = "Not enough storage. Need ${needed}GB, have ${avail}GB")
                channel.disconnect()
                session.disconnect()
                return@withContext null
            }

            _progress.value = DownloadProgress(
                DownloadState.DOWNLOADING,
                bytesDownloaded = existingBytes,
                totalBytes = totalBytes
            )

            // Resume support: if partial file exists, resume from that offset
            val raf = RandomAccessFile(destFile, "rw")
            if (existingBytes > 0 && existingBytes < totalBytes) {
                raf.seek(existingBytes)
                Log.i(TAG, "Resuming SFTP download from byte $existingBytes")
            } else if (existingBytes >= totalBytes) {
                // Already complete
                raf.close()
                channel.disconnect()
                session.disconnect()
                _progress.value = DownloadProgress(DownloadState.COMPLETE,
                    bytesDownloaded = totalBytes, totalBytes = totalBytes)
                return@withContext destFile
            } else {
                raf.seek(0)
            }

            val startOffset = if (existingBytes > 0 && existingBytes < totalBytes) existingBytes else 0L
            val inputStream = if (startOffset > 0) {
                channel.get(remotePath, null as SftpProgressMonitor?, startOffset)
            } else {
                channel.get(remotePath)
            }

            val buffer = ByteArray(BUFFER_SIZE)
            var totalRead = startOffset
            var lastSpeedCheck = System.currentTimeMillis()
            var bytesAtLastCheck = totalRead

            inputStream.use {
                while (isActive && !cancelled) {
                    val read = inputStream.read(buffer)
                    if (read == -1) break

                    raf.write(buffer, 0, read)
                    totalRead += read

                    val now = System.currentTimeMillis()
                    val elapsed = now - lastSpeedCheck
                    if (elapsed >= 500) {
                        val speed = ((totalRead - bytesAtLastCheck) * 1000) / elapsed
                        _progress.value = DownloadProgress(
                            DownloadState.DOWNLOADING,
                            bytesDownloaded = totalRead,
                            totalBytes = totalBytes,
                            speedBytesPerSec = speed
                        )
                        lastSpeedCheck = now
                        bytesAtLastCheck = totalRead
                    }
                }
            }

            raf.close()
            channel.disconnect()
            session.disconnect()

            if (cancelled) {
                _progress.value = DownloadProgress(DownloadState.PAUSED,
                    bytesDownloaded = totalRead, totalBytes = totalBytes)
                return@withContext null
            }

            _progress.value = DownloadProgress(DownloadState.COMPLETE,
                bytesDownloaded = totalRead, totalBytes = totalBytes)
            Log.i(TAG, "SFTP download complete: ${destFile.absolutePath} ($totalRead bytes)")
            destFile
        } catch (e: Exception) {
            Log.e(TAG, "SFTP download failed", e)
            _progress.value = DownloadProgress(DownloadState.ERROR,
                error = e.message ?: "SFTP download failed")
            null
        }
    }
}
