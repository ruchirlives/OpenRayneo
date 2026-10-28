// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.os.Handler
import android.os.Looper
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

internal class CodexClient(private val settings: AppSettings, private val changed: () -> Unit,
    private val finalAnswer: (String, String) -> Unit) {
    data class Request(val id: Any, val method: String, val params: JSONObject, val details: String)
    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder().pingInterval(25, TimeUnit.SECONDS).build()
    private var socket: WebSocket? = null
    private var generation = 0
    private var nextID = 0
    private val pending = mutableMapOf<Int, (JSONObject) -> Unit>()
    private var threadID: String? = null
    private var turnID: String? = null
    private val messages = linkedMapOf<String, String>()
    private val items = linkedMapOf<String, JSONObject>()
    val requests = mutableListOf<Request>()
    var connected = false; private set
    var running = false; private set
    var status = "Computer not connected"; private set
    var answer = ""; private set
    var question = ""; private set

    fun connect() {
        close()
        val config = settings.read()
        val endpoint = config.optString("codexUrl").trim()
        val uri = URI(endpoint)
        require(uri.scheme == "wss" || (uri.scheme == "ws" && uri.host in setOf("127.0.0.1", "localhost", "::1"))) {
            "Use wss:// for the computer address; use ws://127.0.0.1:4500 for USB forwarding"
        }
        require(uri.userInfo == null && uri.query == null && uri.fragment == null) { "Do not include tokens in the address" }
        val token = config.optString("codexToken")
        check(token.isNotBlank()) { "Configure the computer connection token first" }
        val session = generation
        status = "Connecting to computer…"; changed()
        socket = http.newWebSocket(okhttp3.Request.Builder().url(endpoint).header("Authorization", "Bearer $token").build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { main.post {
                    if (session != generation) return@post
                    rpc("initialize", JSONObject().put("clientInfo", JSONObject().put("name", "openrayneo_android")
                        .put("title", "OpenRayneo Android").put("version", "0.2.0"))) {
                        send(JSONObject().put("method", "initialized"))
                        openThread(config)
                    }
                } }
                override fun onMessage(webSocket: WebSocket, text: String) { main.post {
                    if (session != generation) return@post
                    try { receive(JSONObject(text)) } catch (_: Exception) { status = "Could not parse computer message"; changed() }
                } }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { main.post {
                    if (session == generation) disconnected("Computer connection failed. Check the service, network, and token${response?.let { "（HTTP ${it.code}）" } ?: ""}")
                } }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { main.post {
                    if (session == generation) disconnected("Computer connection closed. Reconnect to resume; tasks are not resent automatically.")
                } }
            })
    }

    private fun openThread(config: JSONObject) {
        val saved = config.optString("codexThread").takeIf { it.isNotBlank() }
        val params = JSONObject().put("approvalPolicy", "on-request").put("approvalsReviewer", "user").put("sandbox", "workspace-write")
        val cwd = config.optString("codexCwd").trim()
        if (cwd.isNotEmpty()) params.put("cwd", cwd)
        if (saved != null) params.put("threadId", saved)
        rpc(if (saved == null) "thread/start" else "thread/resume", params) { result ->
            val thread = result.getJSONObject("thread")
            threadID = thread.getString("id")
            val updated = settings.read().put("codexThread", threadID)
            settings.save(updated)
            connected = true
            val turns = thread.optJSONArray("turns")
            val last = turns?.optJSONObject(turns.length() - 1)
            running = last?.optString("status") == "inProgress" || thread.optJSONObject("status")?.optString("type") == "active"
            turnID = if (running) last?.optString("id") else null
            messages.clear(); answer = ""; question = ""
            last?.optJSONArray("items")?.let { items -> for (i in 0 until items.length()) acceptItem(items.getJSONObject(i)) }
            status = if (running) "Resumed the running computer task" else "Codex connected. Ready for tasks."
            changed()
        }
    }

    fun submit(text: String) {
        check(connected && !running) { "Connect your computer and wait for the current task to finish" }
        val id = checkNotNull(threadID)
        question = text; answer = ""; messages.clear(); running = true; status = "Computer is working…"; changed()
        rpc("turn/start", JSONObject().put("threadId", id).put("input", JSONArray().put(JSONObject().put("type", "text").put("text", text)))) {
            turnID = it.getJSONObject("turn").getString("id")
        }
    }
    fun cancel() {
        val turn = turnID ?: return
        rpc("turn/interrupt", JSONObject().put("threadId", threadID).put("turnId", turn)) { status = "Requested computer task cancellation"; changed() }
    }
    fun newConversation() {
        check(!running) { "End the current computer task first" }
        close(); settings.save(settings.read().apply { remove("codexThread") }); connect()
    }
    fun reply(request: Request, result: JSONObject) {
        check(connected && requests.contains(request)) { "This request has expired" }
        send(JSONObject().put("id", request.id).put("result", result))
        requests.remove(request); changed()
    }

    private fun receive(message: JSONObject) {
        if (message.has("id") && !message.has("method")) {
            val callback = pending.remove(message.getInt("id")) ?: return
            if (message.has("error")) { running = false; status = "Codex：${message.getJSONObject("error").optString("message")}"; changed() }
            else callback(message.optJSONObject("result") ?: JSONObject())
            return
        }
        val method = message.optString("method")
        val params = message.optJSONObject("params") ?: JSONObject()
        if (message.has("id")) {
            if (method in setOf("item/commandExecution/requestApproval", "item/fileChange/requestApproval", "item/tool/requestUserInput")) {
                val item = items[params.optString("itemId")]
                val details = listOfNotNull(params.optString("reason").takeIf { it.isNotBlank() && it != "null" },
                    params.optString("cwd").takeIf { it.isNotBlank() && it != "null" }?.let { "Directory: $it" },
                    params.optString("command").takeIf { it.isNotBlank() && it != "null" },
                    item?.optJSONArray("changes")?.toString(2),
                    params.optString("grantRoot").takeIf { it.isNotBlank() && it != "null" }?.let { "File access scope: $it" })
                    .joinToString("\n\n").ifBlank { "Review the operation on your computer before deciding whether to allow it." }
                requests += Request(message.get("id"), method, params, details); status = "Computer task needs your reply"; changed()
            } else send(JSONObject().put("id", message.get("id")).put("error", JSONObject().put("code", -32601).put("message", "Unsupported client request")))
            return
        }
        if (params.has("threadId") && params.optString("threadId") != threadID) return
        when (method) {
            "turn/started" -> { running = true; turnID = params.getJSONObject("turn").getString("id"); status = "Computer is working…" }
            "item/agentMessage/delta" -> {
                val id = params.optString("itemId")
                messages[id] = (messages[id].orEmpty() + params.optString("delta")).takeLast(24000)
                answer = messages.values.joinToString("\n\n").takeLast(24000)
            }
            "item/started", "item/completed" -> {
                val item = params.getJSONObject("item")
                if (item.optString("type") in setOf("fileChange", "commandExecution")) {
                    items[item.optString("id")] = item
                    while (items.size > 32) items.remove(items.keys.first())
                }
                if (method == "item/completed") acceptItem(item)
            }
            "turn/completed" -> {
                running = false; turnID = null; requests.clear()
                val turn = params.getJSONObject("turn")
                status = when (turn.optString("status")) { "completed" -> "Computer task complete"; "interrupted" -> "Computer task stopped"; else -> "Computer task failed: ${turn.optJSONObject("error")?.optString("message").orEmpty()}" }
                if (turn.optString("status") == "completed" && answer.isNotBlank()) finalAnswer(question, answer)
            }
            "error" -> status = "Codex：${params.optJSONObject("error")?.optString("message").orEmpty()}"
        }
        changed()
    }
    private fun acceptItem(item: JSONObject) {
        if (item.optString("type") == "userMessage") {
            val content = item.optJSONArray("content")
            if (content != null) question = (0 until content.length()).mapNotNull { index ->
                content.optJSONObject(index)?.takeIf { it.optString("type") == "text" }?.optString("text")
            }.joinToString("\n")
        }
        if (item.optString("type") == "agentMessage") {
            messages[item.optString("id")] = item.optString("text").takeLast(24000)
            answer = messages.values.joinToString("\n\n").takeLast(24000)
        }
    }
    private fun rpc(method: String, params: JSONObject, complete: (JSONObject) -> Unit) {
        val id = ++nextID; pending[id] = complete
        if (socket?.send(JSONObject().put("id", id).put("method", method).put("params", params).toString()) != true) {
            pending.remove(id); disconnected("Computer unavailable. Reconnect to check its status."); return
        }
        main.postDelayed({ if (pending.remove(id) != null) { status = "Computer request timed out: $method; reconnect to check status"; connected = false; changed() } }, 30_000)
    }
    private fun send(message: JSONObject) { check(socket?.send(message.toString()) == true) { "Computer connection unavailable" } }
    private fun disconnected(message: String) { connected = false; pending.clear(); requests.clear(); status = message; changed() }
    fun close() { generation++; socket?.cancel(); socket = null; running = false; turnID = null; items.clear(); disconnected("Computer not connected") }
}
