package com.openrayneo.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

internal object AppSwitcher {
    private val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
    suspend fun switch(config: JSONObject, official: Boolean) = withContext(Dispatchers.IO) {
        val uri = URI(config.optString("codexUrl"))
        require(uri.scheme == "ws" && uri.host in setOf("127.0.0.1", "localhost") && uri.port == 4500) {
            "Quick switching requires the local PC helper and ADB forwarding on ports 4500 and 4501."
        }
        val token = config.optString("codexToken")
        require(token.isNotBlank()) { "Import computer connection settings first." }
        val body = JSONObject().put("owner", if (official) "official" else "openrayneo").toString()
        val request = Request.Builder().url("http://${uri.host}:4501/switch")
            .header("Authorization", "Bearer $token")
            .post(body.toRequestBody("application/json".toMediaType())).build()
        http.newCall(request).execute().use {
            check(it.isSuccessful) { "PC app switch failed (HTTP ${it.code}). Check ADB and the PC helper." }
        }
    }
}
