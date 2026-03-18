package com.pocketnode.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL


/**
 * Checks GitHub Releases for a newer version and handles APK download + install.
 * Detects rebuilt releases via APK size comparison when version matches.
 */
object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private const val RELEASES_URL = "https://api.github.com/repos/FreeOnlineUser/bitcoin-pocket-node/releases/latest"
    private const val ALL_RELEASES_URL = "https://api.github.com/repos/FreeOnlineUser/bitcoin-pocket-node/releases?per_page=20"

    data class UpdateInfo(
        val latestVersion: String,
        val currentVersion: String,
        val htmlUrl: String,
        val apkUrl: String?,
        val hasUpdate: Boolean,
        val releaseNotes: String = ""
    )

    /**
     * Check GitHub for the latest release. Returns null on network error.
     */
    suspend fun check(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val currentVersion = context.packageManager
                .getPackageInfo(context.packageName, 0).versionName ?: return@withContext null

            val conn = com.pocketnode.tor.TorAwareHttp.openConnection(RELEASES_URL)
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000

            if (conn.responseCode != 200) {
                Log.w(TAG, "GitHub API returned ${conn.responseCode}")
                return@withContext null
            }

            val body = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(body)
            val tagName = json.getString("tag_name").removePrefix("v")
            val htmlUrl = json.getString("html_url")
            val releaseBody = json.optString("body", "")

            // Find APK asset
            var apkUrl: String? = null
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.getString("name")
                    if (name.endsWith(".apk")) {
                        apkUrl = asset.getString("browser_download_url")
                        break
                    }
                }
            }

            var hasUpdate = isNewer(tagName, currentVersion)

            // Same version? Compare APK size to detect rebuilt releases
            if (!hasUpdate && assets != null) {
                val localSize = getInstalledApkSize(context)
                val remoteSize = getRemoteApkSize(assets)
                if (localSize != null && remoteSize != null && localSize != remoteSize) {
                    hasUpdate = true
                    Log.i(TAG, "Same version but different APK size: local=$localSize remote=$remoteSize")
                }
            }

            // Collect release notes from all versions since current
            val combinedNotes = if (hasUpdate) {
                collectReleaseNotes(currentVersion)
            } else {
                releaseBody
            }

            UpdateInfo(
                latestVersion = tagName,
                currentVersion = currentVersion,
                htmlUrl = htmlUrl,
                apkUrl = apkUrl,
                hasUpdate = hasUpdate,
                releaseNotes = combinedNotes
            )
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed: ${e.message}")
            null
        }
    }

    /**
     * Download APK and trigger Android's package installer.
     * Returns progress via callback (0-100), or -1 on error.
     */
    suspend fun downloadAndInstall(
        context: Context,
        apkUrl: String,
        onProgress: (Int) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val updateDir = File(context.cacheDir, "updates")
            updateDir.mkdirs()
            val apkFile = File(updateDir, "update.apk")

            val conn = com.pocketnode.tor.TorAwareHttp.openConnection(apkUrl)
            conn.connectTimeout = 30_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true

            if (conn.responseCode != 200) {
                Log.e(TAG, "Download failed: HTTP ${conn.responseCode}")
                return@withContext false
            }

            val totalBytes = conn.contentLengthLong
            var downloaded = 0L

            conn.inputStream.use { input ->
                apkFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (totalBytes > 0) {
                            onProgress(((downloaded * 100) / totalBytes).toInt())
                        }
                    }
                }
            }

            Log.i(TAG, "APK downloaded: ${apkFile.length()} bytes")

            // Stop bitcoind service before install to avoid orphan processes
            try {
                val stopIntent = Intent(context, com.pocketnode.service.BitcoindService::class.java)
                context.stopService(stopIntent)
                Log.i(TAG, "Stopped BitcoindService before update install")
                Thread.sleep(3000) // Give bitcoind time to shut down cleanly
            } catch (e: Exception) {
                Log.w(TAG, "Could not stop bitcoind: ${e.message}")
            }

            // Trigger install via FileProvider
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
            val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                data = uri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Download/install failed: ${e.message}", e)
            false
        }
    }

    /**
     * Fetch release notes from all versions newer than the current version.
     * Returns combined notes with version headers, newest first.
     */
    private fun collectReleaseNotes(currentVersion: String): String {
        return try {
            val conn = com.pocketnode.tor.TorAwareHttp.openConnection(ALL_RELEASES_URL)
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000

            if (conn.responseCode != 200) return ""

            val body = conn.inputStream.bufferedReader().readText()
            val releases = org.json.JSONArray(body)
            val notes = StringBuilder()

            for (i in 0 until releases.length()) {
                val release = releases.getJSONObject(i)
                val tag = release.getString("tag_name").removePrefix("v")
                val releaseBody = release.optString("body", "").trim()

                // Stop when we reach the current version or older
                if (!isNewer(tag, currentVersion)) break

                if (releaseBody.isNotEmpty()) {
                    if (notes.isNotEmpty()) notes.append("\n\n")
                    notes.append("v$tag\n")
                    notes.append(releaseBody)
                }
            }

            notes.toString()
        } catch (e: Exception) {
            Log.w(TAG, "Could not fetch release notes: ${e.message}")
            ""
        }
    }

    /**
     * Size of the installed APK file in bytes.
     */
    private fun getInstalledApkSize(context: Context): Long? {
        return try {
            val apkPath = context.packageManager
                .getApplicationInfo(context.packageName, 0).sourceDir
            File(apkPath).length()
        } catch (e: Exception) {
            Log.w(TAG, "Could not get installed APK size: ${e.message}")
            null
        }
    }

    /**
     * Get the APK file size from GitHub release assets.
     * Different builds almost always produce different file sizes.
     */
    private fun getRemoteApkSize(assets: org.json.JSONArray): Long? {
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (asset.getString("name").endsWith(".apk")) {
                return asset.getLong("size")
            }
        }
        return null
    }

    /**
     * Compare version strings. Strip suffixes, compare major.minor numerically.
     */
    private fun isNewer(remote: String, local: String): Boolean {
        fun parse(v: String): List<Int> = v.split("-")[0].split(".").mapNotNull { it.toIntOrNull() }

        val r = parse(remote)
        val l = parse(local)

        for (i in 0 until maxOf(r.size, l.size)) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv > lv) return true
            if (rv < lv) return false
        }
        return false
    }
}
