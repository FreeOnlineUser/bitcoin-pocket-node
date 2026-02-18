package com.pocketnode.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Monitors battery level and charging state via sticky broadcast.
 * Exposes reactive flows for UI and service consumption.
 */
class BatteryMonitor(private val context: Context) {

    companion object {
        private const val TAG = "BatteryMonitor"
    }

    data class BatteryState(
        val level: Int = 100,
        val isCharging: Boolean = true
    ) {
        /** True when on battery (not charging) and below the given threshold */
        fun shouldPause(threshold: Int): Boolean = !isCharging && level < threshold
    }

    private val _state = MutableStateFlow(BatteryState())
    val state: StateFlow<BatteryState> = _state.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            updateFromIntent(intent)
        }
    }

    fun start() {
        // Register for battery change broadcasts
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val stickyIntent = context.registerReceiver(receiver, filter)
        // Sticky broadcast gives us the current state immediately
        if (stickyIntent != null) {
            updateFromIntent(stickyIntent)
        }
        Log.i(TAG, "Battery monitor started")
    }

    fun stop() {
        try {
            context.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
            // Already unregistered
        }
    }

    private fun updateFromIntent(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val pct = if (scale > 0) (level * 100) / scale else 100

        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val charging = plugged != 0

        val newState = BatteryState(level = pct, isCharging = charging)
        if (newState != _state.value) {
            _state.value = newState
            Log.d(TAG, "Battery: ${pct}%, charging=$charging")
        }
    }
}
