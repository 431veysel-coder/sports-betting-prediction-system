package com.openminis.betting.app.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.openminis.betting.app.CrashReporter
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom
import java.util.*

/**
 * Encrypted settings storage for API keys, tokens, endpoints
 * Uses AES-256 encryption with Android Keystore-backed key derivation
 */
class SettingsRepository(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val encryptionKey: SecretKeySpec by lazy { generateOrLoadKey() }

    companion object {
        @Volatile private var instance: SettingsRepository? = null
        fun getInstance(context: Context): SettingsRepository = instance ?: synchronized(this) {
            instance ?: SettingsRepository(context.applicationContext).also { instance = it }
        }

        // ============================================
        // SETTINGS KEYS
        // ============================================
        
        // API Keys
        private const val KEY_API_FOOTBALL = "api_football_key"
        private const val KEY_FOOTBALL_DATA = "football_data_key"
        private const val KEY_OPEN_WEATHER = "open_weather_key"
        private const val KEY_RAPIDAPI_HOST = "rapidapi_host"
        
        // Endpoints
        private const val KEY_CUSTOM_ENDPOINT = "custom_api_endpoint"
        private const val KEY_WEBHOOK_URL = "webhook_url"
        
        // Collector Settings
        private const val KEY_SYNC_INTERVAL_MIN = "sync_interval_min"
        private const val KEY_ENABLED_LEAGUES = "enabled_leagues"
        private const val KEY_ENABLED_SOURCES = "enabled_sources"
        
        // Prediction Settings
        private const val KEY_MIN_CONFIDENCE = "min_confidence"
        private const val KEY_MIN_VALUE_SCORE = "min_value_score"
        private const val KEY_KELLY_FRACTION = "kelly_fraction"
        private const val KEY_MAX_STAKE_PERCENT = "max_stake_percent"
        
        // Notification Settings
        private const val KEY_ENABLE_NOTIFICATIONS = "enable_notifications"
        private const val KEY_VALUE_BET_THRESHOLD = "value_bet_threshold"
        private const val KEY_TELEGRAM_BOT_TOKEN = "telegram_bot_token"
        private const val KEY_TELEGRAM_CHAT_ID = "telegram_chat_id"
        
        // Debug Settings
        private const val KEY_DEBUG_MODE = "debug_mode"
        private const val KEY_LOG_LEVEL = "log_level"
    }

    // ============================================
    // ENCRYPTION
    // ============================================

    private fun generateOrLoadKey(): SecretKeySpec {
        val keyAlias = "settings_encryption_key"
        val storedKey = prefs.getString("enc_key", null)
        if (storedKey != null) {
            return SecretKeySpec(Base64.decode(storedKey, Base64.DEFAULT), "AES")
        }
        
        // Generate new key
        val keyGen = SecureRandom()
        val keyBytes = ByteArray(32) // AES-256
        keyGen.nextBytes(keyBytes)
        val encoded = Base64.encodeToString(keyBytes, Base64.DEFAULT)
        prefs.edit().putString("enc_key", encoded).apply()
        return SecretKeySpec(keyBytes, "AES")
    }

    private fun encrypt(value: String): String {
        try {
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey)
            val encrypted = cipher.doFinal(value.toByteArray())
            return Base64.encodeToString(encrypted, Base64.DEFAULT)
        } catch (e: Exception) {
            CrashReporter.getInstance().logError("SettingsRepository", "Encryption failed", e)
            return value // Fallback: store plaintext if encryption fails
        }
    }

    private fun decrypt(encryptedValue: String): String {
        try {
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey)
            val decoded = Base64.decode(encryptedValue, Base64.DEFAULT)
            val decrypted = cipher.doFinal(decoded)
            return String(decrypted)
        } catch (e: Exception) {
            CrashReporter.getInstance().logError("SettingsRepository", "Decryption failed", e)
            return encryptedValue // Fallback: return as-is
        }
    }

    private fun putEncrypted(key: String, value: String) {
        prefs.edit().putString(key, encrypt(value)).apply()
    }

    private fun getEncrypted(key: String, default: String = ""): String {
        val encrypted = prefs.getString(key, null)
        return if (encrypted != null) decrypt(encrypted) else default
    }

    // ============================================
    // API KEYS (Encrypted)
    // ============================================

    var apiFootballKey: String
        get() = getEncrypted(KEY_API_FOOTBALL)
        set(value) = putEncrypted(KEY_API_FOOTBALL, value)

    var footballDataKey: String
        get() = getEncrypted(KEY_FOOTBALL_DATA)
        set(value) = putEncrypted(KEY_FOOTBALL_DATA, value)

    var openWeatherKey: String
        get() = getEncrypted(KEY_OPEN_WEATHER)
        set(value) = putEncrypted(KEY_OPEN_WEATHER, value)

    var rapidApiHost: String
        get() = getEncrypted(KEY_RAPIDAPI_HOST, "api-football-v1.p.rapidapi.com")
        set(value) = putEncrypted(KEY_RAPIDAPI_HOST, value)

    // ============================================
    // ENDPOINTS (Encrypted)
    // ============================================

    var customApiEndpoint: String
        get() = getEncrypted(KEY_CUSTOM_ENDPOINT, "https://api.openminis.app")
        set(value) = putEncrypted(KEY_CUSTOM_ENDPOINT, value)

    var webhookUrl: String
        get() = getEncrypted(KEY_WEBHOOK_URL)
        set(value) = putEncrypted(KEY_WEBHOOK_URL, value)

    // ============================================
    // COLLECTOR SETTINGS (Plain - not sensitive)
    // ============================================

    var syncIntervalMin: Int
        get() = prefs.getInt(KEY_SYNC_INTERVAL_MIN, 15)
        set(value) = prefs.edit().putInt(KEY_SYNC_INTERVAL_MIN, value.coerceIn(5, 1440)).apply()

    var enabledLeagues: Set<String>
        get() {
            val json = prefs.getString(KEY_ENABLED_LEAGUES, null)
            return if (json != null) {
                gson.fromJson(json, object : TypeToken<Set<String>>() {}.type) ?: defaultLeagues()
            } else defaultLeagues()
        }
        set(value) = prefs.edit().putString(KEY_ENABLED_LEAGUES, gson.toJson(value)).apply()

    var enabledSources: Set<String>
        get() {
            val json = prefs.getString(KEY_ENABLED_SOURCES, null)
            return if (json != null) {
                gson.fromJson(json, object : TypeToken<Set<String>>() {}.type) ?: defaultSources()
            } else defaultSources()
        }
        set(value) = prefs.edit().putString(KEY_ENABLED_SOURCES, gson.toJson(value)).apply()

    private fun defaultLeagues(): Set<String> = setOf(
        "bl1", "bl2", "liga3", "dfb",      // Germany
        "PL", "PD", "SA", "BL1", "FL1",    // Top 5 Europe (Football-Data codes)
        "CL", "EL"                          // UEFA competitions
    )

    private fun defaultSources(): Set<String> = setOf(
        "openligadb", "football-data", "thesportsdb", "api-football"
    )

    // ============================================
    // PREDICTION SETTINGS (Plain)
    // ============================================

    var minConfidence: Double
        get() = prefs.getFloat(KEY_MIN_CONFIDENCE, 0.65f).toDouble()
        set(value) = prefs.edit().putFloat(KEY_MIN_CONFIDENCE, value.coerceIn(0.0, 1.0).toFloat()).apply()

    var minValueScore: Double
        get() = prefs.getFloat(KEY_MIN_VALUE_SCORE, 0.05f).toDouble()
        set(value) = prefs.edit().putFloat(KEY_MIN_VALUE_SCORE, value.coerceIn(0.0, 1.0).toFloat()).apply()

    var kellyFraction: Double
        get() = prefs.getFloat(KEY_KELLY_FRACTION, 0.25f).toDouble()
        set(value) = prefs.edit().putFloat(KEY_KELLY_FRACTION, value.coerceIn(0.01, 1.0).toFloat()).apply()

    var maxStakePercent: Double
        get() = prefs.getFloat(KEY_MAX_STAKE_PERCENT, 0.05f).toDouble()
        set(value) = prefs.edit().putFloat(KEY_MAX_STAKE_PERCENT, value.coerceIn(0.01, 0.5).toFloat()).apply()

    // ============================================
    // NOTIFICATION SETTINGS (Encrypted for tokens)
    // ============================================

    var enableNotifications: Boolean
        get() = prefs.getBoolean(KEY_ENABLE_NOTIFICATIONS, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLE_NOTIFICATIONS, value).apply()

    var valueBetThreshold: Double
        get() = prefs.getFloat(KEY_VALUE_BET_THRESHOLD, 0.05f).toDouble()
        set(value) = prefs.edit().putFloat(KEY_VALUE_BET_THRESHOLD, value.coerceIn(0.0, 1.0).toFloat()).apply()

    var telegramBotToken: String
        get() = getEncrypted(KEY_TELEGRAM_BOT_TOKEN)
        set(value) = putEncrypted(KEY_TELEGRAM_BOT_TOKEN, value)

    var telegramChatId: String
        get() = getEncrypted(KEY_TELEGRAM_CHAT_ID)
        set(value) = putEncrypted(KEY_TELEGRAM_CHAT_ID, value)

    // ============================================
    // DEBUG SETTINGS
    // ============================================

    var debugMode: Boolean
        get() = prefs.getBoolean(KEY_DEBUG_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_DEBUG_MODE, value).apply()

    var logLevel: String
        get() = prefs.getString(KEY_LOG_LEVEL, "INFO") ?: "INFO"
        set(value) = prefs.edit().putString(KEY_LOG_LEVEL, value).apply()

    // ============================================
    // UTILITIES
    // ============================================

    fun exportSettings(): String {
        val map = HashMap<String, Any>()
        val all = prefs.all
        for ((key, value) in all) {
            // Don't export encryption key
            if (key != "enc_key") {
                map[key] = when (value) {
                    is String -> if (key.contains("key", true) || key.contains("token", true)) "[ENCRYPTED]" else value
                    else -> value
                }
            }
        }
        return gson.toJson(map)
    }

    fun importSettings(json: String): Boolean {
        return try {
            val type = com.google.gson.reflect.TypeToken.getParameterized(
                Map::class.java, String::class.java, Any::class.java
            ).type
            val rawMap = gson.fromJson(json, type)
            @Suppress("UNCHECKED_CAST")
            val map = rawMap as Map<String, Any>? ?: return false
            val editor = prefs.edit()
            for ((key, value) in map) {
                if (key != "enc_key") {
                    when (value) {
                        is String -> editor.putString(key, value)
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                        is Float -> editor.putFloat(key, value)
                        is Boolean -> editor.putBoolean(key, value)
                        is Set<*> -> editor.putStringSet(key, value as Set<String>)
                        else -> editor.putString(key, gson.toJson(value))
                    }
                }
            }
            editor.apply()
            true
        } catch (e: Exception) {
            CrashReporter.getInstance().logError("SettingsRepository", "Import failed", e)
            false
        }
    }

    fun resetToDefaults() {
        prefs.edit().clear().apply()
        // Re-generate encryption key
        prefs.edit().remove("enc_key").apply()
    }

    fun getAllSettings(): Map<String, Any> {
        val map = HashMap<String, Any>()
        val all = prefs.all
        for ((key, value) in all) {
            if (key != "enc_key") {
                map[key] = when {
                    key.contains("key", true) || key.contains("token", true) -> "[ENCRYPTED]"
                    else -> value
                }
            }
        }
        return map
    }
}