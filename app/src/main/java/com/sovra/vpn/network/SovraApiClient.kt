package com.sovra.vpn.network

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object SovraApiClient {
    private const val BASE_URL = "http://77.90.52.130:8750/v1"
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    suspend fun getAppConfig(): JsonObject? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("$BASE_URL/app-config").build()
            val resp = client.newCall(req).execute()
            if (resp.isSuccessful) {
                gson.fromJson(resp.body?.string(), JsonObject::class.java)
            } else null
        } catch (e: Exception) {
            null
        }
    }

    suspend fun getPlans(): JsonObject? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("$BASE_URL/plans").build()
            val resp = client.newCall(req).execute()
            if (resp.isSuccessful) {
                gson.fromJson(resp.body?.string(), JsonObject::class.java)
            } else null
        } catch (e: Exception) {
            null
        }
    }

    suspend fun loginWithSub(subLink: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val body = JsonObject().apply { addProperty("sub", subLink) }
            val req = Request.Builder()
                .url("$BASE_URL/auth/login-sub")
                .post(body.toString().toRequestBody(JSON))
                .build()
            val resp = client.newCall(req).execute()
            val str = resp.body?.string() ?: ""
            if (resp.isSuccessful) {
                val json = gson.fromJson(str, JsonObject::class.java)
                val token = json.get("token")?.asString ?: ""
                Pair(true, token)
            } else {
                Pair(false, str)
            }
        } catch (e: Exception) {
            Pair(false, e.message ?: "Network error")
        }
    }
}
