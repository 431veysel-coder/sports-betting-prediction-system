package com.openminis.betting.app.settings

import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.openminis.betting.app.CrashReporter
import com.openminis.betting.app.R
import com.openminis.betting.app.debug.DebugChatActivity
import android.content.Intent
import android.net.Uri
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: SettingsRepository
    private lateinit var scrollView: ScrollView
    private lateinit var contentLayout: LinearLayout

    // API Key fields
    private lateinit var etApiFootball: TextInputEditText
    private lateinit var etFootballData: TextInputEditText
    private lateinit var etOpenWeather: TextInputEditText
    private lateinit var etRapidApiHost: TextInputEditText

    // Endpoint fields
    private lateinit var etCustomEndpoint: TextInputEditText
    private lateinit var etWebhookUrl: TextInputEditText

    // Collector settings
    private lateinit var etSyncInterval: TextInputEditText
    private lateinit var etEnabledLeagues: TextInputEditText
    private lateinit var etEnabledSources: TextInputEditText

    // Prediction settings
    private lateinit var etMinConfidence: TextInputEditText
    private lateinit var etMinValueScore: TextInputEditText
    private lateinit var etKellyFraction: TextInputEditText
    private lateinit var etMaxStakePercent: TextInputEditText

    // Notification settings
    private lateinit var switchNotifications: SwitchMaterial
    private lateinit var etValueBetThreshold: TextInputEditText
    private lateinit var etTelegramBotToken: TextInputEditText
    private lateinit var etTelegramChatId: TextInputEditText

    // Debug settings
    private lateinit var switchDebugMode: SwitchMaterial
    private lateinit var spinnerLogLevel: Spinner

    // Buttons
    private lateinit var btnSave: MaterialButton
    private lateinit var btnExport: MaterialButton
    private lateinit var btnImport: MaterialButton
    private lateinit var btnReset: MaterialButton
    private lateinit var btnTestConnections: MaterialButton
    private lateinit var btnDebugChat: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        CrashReporter.init(this)
        settings = SettingsRepository.getInstance(this)

        initViews()
        loadSettings()
        setupListeners()
    }

    private fun initViews() {
        scrollView = findViewById(R.id.scrollView)
        contentLayout = findViewById(R.id.contentLayout)

        // API Keys
        etApiFootball = findViewById(R.id.etApiFootball)
        etFootballData = findViewById(R.id.etFootballData)
        etOpenWeather = findViewById(R.id.etOpenWeather)
        etRapidApiHost = findViewById(R.id.etRapidApiHost)

        // Endpoints
        etCustomEndpoint = findViewById(R.id.etCustomEndpoint)
        etWebhookUrl = findViewById(R.id.etWebhookUrl)

        // Collector
        etSyncInterval = findViewById(R.id.etSyncInterval)
        etEnabledLeagues = findViewById(R.id.etEnabledLeagues)
        etEnabledSources = findViewById(R.id.etEnabledSources)

        // Prediction
        etMinConfidence = findViewById(R.id.etMinConfidence)
        etMinValueScore = findViewById(R.id.etMinValueScore)
        etKellyFraction = findViewById(R.id.etKellyFraction)
        etMaxStakePercent = findViewById(R.id.etMaxStakePercent)

        // Notifications
        switchNotifications = findViewById(R.id.switchNotifications)
        etValueBetThreshold = findViewById(R.id.etValueBetThreshold)
        etTelegramBotToken = findViewById(R.id.etTelegramBotToken)
        etTelegramChatId = findViewById(R.id.etTelegramChatId)

        // Debug
        switchDebugMode = findViewById(R.id.switchDebugMode)
        spinnerLogLevel = findViewById(R.id.spinnerLogLevel)

        // Buttons
        btnSave = findViewById(R.id.btnSave)
        btnExport = findViewById(R.id.btnExport)
        btnImport = findViewById(R.id.btnImport)
        btnReset = findViewById(R.id.btnReset)
        btnTestConnections = findViewById(R.id.btnTestConnections)
        btnDebugChat = findViewById(R.id.btnDebugChat)

        // Setup log level spinner
        val logLevels = arrayOf("DEBUG", "INFO", "WARN", "ERROR")
        spinnerLogLevel.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, logLevels).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        // Toggle password visibility for sensitive fields
        setupPasswordToggle(etApiFootball)
        setupPasswordToggle(etFootballData)
        setupPasswordToggle(etOpenWeather)
        setupPasswordToggle(etTelegramBotToken)
    }

    private fun setupPasswordToggle(editText: TextInputEditText) {
        val layout = editText.parent as? TextInputLayout
        layout?.setEndIconMode(TextInputLayout.END_ICON_PASSWORD_TOGGLE)
    }

    private fun loadSettings() {
        // API Keys
        etApiFootball.setText(settings.apiFootballKey)
        etFootballData.setText(settings.footballDataKey)
        etOpenWeather.setText(settings.openWeatherKey)
        etRapidApiHost.setText(settings.rapidApiHost)

        // Endpoints
        etCustomEndpoint.setText(settings.customApiEndpoint)
        etWebhookUrl.setText(settings.webhookUrl)

        // Collector
        etSyncInterval.setText(settings.syncIntervalMin.toString())
        etEnabledLeagues.setText(settings.enabledLeagues.joinToString(","))
        etEnabledSources.setText(settings.enabledSources.joinToString(","))

        // Prediction
        etMinConfidence.setText(settings.minConfidence.toString())
        etMinValueScore.setText(settings.minValueScore.toString())
        etKellyFraction.setText(settings.kellyFraction.toString())
        etMaxStakePercent.setText(settings.maxStakePercent.toString())

        // Notifications
        switchNotifications.isChecked = settings.enableNotifications
        etValueBetThreshold.setText(settings.valueBetThreshold.toString())
        etTelegramBotToken.setText(settings.telegramBotToken)
        etTelegramChatId.setText(settings.telegramChatId)

        // Debug
        switchDebugMode.isChecked = settings.debugMode
        val logLevelIndex = arrayOf("DEBUG", "INFO", "WARN", "ERROR").indexOf(settings.logLevel)
        spinnerLogLevel.setSelection(maxOf(logLevelIndex, 0))
    }

    private fun setupListeners() {
        btnSave.setOnClickListener { saveSettings() }
        btnExport.setOnClickListener { exportSettings() }
        btnImport.setOnClickListener { importSettings() }
        btnReset.setOnClickListener { confirmReset() }
        btnTestConnections.setOnClickListener { testConnections() }
        btnDebugChat.setOnClickListener { openDebugChat() }
    }

    private fun saveSettings() {
        try {
            // API Keys (encrypted)
            settings.apiFootballKey = etApiFootball.text.toString().trim()
            settings.footballDataKey = etFootballData.text.toString().trim()
            settings.openWeatherKey = etOpenWeather.text.toString().trim()
            settings.rapidApiHost = etRapidApiHost.text.toString().trim()

            // Endpoints
            settings.customApiEndpoint = etCustomEndpoint.text.toString().trim()
            settings.webhookUrl = etWebhookUrl.text.toString().trim()

            // Collector
            settings.syncIntervalMin = etSyncInterval.text.toString().toIntOrNull() ?: 15
            settings.enabledLeagues = etEnabledLeagues.text.toString()
                .split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
            settings.enabledSources = etEnabledSources.text.toString()
                .split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()

            // Prediction
            settings.minConfidence = etMinConfidence.text.toString().toDoubleOrNull() ?: 0.65
            settings.minValueScore = etMinValueScore.text.toString().toDoubleOrNull() ?: 0.05
            settings.kellyFraction = etKellyFraction.text.toString().toDoubleOrNull() ?: 0.25
            settings.maxStakePercent = etMaxStakePercent.text.toString().toDoubleOrNull() ?: 0.05

            // Notifications
            settings.enableNotifications = switchNotifications.isChecked
            settings.valueBetThreshold = etValueBetThreshold.text.toString().toDoubleOrNull() ?: 0.05
            settings.telegramBotToken = etTelegramBotToken.text.toString().trim()
            settings.telegramChatId = etTelegramChatId.text.toString().trim()

            // Debug
            settings.debugMode = switchDebugMode.isChecked
            settings.logLevel = spinnerLogLevel.selectedItem.toString()

            CrashReporter.getInstance().logInfo("SettingsActivity", "Settings saved successfully")
            Toast.makeText(this, "✅ Ayarlar kaydedildi", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            CrashReporter.getInstance().logError("SettingsActivity", "Save failed", e)
            Toast.makeText(this, "❌ Kaydetme hatası: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun exportSettings() {
        val json = settings.exportSettings()
        val fileName = "betting_settings_${java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.json"
        
        try {
            val file = File(filesDir, fileName)
            file.writeText(json)
            
            // Share via system share dialog
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "${packageName}.fileprovider", file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra(Intent.EXTRA_SUBJECT, "Sports Betting Settings Export")
            }
            startActivity(Intent.createChooser(intent, "Ayarları Paylaş"))
            
            CrashReporter.getInstance().logInfo("SettingsActivity", "Settings exported to $fileName")
        } catch (e: Exception) {
            CrashReporter.getInstance().logError("SettingsActivity", "Export failed", e)
            Toast.makeText(this, "❌ Dışa aktarma hatası: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun importSettings() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "application/json"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(intent, 1001)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001 && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                try {
                    val json = contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: ""
                    if (settings.importSettings(json)) {
                        loadSettings()
                        Toast.makeText(this, "✅ Ayarlar içe aktarıldı", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "❌ Geçersiz ayar dosyası", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    CrashReporter.getInstance().logError("SettingsActivity", "Import failed", e)
                    Toast.makeText(this, "❌ İçe aktarma hatası: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle("Ayarları Sıfırla")
            .setMessage("Tüm ayarlar varsayılana döndürülecek (API key'ler silinecek). Emin misiniz?")
            .setPositiveButton("Evet, Sıfırla") { _, _ ->
                settings.resetToDefaults()
                loadSettings()
                Toast.makeText(this, "✅ Ayarlar sıfırlandı", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun testConnections() {
        val results = mutableListOf<String>()
        
        // Test API-Football
        if (settings.apiFootballKey.isNotBlank()) {
            testApiConnection(
                "API-Football",
                "https://${settings.rapidApiHost}/v3/status",
                mapOf("X-RapidAPI-Key" to settings.apiFootballKey, "X-RapidAPI-Host" to settings.rapidApiHost)
            ) { success, msg -> results.add("API-Football: ${if (success) "✅" else "❌"} $msg") }
        } else {
            results.add("API-Football: ⏭️ Key yok")
        }

        // Test Football-Data
        if (settings.footballDataKey.isNotBlank()) {
            testApiConnection(
                "Football-Data",
                "https://api.football-data.org/v4/competitions",
                mapOf("X-Auth-Token" to settings.footballDataKey)
            ) { success, msg -> results.add("Football-Data: ${if (success) "✅" else "❌"} $msg") }
        } else {
            results.add("Football-Data: ⏭️ Key yok")
        }

        // Test OpenWeather
        if (settings.openWeatherKey.isNotBlank()) {
            testApiConnection(
                "OpenWeather",
                "https://api.openweathermap.org/data/2.5/weather?q=London&appid=${settings.openWeatherKey}",
                emptyMap()
            ) { success, msg -> results.add("OpenWeather: ${if (success) "✅" else "❌"} $msg") }
        } else {
            results.add("OpenWeather: ⏭️ Key yok")
        }

        // Show results in dialog
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("Bağlantı Test Sonuçları")
                .setMessage(results.joinToString("\n"))
                .setPositiveButton("Tamam", null)
                .show()
        }
    }

    private fun testApiConnection(name: String, url: String, headers: Map<String, String>, callback: (Boolean, String) -> Unit) {
        Thread {
            try {
                val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
                
                val responseCode = connection.responseCode
                val success = responseCode == 200
                val msg = if (success) "Bağlandı (HTTP $responseCode)" else "HTTP $responseCode"
                callback(success, msg)
            } catch (e: Exception) {
                callback(false, e.message ?: "Hata")
            }
        }.start()
    }

    private fun openDebugChat() {
        val intent = Intent(this, DebugChatActivity::class.java)
        startActivity(intent)
    }
}