// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import org.json.JSONObject
import java.util.UUID

/** Protocol executor. It reports facts; only GlassesCoordinator chooses pages. */
internal class GlassesDisplay(
    private val write: (ByteArray, (Result<Unit>) -> Unit) -> Unit,
    private val schedule: (Runnable, Long) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val report: (String) -> Unit,
    private val signal: (Long, PageSignal) -> Unit,
    private val onAudio: (Long, ByteArray) -> Unit,
    private val externalPage: (String) -> Unit,
) {
    private var session: PageSession? = null
    private var sid: String? = null
    private var generation = 0
    private var audioAccepted = false
    private var accepted = false
    private var settled = false
    private var writing = false
    private var failed = false
    private var navigationFrameSent = false
    private var sequence = 0x4000
    private var pending: PageContent? = null
    private var lastWritten: PageContent? = null
    private val recentAudioSequences = ArrayDeque<Long>()
    private var audioReported = false
    private var unmatchedAudioReported = false
    private val timeout = Runnable { fail("Glasses did not confirm startup or layout. Retry manually.") }
    private val settle = Runnable { settled = true; flush() }

    fun start(next: PageSession) {
        check(session == null) { "The previous display session is still open" }
        reset()
        validate(next.mode, next.content)
        session = next
        val id = UUID.randomUUID().toString().replace("-", "")
        sid = id; pending = next.content
        val body = JSONObject().put("sid", id).put("trigger", 1).put("force", false).put("code", 0)
            .put("settings", JSONObject().put("mode", if (next.mode == PageMode.NAVIGATION) "translation" else "conversation")
                .put("source_language", "zh-CN").put("target_language", "zh-CN")
                .put("save_audio", false).put("direction", "ahead"))
        schedule(timeout, 8_000)
        send(protocol(next.mode), 1, body) { if (sid == id && it.isFailure) fail("Failed to send display startup") }
    }

    fun update(id: Long, content: PageContent) {
        val active = session ?: return
        if (active.id != id || failed) return
        validate(active.mode, content)
        pending = content
        flush()
    }

    fun receive(frame: ProtocolCodec.Frame, source: String) {
        if (frame.protocol == 0x14) {
            val message = ProtocolCodec.message(frame.payload)
            val json = message.json?.let(::JSONObject)
            val activity = message.type == 9 && message.audio?.isNotEmpty() == true ||
                (message.type in setOf(2, 3, 5, 8) && !json?.optString("did").isNullOrBlank() && json?.optInt("code", 1) == 1)
            if (activity) externalPage("Glasses teleprompter activity detected; display released")
            return
        }
        val active = session ?: return
        val receivingGeneration = generation
        if (frame.protocol != protocol(active.mode) || sid == null || failed) return
        val message = ProtocolCodec.message(frame.payload)
        val json = message.json?.let(::JSONObject) ?: return
        if (json.optString("sid") != sid) {
            if (message.type == 4 && !unmatchedAudioReported) {
                unmatchedAudioReported = true
                report("Received $source audio for a different session; not passed to decoder")
            }
            return
        }
        if (audioAccepted && message.type == 4) {
            message.audio?.takeIf { it.isNotEmpty() }?.let { packet ->
                val number = json.optLong("seq", -1)
                if (number >= 0) {
                    if (number in recentAudioSequences) return
                    recentAudioSequences.addLast(number)
                    if (recentAudioSequences.size > 64) recentAudioSequences.removeFirst()
                }
                if (!audioReported) {
                    audioReported = true
                    report("First glasses audio packet: $source, ${packet.size} bytes")
                }
                onAudio(active.id, packet)
            }
            return
        }
        if (message.type == 3) {
            val reason = json.optInt("reason_code", -1)
            reset()
            signal(active.id, PageSignal.Ended("Glasses requested ending or pausing this page (reason $reason); display released"))
            return
        }
        if (accepted) return
        if (!audioAccepted) {
            if (message.type != 2) return
            cancel(timeout)
            if (json.optInt("code") != 1) { fail("Glasses rejected session startup: ${json.optInt("code")}"); return }
            audioAccepted = true
            signal(active.id, PageSignal.AudioAccepted(if (active.mode == PageMode.NAVIGATION) 1 else 2))
            if (generation != receivingGeneration) return
            if (active.mode == PageMode.NAVIGATION) {
                signal(active.id, PageSignal.LayoutPending)
                if (generation != receivingGeneration) return
                val id = sid
                val config = JSONObject().put("font_size", 2).put("content_width", 100).put("max_lines", 5)
                    .put("position", "center").put("is_display", true).put("straight_view", "original")
                schedule(timeout, 8_000)
                send(0x13, 7, JSONObject().put("sid", id).put("force", false)
                    .put("scope", "temporary").put("config", config)) {
                    if (sid == id && it.isFailure) fail("Failed to send navigation layout")
                }
                return
            }
        } else {
            if (active.mode != PageMode.NAVIGATION || message.type != 8) return
            cancel(timeout)
            val config = json.optJSONObject("effective_config")
            if (json.optInt("code") != 1 || config?.optInt("font_size") != 2 || config.optInt("content_width") != 100 || config.optInt("max_lines") != 5) {
                fail("Glasses layout is not font 2 / width 100 / 5 rows. Navigation grid transmission stopped.")
                return
            }
        }
        accepted = true
        signal(active.id, PageSignal.Settling)
        if (generation != receivingGeneration) return
        schedule(settle, 500)
    }

    private fun flush() {
        if (!accepted || !settled || writing || failed) return
        val content = pending ?: return
        val id = sid ?: return
        val active = session ?: return
        pending = null
        if (content == lastWritten) return
        writing = true
        val text = (if (active.mode == PageMode.NAVIGATION && navigationFrameSent) "\u3000" else "") + content.text
        val fields = JSONObject().put("source_transcript", text)
        if (active.mode == PageMode.PROMPT) fields.put("target_translation", content.answer.orEmpty()).put("keyword_info", JSONObject.NULL).put("label", 0)
        send(protocol(active.mode), 5, JSONObject().put("sid", id).put("mode", if (active.mode == PageMode.NAVIGATION) 3 else 4)
            .put("status", 1).put("content", fields)) { result ->
            if (sid != id) return@send
            writing = false
            if (result.isSuccess) {
                lastWritten = content
                if (active.mode == PageMode.NAVIGATION) navigationFrameSent = true
                signal(active.id, PageSignal.Written(content))
                flush()
            } else fail("Display update failed: ${result.exceptionOrNull()?.message}")
        }
    }

    fun stop(token: Long) {
        val active = session; val id = sid
        if (active == null || active.id != token || id == null) { signal(token, PageSignal.Closed()); return }
        reset()
        val closingGeneration = generation
        send(protocol(active.mode), 3, JSONObject().put("sid", id)
            .put("reason_code", if (active.mode == PageMode.NAVIGATION) 10 else 2).put("text", "")) { result ->
            if (generation == closingGeneration) signal(token, PageSignal.Closed(result.exceptionOrNull()?.let { "Failed to send close command: ${it.message}" }))
        }
    }
    private fun fail(message: String) {
        val active = session ?: return
        if (failed) return
        failed = true; cancel(timeout); cancel(settle)
        signal(active.id, PageSignal.Failed(message))
    }
    fun reset() {
        generation++
        cancel(timeout); cancel(settle)
        session = null; sid = null; audioAccepted = false; accepted = false; settled = false; writing = false; failed = false
        navigationFrameSent = false; pending = null; lastWritten = null
        recentAudioSequences.clear(); audioReported = false; unmatchedAudioReported = false
    }
    private fun protocol(mode: PageMode) = if (mode == PageMode.NAVIGATION) 0x13 else 0x17
    private fun validate(mode: PageMode, content: PageContent) {
        if (mode == PageMode.NAVIGATION) require(NavigationFrame.isGrid(content.text)) { "Navigation display must remain 20 columns by 5 rows" }
    }
    private fun send(protocol: Int, type: Int, json: JSONObject, completion: (Result<Unit>) -> Unit) {
        try { write(ProtocolCodec.display(protocol, sequence++ and 65535, type, json.toString()), completion) }
        catch (error: Exception) { completion(Result.failure(error)) }
    }
}

internal object NavigationFrame {
    fun directionLabel(direction: String): String = when (direction) {
        "左转" -> "Left"
        "左前" -> "Veer L"
        "左后" -> "Sharp L"
        "右转" -> "Right"
        "右前" -> "Veer R"
        "右后" -> "Sharp R"
        "掉头" -> "U-turn"
        "直行" -> "Straight"
        else -> direction
    }
    fun isGrid(text: String): Boolean = text.split('\n').let { it.size == 5 && it.all { row -> row.length == 20 } }
    private fun grid(): Array<CharArray> = Array(5) { CharArray(20) { '　' } }
    private fun put(rows: Array<CharArray>, text: String, x: Int, y: Int) {
        text.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').take(20 - x).forEachIndexed { i, c ->
            rows[y][x + i] = when (c) { ' ' -> '　'; in '!'..'~' -> (c.code + 0xfee0).toChar(); else -> c }
        }
    }
    private fun text(rows: Array<CharArray>) = rows.joinToString("\n") { String(it) }
    fun status(title: String, detail: String = ""): String = grid().let {
        put(it, title, 2, 1); put(it, detail, 2, 3); text(it)
    }
    fun render(direction: String, meters: Int, roadLabel: String = ""): String {
        val left = listOf("  █    ", " █     ", "██████ ", " █   █ ", "  █  █ ")
        val arrow = when (direction) {
            "左转", "左前", "左后" -> left
            "右转", "右前", "右后" -> left.map { it.reversed() }
            "掉头" -> listOf("  ████ ", " █   █ ", " █   █ ", "███  █ ", " █   █ ")
            "直行" -> listOf("   █   ", "  ███  ", " █████ ", "   █   ", "   █   ")
            else -> List(5) { "       " }
        }
        val rows = grid()
        arrow.forEachIndexed { i, row -> put(rows, row, 2, i) }
        put(rows, if (meters <= 0) "Now" else "${meters.coerceAtMost(9999)}m", 12, 1)
        put(rows, directionLabel(direction), 12, 3)
        put(rows, roadLabel.trim(), 12, 4)
        return text(rows)
    }
}
