// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.log10
import kotlin.math.sqrt

/** Decodes caption or prompt Opus streams. PCM is consumed on this worker; optional voice mode forwards bounded channel-1 speech to the paired PC. */
internal class PromptAudio(private val consume: (ShortArray, ShortArray) -> Unit = { _, _ -> }) {
    data class State(
        val active: Boolean = false, val channels: Int = 2, val packets: Long = 0, val samples: Long = 0,
        val left: Double = 0.0, val right: Double = 0.0, val lastAudio: Long = 0,
        val receivedPackets: Long = 0,
        val dropped: Int = 0, val error: String? = null
    ) {
        fun summary(): String {
            if (!active) return "Glasses audio stopped"
            if (error != null) return "Glasses audio: $error"
            if (receivedPackets == 0L) return "Audio session confirmed; waiting for the first glasses audio packet"
            if (lastAudio == 0L) return "Received $receivedPackets audio packets; waiting for decoded output"
            if (SystemClock.elapsedRealtime() - lastAudio > 3_000) return "Glasses audio interrupted; navigation display can continue"
            fun db(value: Double) = if (value <= 0.00001) "Silent" else "${(20 * log10(value)).toInt()} dBFS"
            val levels = if (channels == 1) "Glasses mono · ${db(left)}" else "Glasses stereo · Channel 1 ${db(left)} · Channel 2 ${db(right)}"
            return "$levels\nDecoded ${samples / 48_000} seconds · Dropped $dropped packets"
        }
        fun coordinatorState(): AudioState {
            val phase = when {
                !active -> AudioPhase.STOPPED
                error != null -> AudioPhase.FAILED
                receivedPackets == 0L -> AudioPhase.WAITING_PACKET
                lastAudio == 0L -> AudioPhase.WAITING_PCM
                SystemClock.elapsedRealtime() - lastAudio > 3_000 -> AudioPhase.STALLED
                else -> AudioPhase.RECEIVING
            }
            return AudioState(phase, summary())
        }

    }
    private val thread = HandlerThread("glasses-opus").apply { start() }
    private val worker = Handler(thread.looper)
    private val queued = AtomicInteger()
    private val dropped = AtomicInteger()
    @Volatile private var generation = 0
    @Volatile var state = State(); private set
    private var codec: MediaCodec? = null
    private var timestamp = 0L
    private var decodeChannels = 2
    private var outputChannels = 2
    private var outputEncoding = AudioFormat.ENCODING_PCM_16BIT
    private var outputRate = 48_000
    private val info = MediaCodec.BufferInfo()

    @Synchronized fun start(channels: Int = 2) {
        val current = ++generation
        state = State(active = true, channels = channels)
        dropped.set(0)
        worker.post {
            release()
            if (current != generation) return@post
            try {
                decodeChannels = channels
                val header = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
                    .put("OpusHead".toByteArray(Charsets.US_ASCII)).put(1).put(channels.toByte()).putShort(0)
                    .putInt(48_000).putShort(0).put(0).array()
                val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 48_000, channels).apply {
                    setByteBuffer("csd-0", ByteBuffer.wrap(header))
                    setByteBuffer("csd-1", ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(0).apply { flip() })
                    setByteBuffer("csd-2", ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(80_000_000).apply { flip() })
                    setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                }
                val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
                codec = decoder
                decoder.configure(format, null, null, 0); decoder.start()
                timestamp = 0; outputChannels = channels; outputRate = 48_000; outputEncoding = AudioFormat.ENCODING_PCM_16BIT
            } catch (error: Exception) {
                release()
                publish(current) { it.copy(error = "Decoder startup failed: ${error.message}") }
            }
        }
    }

    @Synchronized fun accept(packet: ByteArray) {
        if (!state.active || packet.isEmpty()) return
        val current = generation
        state = state.copy(receivedPackets = state.receivedPackets + 1)
        if (queued.incrementAndGet() > 16) { queued.decrementAndGet(); dropped.incrementAndGet(); return }
        worker.post {
            try {
                if (current != generation) return@post
                val decoder = codec ?: return@post
                // The Opus TOC stereo bit describes encoded channels; do not duplicate mono into two sources.
                if (decodeChannels == 2 && packet[0].toInt() and 4 == 0) {
                    publish(current) { it.copy(error = "Mono audio received; waiting for glasses stereo audio", left = 0.0, right = 0.0) }
                    return@post
                }
                val duration = packetDurationUs(packet)
                drain(decoder, current)
                val index = decoder.dequeueInputBuffer(5_000)
                if (index < 0) { dropped.incrementAndGet(); return@post }
                decoder.getInputBuffer(index)!!.apply { clear(); put(packet) }
                decoder.queueInputBuffer(index, 0, packet.size, timestamp, 0)
                timestamp += duration
                publish(current) { it.copy(packets = it.packets + 1, dropped = dropped.get()) }
                drain(decoder, current)
            } catch (error: Exception) {
                release()
                publish(current) { it.copy(error = "Audio decoding failed. Restart the decoder: ${error.message}") }
            } finally { queued.decrementAndGet() }
        }
    }

    private fun drain(decoder: MediaCodec, current: Int) {
        while (current == generation) {
            val index = decoder.dequeueOutputBuffer(info, 1_000)
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val format = decoder.outputFormat
                outputChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                outputRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                outputEncoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) format.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
            } else if (index >= 0) {
                try {
                    check(outputChannels == decodeChannels && outputRate == 48_000 && outputEncoding == AudioFormat.ENCODING_PCM_16BIT) { "Decoder output does not match the requested PCM16 format" }
                    if (info.size == 0) continue
                    val pcm = decoder.getOutputBuffer(index)!!.order(ByteOrder.LITTLE_ENDIAN)
                    pcm.position(info.offset); pcm.limit(info.offset + info.size)
                    val count = info.size / (2 * outputChannels)
                    val left = ShortArray(count); val right = ShortArray(if (outputChannels == 2) count else 0)
                    var leftPower = 0.0; var rightPower = 0.0
                    for (i in 0 until count) {
                        left[i] = pcm.short
                        leftPower += left[i].toDouble() * left[i]
                        if (outputChannels == 2) { right[i] = pcm.short; rightPower += right[i].toDouble() * right[i] }
                    }
                    if (current != generation || count == 0) continue
                    publish(current) {
                        consume(left, right)
                        it.copy(samples = it.samples + count, left = sqrt(leftPower / count) / 32768,
                            right = sqrt(rightPower / count) / 32768, lastAudio = SystemClock.elapsedRealtime(),
                            dropped = dropped.get(), error = null)
                    }
                } finally { decoder.releaseOutputBuffer(index, false) }
            }
        }
    }

    @Synchronized fun stop() {
        ++generation
        state = State()
        worker.post { release() }
    }
    fun close() { stop(); thread.quitSafely() }
    @Synchronized private fun publish(current: Int, update: (State) -> State) {
        if (current == generation) state = update(state)
    }
    private fun release() { codec?.let { runCatching { it.stop() }; runCatching { it.release() } }; codec = null }

    private fun packetDurationUs(packet: ByteArray): Long {
        val toc = packet[0].toInt() and 255
        val frameUs = when {
            toc and 128 != 0 -> 2_500L shl ((toc ushr 3) and 3)
            toc and 96 == 96 -> if (toc and 8 != 0) 20_000L else 10_000L
            else -> (10_000L shl ((toc ushr 3) and 3)).coerceAtMost(60_000L)
        }
        val count = when (toc and 3) { 0 -> 1; 3 -> packet[1].toInt() and 63; else -> 2 }
        return frameUs * count
    }
}
