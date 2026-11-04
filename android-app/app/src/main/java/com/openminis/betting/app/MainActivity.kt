package com.openminis.betting.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import com.openminis.betting.app.core.ForegroundDaemon
import com.openminis.betting.app.core.McpServer
import com.openminis.betting.app.data.DataRepository
import com.openminis.betting.app.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val gson = Gson()
    private val repository = DataRepository.getInstance(this)
    private val scope = CoroutineScope(Dispatchers.IO)

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
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
        webSettings.allowContentAccess = true
        webSettings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        // Register Native JS Bridge for Web UI <-> Android Communication
        webView.addJavascriptInterface(WebAppInterface(this), "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Initialize bridge after page loads
                view?.evaluateJavascript("if(window.Bridge) Bridge.ready = true;", null)
            }
        }

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

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    // ============================================
    // JS BRIDGE INTERFACE
    // ============================================
    inner class WebAppInterface(private val context: Context) {

        @JavascriptInterface
        fun getAllData(): String {
            return runBlocking { 
                gson.toJson(DataBundle(
                    bankroll = repository.getBankroll(),
                    matches = repository.getUpcomingMatches(),
                    predictions = repository.getAllPredictions(),
                    coupons = repository.getAllCoupons(),
                    transactions = repository.getRecentTransactions(20)
                ))
            }
        }

        @JavascriptInterface
        fun syncLiveData(): String {
            return runBlocking { 
                val result = repository.syncLiveData()
                gson.toJson(result)
            }
        }

        @JavascriptInterface
        fun generateCoupons(): String {
            return runBlocking { 
                val result = repository.generateDailyCoupons()
                gson.toJson(result)
            }
        }

        @JavascriptInterface
        fun sendNotification(title: String, body: String) {
            McpServer.sendNotification(context, title, body)
        }

        @JavascriptInterface
        fun getDeviceInfo(): String {
            return gson.toJson(McpServer.getDeviceInfo())
        }
    }

    // ============================================
    // DATA CLASSES FOR BRIDGE
    // ============================================
    data class DataBundle(
        val bankroll: Bankroll,
        val matches: List<Match>,
        val predictions: List<Prediction>,
        val coupons: List<Coupon>,
        val transactions: List<BankrollTransaction>
    )

    data class Result(
        val success: Boolean = true,
        val count: Int = 0,
        val matches: List<Match> = emptyList(),
        val predictions: List<Prediction> = emptyList(),
        val coupons: List<Coupon> = emptyList(),
        val bankroll: Bankroll? = null,
        val error: String? = null
    )
}