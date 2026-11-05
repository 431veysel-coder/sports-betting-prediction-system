package com.openminis.betting.app.debug

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import com.openminis.betting.app.CrashReporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.net.URI
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject

class RemoteDebugService : Service() {

    private val binder = LocalBinder()
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var webSocket: WebSocket? = null
    private var isConnected = false
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 10

    // Debug server URL - change to your debug server
    private val debugServerUrl = "wss://debug.openminis.app/ws" // Or use ngrok tunnel

    inner class LocalBinder : Binder() {
        fun getService(): RemoteDebugService = this@RemoteDebugService
    }

    override fun onCreate() {
        super.onCreate()
        CrashReporter.init(this)
        CrashReporter.getInstance().logInfo("RemoteDebugService", "Service created")
        connect()
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnect()
        scope.cancel()
        CrashReporter.getInstance().logInfo("RemoteDebugService", "Service destroyed")
    }

    private fun connect() {
        scope.launch {
            try {
                val client = OkHttpClient.Builder()
                    .pingInterval(30, java.util.concurrent.TimeUnit.SECONDS)
                    .build()

                val request = Request.Builder()
                    .url(debugServerUrl)
                    .addHeader("X-Device-ID", getDeviceId())
                    .addHeader("X-App-Version", getAppVersion())
                    .build()

                webSocket = client.newWebSocket(request, DebugWebSocketListener())
                CrashReporter.getInstance().logInfo("RemoteDebugService", "Connecting to $debugServerUrl")
            } catch (e: Exception) {
                CrashReporter.getInstance().logError("RemoteDebugService", "Connection failed", e)
                scheduleReconnect()
            }
        }
    }

    private fun disconnect() {
        webSocket?.close(1000, "Service stopping")
        webSocket = null
        isConnected = false
    }

    private fun scheduleReconnect() {
        if (reconnectAttempts < maxReconnectAttempts) {
            reconnectAttempts++
            val delay = minOf(1000 * 2 * reconnectAttempts, 60000)
            scope.launch {
                kotlinx.coroutines.delay(delay)
                connect()
            }
        } else {
            CrashReporter.getInstance().logError("RemoteDebugService", "Max reconnect attempts reached")
        }
    }

    inner class DebugWebSocketListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
            isConnected = true
            reconnectAttempts = 0
            CrashReporter.getInstance().logInfo("RemoteDebugService", "Connected to debug server")
            
            // Send device info on connect
            sendMessage(JSONObject().apply {
                put("type", "DEVICE_INFO")
                put("deviceId", getDeviceId())
                put("model", android.os.Build.MODEL)
                put("androidVersion", android.os.Build.VERSION.RELEASE)
                put("appVersion", getAppVersion())
            }.toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleCommand(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            // Handle binary messages if needed
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            isConnected = false
            CrashReporter.getInstance().logWarning("RemoteDebugService", "Disconnected: $code - $reason")
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
            isConnected = false
            CrashReporter.getInstance().logError("RemoteDebugService", "WebSocket failure", t)
            scheduleReconnect()
        }
    }

    private fun handleCommand(jsonText: String) {
        try {
            val json = JSONObject(jsonText)
            val type = json.optString("type", "")
            val id = json.optString("id", "")

            when (type) {
                "PING" -> sendMessage(JSONObject().put("type", "PONG").put("id", id).toString())
                "GET_LOGS" -> sendLogs(json.optInt("lines", 200), id)
                "GET_LOGCAT" -> sendLogcat(json.optInt("lines", 500), id)
                "EXEC_SQL" -> executeSql(json.optString("sql", ""), id)
                "EXEC_SHELL" -> executeShell(json.optString("command", ""), id)
                "GET_DB_INFO" -> sendDbInfo(id)
                "CLEAR_DATA" -> clearAppData(id)
                "RESTART_APP" -> restartApp(id)
                "CHAT_MESSAGE" -> handleChatMessage(json.optString("message", ""), id)
            }
        } catch (e: Exception) {
            CrashReporter.getInstance().logError("RemoteDebugService", "Command handling failed", e)
            sendError("COMMAND_ERROR", e.message ?: "Unknown error", "")
        }
    }

    private fun sendLogs(lines: Int, requestId: String) {
        scope.launch {
            val logs = CrashReporter.getInstance().getRecentLogs(lines)
            sendMessage(JSONObject()
                .put("type", "LOGS_RESPONSE")
                .put("id", requestId)
                .put("logs", logs)
                .toString())
        }
    }

    private fun sendLogcat(lines: Int, requestId: String) {
        scope.launch {
            try {
                val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", lines.toString()))
                val output = process.inputStream.bufferedReader().readText()
                process.waitFor()
                sendMessage(JSONObject()
                    .put("type", "LOGCAT_RESPONSE")
                    .put("id", requestId)
                    .put("logcat", output)
                    .toString())
            } catch (e: Exception) {
                sendError("LOGCAT_ERROR", e.message ?: "Failed", requestId)
            }
        }
    }

    private fun executeSql(sql: String, requestId: String) {
        scope.launch {
            try {
                val db = (getApplication() as? android.app.Application)?.let { 
                    com.openminis.betting.app.data.DataRepository.getInstance(it).dbHelper.writableDatabase 
                }
                if (db != null) {
                    val cursor = db.rawQuery(sql, null)
                    val results = mutableListOf<JSONObject>()
                    val columns = cursor.columnNames
                    while (cursor.moveToNext()) {
                        val row = JSONObject()
                        for (col in columns) {
                            val idx = cursor.getColumnIndex(col)
                            if (idx >= 0) {
                                when (cursor.getType(idx)) {
                                    Cursor.FIELD_TYPE_INTEGER -> row.put(col, cursor.getLong(idx))
                                    Cursor.FIELD_TYPE_FLOAT -> row.put(col, cursor.getDouble(idx))
                                    Cursor.FIELD_TYPE_STRING -> row.put(col, cursor.getString(idx))
                                    Cursor.FIELD_TYPE_BLOB -> row.put(col, "BLOB")
                                    else -> row.put(col, JSONObject.NULL)
                                }
                            }
                        }
                        results.add(row)
                    }
                    cursor.close()
                    sendMessage(JSONObject()
                        .put("type", "SQL_RESPONSE")
                        .put("id", requestId)
                        .put("data", results)
                        .toString())
                } else {
                    sendError("SQL_ERROR", "Database not available", requestId)
                }
            } catch (e: Exception) {
                sendError("SQL_ERROR", e.message ?: "Failed", requestId)
            }
        }
    }

    private fun executeShell(command: String, requestId: String) {
        scope.launch {
            try {
                val process = Runtime.getRuntime().exec(command)
                val output = process.inputStream.bufferedReader().readText()
                val error = process.errorStream.bufferedReader().readText()
                process.waitFor()
                sendMessage(JSONObject()
                    .put("type", "SHELL_RESPONSE")
                    .put("id", requestId)
                    .put("output", output)
                    .put("error", error)
                    .put("exitCode", process.exitValue())
                    .toString())
            } catch (e: Exception) {
                sendError("SHELL_ERROR", e.message ?: "Failed", requestId)
            }
        }
    }

    private fun sendDbInfo(requestId: String) {
        scope.launch {
            try {
                val db = com.openminis.betting.app.data.DataRepository.getInstance(this@RemoteDebugService).dbHelper.readableDatabase
                val tables = mutableListOf<String>()
                val cursor = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null)
                while (cursor.moveToNext()) {
                    tables.add(cursor.getString(0))
                }
                cursor.close()

                val info = JSONObject().put("tables", tables)
                sendMessage(JSONObject()
                    .put("type", "DB_INFO_RESPONSE")
                    .put("id", requestId)
                    .put("info", info)
                    .toString())
            } catch (e: Exception) {
                sendError("DB_INFO_ERROR", e.message ?: "Failed", requestId)
            }
        }
    }

    private fun clearAppData(requestId: String) {
        scope.launch {
            try {
                val pm = packageManager
                pm.clearApplicationUserData(packageName, null)
                sendMessage(JSONObject()
                    .put("type", "CLEAR_DATA_RESPONSE")
                    .put("id", requestId)
                    .put("success", true)
                    .toString())
            } catch (e: Exception) {
                sendError("CLEAR_DATA_ERROR", e.message ?: "Failed", requestId)
            }
        }
    }

    private fun restartApp(requestId: String) {
        sendMessage(JSONObject()
            .put("type", "RESTART_RESPONSE")
            .put("id", requestId)
            .put("success", true)
            .toString())
        
        scope.launch {
            kotlinx.coroutines.delay(500)
            val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            intent?.let { startActivity(it) }
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    private fun handleChatMessage(message: String, requestId: String) {
        // Forward to AI chat handler
        sendMessage(JSONObject()
            .put("type", "CHAT_RESPONSE")
            .put("id", requestId)
            .put("message", "AI: $message (echo - connect to real AI)")
            .toString())
    }

    private fun sendMessage(message: String) {
        webSocket?.send(message)
    }

    private fun sendError(type: String, message: String, requestId: String) {
        sendMessage(JSONObject()
            .put("type", type)
            .put("id", requestId)
            .put("error", message)
            .toString())
    }

    private fun getDeviceId(): String {
        return android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "unknown"
    }

    private fun getAppVersion(): String {
        try {
            val info = packageManager.getPackageInfo(packageName, 0)
            return "${info.versionName} (${info.versionCode})"
        } catch (e: Exception) {
            return "unknown"
        }
    }

    // Public API for UI
    fun sendChatMessage(message: String) {
        val id = java.util.UUID.randomUUID().toString()
        sendMessage(JSONObject()
            .put("type", "CHAT_MESSAGE")
            .put("id", id)
            .put("message", message)
            .toString())
    }

    fun isConnected(): Boolean = isConnected
}

import android.database.Cursor
import android.content.Intent
import android.content.pm.PackageManager