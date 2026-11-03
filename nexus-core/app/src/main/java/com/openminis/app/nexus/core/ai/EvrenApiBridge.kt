package com.openminis.app.nexus.core.ai

import android.util.Log

class EvrenApiBridge(private val apiKey: String) {

    companion object {
        private const val BASE_URL = "https://api.ssyz.org.tr/api/v1"
        private const val TAG = "MinisEvrenBridge"
    }

    fun listModels(): String {
        Log.i(TAG, "Querying EVREN API models at $BASE_URL/models")
        return "Models list JSON"
    }

    fun chatCompletion(model: String, prompt: String): String {
        Log.i(TAG, "Sending chat completion to EVREN model: $model")
        return "EVREN Response for $prompt"
    }
}
