package com.kprint.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class SupabaseQueueClient {
    fun claimJobs(config: AppConfig, limit: Int = 5): List<PrintJob> {
        val response = postRpc(
            config = config,
            function = "kprint_claim_jobs",
            body = JSONObject()
                .put("p_store_id", config.storeId)
                .put("p_device_id", config.deviceId)
                .put("p_device_token", config.deviceToken)
                .put("p_limit", limit),
        )
        val array = if (response.isBlank()) JSONArray() else JSONArray(response)
        return buildList {
            for (index in 0 until array.length()) {
                add(PrintJob.fromJson(array.getJSONObject(index)))
            }
        }
    }

    fun completeJob(config: AppConfig, jobId: String, printed: Boolean, error: String? = null): Boolean {
        val response = postRpc(
            config = config,
            function = "kprint_complete_job",
            body = JSONObject()
                .put("p_job_id", jobId)
                .put("p_device_id", config.deviceId)
                .put("p_device_token", config.deviceToken)
                .put("p_printed", printed)
                .put("p_error", error?.take(500) ?: JSONObject.NULL),
        )
        return response.trim().equals("true", ignoreCase = true)
    }

    private fun postRpc(config: AppConfig, function: String, body: JSONObject): String {
        val endpoint = "${config.supabaseUrl.trimEnd('/')}/rest/v1/rpc/$function"
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("apikey", config.supabaseAnonKey)
            // Legacy anon keys are JWTs and also act as the unauthenticated bearer token.
            // New sb_publishable_* keys are opaque and must not be decoded as JWTs.
            if (!config.supabaseAnonKey.startsWith("sb_publishable_")) {
                setRequestProperty("Authorization", "Bearer ${config.supabaseAnonKey}")
            }
        }

        return try {
            connection.outputStream.use { output ->
                output.write(body.toString().toByteArray(Charsets.UTF_8))
            }
            val status = connection.responseCode
            val response = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { reader -> reader.readText() }
                .orEmpty()
            if (status !in 200..299) {
                val detail = runCatching {
                    JSONObject(response).optString("message").ifBlank { response }
                }.getOrDefault(response)
                throw QueueApiException("Supabase respondeu $status: ${detail.take(240)}")
            }
            response
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000
    }
}

class QueueApiException(message: String) : Exception(message)
