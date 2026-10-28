// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.content.Context
import android.os.Handler

/** Owns transport retries; it never starts a glasses page. */
internal class ConnectionSupervisor(
    context: Context,
    private val main: Handler,
    private val changed: (LinkState) -> Unit,
    private val discovered: (RayneoConnection.Device) -> Unit,
    private val received: (ProtocolCodec.Frame, String) -> Unit,
    private val report: (String) -> Unit,
) : RayneoConnection.Listener {
    val transport = RayneoConnection(context, this)
    var state = LinkState(); private set
    private var target: RayneoConnection.Device? = null
    private var attempts = 0
    private var retrying = false
    private var retryScanning = false
    private var starting = false
    private var bleOnly = false
    private val retry = Runnable {
        if (!retrying || target == null || attempts >= 3) return@Runnable
        attempts++; retryScanning = true
        runCatching { transport.scan() }.onFailure {
            retryScanning = false
            report(it.message ?: "Reconnect scan failed")
            scheduleRetry()
        }
    }

    fun scan() { cancelRetries(); transport.scan() }
    fun connect(device: RayneoConnection.Device, diagnostic: Boolean) {
        cancelRetries(); bleOnly = diagnostic
        if (!diagnostic) target = device
        starting = true
        try { transport.connect(device, diagnostic) } finally { starting = false }
    }
    fun cancelRetries() { target = null; retrying = false; retryScanning = false; attempts = 0; main.removeCallbacks(retry) }
    fun disconnect() { cancelRetries(); transport.disconnect() }
    fun close() { cancelRetries(); transport.close() }

    override fun onDevice(device: RayneoConnection.Device) {
        discovered(device)
        val expected = target ?: return
        if (retryScanning && (device.bluetooth.address == expected.bluetooth.address ||
                expected.classicAddress != null && device.classicAddress == expected.classicAddress)) {
            retryScanning = false; starting = true
            val result = runCatching { transport.connect(device, false) }
            if (result.isFailure) {
                transport.disconnect()
                report(result.exceptionOrNull()?.message ?: "Reconnection failed")
            }
            starting = false
            if (result.isFailure) scheduleRetry()
        }
    }
    override fun onState(text: String, ready: Boolean, busy: Boolean) {
        val wasReady = state.phase == LinkPhase.FULL
        val phase = when {
            ready -> if (bleOnly) LinkPhase.BLE_ONLY else LinkPhase.FULL
            busy -> if (text.startsWith("Scanning")) LinkPhase.SCANNING else LinkPhase.CONNECTING
            else -> LinkPhase.DISCONNECTED
        }
        state = LinkState(phase, text)
        changed(state)
        if (ready) {
            retrying = false; retryScanning = false; attempts = 0; main.removeCallbacks(retry)
            target = target?.copy(classicAddress = transport.resolvedClassicAddress ?: target?.classicAddress)
        } else if (!starting && target != null && (wasReady || retrying)) {
            retrying = true
            if (!busy) scheduleRetry()
        }
    }
    private fun scheduleRetry() {
        main.removeCallbacks(retry)
        if (!retrying || target == null) return
        if (attempts >= 3) {
            retrying = false; retryScanning = false
            state = LinkState(LinkPhase.DISCONNECTED, "Three reconnect attempts failed. Check Bluetooth and reconnect.")
            changed(state)
        } else {
            state = LinkState(LinkPhase.RETRY_WAIT, "Connection lost. Waiting for reconnect attempt ${attempts + 1} ")
            changed(state)
            main.postDelayed(retry, 5_000L * (attempts + 1))
        }
    }
    override fun onFrame(frame: ProtocolCodec.Frame, source: String) = received(frame, source)
    override fun onLog(text: String) = report(text)
}
