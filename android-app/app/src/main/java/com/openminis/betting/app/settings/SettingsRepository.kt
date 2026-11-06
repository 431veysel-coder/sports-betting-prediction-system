package com.openminis.betting.app.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.google.gson.Gson
import com.openminis.betting.app.CrashReporter
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom

/**
 * Minimal Settings Repository - AES-256 encrypted storage
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

        // Settings keys
        const val KEY_API_FOOTBALL = "api_football_key"
        const val KEY_CUSTOM_ENDPOINT = "custom_api_endpoint"
        const val KEY_SYNC_INTERVAL = "sync_interval_min"
        const val KEY_MIN_CONFIDENCE = "min_confidence"
        const val KEY_MIN_VALUE_SCORE = "min_value_score"
        const val KEY_KELLY_FRACTION = "kelly_fraction"
        const val KEY_MAX_STAKE = "max_stake_percent"
    }

    private fun generateOrLoadKey(): SecretKeySpec {
        val storedKey = prefs.getString("enc_key", null)
        if (storedKey != null) {
            return SecretKeySpec(Base64.decode(storedKey, Base64.DEFAULT), "AES")
        }
        val keyGen = SecureRandom()
        val keyBytes = ByteArray(32)
        keyGen.nextBytes(keyBytes)
        val encoded = Base64.encodeToString(keyBytes, Base64.DEFAULT)
        prefs.edit().putString("enc_key", encoded).apply()
        return SecretKeySpec(keyBytes, "AES")
    }

    private fun encrypt(value: String): String {
        try {
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey)
            return Base64.encodeToString(cipher.doFinal(value.toByteArray()), Base64.DEFAULT)
        } catch (e: Exception) {
            CrashReporter.getInstance().logError("SettingsRepository", "Encryption failed", e)
            return value
        }
    }

    private fun decrypt(encryptedValue: String): String {
        try {
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey)
            return String(cipher.doFinal(Base64.decode(encryptedValue, Base64.DEFAULT)))
        } catch (e: Exception) {
            CrashReporter.getInstance().logError("SettingsRepository", "Decryption failed", e)
            return encryptedValue
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
    // SETTINGS PROPERTIES
    // ============================================

    var apiFootballKey: String
        get() = getEncrypted(KEY_API_FOOTBALL)
        set(value) = putEncrypted(KEY_API_FOOTBALL, value)

    var customApiEndpoint: String
        get() = getEncrypted(KEY_CUSTOM_ENDPOINT, "http://10.0.2.2:8000")
        set(value) = putEncrypted(KEY_CUSTOM_ENDPOINT, value)

    var syncIntervalMin: Int
        get() = prefs.getInt(KEY_SYNC_INTERVAL, 15)
        set(value) = prefs.edit().putInt(KEY_SYNC_INTERVAL, value.coerceIn(5, 1440)).apply()

    var minConfidence: Double
        get() = prefs.getFloat(KEY_MIN_CONFIDENCE, 0.65f).toDouble()
        set(value) = prefs.edit().putFloat(KEY_MIN_CONFIDENCE, value.coerceIn(0.0, 1.0).toFloat()).apply()

    var minValueScore: Double
        get() = prefs.getFloat(KEY_MIN_VALUE_SCORE, 1.05f).toDouble()
        set(value) = prefs.edit().putFloat(KEY_MIN_VALUE_SCORE, value.coerceIn(0.0, 10.0).toFloat()).apply()

    var kellyFraction: Double
        get() = prefs.getFloat(KEY_KELLY_FRACTION, 0.25f).toDouble()
        set(value) = prefs.edit().putFloat(KEY_KELLY_FRACTION, value.coerceIn(0.01, 1.0).toFloat()).apply()

    var maxStakePercent: Double
        get() = prefs.getFloat(KEY_MAX_STAKE, 0.05f).toDouble()
        set(value) = prefs.edit().putFloat(KEY_MAX_STAKE, value.coerceIn(0.01, 0.5).toFloat()).apply()

    fun resetToDefaults() {
        prefs.edit().clear().apply()
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