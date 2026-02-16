package com.pocketnode.snapshot

import android.content.Context
import android.util.Log
import com.pocketnode.rpc.BitcoinRpcClient
import com.pocketnode.util.ConfigGenerator
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray

/**
 * Orchestrates the full "Pull from my node" flow:
 * 1. Connect to remote node
 * 2. Trigger dumptxoutset
 * 3. Download snapshot over HTTP
 * 4. Load snapshot into local bitcoind
 */
class SnapshotManager(private val context: Context) {

    companion object {
        private const val TAG = "SnapshotManager"
        // Default remote dump filename
        const val REMOTE_DUMP_FILENAME = "utxo-snapshot.dat"
    }

    data class FlowState(
        val step: Step = Step.NOT_STARTED,
        val remoteConnected: Boolean = false,
        val remoteChain: String? = null,
        val remoteBlocks: Int = 0,
        val dumpTriggered: Boolean = false,
        val dumpComplete: Boolean = false,
        val downloadProgress: SnapshotDownloader.DownloadProgress = SnapshotDownloader.DownloadProgress(),
        val loadStarted: Boolean = false,
        val loadComplete: Boolean = false,
        val error: String? = null
    )

    enum class Step {
        NOT_STARTED,
        CONNECTING,
        CONNECTED,
        DUMPING,
        DUMP_COMPLETE,
        DOWNLOADING,
        DOWNLOAD_COMPLETE,
        LOADING,
        COMPLETE,
        ERROR
    }

    private val _state = MutableStateFlow(FlowState())
    val state: StateFlow<FlowState> = _state

    private var remoteNode: NodeConnectionManager? = null
    val downloader = SnapshotDownloader(context)

    /**
     * Step 1: Connect to remote node and verify.
     */
    suspend fun connectToNode(host: String, port: Int, rpcUser: String, rpcPassword: String): Boolean {
        _state.value = _state.value.copy(step = Step.CONNECTING, error = null)

        val node = NodeConnectionManager(host, port, rpcUser, rpcPassword)
        val result = node.testConnection()

        return if (result.success) {
            remoteNode = node
            _state.value = _state.value.copy(
                step = Step.CONNECTED,
                remoteConnected = true,
                remoteChain = result.chain,
                remoteBlocks = result.blocks
            )
            Log.i(TAG, "Connected to remote node: ${result.chain} at block ${result.blocks}")
            true
        } else {
            _state.value = _state.value.copy(step = Step.ERROR, error = result.error)
            false
        }
    }

    /**
     * Step 2: Trigger dumptxoutset on the remote node.
     * This writes a file on the remote node. The user then needs to serve it over HTTP.
     */
    suspend fun triggerDump(): Boolean {
        val node = remoteNode ?: run {
            _state.value = _state.value.copy(step = Step.ERROR, error = "Not connected to remote node")
            return false
        }

        _state.value = _state.value.copy(step = Step.DUMPING, dumpTriggered = true, error = null)

        val result = node.triggerDumpTxOutset(REMOTE_DUMP_FILENAME)
        return if (result.success) {
            _state.value = _state.value.copy(step = Step.DUMP_COMPLETE, dumpComplete = true)
            Log.i(TAG, "dumptxoutset complete, hash: ${result.txoutsetHash}")
            true
        } else {
            _state.value = _state.value.copy(step = Step.ERROR, error = result.error)
            false
        }
    }

    /**
     * Step 2b: After dump completes on Umbrel, trigger copy to SFTP-accessible location.
     * Uses the copy-snapshot.sh helper created during setup.
     */
    suspend fun triggerSnapshotCopy(sshHost: String, sshPort: Int, sshUser: String, sshPassword: String): Boolean {
        try {
            val jsch = com.jcraft.jsch.JSch()
            val session = jsch.getSession(sshUser, sshHost, sshPort)
            session.setPassword(sshPassword)
            session.setConfig("StrictHostKeyChecking", "no")
            session.connect(15_000)

            val channel = session.openChannel("exec") as com.jcraft.jsch.ChannelExec
            channel.setCommand("echo '$sshPassword' | sudo -S /usr/local/bin/pocketnode-copy-snapshot.sh 2>&1")
            val output = java.io.ByteArrayOutputStream()
            channel.outputStream = output
            channel.connect(30_000)

            val start = System.currentTimeMillis()
            while (!channel.isClosed && System.currentTimeMillis() - start < 300_000) {
                kotlinx.coroutines.delay(1_000)
            }

            channel.disconnect()
            session.disconnect()

            val result = output.toString("UTF-8")
            Log.i(TAG, "Copy snapshot result: $result")
            return result.contains("copied", ignoreCase = true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to trigger snapshot copy", e)
            _state.value = _state.value.copy(step = Step.ERROR, error = "Failed to copy snapshot: ${e.message}")
            return false
        }
    }

    /**
     * Step 3: Download the snapshot via SFTP from the node.
     */
    suspend fun downloadSnapshotSftp(
        sftpHost: String,
        sftpPort: Int,
        sftpUser: String,
        sftpPassword: String,
        remoteFilename: String = "utxo-910000.dat"
    ): Boolean {
        _state.value = _state.value.copy(step = Step.DOWNLOADING, error = null)

        val file = downloader.downloadSftp(
            host = sftpHost,
            port = sftpPort,
            username = sftpUser,
            password = sftpPassword,
            remotePath = "/snapshots/$remoteFilename"
        )
        return if (file != null) {
            _state.value = _state.value.copy(step = Step.DOWNLOAD_COMPLETE)
            Log.i(TAG, "Snapshot downloaded via SFTP to: ${file.absolutePath}")
            true
        } else {
            val dlProgress = downloader.progress.value
            if (dlProgress.state == SnapshotDownloader.DownloadState.ERROR) {
                _state.value = _state.value.copy(step = Step.ERROR, error = dlProgress.error)
            }
            false
        }
    }

    /**
     * Step 3 (alt): Download the snapshot from HTTP URL.
     *
     * @param downloadUrl The HTTP URL where the snapshot file is served
     *   e.g., "https://utxo.download/utxo-910000.dat"
     */
    suspend fun downloadSnapshot(downloadUrl: String): Boolean {
        _state.value = _state.value.copy(step = Step.DOWNLOADING, error = null)

        val file = downloader.download(downloadUrl)
        return if (file != null) {
            _state.value = _state.value.copy(step = Step.DOWNLOAD_COMPLETE)
            Log.i(TAG, "Snapshot downloaded to: ${file.absolutePath}")
            true
        } else {
            val dlProgress = downloader.progress.value
            if (dlProgress.state == SnapshotDownloader.DownloadState.ERROR) {
                _state.value = _state.value.copy(step = Step.ERROR, error = dlProgress.error)
            }
            false
        }
    }

    /**
     * Step 4: Load the snapshot into local bitcoind via loadtxoutset RPC.
     */
    suspend fun loadSnapshot(): Boolean {
        val snapshotPath = downloader.getSnapshotFile().absolutePath

        val creds = ConfigGenerator.readCredentials(context)
        if (creds == null) {
            _state.value = _state.value.copy(step = Step.ERROR, error = "Local bitcoind credentials not found. Is bitcoind running?")
            return false
        }

        _state.value = _state.value.copy(step = Step.LOADING, loadStarted = true, error = null)

        val localRpc = BitcoinRpcClient(creds.first, creds.second)

        // Verify local bitcoind is responsive
        val info = localRpc.getBlockchainInfo()
        if (info == null) {
            _state.value = _state.value.copy(step = Step.ERROR, error = "Local bitcoind is not running or not responding")
            return false
        }

        // Call loadtxoutset — this can take a while
        val params = JSONArray().apply { put(snapshotPath) }
        val result = localRpc.call("loadtxoutset", params)

        return if (result != null) {
            _state.value = _state.value.copy(step = Step.COMPLETE, loadComplete = true)
            Log.i(TAG, "loadtxoutset succeeded")
            true
        } else {
            _state.value = _state.value.copy(step = Step.ERROR, error = "loadtxoutset failed — check that the snapshot file is valid")
            false
        }
    }

    /**
     * Monitor loadtxoutset progress by polling getblockchaininfo.
     */
    suspend fun pollLoadProgress(onProgress: (Int, Int) -> Unit) {
        val creds = ConfigGenerator.readCredentials(context) ?: return
        val localRpc = BitcoinRpcClient(creds.first, creds.second)

        while (true) {
            val info = localRpc.getBlockchainInfo() ?: break
            val blocks = info.optInt("blocks", 0)
            val headers = info.optInt("headers", 0)
            onProgress(blocks, headers)

            if (blocks >= headers && headers > 0) break
            delay(5_000)
        }
    }

    fun reset() {
        _state.value = FlowState()
        remoteNode = null
        downloader.reset()
    }
}
