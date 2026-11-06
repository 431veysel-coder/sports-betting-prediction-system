package com.openminis.betting.app.network

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.openminis.betting.app.CrashReporter
import com.openminis.betting.app.model.*
import com.openminis.betting.app.settings.SettingsRepository
import kotlinx.coroutines.await
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import java.io.IOException
import java.lang.reflect.Type
import java.util.concurrent.TimeUnit

/**
 * API Client for communicating with the Python FastAPI backend
 * Supports: REST calls, WebSocket for real-time updates, Offline-first with local SQLite fallback
 */
class ApiClient(private val context: Context) {

    private val gson = Gson()
    private val settings = SettingsRepository.getInstance(context)
    private val prefs = context.getSharedPreferences("api_client", Context.MODE_PRIVATE)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor(LoggingInterceptor())
        .addInterceptor { chain ->
            val original = chain.request()
            val url = original.url.newBuilder()
                .addQueryParameter("api_key", settings.apiFootballKey)
                .build()
            val request = original.newBuilder().url(url).build()
            chain.proceed(request)
        }
        .build()

    private val baseUrl: String
        get() = settings.customApiEndpoint.ifBlank { "http://10.0.2.2:8000" }

    // ============================================
    // TYPE TOKENS FOR GSON
    // ============================================
    private val matchListType = object : TypeToken<List<Match>>() {}.type
    private val oddsListType = object : TypeToken<List<Odds>>() {}.type
    private val predictionListType = object : TypeToken<List<Prediction>>() {}.type
    private val couponListType = object : TypeToken<List<Coupon>>() {}.type
    private val transactionListType = object : TypeToken<List<BankrollTransaction>>() {}.type
    private val settingsListType = object : TypeToken<List<ApiSetting>>() {}.type

    // ============================================
    // HEALTH & INFO
    // ============================================
    suspend fun healthCheck(): Result<ApiHealth> {
        return try {
            val request = Request.Builder().url("$baseUrl/health").get().build()
            client.newCall(request).await().parseJson(ApiHealth::class.java)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ============================================
    // MATCHES
    // ============================================
    suspend fun getMatches(
        status: String? = null,
        leagueId: String? = null,
        daysAhead: Int = 7,
        limit: Int = 100
    ): Result<List<Match>> {
        return try {
            val url = HttpUrl.parse("$baseUrl/api/matches")?.newBuilder()
                ?.addQueryParameter("days_ahead", daysAhead.toString())
                ?.addQueryParameter("limit", limit.toString())
                ?.apply { status?.let { addQueryParameter("status", it) } }
                ?.apply { leagueId?.let { addQueryParameter("league_id", it) } }
                ?.build()
                ?: throw IllegalArgumentException("Invalid URL")
            
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).await().parseJsonList(matchListType)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getMatch(matchId: String): Result<Match> {
        return try {
            val request = Request.Builder().url("$baseUrl/api/matches/$matchId").get().build()
            client.newCall(request).await().parseJson(Match::class.java)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ============================================
    // ODDS
    // ============================================
    suspend fun getMatchOdds(matchId: String): Result<List<Odds>> {
        return try {
            val request = Request.Builder().url("$baseUrl/api/matches/$matchId/odds").get().build()
            client.newCall(request).await().parseJsonList(oddsListType)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getLatestOdds(matchId: String): Result<Odds> {
        return try {
            val request = Request.Builder().url("$baseUrl/api/matches/$matchId/odds/latest").get().build()
            client.newCall(request).await().parseJson(Odds::class.java)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ============================================
    // PREDICTIONS
    // ============================================
    suspend fun getPredictions(
        matchId: String? = null,
        minScore: Double = 0.0,
        limit: Int = 50
    ): Result<List<Prediction>> {
        return try {
            val url = HttpUrl.parse("$baseUrl/api/predictions")?.newBuilder()
                ?.addQueryParameter("min_score", minScore.toString())
                ?.addQueryParameter("limit", limit.toString())
                ?.apply { matchId?.let { addQueryParameter("match_id", it) } }
                ?.build()
                ?: throw IllegalArgumentException("Invalid URL")
            
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).await().parseJsonList(predictionListType)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun generatePrediction(matchId: String, forceRefresh: Boolean = false): Result<Prediction> {
        return try {
            val body = gson.toJson(mapOf("match_id" to matchId, "force_refresh" to forceRefresh))
            val request = Request.Builder()
                .url("$baseUrl/api/predictions/generate")
                .post(RequestBody.create(body, MediaType.get("application/json")))
                .build()
            client.newCall(request).await().parseJson(Prediction::class.java)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun batchGeneratePredictions(matchIds: List<String>): Result<BatchPredictionResponse> {
        return try {
            val body = gson.toJson(matchIds)
            val request = Request.Builder()
                .url("$baseUrl/api/predictions/batch-generate")
                .post(RequestBody.create(body, MediaType.get("application/json")))
                .build()
            client.newCall(request).await().parseJson(BatchPredictionResponse::class.java)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ============================================
    // COUPONS
    // ============================================
    suspend fun getCoupons(
        status: String? = null,
        couponType: String? = null,
        limit: Int = 50
    ): Result<List<Coupon>> {
        return try {
            val url = HttpUrl.parse("$baseUrl/api/coupons")?.newBuilder()
                ?.addQueryParameter("limit", limit.toString())
                ?.apply { status?.let { addQueryParameter("status", it) } }
                ?.apply { couponType?.let { addQueryParameter("coupon_type", it) } }
                ?.build()
                ?: throw IllegalArgumentException("Invalid URL")
            
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).await().parseJsonList(couponListType)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getCoupon(couponId: String): Result<Coupon> {
        return try {
            val request = Request.Builder().url("$baseUrl/api/coupons/$couponId").get().build()
            client.newCall(request).await().parseJson(Coupon::class.java)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun generateCoupons(request: GenerateCouponsRequest): Result<List<Coupon>> {
        return try {
            val body = gson.toJson(request)
            val req = Request.Builder()
                .url("$baseUrl/api/generate-coupons")
                .post(RequestBody.create(body, MediaType.get("application/json")))
                .build()
            client.newCall(req).await().parseJsonList(couponListType)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun settleCoupon(couponId: String, legResults: List<LegResult>): Result<SettleResponse> {
        return try {
            val body = gson.toJson(legResults)
            val req = Request.Builder()
                .url("$baseUrl/api/coupons/$couponId/settle")
                .post(RequestBody.create(body, MediaType.get("application/json")))
                .build()
            client.newCall(req).await().parseJson(SettleResponse::class.java)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ============================================
    // BANKROLL
    // ============================================
    suspend fun getBankroll(): Result<Bankroll> {
        return try {
            val request = Request.Builder().url("$baseUrl/api/bankroll").get().build()
            client.newCall(request).await().parseJson(Bankroll::class.java)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getTransactions(limit: Int = 50): Result<List<BankrollTransaction>> {
        return try {
            val url = HttpUrl.parse("$baseUrl/api/bankroll/transactions")?.newBuilder()
                ?.addQueryParameter("limit", limit.toString())
                ?.build()
                ?: throw IllegalArgumentException("Invalid URL")
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).await().parseJsonList(transactionListType)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deposit(amount: Double, note: String = ""): Result<Bankroll> {
        return try {
            val body = gson.toJson(mapOf("amount" to amount, "note" to note))
            val request = Request.Builder()
                .url("$baseUrl/api/bankroll/deposit")
                .post(RequestBody.create(body, MediaType.get("application/json")))
                .build()
            client.newCall(request).await().parseJson(Bankroll::class.java)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun withdraw(amount: Double, note: String = ""): Result<Bankroll> {
        return try {
            val body = gson.toJson(mapOf("amount" to amount, "note" to note))
            val request = Request.Builder()
                .url("$baseUrl/api/bankroll/withdraw")
                .post(RequestBody.create(body, MediaType.get("application/json")))
                .build()
            client.newCall(request).await().parseJson(Bankroll::class.java)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun resetBankroll(initialBalance: Double = 1000.0): Result<Bankroll> {
        return try {
            val body = gson.toJson(mapOf("initial_balance" to initialBalance))
            val request = Request.Builder()
                .url("$baseUrl/api/bankroll/reset")
                .post(RequestBody.create(body, MediaType.get("application/json")))
                .build()
            client.newCall(request).await().parseJson(Bankroll::class.java)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ============================================
    // SETTINGS
    // ============================================
    suspend fun getSettings(category: String? = null): Result<List<ApiSetting>> {
        return try {
            val url = HttpUrl.parse("$baseUrl/api/settings")?.newBuilder()
                ?.apply { category?.let { addQueryParameter("category", it) } }
                ?.build()
                ?: throw IllegalArgumentException("Invalid URL")
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).await().parseJsonList(settingsListType)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateSetting(key: String, value: String): Result<ApiSettingUpdateResponse> {
        return try {
            val body = gson.toJson(mapOf("value" to value))
            val request = Request.Builder()
                .url("$baseUrl/api/settings/$key")
                .put(RequestBody.create(body, MediaType.get("application/json")))
                .build()
            client.newCall(request).await().parseJson(ApiSettingUpdateResponse::class.java)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ============================================
    // SYNC
    // ============================================
    suspend fun syncData(daysAhead: Int = 7, sources: List<String>? = null): Result<SyncResponse> {
        return try {
            val body = gson.toJson(mapOf(
                "days_ahead" to daysAhead,
                "sources" to sources
            ))
            val request = Request.Builder()
                .url("$baseUrl/api/sync")
                .post(RequestBody.create(body, MediaType.get("application/json")))
                .build()
            client.newCall(request).await().parseJson(SyncResponse::class.java)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getSyncStatus(): Result<SyncStatusResponse> {
        return try {
            val request = Request.Builder().url("$baseUrl/api/sync/status").get().build()
            client.newCall(request).await().parseJson(SyncStatusResponse::class.java)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ============================================
    // HELPER CLASSES
    // ============================================
    
    data class ApiHealth(
        val status: String,
        val version: String,
        val timestamp: String,
        val service: String
    )

    data class BatchPredictionResponse(
        val results: List<BatchPredictionResult>
    )

    data class BatchPredictionResult(
        val match_id: String,
        val success: Boolean,
        val prediction: Prediction? = null,
        val error: String? = null
    )

    data class GenerateCouponsRequest(
        val stake_per_coupon: Double = 10.0,
        val strategy: String = "BALANCEDED",
        val max_coupons: Int = 20,
        val coupon_types: List<String>? = null
    )

    data class LegResult(
        val leg_id: Long,
        val result: String // WON, LOST, VOIDED
    )

    data class SettleResponse(
        val success: Boolean,
        val coupon_id: String,
        val status: String,
        val won_legs: Int
    )

    data class ApiSetting(
        val key: String,
        val value: String,
        val value_type: String,
        val category: String,
        val subcategory: String? = null,
        val description: String? = null,
        val is_sensitive: Boolean,
        val is_user_editable: Boolean
    )

    data class ApiSettingUpdateResponse(
        val success: Boolean,
        val key: String,
        val value: String
    )

    data class SyncResponse(
        val sync_results: Map<String, Any>,
        val new_matches_found: Int,
        val predictions_generated: Int,
        val prediction_errors: Int
    )

    data class SyncStatusResponse(
        val sources: List<DataSourceInfo>
    )

    data class DataSourceInfo(
        val id: String,
        val name: String,
        val source_type: String,
        val base_url: String? = null,
        val api_key_env: String? = null,
        val rate_limit_per_min: Int,
        val rate_limit_per_day: Int
    )

    // ============================================
    // RESULT WRAPPER
    // ============================================
    sealed class Result<out T> {
        data class Success<T>(val value: T) : Result<T>()
        data class Failure(val exception: Exception) : Result<Nothing>()

        companion object {
            fun <T> success(value: T): Result<T> = Success(value)
            fun <T> failure(exception: Exception): Result<T> = Failure(exception)
        }

        fun isSuccess(): Boolean = this is Success
        fun isFailure(): Boolean = this is Failure
        fun getOrNull(): T? = if (this is Success) value else null
        fun getOrElse(default: T): T = if (this is Success) value else default
        fun onSuccess(action: (T) -> Unit): Result<T> { if (this is Success) action(value); return this }
        fun onFailure(action: (Exception) -> Unit): Result<T> { if (this is Failure) action(exception); return this }
    }

    // Extension functions for Response parsing
    private suspend fun Response.parseJson(clazz: Class<T>): Result<T> {
        return try {
            val body = this.body?.string() ?: throw IOException("Empty response")
            if (!this.isSuccessful) {
                val error = gson.fromJson(body, ApiError::class.java)
                return Result.failure(ApiException(this.code, error.error ?: "HTTP ${this.code}"))
            }
            val parsed = gson.fromJson(body, clazz)
            Result.success(parsed)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            this.close()
        }
    }

    private suspend fun Response.parseJsonList(type: Type): Result<List<T>> {
        return try {
            val body = this.body?.string() ?: throw IOException("Empty response")
            if (!this.isSuccessful) {
                val error = gson.fromJson(body, ApiError::class.java)
                return Result.failure(ApiException(this.code, error.error ?: "HTTP ${this.code}"))
            }
            val parsed = gson.fromJson(body, type)
            Result.success(parsed)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            this.close()
        }
    }

    data class ApiError(val error: String?, val status_code: Int?)
    class ApiException(code: Int, message: String) : Exception("API Error $code: $message") {
        val code: Int = code
    }

    // Logging Interceptor
    private class LoggingInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val start = System.currentTimeMillis()
            val response = chain.proceed(request)
            val duration = System.currentTimeMillis() - start
            Log.d("ApiClient", "${request.method} ${request.url} -> ${response.code} (${duration}ms)")
            return response
        }
    }
}

// ============================================
// COROUTINES EXTENSION FOR OKHTTP
// ============================================

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            cont.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            cont.resume(response)
        }
    })
}