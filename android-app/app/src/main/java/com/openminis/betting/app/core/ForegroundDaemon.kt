package com.openminis.betting.app.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.openminis.betting.app.CrashReporter
import com.openminis.betting.app.R

class ForegroundDaemon : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        CrashReporter.init(this)
        CrashReporter.getInstance().logInfo("ForegroundDaemon", "Service created")
        
        try {
            startForegroundServiceNotification()
            WebSyncBridge.initRealtimeSync(this)
            CrashReporter.getInstance().logInfo("ForegroundDaemon", "Service initialized successfully")
        } catch (e: Exception) {
            CrashReporter.getInstance().logError("ForegroundDaemon", "onCreate failed", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        CrashReporter.getInstance().logInfo("ForegroundDaemon", "Service destroyed")
    }

    private fun startForegroundServiceNotification() {
        val channelId = "betting_daemon_channel"
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Sports Betting OS Background Service",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Sports Betting Intelligence")
            .setContentText("7/24 Canlı Bahis ve Tahmin Senkronizasyonu Aktif")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        startForeground(1001, notification)
    }
}