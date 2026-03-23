package com.pocketnode.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receives ADB commands for clean Lightning stop/restart.
 *
 * Usage:
 *   adb shell am broadcast -a com.pocketnode.STOP_LIGHTNING -n com.pocketnode/.service.AdminReceiver
 *   adb shell am broadcast -a com.pocketnode.RESTART_LIGHTNING -n com.pocketnode/.service.AdminReceiver
 *
 * STOP waits for LDK to flush state, then stops. Safe for APK updates.
 * RESTART does a clean stop, waits 3s, then starts again.
 */
class AdminReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AdminReceiver"
        const val ACTION_STOP = "com.pocketnode.STOP_LIGHTNING"
        const val ACTION_RESTART = "com.pocketnode.RESTART_LIGHTNING"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_STOP -> {
                Log.i(TAG, "Clean stop requested via ADB")
                Thread({
                    try {
                        val lightning = com.pocketnode.lightning.LightningService.getInstance(context)
                        lightning.stop()
                        Log.i(TAG, "Lightning stopped cleanly. Safe to install update.")
                    } catch (e: Exception) {
                        Log.e(TAG, "Clean stop failed: ${e.message}")
                    }
                }, "admin-clean-stop").start()
            }
            ACTION_RESTART -> {
                Log.i(TAG, "Clean restart requested via ADB")
                Thread({
                    try {
                        val lightning = com.pocketnode.lightning.LightningService.getInstance(context)
                        val creds = lightning.getStoredCredentials()
                        lightning.stop()
                        Log.i(TAG, "Lightning stopped, waiting 3s before restart...")
                        Thread.sleep(3000)
                        if (creds != null) {
                            val prefs = context.getSharedPreferences("pocketnode_prefs", android.content.Context.MODE_PRIVATE)
                            val port = prefs.getInt("rpc_port", 8332)
                            lightning.start(creds.first, creds.second, port)
                            Log.i(TAG, "Lightning restarted cleanly.")
                        } else {
                            Log.w(TAG, "No stored credentials, cannot restart.")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Clean restart failed: ${e.message}")
                    }
                }, "admin-clean-restart").start()
            }
        }
    }
}
