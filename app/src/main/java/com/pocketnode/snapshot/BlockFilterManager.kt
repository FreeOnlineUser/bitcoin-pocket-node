package com.pocketnode.snapshot

import android.content.Context
import android.util.Log
import com.pocketnode.ssh.SshUtils
import com.pocketnode.util.ConfigGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Manages block filter index (BIP 157/158) for Lightning support.
 *
 * Handles:
 * - Detection: check if donor node has block filters built
 * - Config: enable/disable blockfilterindex on donor and local node
 * - Copy: download filter index from donor via SFTP
 * - Revert: restore donor config after copy
 * - Remove: delete local filters and revert local config
 */
class BlockFilterManager(private val context: Context) {

    companion object {
        private const val TAG = "BlockFilterManager"
        private const val FILTER_DIR = "indexes/blockfilter/basic"
        private const val FILTER_ARCHIVE = "blockfilters.tar"
        private const val CONFIG_KEY_INDEX = "blockfilterindex"
        private const val CONFIG_KEY_SERVE = "peerblockfilters"
    }

    enum class Step {
        IDLE,
        CONNECTING,
        CHECKING_DONOR,
        ENABLING_ON_DONOR,
        WAITING_FOR_BUILD,
        ARCHIVING,
        DOWNLOADING,
        EXTRACTING,
        CONFIGURING_LOCAL,
        REVERTING_DONOR,
        COMPLETE,
        ERROR
    }

    data class FilterState(
        val step: Step = Step.IDLE,
        val progress: String = "",
        val downloadProgress: Float = 0f,
        val downloadedBytes: Long = 0,
        val totalBytes: Long = 0,
        val error: String? = null,
        val donorHasFilters: Boolean? = null,
        val donorFilterSizeBytes: Long = 0,
        val localHasFilters: Boolean = false,
        val buildProgress: Float = 0f
    )

    private val _state = MutableStateFlow(FilterState())
    val state: StateFlow<FilterState> = _state.asStateFlow()

    private val dataDir: File get() = context.filesDir.resolve("bitcoin")

    /**
     * Check if block filters are installed locally.
     */
    fun isInstalledLocally(): Boolean {
        val filterDir = dataDir.resolve(FILTER_DIR)
        return filterDir.exists() && (filterDir.listFiles()?.any { it.name.startsWith("fltr") } == true)
    }

    /**
     * Get local filter index size in bytes.
     */
    fun localSizeBytes(): Long {
        val filterDir = dataDir.resolve(FILTER_DIR)
        if (!filterDir.exists()) return 0
        return filterDir.walkTopDown().sumOf { it.length() }
    }

    /**
     * Check if donor node has block filters built.
     * Returns the size in bytes, or -1 if not present.
     */
    suspend fun checkDonor(
        sshHost: String, sshPort: Int, sshUser: String, sshPassword: String
    ): Long = withContext(Dispatchers.IO) {
        try {
            _state.value = FilterState(step = Step.CONNECTING, progress = "Connecting to source node...")
            val session = SshUtils.connectSsh(sshHost, sshPort, sshUser, sshPassword)

            _state.value = _state.value.copy(step = Step.CHECKING_DONOR, progress = "Looking for block filters...")
            val bitcoinDir = SshUtils.findBitcoinDataDir(session, sshPassword)
            if (bitcoinDir.isEmpty()) {
                session.disconnect()
                _state.value = _state.value.copy(step = Step.ERROR, error = "Could not find Bitcoin data directory")
                return@withContext -1L
            }

            val filterPath = "$bitcoinDir/$FILTER_DIR"
            val sizeResult = SshUtils.execSudo(session, sshPassword,
                "du -sb '$filterPath' 2>/dev/null | awk '{print \$1}' || echo '-1'")
                .trim().lines().lastOrNull()?.trim()?.toLongOrNull() ?: -1L

            // Also check for fltr files specifically
            val fileCount = if (sizeResult > 0) {
                SshUtils.execSudo(session, sshPassword,
                    "ls '$filterPath'/fltr*.dat 2>/dev/null | wc -l")
                    .trim().lines().lastOrNull()?.trim()?.toIntOrNull() ?: 0
            } else 0

            session.disconnect()

            val hasFilters = sizeResult > 0 && fileCount > 0
            _state.value = _state.value.copy(
                step = Step.IDLE,
                donorHasFilters = hasFilters,
                donorFilterSizeBytes = if (hasFilters) sizeResult else 0,
                progress = ""
            )
            if (hasFilters) sizeResult else -1L
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check donor", e)
            _state.value = _state.value.copy(step = Step.ERROR, error = e.message)
            -1L
        }
    }

    /**
     * Enable block filter index on the donor node's bitcoin.conf and restart.
     */
    suspend fun enableOnDonor(
        sshHost: String, sshPort: Int, sshUser: String, sshPassword: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            _state.value = _state.value.copy(step = Step.ENABLING_ON_DONOR,
                progress = "Enabling block filters on source node...")

            val session = SshUtils.connectSsh(sshHost, sshPort, sshUser, sshPassword)
            val bitcoinDir = SshUtils.findBitcoinDataDir(session, sshPassword)
            if (bitcoinDir.isEmpty()) {
                session.disconnect()
                _state.value = _state.value.copy(step = Step.ERROR, error = "Could not find Bitcoin data directory")
                return@withContext false
            }

            val confPath = "$bitcoinDir/bitcoin.conf"

            // Check if already enabled
            val existing = SshUtils.execSudo(session, sshPassword,
                "grep -c '$CONFIG_KEY_INDEX=1' '$confPath' 2>/dev/null || echo '0'")
                .trim().lines().lastOrNull()?.trim()?.toIntOrNull() ?: 0

            if (existing == 0) {
                // Add to config
                SshUtils.execSudo(session, sshPassword,
                    "echo '' >> '$confPath' && echo '# Added by Pocket Node for Lightning support' >> '$confPath' && echo '$CONFIG_KEY_INDEX=1' >> '$confPath'")

                // Restart bitcoind
                _state.value = _state.value.copy(progress = "Restarting source node...")
                val container = SshUtils.detectDockerContainer(session, sshPassword)
                if (container != null) {
                    SshUtils.execSudo(session, sshPassword, "docker restart $container",
                        timeoutMs = 120_000)
                } else {
                    SshUtils.execSudo(session, sshPassword,
                        "bitcoin-cli stop 2>/dev/null || systemctl restart bitcoind 2>/dev/null || true")
                    delay(10_000) // Wait for restart
                }
            }

            session.disconnect()
            _state.value = _state.value.copy(step = Step.WAITING_FOR_BUILD,
                progress = "Source node is building block filter index...")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable on donor", e)
            _state.value = _state.value.copy(step = Step.ERROR, error = e.message)
            false
        }
    }

    /**
     * Poll donor for block filter build progress.
     * Returns true when synced, false if still building, null on error.
     */
    suspend fun pollBuildProgress(
        sshHost: String, sshPort: Int, sshUser: String, sshPassword: String
    ): Boolean? = withContext(Dispatchers.IO) {
        try {
            val session = SshUtils.connectSsh(sshHost, sshPort, sshUser, sshPassword)
            val bitcoinDir = SshUtils.findBitcoinDataDir(session, sshPassword)

            // Try bitcoin-cli getindexinfo
            val container = SshUtils.detectDockerContainer(session, sshPassword)
            val cliCmd = if (container != null) {
                "docker exec $container bitcoin-cli getindexinfo 2>/dev/null"
            } else {
                "bitcoin-cli getindexinfo 2>/dev/null"
            }
            val indexInfo = SshUtils.execSudo(session, sshPassword, cliCmd)

            // Also check if filter files exist yet
            val filterPath = "$bitcoinDir/$FILTER_DIR"
            val fileCount = SshUtils.execSudo(session, sshPassword,
                "ls '$filterPath'/fltr*.dat 2>/dev/null | wc -l")
                .trim().lines().lastOrNull()?.trim()?.toIntOrNull() ?: 0

            session.disconnect()

            // Parse getindexinfo response for block filter progress
            val synced = indexInfo.contains("\"synced\": true") ||
                         indexInfo.contains("\"synced\":true")

            if (synced) {
                _state.value = _state.value.copy(
                    step = Step.WAITING_FOR_BUILD,
                    progress = "Block filter index ready!",
                    buildProgress = 1.0f
                )
                return@withContext true
            }

            // Estimate progress from file count (each file ~16MB, ~780 files for full chain)
            val estimatedTotal = 780f
            val progress = (fileCount / estimatedTotal).coerceIn(0f, 0.99f)
            _state.value = _state.value.copy(
                step = Step.WAITING_FOR_BUILD,
                progress = "Building block filters: $fileCount files (~${"%,.0f".format(progress * 100)}%)",
                buildProgress = progress
            )
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to poll build progress", e)
            null
        }
    }

    /**
     * Copy block filter index from donor to local phone.
     *
     * Stops donor node first for consistent LevelDB state, then archives,
     * restarts donor, downloads, extracts, and configures local node.
     * Same pattern as ChainstateManager.copyChainstate().
     */
    suspend fun copyFromDonor(
        sshHost: String, sshPort: Int, sshUser: String, sshPassword: String,
        sftpUser: String = "pocketnode", sftpPassword: String = ""
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // --- Step 1: Connect and find data dir ---
            _state.value = _state.value.copy(step = Step.CONNECTING,
                progress = "Connecting to source node...")

            val session = SshUtils.connectSsh(sshHost, sshPort, sshUser, sshPassword)
            val bitcoinDir = SshUtils.findBitcoinDataDir(session, sshPassword)
            if (bitcoinDir.isEmpty()) {
                session.disconnect()
                _state.value = _state.value.copy(step = Step.ERROR, error = "Could not find Bitcoin data directory")
                return@withContext false
            }

            // Filter index uses LevelDB, same as chainstate -- must stop the node to get
            // a consistent snapshot. The filter DB references block index entries, so a
            // partial copy would leave dangling references.
            _state.value = _state.value.copy(step = Step.ARCHIVING,
                progress = "Stopping source node for consistent copy...")

            val containerName = SshUtils.detectDockerContainer(session, sshPassword)
            if (containerName != null) {
                SshUtils.execSudo(session, sshPassword, "docker stop $containerName",
                    timeoutMs = 120_000)
            } else {
                SshUtils.execSudo(session, sshPassword,
                    "bitcoin-cli stop 2>/dev/null || systemctl stop bitcoind 2>/dev/null || true")
                delay(5000)
            }

            // --- Step 3: Archive filter index ---
            _state.value = _state.value.copy(progress = "Archiving block filters...")

            val destDir = "/home/pocketnode/snapshots"
            val archivePath = "$destDir/$FILTER_ARCHIVE"
            val tarCmd = "mkdir -p '$destDir' && cd '$bitcoinDir' && tar cf '$archivePath' '$FILTER_DIR/' 2>&1 && chown pocketnode:pocketnode '$archivePath' 2>/dev/null; chmod 644 '$archivePath' 2>/dev/null; ls -l '$archivePath'"
            SshUtils.execSudo(session, sshPassword, tarCmd, timeoutMs = 600_000)

            // --- Step 4: Restart donor node immediately ---
            _state.value = _state.value.copy(progress = "Restarting source node...")
            if (containerName != null) {
                SshUtils.execSudo(session, sshPassword, "docker start $containerName")
            } else {
                SshUtils.execSudo(session, sshPassword,
                    "bitcoin-cli -daemon 2>/dev/null || systemctl start bitcoind 2>/dev/null || true")
            }
            Log.i(TAG, "Donor node restarted")

            // Get archive size
            val sizeStr = SshUtils.execSudo(session, sshPassword,
                "stat -c%s '$archivePath' 2>/dev/null || echo '0'")
                .trim().lines().lastOrNull()?.trim()
            val archiveSize = sizeStr?.toLongOrNull() ?: 0L

            if (archiveSize < 1_000_000) {
                session.disconnect()
                _state.value = _state.value.copy(step = Step.ERROR,
                    error = "Archive too small ($archiveSize bytes), something went wrong")
                return@withContext false
            }

            session.disconnect()

            // Download via SFTP using same system as chainstate copy
            _state.value = _state.value.copy(step = Step.DOWNLOADING,
                progress = "Connecting to SFTP...")

            val localArchive = dataDir.resolve(FILTER_ARCHIVE)
            val downloader = SnapshotDownloader(context)
            val progressJob = kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch {
                downloader.progress.collect { dp ->
                    if (dp.totalBytes > 0) {
                        _state.value = _state.value.copy(
                            step = Step.DOWNLOADING,
                            downloadedBytes = dp.bytesDownloaded,
                            totalBytes = dp.totalBytes,
                            downloadProgress = dp.progressFraction,
                            progress = "Downloading: ${"%.1f".format(dp.bytesDownloaded / (1024.0 * 1024 * 1024))} / ${"%.1f".format(dp.totalBytes / (1024.0 * 1024 * 1024))} GB"
                        )
                    }
                }
            }

            val file = downloader.downloadSftp(
                host = sshHost,
                port = sshPort,
                username = sftpUser,
                password = sftpPassword,
                remotePath = "/snapshots/$FILTER_ARCHIVE",
                destinationFile = localArchive
            )
            progressJob.cancel()

            if (file == null) {
                _state.value = _state.value.copy(step = Step.ERROR,
                    error = "Failed to download block filters from node")
                return@withContext false
            }

            // Clean up remote archive
            try {
                val cleanSession = SshUtils.connectSsh(sshHost, sshPort, sshUser, sshPassword)
                SshUtils.execSudo(cleanSession, sshPassword, "rm -f '$archivePath'")
                cleanSession.disconnect()
            } catch (_: Exception) {}

            // Extract locally using Apache Commons Compress (no system tar on Android)
            _state.value = _state.value.copy(step = Step.EXTRACTING,
                progress = "Extracting block filters...")

            var fileCount = 0
            val tarInput = org.apache.commons.compress.archivers.tar.TarArchiveInputStream(
                java.io.BufferedInputStream(localArchive.inputStream(), 256 * 1024)
            )
            var entry = tarInput.nextTarEntry
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
                    if (fileCount % 50 == 0) {
                        _state.value = _state.value.copy(
                            progress = "Extracting: $fileCount files...")
                    }
                }
                entry = tarInput.nextTarEntry
            }
            tarInput.close()
            Log.i(TAG, "Extracted $fileCount filter files")

            // Clean up local archive
            localArchive.delete()

            if (!isInstalledLocally()) {
                _state.value = _state.value.copy(step = Step.ERROR,
                    error = "Extraction failed — no filter files found")
                return@withContext false
            }

            // Configure local bitcoind
            _state.value = _state.value.copy(step = Step.CONFIGURING_LOCAL,
                progress = "Configuring local node...")
            enableLocalConfig()

            _state.value = _state.value.copy(step = Step.COMPLETE,
                progress = "Lightning support enabled!",
                localHasFilters = true)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy filters", e)
            _state.value = _state.value.copy(step = Step.ERROR, error = e.message)
            false
        }
    }

    /**
     * Revert donor's bitcoin.conf — remove blockfilterindex=1 and restart.
     */
    suspend fun revertDonor(
        sshHost: String, sshPort: Int, sshUser: String, sshPassword: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            _state.value = _state.value.copy(step = Step.REVERTING_DONOR,
                progress = "Reverting source node config...")

            val session = SshUtils.connectSsh(sshHost, sshPort, sshUser, sshPassword)
            val bitcoinDir = SshUtils.findBitcoinDataDir(session, sshPassword)
            val confPath = "$bitcoinDir/bitcoin.conf"

            // Remove the lines we added
            SshUtils.execSudo(session, sshPassword,
                "sed -i '/# Added by Pocket Node for Lightning support/d' '$confPath' && " +
                "sed -i '/$CONFIG_KEY_INDEX=1/d' '$confPath'")

            // Restart
            val container = SshUtils.detectDockerContainer(session, sshPassword)
            if (container != null) {
                SshUtils.execSudo(session, sshPassword, "docker restart $container",
                    timeoutMs = 120_000)
            } else {
                SshUtils.execSudo(session, sshPassword,
                    "bitcoin-cli stop 2>/dev/null || systemctl restart bitcoind 2>/dev/null || true")
            }

            session.disconnect()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to revert donor", e)
            false // Non-fatal — donor will still work, just has an extra config line
        }
    }

    /**
     * Add blockfilterindex=1 and peerblockfilters=1 to local bitcoin.conf.
     */
    fun enableLocalConfig() {
        val confFile = dataDir.resolve("bitcoin.conf")
        if (!confFile.exists()) return

        var content = confFile.readText()

        // Lightning wallets (Zeus/Neutrino) connect via Bitcoin's P2P protocol to fetch
        // compact block filters (BIP 157). This requires listen=1, but we bind to localhost
        // only so the node isn't exposed to the internet -- only local apps can connect.
        if (content.contains("listen=0")) {
            content = content.replace("listen=0", "listen=1")
            if (!content.contains("bind=")) {
                content = content.replace("listen=1", "listen=1\nbind=127.0.0.1")
            }
            confFile.writeText(content)
        }

        val lines = mutableListOf<String>()

        if (!content.contains("$CONFIG_KEY_INDEX=1")) {
            lines.add("$CONFIG_KEY_INDEX=1")
        }
        if (!content.contains("$CONFIG_KEY_SERVE=1")) {
            lines.add("$CONFIG_KEY_SERVE=1")
        }

        if (lines.isNotEmpty()) {
            confFile.appendText("\n# Lightning support (BIP 157/158 block filters)\n${lines.joinToString("\n")}\n")
        }
    }

    /**
     * Remove block filter config and delete filter files.
     * Stops the node first so no files are held open or recreated.
     */
    suspend fun removeLocal(context: android.content.Context): Boolean {
        // Step 1: Remove config lines FIRST
        val confFile = dataDir.resolve("bitcoin.conf")
        if (confFile.exists()) {
            val content = confFile.readText()
            val cleaned = content
                .replace(Regex("# (Added by Pocket Node for )?Lightning support[^\n]*\n?"), "")
                .replace(Regex("$CONFIG_KEY_INDEX=1\n?"), "")
                .replace(Regex("$CONFIG_KEY_SERVE=1\n?"), "")
                .replace("listen=1", "listen=0")
                .replace(Regex("bind=127\\.0\\.0\\.1\n?"), "")
            confFile.writeText(cleaned)
        }

        // Step 2: Stop the node so no files are held open
        if (com.pocketnode.service.BitcoindService.isRunningFlow.value) {
            context.stopService(android.content.Intent(context, com.pocketnode.service.BitcoindService::class.java))
            // Wait for node to stop
            for (i in 0 until 30) {
                if (!com.pocketnode.service.BitcoindService.isRunningFlow.value) break
                kotlinx.coroutines.delay(1000)
            }
            kotlinx.coroutines.delay(500)
        }

        // Step 3: Delete entire indexes directory (clean slate)
        val indexesDir = dataDir.resolve("indexes")
        if (indexesDir.exists()) {
            indexesDir.deleteRecursively()
        }

        // Step 4: Restart node without filters
        val intent = android.content.Intent(context, com.pocketnode.service.BitcoindService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }

        _state.value = _state.value.copy(localHasFilters = false)
        return true
    }

    /**
     * Reset state to idle.
     */
    fun reset() {
        _state.value = FilterState(localHasFilters = isInstalledLocally())
    }
}
