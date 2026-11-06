package com.openminis.betting.app.debug

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.openminis.betting.app.CrashReporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject

class RemoteDebugService : Service() {

    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        CrashReporter.init(this)
        CrashReporter.getInstance().logInfo("RemoteDebugService", "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
        CrashReporter.getInstance().logInfo("RemoteDebugService", "Service destroyed")
    }

    private fun sendMessage(message: String) {
        // Broadcast to WebView or UI
    }

    private fun sendError(code: String, message: String, requestId: String) {
        try {
            val error = JSONObject().put("type", "ERROR").put("code", code).put("message", message).put("id", requestId)
            sendMessage(error.toString())
        } catch (e: Exception) {
            CrashReporter.getInstance().logError("RemoteDebugService", "sendError failed", e)
        }
    }
}