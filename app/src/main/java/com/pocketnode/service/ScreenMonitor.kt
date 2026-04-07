package com.pocketnode.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Monitors screen on/off and lock/unlock events.
 * Used to stop LDK when screen is locked on Low/Away mode (no active channels).
 */
class ScreenMonitor(private val context: Context) {

    companion object {
        private const val TAG = "ScreenMonitor"
    }

    private val _isLocked = MutableStateFlow(isCurrentlyLocked())
    val isLockedFlow: StateFlow<Boolean> = _isLocked.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d(TAG, "Screen off (locked)")
                    _isLocked.value = true
                }
                Intent.ACTION_USER_PRESENT -> {
                    // User dismissed lock screen
                    Log.d(TAG, "User present (unlocked)")
                    _isLocked.value = false
                }
                Intent.ACTION_SCREEN_ON -> {
                    // Screen on but may still be locked — USER_PRESENT fires on unlock
                    // Don't change lock state here
                }
            }
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        context.registerReceiver(receiver, filter)
        Log.i(TAG, "Screen monitor started (locked=${_isLocked.value})")
    }

    fun stop() {
        try {
            context.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {}
    }

    private fun isCurrentlyLocked(): Boolean {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        return km.isKeyguardLocked
    }
}
