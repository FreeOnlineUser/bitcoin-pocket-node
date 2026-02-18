package com.pocketnode.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.pocketnode.MainActivity
import com.pocketnode.R

/**
 * Manages notifications for transaction confirmations
 */
class TransactionNotificationManager(private val context: Context) {
    companion object {
        private const val CHANNEL_ID = "mempool_confirmations"
        private const val CHANNEL_NAME = "Transaction Confirmations"
        private const val CHANNEL_DESCRIPTION = "Notifications when watched transactions are confirmed"
        private const val NOTIFICATION_ID_BASE = 1000
    }
    
    private val notificationManager = NotificationManagerCompat.from(context)
    
    init {
        createNotificationChannel()
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = CHANNEL_DESCRIPTION
                enableLights(true)
                lightColor = android.graphics.Color.parseColor("#FF9500") // Bitcoin orange
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 150, 100, 150) // Double buzz pattern
            }
            
            val systemNotificationManager = context.getSystemService(NotificationManager::class.java)
            systemNotificationManager.createNotificationChannel(channel)
        }
    }
    
    /**
     * Send notification when a watched transaction is confirmed
     */
    fun notifyTransactionConfirmed(
        txid: String,
        blockNumber: Int,
        confirmations: Int
    ) {
        if (!hasNotificationPermission()) {
            return
        }
        
        val truncatedTxid = truncateTxid(txid)
        val title = "⛏️ Transaction Confirmed"
        val message = "$truncatedTxid confirmed in block #$blockNumber ($confirmations confirmations)"
        
        // Create intent to open transaction detail (for now just opens main activity)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            // TODO: Add extra data to navigate to transaction detail
            putExtra("txid", txid)
        }
        
        val pendingIntent = PendingIntent.getActivity(
            context,
            txid.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bitcoin_notification) // We'll need to create this
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setColor(context.getColor(android.R.color.holo_orange_light))
            .setVibrate(longArrayOf(0, 150, 100, 150)) // Double buzz
            .build()
        
        val notificationId = NOTIFICATION_ID_BASE + txid.hashCode() % 1000
        notificationManager.notify(notificationId, notification)
    }
    
    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // No permission needed for older Android versions
        }
    }
    
    private fun truncateTxid(txid: String): String {
        return if (txid.length > 12) {
            "${txid.take(6)}...${txid.takeLast(6)}"
        } else {
            txid
        }
    }
}