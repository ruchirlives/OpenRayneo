// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import org.json.JSONObject
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CancellationException

internal class MainActivity : ComponentActivity() {
    private val model: AppModel by viewModels()
    private var voiceSource = AnswerSource.MOBILE_AGENT
    private val permissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            model.refreshPermissions()
            if (model.wantsScan) {
                model.wantsScan = false
                if (model.bluetoothAllowed) model.scan()
                else model.error = "Nearby Devices permission is required to connect glasses. Grant it later in Settings and configure other features now."
            }
        }
    private val importFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null)
                model.attempt {
                    val bytes = contentResolver.openInputStream(uri)!!.use { it.readNBytes(16385) }
                    require(bytes.size <= 16384) { "Configuration file too large" }
                    model.importConfig(JSONObject(String(bytes, Charsets.UTF_8)))
                }
        }
    private val speech =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val text =
                    result.data
                        ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                        ?.firstOrNull()
                        .orEmpty()
                if (voiceSource == AnswerSource.MOBILE_AGENT) model.phoneDraft = text
                else model.computerDraft = text
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        voiceSource =
            savedInstanceState?.getString("voiceSource")?.let(AnswerSource::valueOf)
                ?: AnswerSource.MOBILE_AGENT
        enableEdgeToEdge()
        setContent {
            CompanionTheme(model.appearance) {
                CompanionApp(
                    model,
                    UiActions(
                        scan = {
                            if (model.bluetoothAllowed) model.scan()
                            else {
                                model.wantsScan = true
                                permissions.launch(
                                    arrayOf(
                                        Manifest.permission.BLUETOOTH_SCAN,
                                        Manifest.permission.BLUETOOTH_CONNECT,
                                    )
                                )
                            }
                        },
                        notificationAccess = {
                            model.attempt {
                                startActivity(
                                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                )
                            }
                        },
                        notificationPermission = {
                            permissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                        },
                        appSettings = {
                            startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:$packageName"),
                                )
                            )
                        },
                        openGaode = {
                            model.attempt {
                                startActivity(
                                    checkNotNull(
                                        packageManager.getLaunchIntentForPackage(
                                            NavigationFeed.PACKAGE
                                        )
                                    ) {
                                        "Install Amap on your phone first"
                                    }
                                )
                            }
                        },
                        importConfig = {
                            importFile.launch(arrayOf("application/json", "text/plain"))
                        },
                        voice = { source ->
                            model.attempt {
                                voiceSource = source
                                speech.launch(
                                    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                        .putExtra(
                                            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                                        )
                                        .putExtra(
                                            RecognizerIntent.EXTRA_LANGUAGE,
                                            java.util.Locale.getDefault()
                                                .takeIf { it.language == "en" }
                                                ?.toLanguageTag() ?: "en-GB",
                                        )
                                )
                            }
                        },
                        switchApp = ::switchGlassesApp,
                    ),
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        model.refreshPermissions()
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun switchGlassesApp(official: Boolean) {
        if (model.switchingApps) return
        if (!official && !model.bluetoothAllowed) { model.error = "Grant Nearby Devices permission before connecting glasses."; return }
        val launch = if (official) packageManager.getLaunchIntentForPackage("com.rayneo.venus.pub") else null
        if (official && launch == null) { model.error = "Official RayNeo app is not installed."; return }
        model.switchingApps = true
        lifecycleScope.launch {
            try {
                if (official) {
                    model.service().stopGlassesVoice()
                    model.service().disconnect()
                    withTimeout(8000) {
                        while (model.state.glasses.link.phase != LinkPhase.DISCONNECTED || model.state.glasses.disconnecting) delay(100)
                    }
                }
                AppSwitcher.switch(model.config, official)
                if (official) startActivity(launch)
                else {
                    delay(600) // Let Android release the official RFCOMM socket before reconnecting.
                    if (model.state.glasses.link.phase != LinkPhase.FULL) {
                        model.service().connection.showBonded()
                        val choices = model.state.devices
                        val selected = choices.firstOrNull { it.bluetooth.address == model.lastGlassesAddress }
                            ?: choices.singleOrNull()
                        if (selected != null) model.connect(selected, false)
                        else model.notice = "Official connection released. Tap Connect and choose your glasses."
                    } else model.notice = "OpenRayneo already has the glasses connection."
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                model.error = error.message ?: "App switch failed. Check the PC connection."
            } finally { model.switchingApps = false }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("voiceSource", voiceSource.name)
        super.onSaveInstanceState(outState)
    }
}

internal data class UiActions(
    val scan: () -> Unit,
    val notificationAccess: () -> Unit,
    val notificationPermission: () -> Unit,
    val appSettings: () -> Unit,
    val openGaode: () -> Unit,
    val importConfig: () -> Unit,
    val voice: (AnswerSource) -> Unit,
    val switchApp: (Boolean) -> Unit,
)
