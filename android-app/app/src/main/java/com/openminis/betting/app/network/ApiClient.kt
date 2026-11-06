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
 * Minimal API Client for communicating with the Python FastAPI backend
 */
class ApiClient(private val context: Context) {

    private val gson = Gson()
    private val settings = SettingsRepository.getInstance(context)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
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
    private val predictionListType = object : TypeToken<List<Prediction>>() {}.type
    private val couponListType = object : TypeToken<List<Coupon>>() {}.type
    private val transactionListType = object : TypeToken<List<BankrollTransaction>>() {}.type

    // ============================================
    // HEALTH
    // ============================================
    suspend fun healthCheck(): Result<ApiHealth> = try {
        val request = Request.Builder().url("$baseUrl/health").get().build()
        client.newCall(request).await().parseJson(ApiHealth::class.java)
    } catch (e: Exception) { Result.failure(e) }

    // ============================================
    // MATCHES
    // ============================================
    suspend fun getMatches(daysAhead: Int = 7, limit: Int = 100): Result<List<Match>> = try {
        val url = HttpUrl.parse("$baseUrl/api/matches")?.newBuilder()
            ?.addQueryParameter("days_ahead", daysAhead.toString())
            ?.addQueryParameter("limit", limit.toString())
            ?.build() ?: throw IllegalArgumentException("Invalid URL")
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).await().parseJsonList(matchListType)
    } catch (e: Exception) { Result.failure(e) }

    // ============================================
    // PREDICTIONS
    // ============================================
    suspend fun getPredictions(minScore: Double = 50.0, limit: Int = 50): Result<List<Prediction>> = try {
        val url = HttpUrl.parse("$baseUrl/api/predictions")?.newBuilder()
            ?.addQueryParameter("min_score", minScore.toString())
            ?.addQueryParameter("limit", limit.toString())
            ?.build() ?: throw IllegalArgumentException("Invalid URL")
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).await().parseJsonList(predictionListType)
    } catch (e: Exception) { Result.failure(e) }

    // ============================================
    // COUPONS
    // ============================================
    suspend fun getCoupons(limit: Int = 20): Result<List<Coupon>> = try {
        val url = HttpUrl.parse("$baseUrl/api/coupons")?.newBuilder()
            ?.addQueryParameter("limit", limit.toString())
            ?.build() ?: throw IllegalArgumentException("Invalid URL")
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).await().parseJsonList(couponListType)
    } catch (e: Exception) { Result.failure(e) }

    // ============================================
    // BANKROLL
    // ============================================
    suspend fun getBankroll(): Result<Bankroll> = try {
        val request = Request.Builder().url("$baseUrl/api/bankroll").get().build()
        client.newCall(request).await().parseJson(Bankroll::class.java)
    } catch (e: Exception) { Result.failure(e) }

    suspend fun getTransactions(limit: Int = 20): Result<List<BankrollTransaction>> = try {
        val url = HttpUrl.parse("$baseUrl/api/bankroll/transactions")?.newBuilder()
            ?.addQueryParameter("limit", limit.toString())
            ?.build() ?: throw IllegalArgumentException("Invalid URL")
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).await().parseJsonList(transactionListType)
    } catch (e: Exception) { Result.failure(e) }

    // ============================================
    // SYNC
    // ============================================
    suspend fun syncData(daysAhead: Int = 7): Result<SyncResponse> = try {
        val body = gson.toJson(mapOf("days_ahead" to daysAhead))
        val request = Request.Builder()
            .url("$baseUrl/api/sync")
            .post(RequestBody.create(body, MediaType.parse("application/json")))
            .build()
        client.newCall(request).await().parseJson(SyncResponse::class.java)
    } catch (e: Exception) { Result.failure(e) }

    // ============================================
    // HELPER CLASSES
    // ============================================
    data class ApiHealth(val status: String, val version: String, val timestamp: String, val service: String)
    data class SyncResponse(val sync_results: Map<String, Any>, val new_matches_found: Int, val predictions_generated: Int, val prediction_errors: Int)

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

        val isSuccess: Boolean get() = this is Success
        val isFailure: Boolean get() = this is Failure
        fun getOrNull(): T? = if (this is Success) value else null
        fun getOrElse(default: T): T = if (this is Success) value else default
        fun onSuccess(action: (T) -> Unit): Result<T> { if (this is Success) action(value); return this }
        fun onFailure(action: (Exception) -> Unit): Result<T> { if (this is Failure) action(exception); return this }
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
// EXTENSION FUNCTIONS
// ============================================

private suspend fun <T> Response.parseJson(clazz: Class<T>): ApiClient.Result<T> {
    val gson = Gson()
    return try {
        val body = this.body?.string() ?: throw IOException("Empty response")
        if (!this.isSuccessful) {
            val error = gson.fromJson(body, ApiError::class.java)
            return ApiClient.Result.failure(ApiException(this.code, error.error ?: "HTTP ${this.code}"))
        }
        val parsed = gson.fromJson(body, clazz)
        ApiClient.Result.success(parsed)
    } catch (e: Exception) {
        ApiClient.Result.failure(e)
    } finally { this.close() }
}

private suspend fun <T> Response.parseJsonList(type: Type): ApiClient.Result<List<T>> {
    val gson = Gson()
    return try {
        val body = this.body?.string() ?: throw IOException("Empty response")
        if (!this.isSuccessful) {
            val error = gson.fromJson(body, ApiError::class.java)
            return ApiClient.Result.failure(ApiException(this.code, error.error ?: "HTTP ${this.code}"))
        }
        val parsed = gson.fromJson(body, type)
        ApiClient.Result.success(parsed)
    } catch (e: Exception) {
        ApiClient.Result.failure(e)
    } finally { this.close() }
}

private data class ApiError(val error: String?, val status_code: Int?)
private class ApiException(code: Int, message: String) : Exception("API Error $code: $message") { val code: Int = code }

// ============================================
// COROUTINES EXTENSION FOR OKHTTP
// ============================================

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { cont.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) { cont.resume(response) }
    })
}