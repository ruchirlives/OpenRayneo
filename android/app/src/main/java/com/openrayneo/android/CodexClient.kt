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
    private val historyReady: () -> Unit = {},
    private val finalAnswer: (String, String) -> Unit) {
    data class Request(val id: Any, val method: String, val params: JSONObject, val details: String)
    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder().pingInterval(25, TimeUnit.SECONDS).build()
    private var socket: WebSocket? = null
    private var generation = 0
    private var nextID = 0
    private data class Pending(val complete: (JSONObject) -> Unit, val failed: ((String) -> Unit)?)
    private val pending = mutableMapOf<Int, Pending>()
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
    var recentProjects = emptyList<RecentProject>(); private set
    var loadingProjects = false; private set
    var projectsError: String? = null; private set
    var currentProject = ""; private set
    var currentThreadTitle = ""; private set
    var readOnly = false; private set
    var loadingHistory = false; private set
    var history = emptyList<ChatRecord>(); private set

    fun connect(project: RecentProject? = null) {
        close()
        val config = JSONObject(settings.read().toString())
        if (project != null) config.put("codexThread", project.threadId).put("codexCwd", project.cwd)
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
        if (saved != null) params.put("excludeTurns", true)
        rpc(if (saved == null) "thread/start" else "thread/resume", params, onError = { error ->
            if (saved != null && error.contains("active writer", true)) {
                rpc("thread/read", JSONObject().put("threadId", saved).put("includeTurns", false), onError = {
                    status = "Could not open this conversation: $it"; loadingHistory = false; changed()
                }) { result -> openedThread(result.getJSONObject("thread"), cwd, true, true) }
            } else { status = "Could not resume conversation: $error"; loadingHistory = false; changed() }
        }) { result -> openedThread(result.getJSONObject("thread"), cwd, false, saved != null) }
    }

    private fun openedThread(thread: JSONObject, cwd: String, viewOnly: Boolean, loadHistory: Boolean) {
            threadID = thread.getString("id")
            currentProject = thread.optString("cwd").ifBlank { cwd }
            currentThreadTitle = thread.optString("name").takeUnless { it == "null" }.orEmpty()
            val updated = settings.read().put("codexThread", threadID).put("codexCwd", currentProject)
            settings.save(updated)
            connected = true
            readOnly = viewOnly
            val turns = thread.optJSONArray("turns")
            val last = turns?.optJSONObject(turns.length() - 1)
            running = !viewOnly && (last?.optString("status") == "inProgress" || thread.optJSONObject("status")?.optString("type") == "active")
            turnID = if (running) last?.optString("id") else null
            messages.clear(); answer = ""; question = ""; history = emptyList()
            last?.optJSONArray("items")?.let { items -> for (i in 0 until items.length()) acceptItem(items.getJSONObject(i)) }
            status = if (viewOnly) "Open on desktop · viewing history" else if (running) "Resumed the running computer task" else "Codex connected. Ready for tasks."
            loadingHistory = loadHistory
            changed()
            val opened = threadID
            if (loadHistory) rpc("thread/turns/list", JSONObject().put("threadId", opened).put("limit", 20)
                .put("sortDirection", "desc").put("itemsView", "full"), onError = {
                    loadingHistory = false; status = "Conversation opened; history could not be loaded: $it"; changed()
                }) { result ->
                if (threadID == opened) {
                    val turns = result.optJSONArray("data") ?: JSONArray()
                    val turn = turns.optJSONObject(0)
                    if (running) turnID = turn?.optString("id")
                    val records = CodexHistory.records(turns)
                    history = records.dropLast(1)
                    question = records.lastOrNull()?.question.orEmpty()
                    answer = records.lastOrNull()?.answer.orEmpty()
                    loadingHistory = false
                    changed()
                    historyReady()
                }
            }
    }

    fun forkViewedThread() {
        check(connected && readOnly && !loadingHistory) { "Open a read-only conversation first." }
        val source = checkNotNull(threadID)
        loadingHistory = true; status = "Creating a separate continuation…"; changed()
        rpc("thread/fork", JSONObject().put("threadId", source).put("excludeTurns", true)
            .put("approvalPolicy", "on-request").put("approvalsReviewer", "user").put("sandbox", "workspace-write"), onError = {
                loadingHistory = false; status = "Could not create continuation: $it"; changed()
            }) { result -> openedThread(result.getJSONObject("thread"), currentProject, false, true) }
    }

    fun refreshRecentProjects() {
        check(connected && !running && requests.isEmpty()) { "Finish the current task or approvals before choosing a project." }
        if (loadingProjects) return
        loadingProjects = true; projectsError = null; changed()
        val found = mutableListOf<RecentProject>()
        fun page(cursor: String?, number: Int) {
            val params = JSONObject().put("limit", 100).put("sortKey", "updated_at").put("sortDirection", "desc")
                .put("archived", false).put("sourceKinds", JSONArray(listOf("cli", "vscode", "appServer", "unknown")))
            if (cursor != null) params.put("cursor", cursor)
            rpc("thread/list", params, onError = { message ->
                loadingProjects = false; projectsError = message; changed()
            }) { result ->
                val data = result.optJSONArray("data") ?: JSONArray()
                for (i in 0 until data.length()) {
                    val thread = data.getJSONObject(i)
                    val name = thread.optString("name").takeUnless { it.isBlank() || it == "null" }
                    val preview = thread.optString("preview").lineSequence().firstOrNull().orEmpty().take(100)
                    found += RecentProject(thread.optString("id"), thread.optString("cwd"),
                        name ?: preview.ifBlank { "Untitled conversation" }, thread.optLong("updatedAt"))
                }
                val latest = RecentProjects.latest(found)
                val next = result.optString("nextCursor").takeUnless { it.isBlank() || it == "null" }
                if (latest.size < 5 && next != null && number < 20) page(next, number + 1)
                else {
                    recentProjects = latest; loadingProjects = false
                    if (next != null && latest.size < 5) projectsError = "Showing projects found in the latest 2,000 conversations."
                    changed()
                }
            }
        }
        page(null, 1)
    }

    fun resumeProject(project: RecentProject) {
        check(connected && !running && requests.isEmpty()) { "Finish the current task or approvals before switching projects." }
        check(recentProjects.any { it.threadId == project.threadId && it.cwd == project.cwd }) { "Refresh the recent projects list first." }
        connect(project)
    }

    fun submit(text: String) {
        check(connected && !running && !readOnly && !loadingHistory) { "Open an editable conversation and wait for history and the current task to finish." }
        val id = checkNotNull(threadID)
        if (question.isNotBlank()) history = (history + ChatRecord(question, answer)).takeLast(30)
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
            if (message.has("error")) {
                val error = message.getJSONObject("error").optString("message")
                if (callback.failed != null) callback.failed.invoke(error)
                else { running = false; status = "Codex: $error"; changed() }
            } else callback.complete(message.optJSONObject("result") ?: JSONObject())
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
    private fun rpc(method: String, params: JSONObject, onError: ((String) -> Unit)? = null, complete: (JSONObject) -> Unit) {
        val id = ++nextID; pending[id] = Pending(complete, onError)
        if (socket?.send(JSONObject().put("id", id).put("method", method).put("params", params).toString()) != true) {
            pending.remove(id); disconnected("Computer unavailable. Reconnect to check its status."); return
        }
        main.postDelayed({ pending.remove(id)?.let { request ->
            if (request.failed != null) request.failed.invoke("Computer request timed out: $method. Retry or reconnect.")
            else { status = "Computer request timed out: $method; reconnect to check status"; connected = false; changed() }
        } }, 30_000)
    }
    private fun send(message: JSONObject) { check(socket?.send(message.toString()) == true) { "Computer connection unavailable" } }
    private fun disconnected(message: String) { connected = false; loadingProjects = false; pending.clear(); requests.clear(); status = message; changed() }
    fun close() { generation++; socket?.cancel(); socket = null; running = false; readOnly = false; loadingHistory = false; turnID = null; items.clear(); disconnected("Computer not connected") }
}
