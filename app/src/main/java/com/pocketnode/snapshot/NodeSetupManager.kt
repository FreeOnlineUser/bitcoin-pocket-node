package com.pocketnode.snapshot

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.security.SecureRandom

/**
 * Manages one-time SSH setup of the restricted SFTP account on the user's node.
 * Connects via SSH, creates the "pocketnode" user with SFTP-only access,
 * and returns the generated credentials.
 */
class NodeSetupManager(private val context: Context) {

    companion object {
        private const val TAG = "NodeSetupManager"
        private const val PREFS_NAME = "pocketnode_sftp"
        private const val KEY_SFTP_HOST = "sftp_host"
        private const val KEY_SFTP_PORT = "sftp_port"
        private const val KEY_SFTP_USER = "sftp_user"
        private const val KEY_SFTP_PASS = "sftp_pass"
        private const val KEY_SETUP_DONE = "setup_done"
        private const val KEY_ADMIN_USER = "admin_user"
        private const val SFTP_USERNAME = "pocketnode"
        private const val PASSWORD_LENGTH = 20
        private val PASSWORD_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray()
    }

    data class SetupResult(
        val success: Boolean,
        val sftpUser: String = "",
        val sftpPassword: String = "",
        val platform: String = "Unknown",
        val error: String? = null,
        val log: String = ""
    )

    private val prefs: SharedPreferences
        get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isSetupDone(): Boolean = prefs.getBoolean(KEY_SETUP_DONE, false)

    fun getSavedHost(): String = prefs.getString(KEY_SFTP_HOST, "") ?: ""
    fun getSavedPort(): Int = prefs.getInt(KEY_SFTP_PORT, 22)
    fun getSavedUser(): String = prefs.getString(KEY_SFTP_USER, "") ?: ""
    fun getSavedPassword(): String = prefs.getString(KEY_SFTP_PASS, "") ?: ""
    fun getSavedAdminUser(): String = prefs.getString(KEY_ADMIN_USER, "") ?: ""

    fun saveCredentials(host: String, port: Int, user: String, password: String) {
        prefs.edit()
            .putString(KEY_SFTP_HOST, host)
            .putInt(KEY_SFTP_PORT, port)
            .putString(KEY_SFTP_USER, user)
            .putString(KEY_SFTP_PASS, password)
            .putBoolean(KEY_SETUP_DONE, true)
            .apply()
    }

    fun clearCredentials() {
        prefs.edit().clear().apply()
    }

    /**
     * Remove the pocketnode SFTP access from the remote node.
     * Connects with admin SSH creds, removes user/config/scripts, clears local creds.
     */
    suspend fun removeAccess(
        adminHost: String,
        adminPort: Int,
        adminUser: String,
        adminPassword: String
    ): Boolean = withContext(Dispatchers.IO) {
        var session: Session? = null
        try {
            val jsch = JSch()
            session = jsch.getSession(adminUser, adminHost, adminPort)
            session.setPassword(adminPassword)
            session.setConfig("StrictHostKeyChecking", "no")
            session.connect(15_000)

            // Remove user
            execSudo(session, adminPassword, "userdel -r $SFTP_USERNAME 2>/dev/null || true")

            // Remove Match User block from sshd_config
            execSudo(session, adminPassword,
                "sed -i '/Match User $SFTP_USERNAME/,+4d' /etc/ssh/sshd_config")

            // Remove copy scripts
            execSudo(session, adminPassword,
                "rm -f /usr/local/bin/pocketnode-copy-snapshot.sh /usr/local/bin/pocketnode-copy-chainstate.sh")

            // Restart SSH
            execSudo(session, adminPassword,
                "systemctl restart sshd 2>&1 || systemctl restart ssh 2>&1")

            session.disconnect()

            // Clear local credentials
            clearCredentials()

            Log.i(TAG, "Remote access removed successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove access", e)
            session?.disconnect()
            false
        }
    }

    private fun generatePassword(): String {
        val random = SecureRandom()
        return (1..PASSWORD_LENGTH).map { PASSWORD_CHARS[random.nextInt(PASSWORD_CHARS.size)] }.joinToString("")
    }

    /**
     * Connect to the node via SSH and set up the restricted SFTP account.
     */
    suspend fun setup(
        host: String,
        sshPort: Int,
        sshUser: String,
        sshPassword: String,
        onProgress: (String) -> Unit = {}
    ): SetupResult = withContext(Dispatchers.IO) {
        val log = StringBuilder()
        fun log(msg: String) {
            Log.d(TAG, msg)
            log.append(msg).append("\n")
            onProgress(msg)
        }

        var session: Session? = null
        try {
            val jsch = JSch()
            session = jsch.getSession(sshUser, host, sshPort)
            session.setPassword(sshPassword)
            session.setConfig("StrictHostKeyChecking", "no")

            log("Connecting to $host:$sshPort...")
            session.connect(15_000)
            log("Connected.")

            // Detect platform
            val platform = detectPlatform(session, sshPassword)
            log("Detected platform: $platform")

            val sftpPassword = generatePassword()

            // Create user (or update password if exists)
            log("Creating restricted user '$SFTP_USERNAME'...")
            val createUserResult = execSudo(session, sshPassword,
                "id $SFTP_USERNAME >/dev/null 2>&1 && echo 'USER_EXISTS' || useradd -m -s /bin/rbash $SFTP_USERNAME")
            log(createUserResult.trim())

            // Set password
            log("Setting SFTP password...")
            val chpasswdResult = execSudo(session, sshPassword,
                "echo '$SFTP_USERNAME:$sftpPassword' | chpasswd")
            if (chpasswdResult.contains("error", ignoreCase = true) ||
                chpasswdResult.contains("failed", ignoreCase = true)) {
                return@withContext SetupResult(false, error = "Failed to set password: $chpasswdResult", log = log.toString())
            }
            log("Password set.")

            // Create directories with correct ownership
            log("Setting up directories...")
            execSudo(session, sshPassword, "mkdir -p /home/$SFTP_USERNAME/snapshots")
            execSudo(session, sshPassword, "chown root:root /home/$SFTP_USERNAME")
            execSudo(session, sshPassword, "chmod 755 /home/$SFTP_USERNAME")
            execSudo(session, sshPassword, "chown $SFTP_USERNAME:$SFTP_USERNAME /home/$SFTP_USERNAME/snapshots")
            log("Directories ready.")

            // Add SFTP config if not already present
            log("Configuring SFTP access...")
            val sshdConfig = execSudo(session, sshPassword, "cat /etc/ssh/sshd_config")
            if (!sshdConfig.contains("Match User $SFTP_USERNAME")) {
                val configBlock = """
                    |
                    |Match User $SFTP_USERNAME
                    |    ForceCommand internal-sftp
                    |    ChrootDirectory /home/$SFTP_USERNAME
                    |    AllowTcpForwarding no
                    |    X11Forwarding no
                """.trimMargin()
                execSudo(session, sshPassword,
                    "echo '$configBlock' >> /etc/ssh/sshd_config")
                log("SFTP config added.")
            } else {
                log("SFTP config already exists, skipping.")
            }

            // Detect bitcoin data directory and Docker/native setup
            log("Detecting Bitcoin data directory...")
            val isDocker = execSudo(session, sshPassword,
                "docker ps 2>/dev/null | grep -qi bitcoin && echo 'DOCKER' || echo 'NATIVE'")
                .trim().lines().lastOrNull()?.trim() == "DOCKER"
            log(if (isDocker) "Docker-based node detected." else "Native node detected.")

            // Find bitcoin data dir
            val bitcoinDataDir = execSudo(session, sshPassword,
                "find / -name 'chainstate' -path '*/bitcoin/*' -type d 2>/dev/null | head -1 | xargs dirname 2>/dev/null")
                .trim().lines().lastOrNull()?.trim() ?: ""

            if (bitcoinDataDir.isNotEmpty()) {
                log("Bitcoin data: $bitcoinDataDir")
                // Create a secure copy script — pocketnode NEVER gets direct access to the data dir.
                // This script runs as root and copies only snapshot files to the SFTP location.
                execSudo(session, sshPassword, """
                    cat > /usr/local/bin/pocketnode-copy-snapshot.sh << 'SCRIPT'
#!/bin/bash
# Pocket Node — secure snapshot copy
# Copies only utxo-*.dat files to the SFTP-accessible location.
# pocketnode user has NO access to the bitcoin data directory.
DATADIR="$bitcoinDataDir"
DEST="/home/$SFTP_USERNAME/snapshots"
COPIED=0
for f in "\${'$'}DATADIR"/utxo-*.dat; do
    [ -f "\${'$'}f" ] || continue
    BASENAME=\${'$'}(basename "\${'$'}f")
    cp "\${'$'}f" "\${'$'}DEST/\${'$'}BASENAME"
    chown $SFTP_USERNAME:$SFTP_USERNAME "\${'$'}DEST/\${'$'}BASENAME"
    chmod 600 "\${'$'}DEST/\${'$'}BASENAME"
    COPIED=\${'$'}((COPIED + 1))
    echo "Copied: \${'$'}BASENAME (\${'$'}(du -h "\${'$'}DEST/\${'$'}BASENAME" | cut -f1))"
done
[ \${'$'}COPIED -eq 0 ] && echo "No snapshot files found in \${'$'}DATADIR"
[ \${'$'}COPIED -gt 0 ] && echo "Done: \${'$'}COPIED snapshot(s) copied"
SCRIPT
                    chmod 700 /usr/local/bin/pocketnode-copy-snapshot.sh
                    chown root:root /usr/local/bin/pocketnode-copy-snapshot.sh
                """.trimIndent())
                log("Secure copy script installed.")
            } else {
                log("Warning: Could not auto-detect Bitcoin data directory. You may need to copy snapshots manually.")
            }

            // Restart sshd
            log("Restarting SSH service...")
            val restartResult = execSudo(session, sshPassword, "systemctl restart sshd 2>&1 || systemctl restart ssh 2>&1 || service ssh restart 2>&1")
            log("SSH restarted. $restartResult")

            // Save SFTP credentials + admin username (NOT password) for pre-fill
            saveCredentials(host, sshPort, SFTP_USERNAME, sftpPassword)
            prefs.edit().putString(KEY_ADMIN_USER, sshUser).apply()
            log("Setup complete!")

            SetupResult(
                success = true,
                sftpUser = SFTP_USERNAME,
                sftpPassword = sftpPassword,
                platform = platform,
                log = log.toString()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Setup failed", e)
            SetupResult(false, error = e.message ?: "Connection failed", log = log.toString())
        } finally {
            session?.disconnect()
        }
    }

    private fun detectPlatform(session: Session, sudoPass: String): String {
        return try {
            val checks = mapOf(
                "Umbrel" to "/home/umbrel/umbrel",
                "Start9" to "/embassy-os",
                "RaspiBlitz" to "/home/admin/raspiblitz.info",
                "myNode" to "/opt/mynode",
                "Citadel" to "/home/citadel/citadel"
            )
            for ((name, path) in checks) {
                val result = execSudo(session, sudoPass, "test -e $path && echo 'FOUND' || echo 'NOPE'")
                if (result.trim().contains("FOUND")) return name
            }
            "Generic Linux"
        } catch (_: Exception) {
            "Unknown"
        }
    }

    private fun execSudo(session: Session, sudoPass: String, command: String): String {
        val channel = session.openChannel("exec") as ChannelExec
        val escaped = command.replace("'", "'\\''")
        channel.setCommand("sudo -S bash -c '$escaped' 2>&1")
        channel.setErrStream(System.err)
        val output = ByteArrayOutputStream()
        channel.outputStream = output
        // Pipe password via stdin instead of echo to avoid shell injection
        channel.setInputStream((sudoPass + "\n").toByteArray().inputStream())
        channel.connect(30_000)

        // Wait for completion
        val start = System.currentTimeMillis()
        while (!channel.isClosed && System.currentTimeMillis() - start < 60_000) {
            Thread.sleep(200)
        }

        // Filter out sudo password prompt from output
        val result = output.toString("UTF-8")
            .replace(Regex("\\[sudo\\][^\n]*?:\\s*"), "")
        channel.disconnect()
        return result
    }
}
