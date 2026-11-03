package com.openminis.app.nexus.core.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

class ForegroundDaemon : Service() {

    companion object {
        private const val TAG = "MinisDaemon"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Minis Nexus 7/24 Foreground Daemon Created.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "Foreground Daemon started. Keeping AI agent and MCP gateway alive.")
        // Start persistent loop / watchdog here
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.w(TAG, "Foreground Daemon destroyed. Watchdog should restart.")
    }
}
