package com.openminis.betting.app.core

import android.content.Context
import android.app.NotificationManager
import android.app.NotificationChannel
import android.os.Build
import androidx.core.app.NotificationCompat
import com.openminis.betting.app.CrashReporter

object McpServer {

    fun sendNotification(context: Context, title: String, body: String) {
        try {
            CrashReporter.init(context)
            CrashReporter.getInstance().logInfo("McpServer", "Sending notification: $title")
            
            val channelId = "betting_alerts_channel"
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    channelId,
                    "Value Bet & Coupon Alerts",
                    NotificationManager.IMPORTANCE_HIGH
                )
                manager.createNotificationChannel(channel)
            }

            val notification = NotificationCompat.Builder(context, channelId)
                .setContentTitle(title)
                .setContentText(body)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()

            manager.notify((System.currentTimeMillis() % 10000).toInt(), notification)
            CrashReporter.getInstance().logInfo("McpServer", "Notification sent: $title")
        } catch (e: Exception) {
            CrashReporter.getInstance().logError("McpServer", "Failed to send notification", e)
        }
    }

    fun getDeviceInfo(): Map<String, String> {
        return mapOf(
            "model" to Build.MODEL,
            "brand" to Build.BRAND,
            "sdk" to Build.VERSION.SDK_INT.toString(),
            "status" to "MCP Server Active"
        )
    }
}