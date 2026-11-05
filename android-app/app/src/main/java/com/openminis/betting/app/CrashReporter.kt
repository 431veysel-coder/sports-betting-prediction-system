package com.openminis.betting.app

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class CrashReporter private constructor(context: Context) {
    private val context = context.applicationContext
    private val logDir: File
    private val executor = Executors.newSingleThreadExecutor()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    companion object {
        @Volatile private var instance: CrashReporter? = null
        fun init(context: Context) = synchronized(this) {
            instance ?: CrashReporter(context).also { instance = it }
        }
        fun getInstance() = instance ?: throw IllegalStateException("CrashReporter not initialized")
    }

    init {
        logDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "crash_logs")
        if (!logDir.exists()) logDir.mkdirs()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            logCrash(throwable, "UNCAUGHT_EXCEPTION")
            saveCrashToFile(throwable, "UNCAUGHT_EXCEPTION")
            System.exit(1)
        }
    }

    fun logError(tag: String, message: String, throwable: Throwable? = null) {
        val log = buildLogEntry("ERROR", tag, message, throwable)
        writeToLogFile(log)
        Log.e(tag, message, throwable)
    }

    fun logWarning(tag: String, message: String) {
        val log = buildLogEntry("WARN", tag, message, null)
        writeToLogFile(log)
        Log.w(tag, message)
    }

    fun logInfo(tag: String, message: String) {
        val log = buildLogEntry("INFO", tag, message, null)
        writeToLogFile(log)
        Log.i(tag, message)
    }

    fun logDebug(tag: String, message: String) {
        val log = buildLogEntry("DEBUG", tag, message, null)
        writeToLogFile(log)
        Log.d(tag, message)
    }

    private fun logCrash(throwable: Throwable, type: String) {
        val log = buildLogEntry("CRASH", type, throwable.message ?: "Unknown crash", throwable)
        writeToLogFile(log)
        Log.e("CRASH_REPORTER", "$type: ${throwable.message}", throwable)
    }

    private fun buildLogEntry(level: String, tag: String, message: String, throwable: Throwable?): String {
        val sb = StringBuilder()
        sb.append(dateFormat.format(Date())).append(" ")
        sb.append("[$level] ").append(tag).append(": ").append(message).append("\n")
        sb.append("Device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
        sb.append(" | Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        sb.append("App Version: ").append(getAppVersion()).append("\n")
        if (throwable != null) {
            sb.append("Stack Trace:\n").append(getStackTrace(throwable))
        }
        sb.append("\n").append("=".repeat(80)).append("\n")
        return sb.toString()
    }

    private fun getStackTrace(throwable: Throwable): String {
        val sw = java.io.StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        var cause = throwable.cause
        while (cause != null) {
            pw.append("Caused by: ")
            cause.printStackTrace(pw)
            cause = cause.cause
        }
        return sw.toString()
    }

    private fun getAppVersion(): String {
        try {
            val info: PackageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            return "${info.versionName} (${info.versionCode})"
        } catch (e: PackageManager.NameNotFoundException) {
            return "Unknown"
        }
    }

    private fun writeToLogFile(log: String) {
        executor.execute {
            try {
                val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                val logFile = File(logDir, "app_$dateStr.log")
                FileWriter(logFile, true).use { it.write(log) }
                cleanOldLogs()
            } catch (e: Exception) {
                Log.e("CRASH_REPORTER", "Failed to write log", e)
            }
        }
    }

    private fun saveCrashToFile(throwable: Throwable, type: String) {
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
            val crashFile = File(logDir, "crash_${type}_$timestamp.txt")
            FileWriter(crashFile).use { writer ->
                writer.write(buildLogEntry("CRASH", type, throwable.message ?: "Unknown", throwable))
                writer.write("\n--- FULL THREAD DUMP ---\n")
                Thread.getAllStackTraces().forEach { (thread, stack) ->
                    writer.write("$thread:\n")
                    stack.forEach { writer.write("  at $it\n") }
                }
            }
        } catch (e: Exception) {
            Log.e("CRASH_REPORTER", "Failed to save crash file", e)
        }
    }

    private fun cleanOldLogs() {
        val cutoff = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000
        logDir.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoff) file.delete()
        }
    }

    fun getRecentLogs(lines: Int = 100): String {
        val todayFile = File(logDir, "app_${SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())}.log")
        if (!todayFile.exists()) return "No logs for today"
        return try {
            todayFile.readText().lines().takeLast(lines).joinToString("\n")
        } catch (e: Exception) {
            "Failed to read logs: ${e.message}"
        }
    }

    fun exportLogs(): File? {
        val zipFile = File(logDir, "logs_export_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.zip")
        try {
            java.util.zip.ZipOutputStream(File(zipFile).outputStream()).use { zip ->
                val files = logDir.listFiles()
                if (files != null) {
                    for (file in files) {
                        val name = file.name
                        val dotIndex = name.lastIndexOf('.')
                        var ext = ""
                        if (dotIndex >= 0 && dotIndex < name.length - 1) {
                            val sub = name.substring(dotIndex + 1)
                            ext = sub.toLowerCase(Locale.US)
                        }
                        if (ext == "log" || ext == "txt") {
                            zip.putNextEntry(java.util.zip.ZipEntry(file.name))
                            file.inputStream().copyTo(zip)
                            zip.closeEntry()
                        }
                    }
                }
            }
            return zipFile
        } catch (e: Exception) {
            logError("CrashReporter", "Failed to export logs", e)
            return null
        }
    }
}