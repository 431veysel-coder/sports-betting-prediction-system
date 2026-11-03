package com.openminis.betting.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.openminis.betting.app.core.ForegroundDaemon
import com.openminis.betting.app.core.McpServer

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Start 7/24 Persistent Foreground Service Daemon
        startDaemonService()

        webView = findViewById(R.id.webView)
        val webSettings: WebSettings = webView.settings
        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        webSettings.loadWithOverviewMode = true
        webSettings.useWideViewPort = true
        webSettings.allowFileAccess = true

        // Register Native JS Bridge for Web UI <-> Android MCP Communication
        webView.addJavascriptInterface(WebAppInterface(this), "AndroidBridge")

        webView.webViewClient = WebViewClient()
        webView.loadUrl("file:///android_asset/index.html")
    }

    private fun startDaemonService() {
        val intent = Intent(this, ForegroundDaemon::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    // JS Bridge class accessible from HTML/JS
    inner class WebAppInterface(private val mContext: Context) {
        
        @JavascriptInterface
        fun sendNotification(title: String, body: String) {
            McpServer.sendNotification(mContext, title, body)
        }

        @JavascriptInterface
        fun getDeviceInfo(): String {
            return McpServer.getDeviceInfo().toString()
        }
    }
}
