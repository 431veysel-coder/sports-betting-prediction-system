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
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val gson = Gson()
    private lateinit var repository: DataRepository
    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize crash reporter AFTER super.onCreate()
        CrashReporter.init(this)
        CrashReporter.getInstance().logInfo("MainActivity", "Application starting")

        // Initialize repository AFTER super.onCreate()
        repository = DataRepository.getInstance(this)

        try {
            setContentView(R.layout.activity_main)
            CrashReporter.getInstance().logInfo("MainActivity", "Layout inflated")

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
            webSettings.setRenderPriority(WebSettings.RenderPriority.HIGH)
            webSettings.cacheMode = WebSettings.LOAD_DEFAULT

            // Register Native JS Bridge for Web UI <-> Android Communication
            webView.addJavascriptInterface(WebAppInterface(this), "AndroidBridge")

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    CrashReporter.getInstance().logInfo("WebView", "Page loaded: $url")
                    view?.evaluateJavascript("if(window.Bridge) Bridge.ready = true;", null)
                }

                override fun onReceivedError(view: WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?) {
                    super.onReceivedError(view, request, error)
                    CrashReporter.getInstance().logError("WebView", "Load error: ${error?.description}", null)
                }

                override fun onReceivedHttpError(view: WebView?, request: android.webkit.WebResourceRequest?, errorResponse: android.webkit.WebResourceResponse?) {
                    super.onReceivedHttpError(view, request, errorResponse)
                    CrashReporter.getInstance().logError("WebView", "HTTP error: ${errorResponse?.statusCode}", null)
                }
            }

            webView.loadUrl("file:///android_asset/index.html")
            CrashReporter.getInstance().logInfo("MainActivity", "WebView loaded")

        } catch (e: Exception) {
            CrashReporter.getInstance().logError("MainActivity", "onCreate failed", e)
            throw e
        }
    }

    private fun startDaemonService() {
        try {
            val intent = Intent(this, ForegroundDaemon::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            CrashReporter.getInstance().logInfo("MainActivity", "Daemon service started")
        } catch (e: Exception) {
            CrashReporter.getInstance().logError("MainActivity", "Failed to start daemon", e)
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
        job.cancel()
        repository.shutdown()
        CrashReporter.getInstance().logInfo("MainActivity", "Application destroyed")
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                val intent = Intent(this, com.openminis.betting.app.settings.SettingsActivity::class.java)
                startActivity(intent)
                true
            }
            R.id.action_debug_chat -> {
                val intent = Intent(this, com.openminis.betting.app.debug.DebugChatActivity::class.java)
                startActivity(intent)
                true
            }
            R.id.action_logs -> {
                val intent = Intent(this, LogViewerActivity::class.java)
                startActivity(intent)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ============================================
    // JS BRIDGE INTERFACE
    // ============================================
    inner class WebAppInterface(private val context: Context) {

        @JavascriptInterface
        fun getAllData(): String {
            return try {
                runBlocking { 
                    val bankroll = repository.getBankroll()
                    val matches = repository.getUpcomingMatches(7)
                    val predictions = repository.getPredictions(50.0, 50)
                    val coupons = repository.getCoupons(null, 20)
                    val transactions = repository.getTransactions(20)
                    
                    gson.toJson(DataBundle(
                        bankroll = bankroll,
                        matches = matches,
                        predictions = predictions,
                        coupons = coupons,
                        transactions = transactions
                    ))
                }
            } catch (e: Exception) {
                CrashReporter.getInstance().logError("WebAppInterface", "getAllData failed", e)
                gson.toJson(Result(success = false, error = e.message))
            }
        }

        @JavascriptInterface
        fun syncLiveData(forceRefresh: Boolean): String {
            return try {
                runBlocking { 
                    val result = repository.syncAll(forceRefresh)
                    CrashReporter.getInstance().logInfo("WebAppInterface", "Sync completed: ${result.count} matches, ${result.predictions.size} predictions, ${result.coupons.size} coupons")
                    gson.toJson(result)
                }
            } catch (e: Exception) {
                CrashReporter.getInstance().logError("WebAppInterface", "syncLiveData failed", e)
                gson.toJson(Result(success = false, error = e.message))
            }
        }

        @JavascriptInterface
        fun generateCoupons(stakePerCoupon: Double, strategy: String, maxCoupons: Int): String {
            return try {
                runBlocking { 
                    val request = com.openminis.betting.app.network.ApiClient.GenerateCouponsRequest(
                        stake_per_coupon = stakePerCoupon,
                        strategy = strategy,
                        max_coupons = maxCoupons
                    )
                    val coupons = repository.generateCoupons(request)
                    CrashReporter.getInstance().logInfo("WebAppInterface", "Coupons generated: ${coupons.size}")
                    gson.toJson(Result(success = true, count = coupons.size, coupons = coupons))
                }
            } catch (e: Exception) {
                CrashReporter.getInstance().logError("WebAppInterface", "generateCoupons failed", e)
                gson.toJson(Result(success = false, error = e.message))
            }
        }

        @JavascriptInterface
        fun sendNotification(title: String, body: String) {
            try {
                McpServer.sendNotification(context, title, body)
                CrashReporter.getInstance().logInfo("WebAppInterface", "Notification sent: $title")
            } catch (e: Exception) {
                CrashReporter.getInstance().logError("WebAppInterface", "sendNotification failed", e)
            }
        }

        @JavascriptInterface
        fun getDeviceInfo(): String {
            return try {
                gson.toJson(McpServer.getDeviceInfo())
            } catch (e: Exception) {
                CrashReporter.getInstance().logError("WebAppInterface", "getDeviceInfo failed", e)
                gson.toJson(mapOf("error" to e.message))
            }
        }

        @JavascriptInterface
        fun getCrashLogs(): String {
            return gson.toJson(mapOf("logs" to CrashReporter.getInstance().getRecentLogs(200)))
        }

        @JavascriptInterface
        fun openLogViewer(): String {
            try {
                val intent = android.content.Intent(context, LogViewerActivity::class.java)
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return gson.toJson(mapOf("success" to true))
            } catch (e: Exception) {
                CrashReporter.getInstance().logError("WebAppInterface", "openLogViewer failed", e)
                return gson.toJson(mapOf("success" to false, "error" to e.message))
            }
        }

        @JavascriptInterface
        fun getBankroll(): String {
            return try {
                runBlocking {
                    val bankroll = repository.getBankroll()
                    gson.toJson(Result(success = true, bankroll = bankroll))
                }
            } catch (e: Exception) {
                CrashReporter.getInstance().logError("WebAppInterface", "getBankroll failed", e)
                gson.toJson(Result(success = false, error = e.message))
            }
        }

        @JavascriptInterface
        fun deposit(amount: Double, note: String): String {
            return try {
                runBlocking {
                    val bankroll = repository.deposit(amount, note)
                    gson.toJson(Result(success = true, bankroll = bankroll))
                }
            } catch (e: Exception) {
                CrashReporter.getInstance().logError("WebAppInterface", "deposit failed", e)
                gson.toJson(Result(success = false, error = e.message))
            }
        }

        @JavascriptInterface
        fun withdraw(amount: Double, note: String): String {
            return try {
                runBlocking {
                    val bankroll = repository.withdraw(amount, note)
                    gson.toJson(Result(success = true, bankroll = bankroll))
                }
            } catch (e: Exception) {
                CrashReporter.getInstance().logError("WebAppInterface", "withdraw failed", e)
                gson.toJson(Result(success = false, error = e.message))
            }
        }

        @JavascriptInterface
        fun getMatches(): String {
            return try {
                runBlocking {
                    val matches = repository.getUpcomingMatches(7)
                    gson.toJson(Result(success = true, count = matches.size, matches = matches))
                }
            } catch (e: Exception) {
                CrashReporter.getInstance().logError("WebAppInterface", "getMatches failed", e)
                gson.toJson(Result(success = false, error = e.message))
            }
        }

        @JavascriptInterface
        fun getPredictions(minScore: Double): String {
            return try {
                runBlocking {
                    val predictions = repository.getPredictions(minScore, 50)
                    gson.toJson(Result(success = true, count = predictions.size, predictions = predictions))
                }
            } catch (e: Exception) {
                CrashReporter.getInstance().logError("WebAppInterface", "getPredictions failed", e)
                gson.toJson(Result(success = false, error = e.message))
            }
        }

        @JavascriptInterface
        fun getCoupons(): String {
            return try {
                runBlocking {
                    val coupons = repository.getCoupons(null, 20)
                    gson.toJson(Result(success = true, count = coupons.size, coupons = coupons))
                }
            } catch (e: Exception) {
                CrashReporter.getInstance().logError("WebAppInterface", "getCoupons failed", e)
                gson.toJson(Result(success = false, error = e.message))
            }
        }

        @JavascriptInterface
        fun getTransactions(): String {
            return try {
                runBlocking {
                    val transactions = repository.getTransactions(20)
                    gson.toJson(Result(success = true, count = transactions.size, transactions = transactions))
                }
            } catch (e: Exception) {
                CrashReporter.getInstance().logError("WebAppInterface", "getTransactions failed", e)
                gson.toJson(Result(success = false, error = e.message))
            }
        }

        @JavascriptInterface
        fun generatePrediction(matchId: String): String {
            return try {
                runBlocking {
                    val prediction = repository.generatePrediction(matchId, false)
                    gson.toJson(Result(success = true, predictions = listOf(prediction)))
                }
            } catch (e: Exception) {
                CrashReporter.getInstance().logError("WebAppInterface", "generatePrediction failed", e)
                gson.toJson(Result(success = false, error = e.message))
            }
        }

        @JavascriptInterface
        fun openSettings(): String {
            try {
                val intent = android.content.Intent(context, com.openminis.betting.app.settings.SettingsActivity::class.java)
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return gson.toJson(mapOf("success" to true))
            } catch (e: Exception) {
                CrashReporter.getInstance().logError("WebAppInterface", "openSettings failed", e)
                return gson.toJson(mapOf("success" to false, "error" to e.message))
            }
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
        val transactions: List<BankrollTransaction> = emptyList(),
        val bankroll: Bankroll? = null,
        val error: String? = null
    )
}