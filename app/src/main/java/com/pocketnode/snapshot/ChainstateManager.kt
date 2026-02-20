package com.pocketnode.snapshot

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.io.File
import com.jcraft.jsch.Session
import com.pocketnode.rpc.BitcoinRpcClient
import com.pocketnode.service.BitcoindService
import com.pocketnode.ssh.SshUtils
import com.pocketnode.util.ConfigGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
/**
 * Manages the "Fast sync from my node" flow via direct chainstate copy.
 *
 * Proven process (2026-02-16):
 * 1. Stop remote node briefly (for consistent LevelDB state)
 * 2. Archive: chainstate/ + blocks/index/ + blocks/xor.dat + tip blk/rev files
 * 3. Restart remote node
 * 4. Download archive via SFTP
 * 5. Deploy on phone: extract, create stub blk/rev files, set checklevel=0
 * 6. Start node — loads chainstate at tip, verifies last 6 blocks, syncs forward
 *
 * No AssumeUTXO. No background validation. No waiting.
 * Node starts at chain tip instantly with a fully-validated UTXO set.
 *
 * Also supports AssumeUTXO (loadtxoutset) for internet-downloaded snapshots.
 */
class ChainstateManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "ChainstateManager"
        private const val SFTP_USERNAME = "pocketnode"
        private const val ARCHIVE_NAME = "node-sync.tar"
        // AssumeUTXO height must match chainparams — we patched 910000
        private const val SNAPSHOT_HEIGHT = 910000
        // Expected block hash at height 910000 (from Bitcoin Core 30 chainparams)
        const val EXPECTED_BLOCK_HASH = "0000000000000000000108970acb9522ffd516eae17acddcb1bd16469194a821"

        @Volatile
        private var instance: ChainstateManager? = null

        fun getInstance(context: Context): ChainstateManager {
            return instance ?: synchronized(this) {
                instance ?: ChainstateManager(context.applicationContext).also { instance = it }
            }
        }

        /**
         * Read the block hash from a UTXO snapshot file header.
         * Format: "utxo" (4) + 0xff (1) + version u16 LE (2) + network magic (4) + block hash (32 bytes LE)
         * Returns hex string of block hash, or null on error.
         */
        fun readSnapshotBlockHash(file: File): String? {
            return try {
                val header = ByteArray(43)
                file.inputStream().use { it.read(header) }
                val hashBytes = header.sliceArray(11 until 43)
                hashBytes.reverse()
                hashBytes.joinToString("") { "%02x".format(it) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read snapshot header: ${e.message}")
                null
            }
        }
    }

    enum class Step {
        NOT_STARTED,
        STOPPING_REMOTE,
        ARCHIVING,
        DOWNLOADING,
        DEPLOYING,
        STARTING_NODE,
        COMPLETE,
        ERROR
    }

    data class ChainstateState(
        val step: Step = Step.NOT_STARTED,
        val progress: String = "",
        val downloadProgress: Float = 0f,
        val downloadedBytes: Long = 0,
        val totalBytes: Long = 0,
        val error: String? = null
    )

    private val _state = MutableStateFlow(ChainstateState())
    val state: StateFlow<ChainstateState> = _state.asStateFlow()

    /**
     * Execute the full direct chainstate copy flow.
     *
     * Stops remote node → archives chainstate + blocks/index + xor.dat + tip blocks →
     * restarts remote → downloads via SFTP → deploys on phone → starts local node.
     */
    suspend fun copyChainstate(
        sshHost: String,
        sshPort: Int,
        sshUser: String,
        sshPassword: String,
        sftpUser: String,
        sftpPassword: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val dataDir = context.filesDir.resolve("bitcoin")
            dataDir.mkdirs()
            val chainstateDir = dataDir.resolve("chainstate")
            val blocksDir = dataDir.resolve("blocks")
            val archiveFile = dataDir.resolve(ARCHIVE_NAME)

            // --- Early check: valid chainstate already exists? ---
            val chainstateSnapshotDir = dataDir.resolve("chainstate_snapshot")
            val hasAssumeUtxoBaggage = chainstateSnapshotDir.exists() &&
                    (chainstateSnapshotDir.listFiles()?.size ?: 0) > 2
            if (!hasAssumeUtxoBaggage && chainstateDir.exists() &&
                (chainstateDir.listFiles()?.size ?: 0) > 2) {
                _state.value = _state.value.copy(step = Step.STOPPING_REMOTE,
                    progress = "Checking existing chainstate...")

                ConfigGenerator.ensureConfig(context)
                if (!BitcoindService.isRunningFlow.value) {
                    startBitcoindService()
                }

                val quickCreds = ConfigGenerator.readCredentials(context)
                if (quickCreds != null) {
                    val quickRpc = BitcoinRpcClient(quickCreds.first, quickCreds.second)
                    for (attempt in 0 until 30) {
                        _state.value = _state.value.copy(
                            progress = "Verifying chainstate... (${30 - attempt}s)")
                        val check = quickRpc.call("getblockchaininfo",
                            connectTimeoutMs = 2_000, readTimeoutMs = 5_000)
                        if (check != null && !check.optBoolean("_rpc_error", false)) {
                            val blocks = check.optLong("blocks", 0)
                            if (blocks > 800_000) {
                                Log.i(TAG, "Existing chainstate valid at height $blocks — skipping")
                                tickThroughSteps("Chainstate already valid! Node running at height $blocks.")
                                return@withContext true
                            }
                        }
                        delay(2000)
                    }
                }
            }

            // --- Check if archive already exists (skip archiving) ---
            val archiveExists = if (!sshUser.isNullOrEmpty()) {
                // We have admin creds — check SFTP first
                checkArchiveExists(sshHost, sshPort, sftpUser, sftpPassword)
            } else {
                checkArchiveExists(sshHost, sshPort, sftpUser, sftpPassword)
            }

            val localArchiveValid = archiveFile.exists() && archiveFile.length() > 1_000_000_000

            if (!archiveExists && !localArchiveValid) {
                if (sshUser.isNullOrEmpty()) {
                    _state.value = _state.value.copy(step = Step.ERROR,
                        error = "No archive found on node. Admin credentials required to create one.")
                    return@withContext false
                }

                // --- Step 1: Find bitcoin data dir ---
                _state.value = ChainstateState(step = Step.STOPPING_REMOTE,
                    progress = "Connecting to remote node...")

                val setupSession = SshUtils.connectSsh(sshHost, sshPort, sshUser, sshPassword)
                _state.value = _state.value.copy(progress = "Finding Bitcoin data directory...")

                val bitcoinDataDir = SshUtils.findBitcoinDataDir(setupSession, sshPassword)
                setupSession.disconnect()

                if (bitcoinDataDir.isEmpty()) {
                    _state.value = _state.value.copy(step = Step.ERROR,
                        error = "Could not find Bitcoin data directory on remote node")
                    return@withContext false
                }
                Log.i(TAG, "Bitcoin data dir: $bitcoinDataDir")

                // --- Step 2: Stop remote node, archive, restart ---
                _state.value = _state.value.copy(step = Step.STOPPING_REMOTE,
                    progress = "Stopping remote node for consistent snapshot...")

                val archiveSession = SshUtils.connectSsh(sshHost, sshPort, sshUser, sshPassword)

                // Detect if Docker
                val containerName = SshUtils.detectDockerContainer(archiveSession, sshPassword)

                // Stop the remote node
                if (containerName != null) {
                    _state.value = _state.value.copy(progress = "Stopping $containerName...")
                    SshUtils.execSudo(archiveSession, sshPassword, "docker stop $containerName")
                } else {
                    _state.value = _state.value.copy(progress = "Stopping bitcoind...")
                    SshUtils.execSudo(archiveSession, sshPassword, "bitcoin-cli stop 2>/dev/null || systemctl stop bitcoind 2>/dev/null || true")
                    delay(5000) // Give it time to shut down
                }

                // Archive everything needed
                _state.value = _state.value.copy(step = Step.ARCHIVING,
                    progress = "Archiving chainstate + block index...")

                val destDir = "/home/$SFTP_USERNAME/snapshots"
                val archivePath = "$destDir/$ARCHIVE_NAME"

                // Find the last blk file number from the index
                val lastBlkNum = SshUtils.execSudo(archiveSession, sshPassword,
                    "ls -1 $bitcoinDataDir/blocks/blk*.dat 2>/dev/null | sort -V | tail -1 | grep -oP '\\d+' | tail -1"
                ).trim().lines().lastOrNull()?.trim() ?: ""

                val blkFile = if (lastBlkNum.isNotEmpty()) "blocks/blk$lastBlkNum.dat" else ""
                val revFile = if (lastBlkNum.isNotEmpty()) "blocks/rev$lastBlkNum.dat" else ""
                val xorFile = "blocks/xor.dat"

                // Build tar command with all required components
                val tarComponents = mutableListOf("chainstate/", "blocks/index/")
                if (lastBlkNum.isNotEmpty()) {
                    tarComponents.add(blkFile)
                    tarComponents.add(revFile)
                }
                // xor.dat may or may not exist (Core 28+ / Knots)
                val tarCmd = buildString {
                    append("mkdir -p $destDir && cd $bitcoinDataDir && ")
                    append("tar cf $archivePath ${tarComponents.joinToString(" ")} ")
                    // Include xor.dat if it exists (non-fatal if missing)
                    append("$xorFile 2>/dev/null; ")
                    append("chown $SFTP_USERNAME:$SFTP_USERNAME $archivePath 2>/dev/null; ")
                    append("chmod 644 $archivePath 2>/dev/null; ")
                    append("ls -lh $archivePath")
                }

                // Monitor archive creation in parallel
                val monitorSession = SshUtils.connectSsh(sshHost, sshPort, sshUser, sshPassword)
                val monitorJob = CoroutineScope(Dispatchers.IO).launch {
                    val expectedSize = 12_000_000_000L // ~12 GB expected
                    while (true) {
                        delay(3_000)
                        try {
                            val sizeStr = SshUtils.execSudo(monitorSession, sshPassword,
                                "stat -c%s $archivePath 2>/dev/null || echo 0")
                                .trim().lines().lastOrNull()?.trim() ?: "0"
                            val size = sizeStr.toLongOrNull() ?: 0
                            if (size > 0) {
                                val pct = (size.toDouble() / expectedSize * 100).coerceAtMost(99.0)
                                val sizeGb = size / (1024.0 * 1024 * 1024)
                                _state.value = _state.value.copy(
                                    step = Step.ARCHIVING,
                                    progress = "Archiving: ${"%.1f".format(pct)}% (${"%.1f".format(sizeGb)} GB)",
                                    downloadProgress = (size.toFloat() / expectedSize).coerceAtMost(0.99f)
                                )
                            }
                        } catch (_: Exception) {}
                    }
                }

                val archiveResult = SshUtils.execSudo(archiveSession, sshPassword, tarCmd, timeoutMs = 600_000)
                Log.i(TAG, "Archive result: $archiveResult")

                monitorJob.cancel()
                try { monitorSession.disconnect() } catch (_: Exception) {}

                // Restart remote node immediately
                _state.value = _state.value.copy(progress = "Restarting remote node...")
                if (containerName != null) {
                    SshUtils.execSudo(archiveSession, sshPassword, "docker start $containerName")
                } else {
                    SshUtils.execSudo(archiveSession, sshPassword, "bitcoin-cli -daemon 2>/dev/null || systemctl start bitcoind 2>/dev/null || true")
                }

                archiveSession.disconnect()
                Log.i(TAG, "Remote node restarted")

                // Verify archive exists
                if (!checkArchiveExists(sshHost, sshPort, sftpUser, sftpPassword)) {
                    _state.value = _state.value.copy(step = Step.ERROR,
                        error = "Archive creation failed — file not found on remote node")
                    return@withContext false
                }
            } else if (archiveExists && !localArchiveValid) {
                // Archive on remote but not local — skip straight to download
                _state.value = ChainstateState(step = Step.STOPPING_REMOTE,
                    progress = "Archive found on node ✓")
                delay(500)
                _state.value = _state.value.copy(step = Step.ARCHIVING,
                    progress = "Archive ready ✓")
                delay(500)
            } else if (localArchiveValid) {
                // Already downloaded — skip to deploy
                _state.value = ChainstateState(step = Step.STOPPING_REMOTE,
                    progress = "Archive found on node ✓")
                delay(300)
                _state.value = _state.value.copy(step = Step.ARCHIVING,
                    progress = "Archive ready ✓")
                delay(300)
                _state.value = _state.value.copy(step = Step.DOWNLOADING,
                    progress = "Already on phone (${"%.1f".format(archiveFile.length() / (1024.0 * 1024 * 1024))} GB) ✓")
                delay(500)
            }

            // --- Step 3: Download archive via SFTP ---
            if (!localArchiveValid) {
                _state.value = _state.value.copy(step = Step.DOWNLOADING,
                    progress = "Connecting to SFTP...")

                val downloader = SnapshotDownloader(context)
                val progressJob = CoroutineScope(Dispatchers.Default).launch {
                    downloader.progress.collect { dp ->
                        if (dp.totalBytes > 0) {
                            _state.value = _state.value.copy(
                                step = Step.DOWNLOADING,
                                progress = "Downloading: ${"%.1f".format(dp.bytesDownloaded / (1024.0 * 1024 * 1024))} / ${"%.1f".format(dp.totalBytes / (1024.0 * 1024 * 1024))} GB",
                                downloadProgress = dp.progressFraction,
                                downloadedBytes = dp.bytesDownloaded,
                                totalBytes = dp.totalBytes
                            )
                        }
                    }
                }

                val file = downloader.downloadSftp(
                    host = sshHost,
                    port = sshPort,
                    username = sftpUser,
                    password = sftpPassword,
                    remotePath = "/snapshots/$ARCHIVE_NAME",
                    destinationFile = archiveFile
                )
                progressJob.cancel()

                if (file == null) {
                    _state.value = _state.value.copy(step = Step.ERROR,
                        error = "Failed to download archive from node")
                    return@withContext false
                }
            }

            // --- Step 4: Deploy on phone ---
            _state.value = _state.value.copy(step = Step.DEPLOYING,
                progress = "Stopping local node...")

            if (BitcoindService.isRunningFlow.value) {
                context.stopService(Intent(context, BitcoindService::class.java))
                for (w in 0 until 30) {
                    if (!BitcoindService.isRunningFlow.value) break
                    delay(1000)
                }
                delay(2000)
            }

            // Clean existing data
            _state.value = _state.value.copy(progress = "Preparing data directory...")
            if (chainstateDir.exists()) {
                Log.i(TAG, "Deleting existing chainstate")
                chainstateDir.deleteRecursively()
            }
            if (chainstateSnapshotDir.exists()) {
                Log.i(TAG, "Deleting old chainstate_snapshot")
                chainstateSnapshotDir.deleteRecursively()
            }
            val blocksIndexDir = blocksDir.resolve("index")
            if (blocksIndexDir.exists()) {
                Log.i(TAG, "Deleting old blocks/index")
                blocksIndexDir.deleteRecursively()
            }
            // Delete old blk/rev files
            blocksDir.listFiles()?.filter {
                it.name.startsWith("blk") || it.name.startsWith("rev")
            }?.forEach { it.delete() }

            // Extract archive
            _state.value = _state.value.copy(progress = "Extracting archive...")
            var lastBlkFileNum = 0
            try {
                val tarInput = org.apache.commons.compress.archivers.tar.TarArchiveInputStream(
                    java.io.BufferedInputStream(archiveFile.inputStream(), 256 * 1024)
                )
                var entry = tarInput.nextTarEntry
                var fileCount = 0
                while (entry != null) {
                    val outFile = dataDir.resolve(entry.name)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { out ->
                            tarInput.copyTo(out, 256 * 1024)
                        }
                        fileCount++
                        // Track the highest blk file number
                        val blkMatch = Regex("blk(\\d+)\\.dat").find(entry.name)
                        if (blkMatch != null) {
                            val num = blkMatch.groupValues[1].toIntOrNull() ?: 0
                            if (num > lastBlkFileNum) lastBlkFileNum = num
                        }
                    }
                    if (fileCount % 50 == 0) {
                        _state.value = _state.value.copy(
                            progress = "Extracting: $fileCount files...")
                    }
                    entry = tarInput.nextTarEntry
                }
                tarInput.close()
                Log.i(TAG, "Extracted $fileCount files, last blk file: $lastBlkFileNum")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to extract archive", e)
                _state.value = _state.value.copy(step = Step.ERROR,
                    error = "Failed to extract archive: ${e.message}")
                return@withContext false
            }

            // Create stub blk/rev files for all files referenced in the block index
            _state.value = _state.value.copy(progress = "Creating block file stubs...")
            blocksDir.mkdirs()
            var stubCount = 0
            for (i in 0..lastBlkFileNum) {
                val blkName = "blk%05d.dat".format(i)
                val revName = "rev%05d.dat".format(i)
                val blkFile = blocksDir.resolve(blkName)
                val revFile = blocksDir.resolve(revName)
                // Don't overwrite real files (the tip blk/rev from the archive)
                if (!blkFile.exists()) {
                    blkFile.createNewFile()
                    stubCount++
                }
                if (!revFile.exists()) {
                    revFile.createNewFile()
                    stubCount++
                }
                if (stubCount % 1000 == 0) {
                    _state.value = _state.value.copy(
                        progress = "Creating stubs: $stubCount files...")
                }
            }
            Log.i(TAG, "Created $stubCount stub files (0 to $lastBlkFileNum)")

            // Delete archive to free space
            archiveFile.delete()
            Log.i(TAG, "Deleted archive to free space")

            // Ensure bitcoin.conf has checklevel=0 for first boot
            _state.value = _state.value.copy(progress = "Configuring node...")
            ConfigGenerator.ensureConfig(context)
            val confFile = dataDir.resolve("bitcoin.conf")
            if (confFile.exists()) {
                var confText = confFile.readText()
                if (!confText.contains("checklevel=")) {
                    confText += "\nchecklevel=0\n"
                    confFile.writeText(confText)
                    Log.i(TAG, "Added checklevel=0 to bitcoin.conf")
                }
            }

            // --- Step 5: Start node ---
            _state.value = _state.value.copy(step = Step.STARTING_NODE,
                progress = "Starting node...")

            startBitcoindService()

            // Wait for RPC
            val creds = ConfigGenerator.readCredentials(context)
            if (creds == null) {
                _state.value = _state.value.copy(step = Step.ERROR,
                    error = "No RPC credentials — try restarting the app")
                return@withContext false
            }
            val rpc = BitcoinRpcClient(creds.first, creds.second)
            var rpcReady = false
            val rpcDeadlineMs = 1200_000L // 20 minutes (pruning can take a while)
            val rpcStartTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - rpcStartTime < rpcDeadlineMs) {
                val elapsedMs = System.currentTimeMillis() - rpcStartTime
                val remainingSec = ((rpcDeadlineMs - elapsedMs) / 1000).coerceAtLeast(0)
                try {
                    val info = rpc.call("getblockchaininfo",
                        connectTimeoutMs = 2_000, readTimeoutMs = 5_000)
                    if (info != null && !info.optBoolean("_rpc_error", false)) {
                        val blocks = info.optLong("blocks", 0)
                        val ibd = info.optBoolean("initialblockdownload", true)
                        Log.i(TAG, "Node ready! Height: $blocks, IBD: $ibd")
                        _state.value = _state.value.copy(
                            progress = "Node running at height $blocks ✓")
                        rpcReady = true
                        break
                    } else if (info != null && info.optInt("code") == -28) {
                        val msg = info.optString("message", "Loading...")
                        _state.value = _state.value.copy(
                            progress = "$msg (${remainingSec / 60}m ${remainingSec % 60}s remaining)")
                    }
                } catch (_: Exception) {}
                delay(3000)
            }

            if (!rpcReady) {
                _state.value = _state.value.copy(step = Step.ERROR,
                    error = "Node didn't respond after 20 min — check debug.log")
                return@withContext false
            }

            // Mark node as running for auto-start
            context.getSharedPreferences("pocketnode_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean("node_was_running", true).apply()

            _state.value = _state.value.copy(step = Step.COMPLETE,
                progress = "Node running at chain tip — no background validation needed!")
            true

        } catch (e: Exception) {
            Log.e(TAG, "Chainstate copy failed", e)
            _state.value = _state.value.copy(step = Step.ERROR,
                error = e.message ?: "Failed")
            false
        }
    }

    /**
     * Check if the sync archive exists on the remote node via SFTP.
     */
    suspend fun checkArchiveExists(host: String, port: Int, user: String, password: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val session = SshUtils.connectSsh(host, port, user, password)
                val channel = session.openChannel("sftp") as com.jcraft.jsch.ChannelSftp
                channel.connect(10_000)
                val attrs = channel.stat("/snapshots/$ARCHIVE_NAME")
                val exists = attrs.size > 1_000_000_000
                channel.disconnect()
                session.disconnect()
                exists
            } catch (_: Exception) {
                false
            }
        }

    fun reset() {
        _state.value = ChainstateState()
    }

    // --- Private helpers ---

    private fun startBitcoindService() {
        val intent = Intent(context, BitcoindService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    private suspend fun tickThroughSteps(finalMessage: String) {
        _state.value = _state.value.copy(step = Step.STOPPING_REMOTE, progress = "Already synced ✓")
        delay(300)
        _state.value = _state.value.copy(step = Step.ARCHIVING, progress = "Already synced ✓")
        delay(300)
        _state.value = _state.value.copy(step = Step.DOWNLOADING, progress = "Already on device ✓")
        delay(300)
        _state.value = _state.value.copy(step = Step.DEPLOYING, progress = "Chainstate current ✓")
        delay(300)
        _state.value = _state.value.copy(step = Step.STARTING_NODE, progress = "Node running ✓")
        delay(300)
        context.getSharedPreferences("pocketnode_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("node_was_running", true).apply()
        _state.value = _state.value.copy(step = Step.COMPLETE, progress = finalMessage)
    }

}
