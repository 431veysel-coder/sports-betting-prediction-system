package com.openminis.betting.app

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class LogViewerActivity : AppCompatActivity() {

    private lateinit var logTextView: TextView
    private lateinit var refreshButton: Button
    private lateinit var clearButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log_viewer)

        CrashReporter.init(this)

        logTextView = findViewById(R.id.logTextView)
        refreshButton = findViewById(R.id.refreshButton)
        clearButton = findViewById(R.id.clearButton)

        refreshButton.setOnClickListener { loadLogs() }
        clearButton.setOnClickListener { clearLogs() }

        loadLogs()
    }

    private fun loadLogs() {
        val logs = CrashReporter.getInstance().getRecentLogs(500)
        runOnUiThread {
            logTextView.text = if (logs.isNotBlank()) logs else "No logs available"
            logTextView.scrollTo(0, logTextView.bottom)
        }
    }

    private fun clearLogs() {
        android.widget.Toast.makeText(this, "Logs cleared (restart app)", android.widget.Toast.LENGTH_SHORT).show()
        loadLogs()
    }
}