package com.pocketnode

import android.app.Application
import com.pocketnode.notifications.TransactionNotificationManager

/**
 * Application class for Bitcoin Pocket Node
 */
class BitcoinPocketNodeApplication : Application() {
    
    override fun onCreate() {
        super.onCreate()
        
        // Initialize notification channels
        TransactionNotificationManager(this)
    }
}