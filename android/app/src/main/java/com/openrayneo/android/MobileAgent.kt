// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.os.Handler
import android.os.Looper
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class MobileAgent(private val settings: AppSettings, private val changed: () -> Unit,
    private val tool: (String, JSONObject, (Result<JSONObject>) -> Unit) -> Unit,
    private val finalAnswer: (String, String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).build()
    @Volatile private var call: Call? = null
    @Volatile private var generation = 0
    @Volatile private var pendingTool: CountDownLatch? = null
    private var history = JSONArray()
    var running = false; private set
    var status = "Configure a model to get started"; private set
    var answer = ""; private set
    var question = ""; private set

    fun submit(text: String) {
        check(!running) { "Wait for the answer, or stop the current task first" }
        val config = settings.read()
        val base = config.optString("modelUrl").trim().trimEnd('/')
        val uri = URI(base)
        require(uri.scheme == "https" && uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null) { "The model address must use HTTPS without credentials or query parameters" }
        val token = config.optString("modelKey")
        val model = config.optString("modelName").trim()
        require(token.isNotBlank() && model.isNotEmpty()) { "Enter the model name and API key in Settings" }
        val endpoint = if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
        val session = ++generation
        question = text; running = true; answer = ""; status = "Phone assistant is thinking…"; changed()
        val messages = JSONArray(history.toString())
        if (messages.length() == 0) messages.put(JSONObject().put("role", "system").put("content",
            "You are the OpenRayneo phone assistant. Reply concisely in English. Only tool results establish what was executed. The user starts navigation in Amap; this app only reads the selected Amap notification and does not plan routes. Never invent directions, distances, traffic conditions, or completed actions. Stopping glasses updates does not stop Amap navigation."))
        messages.put(JSONObject().put("role", "user").put("content", text))
        worker.execute {
            try {
                var finished = false
                for (round in 0 until 6) {
                    check(session == generation) { "Cancelled" }
                    val body = JSONObject().put("model", model).put("messages", messages).put("stream", true).put("tools", tools())
                    val request = okhttp3.Request.Builder().url(endpoint).header("Authorization", "Bearer $token")
                        .post(body.toString().toRequestBody("application/json".toMediaType())).build()
                    val currentCall = http.newCall(request); call = currentCall
                    val result = currentCall.execute().use { response ->
                        check(response.isSuccessful) { "Model request failed: HTTP ${response.code}. Check the address, model, and quota." }
                        val responseBody = checkNotNull(response.body)
                        if (responseBody.contentType()?.subtype?.contains("event-stream") == true) {
                            readStream(responseBody, session)
                        } else JSONObject(responseBody.string()).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
                    }
                    check(session == generation) { "Cancelled" }
                    messages.put(result)
                    val calls = result.optJSONArray("tool_calls")
                    if (calls == null || calls.length() == 0) {
                        val textResult = result.optString("content").takeIf { it != "null" }.orEmpty()
                        main.post {
                            if (session == generation) {
                                history = messages
                                answer = textResult; running = false; status = "Answer complete"; changed()
                                if (textResult.isNotBlank()) finalAnswer(text, textResult)
                            }
                        }
                        finished = true; break
                    }
                    for (i in 0 until calls.length()) {
                        val invocation = calls.getJSONObject(i)
                        val function = invocation.getJSONObject("function")
                        val name = function.getString("name")
                        val args = JSONObject(function.optString("arguments").ifBlank { "{}" })
                        val latch = CountDownLatch(1)
                        pendingTool = latch
                        var output: Result<JSONObject> = Result.failure(IllegalStateException("Tool timed out; execution result unknown. Check status."))
                        main.post {
                            if (session != generation) { latch.countDown(); return@post }
                            status = "Running tool: $name"; changed()
                            try { tool(name, args) { output = it; latch.countDown() } }
                            catch (error: Exception) { output = Result.failure(error); latch.countDown() }
                        }
                        latch.await(45, TimeUnit.SECONDS)
                        pendingTool = null
                        check(session == generation) { "Cancelled" }
                        val value = output.getOrElse { JSONObject().put("error", it.message) }
                        messages.put(JSONObject().put("role", "tool").put("tool_call_id", invocation.getString("id")).put("content", value.toString()))
                    }
                }
                check(finished) { "Tool limit reached. Check the current state before continuing." }
            } catch (error: Exception) {
                main.post { if (session == generation) { running = false; status = error.message ?: "Model connection failed"; changed() } }
            } finally { if (session == generation) call = null }
        }
    }

    private fun readStream(body: ResponseBody, session: Int): JSONObject {
        val content = StringBuilder()
        val calls = sortedMapOf<Int, JSONObject>()
        var ended = false
        val source = body.source()
        while (!source.exhausted()) {
            check(session == generation) { "Cancelled" }
            val line = source.readUtf8Line() ?: break
            if (!line.startsWith("data:")) continue
            val data = line.substring(5).trim()
            if (data == "[DONE]") { ended = true; break }
            val payload = JSONObject(data)
            check(!payload.has("error")) { "Model returned a streaming error" }
            val choice = payload.optJSONArray("choices")?.optJSONObject(0) ?: continue
            if (!choice.isNull("finish_reason")) {
                check(choice.optString("finish_reason") in setOf("stop", "tool_calls", "function_call")) { "Model response incomplete: ${choice.optString("finish_reason")}" }
                ended = true
            }
            val delta = choice.optJSONObject("delta") ?: continue
            if (!delta.isNull("content")) {
                content.append(delta.optString("content"))
                check(content.length <= 48000) { "Model response too long" }
                val text = content.toString()
                main.post { if (session == generation) { answer = text; changed() } }
            }
            delta.optJSONArray("tool_calls")?.let { parts -> for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                val value = calls.getOrPut(part.getInt("index")) { JSONObject().put("type", "function").put("function", JSONObject().put("name", "").put("arguments", "")) }
                if (part.has("id")) value.put("id", part.getString("id"))
                part.optJSONObject("function")?.let { incoming ->
                    val function = value.getJSONObject("function")
                    for (key in listOf("name", "arguments")) if (!incoming.isNull(key)) function.put(key, function.optString(key) + incoming.optString(key))
                }
            } }
        }
        check(ended) { "Model connection ended early. Please retry." }
        return JSONObject().put("role", "assistant").put("content", content.toString()).apply {
            if (calls.isNotEmpty()) put("tool_calls", JSONArray(calls.values.toList()))
        }
    }

    private fun tools(): JSONArray {
        fun definition(name: String, description: String, vararg fields: Pair<String, String>): JSONObject {
            val properties = JSONObject()
            fields.forEach { (key, description) -> properties.put(key, JSONObject().put("type", "string").put("description", description)) }
            return JSONObject().put("type", "function").put("function", JSONObject().put("name", name).put("description", description)
                .put("parameters", JSONObject().put("type", "object").put("properties", properties).put("required", JSONArray(fields.map { it.first })).put("additionalProperties", false)))
        }
        return JSONArray().put(definition("navigation_status", "Read the current Amap notification following status without inferring routes"))
            .put(definition("stop_navigation_mirroring", "Stop glasses navigation updates while keeping Amap navigation running"))
            .put(definition("show_notification", "Send a short notification to the glasses", "title" to "Title", "body" to "Body"))
    }
    fun cancel() { generation++; call?.cancel(); call = null; pendingTool?.countDown(); running = false; status = "Stopped; tool actions already completed remain in effect"; changed() }
    fun clear() { cancel(); history = JSONArray(); question = ""; answer = ""; changed() }
    fun close() { cancel(); worker.shutdownNow() }
}
