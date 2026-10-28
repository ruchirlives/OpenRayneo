package com.openrayneo.android

import android.os.Handler
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/** Bounded channel-1 speech chunks; transcription is opt-in and sent only to the paired PC. */
internal class GlassesVoice(
    private val main: Handler,
    private val changed: () -> Unit,
    private val command: (String) -> Unit,
) {
    private val http = OkHttpClient.Builder().callTimeout(45, TimeUnit.SECONDS).build()
    private var call: Call? = null
    private var epoch = 0
    @Volatile var active = false; private set
    @Volatile var status = "Glasses voice off"; private set
    @Volatile var transcript = ""; private set
    private var available = false
    private var processing = false
    private var endpoint = ""
    private var token = ""
    private val preRoll = ArrayDeque<Short>()
    private val samples = ByteArrayOutputStream()
    private var voicedSamples = 0
    private var silenceSamples = 0
    private var sum = 0
    private var sumCount = 0
    private var lastSamples = 0L

    @Synchronized fun start(config: JSONObject) {
        stop()
        val uri = URI(config.optString("codexUrl"))
        require(uri.scheme == "ws" && uri.host in setOf("127.0.0.1", "localhost")) {
            "Glasses voice currently requires the local ADB connection (ports 4500 and 4501)."
        }
        require(uri.port == 4500) { "Glasses voice currently requires Codex on port 4500." }
        token = config.optString("codexToken")
        require(token.isNotBlank()) { "Configure computer Codex first." }
        endpoint = "http://${uri.host}:4501/transcribe"
        transcript = ""; active = true
        status = "Waiting for glasses audio. Say: Codex, followed by your request."
        changed()
    }

    @Synchronized fun stop() {
        epoch++; active = false; available = false; processing = false
        call?.cancel(); call = null; token = ""
        reset(); preRoll.clear(); sum = 0; sumCount = 0; lastSamples = 0
        status = "Glasses voice off"
    }

    @Synchronized fun readiness(ready: Boolean, detail: String) {
        if (!active) return
        if (available && !ready) {
            epoch++; call?.cancel(); call = null; processing = false
            reset(); preRoll.clear(); sum = 0; sumCount = 0
        }
        available = ready
        if (!processing) status = if (!ready) detail
            else if (lastSamples == 0L || android.os.SystemClock.elapsedRealtime() - lastSamples > 3000)
                "Waiting for glasses audio. Keep the glasses awake."
            else "Listening on glasses channel 1. Say: Codex, followed by your request."
    }

    // Runs on the Opus worker. No network work or UI access on the decoding thread.
    @Synchronized fun accept(left: ShortArray) {
        if (!active || !available || processing) return
        lastSamples = android.os.SystemClock.elapsedRealtime()
        val downsampled = ArrayList<Short>(left.size / 3 + 1)
        for (sample in left) {
            sum += sample; sumCount++
            if (sumCount == 3) { downsampled.add((sum / 3).toShort()); sum = 0; sumCount = 0 }
        }
        if (downsampled.isEmpty()) return
        val rms = sqrt(downsampled.sumOf { it.toDouble() * it } / downsampled.size) / 32768.0
        val voiced = rms >= 0.004
        if (samples.size() == 0 && !voiced) {
            for (s in downsampled) { preRoll.addLast(s); if (preRoll.size > 4800) preRoll.removeFirst() }
            return
        }
        if (samples.size() == 0) { preRoll.forEach(::writeSample); preRoll.clear() }
        downsampled.forEach(::writeSample)
        if (voiced) { voicedSamples += downsampled.size; silenceSamples = 0 }
        else silenceSamples += downsampled.size
        if (samples.size() > 640000) {
            reset(); status = "Request too long. Pause, then speak a shorter Codex request."
            main.post(changed); return
        }
        if (silenceSamples >= 14400) {
            if (voicedSamples >= 4000) {
                val pcm = samples.toByteArray(); val current = epoch
                reset(); processing = true; status = "Transcribing glasses speech locally…"
                val url = endpoint; val secret = token
                main.post {
                    synchronized(this) {
                        if (!active || epoch != current) return@post
                        changed(); transcribe(current, url, secret, pcm)
                    }
                }
            } else reset()
        }
    }

    private fun writeSample(sample: Short) { samples.write(sample.toInt() and 255); samples.write(sample.toInt() shr 8 and 255) }
    private fun reset() { samples.reset(); voicedSamples = 0; silenceSamples = 0 }

    private fun transcribe(current: Int, url: String, secret: String, pcm: ByteArray) {
        val wav = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(16000).putInt(32000)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(pcm.size).put(pcm).array()
        val request = Request.Builder().url(url).header("Authorization", "Bearer $secret")
            .post(wav.toRequestBody("audio/wav".toMediaType())).build()
        call = http.newCall(request).also { task -> task.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = finish(null, "Transcription unavailable. Check the PC speech server and port 4501 forwarding.")
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) { finish(null, "Transcription failed (HTTP ${it.code}). Check the PC speech server."); return }
                    try { finish(JSONObject(it.body?.string().orEmpty()).optString("text"), null) }
                    catch (_: Exception) { finish(null, "Invalid transcription response") }
                }
            }
            private fun finish(text: String?, error: String?) { main.post {
                synchronized(this@GlassesVoice) {
                    if (!active || epoch != current) return@post
                    processing = false; this@GlassesVoice.call = null
                    if (error != null) { stop(); status = error; changed(); return@post }
                    transcript = text.orEmpty()
                    val requestText = GlassesVoiceCommand.request(transcript).orEmpty()
                    if (requestText.equals("stop listening", true) || requestText.equals("stop listening.", true)) {
                        stop(); status = "Stopped by voice"; changed(); return@post
                    }
                    if (requestText.isNotBlank() && available) {
                        available = false
                        status = "Request sent to Codex. Listening pauses while it works."
                        main.post {
                            if (active && epoch == current) command(requestText)
                        }
                    } else status = if (!available) "Speech discarded because Codex or the glasses session changed."
                        else "No request sent. Begin each request with Codex."
                    changed()
                }
            } }
        }) }
    }

    fun close() { stop(); http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll() }
}
