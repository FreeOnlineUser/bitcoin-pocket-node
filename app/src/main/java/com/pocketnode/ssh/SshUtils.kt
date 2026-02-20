package com.pocketnode.ssh

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.ByteArrayOutputStream

/**
 * Shared SSH/SFTP utilities used by ChainstateManager and BlockFilterManager.
 */
object SshUtils {

    fun connectSsh(host: String, port: Int, user: String, password: String): Session {
        val jsch = JSch()
        val session = jsch.getSession(user, host, port)
        session.setPassword(password)
        session.setConfig("StrictHostKeyChecking", "no")
        session.connect(15_000)
        return session
    }

    fun execSudo(session: Session, sudoPass: String, command: String,
                 timeoutMs: Long = 60_000): String {
        val channel = session.openChannel("exec") as ChannelExec
        val escaped = command.replace("'", "'\\''")
        channel.setCommand("sudo -S bash -c '$escaped' 2>&1")
        val output = ByteArrayOutputStream()
        channel.outputStream = output
        channel.setInputStream((sudoPass + "\n").toByteArray().inputStream())
        channel.connect(30_000)
        val start = System.currentTimeMillis()
        while (!channel.isClosed && System.currentTimeMillis() - start < timeoutMs) {
            Thread.sleep(500)
        }
        val result = output.toString("UTF-8")
            .replace(Regex("\\[sudo\\][^\n]*?:\\s*"), "")
        channel.disconnect()
        return result
    }

    fun exec(session: Session, command: String, timeoutMs: Long = 30_000): String {
        val channel = session.openChannel("exec") as ChannelExec
        channel.setCommand(command)
        val output = ByteArrayOutputStream()
        channel.outputStream = output
        channel.connect(15_000)
        val start = System.currentTimeMillis()
        while (!channel.isClosed && System.currentTimeMillis() - start < timeoutMs) {
            Thread.sleep(500)
        }
        val result = output.toString("UTF-8")
        channel.disconnect()
        return result
    }

    /**
     * Find the Bitcoin data directory on a remote node.
     * Handles Docker (Umbrel, Start9) and native installs.
     */
    fun findBitcoinDataDir(session: Session, sshPassword: String): String {
        val isDocker = execSudo(session, sshPassword,
            "docker ps 2>/dev/null | grep -qi bitcoin && echo 'DOCKER' || echo 'NATIVE'")
            .trim().lines().lastOrNull()?.trim() == "DOCKER"

        return if (isDocker) {
            val containerName = detectDockerContainer(session, sshPassword)
            if (containerName != null) {
                val mountInfo = execSudo(session, sshPassword,
                    "docker inspect $containerName --format '{{range .Mounts}}{{if or (eq .Destination \"/data/.bitcoin\") (eq .Destination \"/data/bitcoin\") (eq .Destination \"/bitcoin/.bitcoin\")}}{{.Source}}{{end}}{{end}}' 2>/dev/null")
                    .trim().lines().lastOrNull()?.trim() ?: ""
                if (mountInfo.isNotEmpty()) {
                    val hasChainstate = execSudo(session, sshPassword,
                        "test -d '$mountInfo/chainstate' && echo 'YES' || echo 'NO'")
                        .trim().lines().lastOrNull()?.trim()
                    if (hasChainstate == "YES") mountInfo
                    else {
                        val withBitcoin = "$mountInfo/bitcoin"
                        val check = execSudo(session, sshPassword,
                            "test -d '$withBitcoin/chainstate' && echo 'YES' || echo 'NO'")
                            .trim().lines().lastOrNull()?.trim()
                        if (check == "YES") withBitcoin else findBySearch(session, sshPassword)
                    }
                } else findBySearch(session, sshPassword)
            } else findBySearch(session, sshPassword)
        } else findBySearch(session, sshPassword)
    }

    fun detectDockerContainer(session: Session, sshPassword: String): String? {
        val result = execSudo(session, sshPassword,
            "docker ps --format '{{.Names}}' 2>/dev/null | grep -i bitcoin | grep -vi 'proxy\\|tor\\|i2p\\|lnd\\|cln' | head -1")
            .trim().lines().lastOrNull()?.trim()
        return if (result.isNullOrEmpty()) null else result
    }

    private fun findBySearch(session: Session, sshPassword: String): String {
        return execSudo(session, sshPassword,
            "find / -name 'chainstate' -path '*/bitcoin/*' -type d 2>/dev/null | head -1 | xargs dirname 2>/dev/null")
            .trim().lines().lastOrNull()?.trim() ?: ""
    }
}
