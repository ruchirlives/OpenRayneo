# English Android and Windows glasses voice fork

Based on Tobi1chi/OpenRayneo commit `7f2fc0dbb4414fa66bdc5a1be23d44b7d4b4ec31`.
This fork preserves the upstream Apache-2.0 license and attribution. It is not an
official RayNeo product and is not maintained by the upstream author.

## Changes from upstream

| Area | Fork changes |
| --- | --- |
| Android language | Translated hardcoded UI, accessibility labels, status/error messages, diagnostics, and demo text into English. Amap's Chinese notification parsing is retained. This is an English build, not a complete resource-based language selector. |
| Phone dictation | Uses the device's English locale, falling back to en-GB, instead of always requesting zh-CN. This remains the phone recognizer, separate from glasses voice. |
| Glasses voice | Explicit start/stop, channel-1 PCM, 48-to-16 kHz downsampling, bounded speech segmentation, final transcription, and a leading Codex prefix before task submission. Supports the observed recognition spelling “codecs” and “Code X”. |
| Windows transcription | Authenticated loopback HTTP endpoint using faster-whisper base.en, CPU int8 by default. No transcription API key or cloud ASR service is required. |
| Codex integration | Reuses upstream's authenticated app-server transport, conversation, approval, cancellation, and glasses answer handling. Approvals remain on the phone. |
| App switching | Releases the OpenRayneo display/audio/Bluetooth session, restores official Bluetooth connection permission, and opens RayNeo. The reverse switch uses ADB to revoke that permission, stop the competing app, and reconnect the selected glasses. |
| Bluetooth reliability | Ignores unsolicited early MTU callbacks and duplicate setup callbacks; retains discovered identity/notification characteristics rather than dereferencing a missing service. |
| Tests | Android tests cover request-prefix filtering and BLE callback ordering. Python HTTP tests cover authentication, malformed/truncated/oversized audio, silence filtering, and switch request validation. |

The Mac implementation remains unchanged. It informed the audio pipeline; Apple's
Speech framework itself is not available on Windows or Android. The upstream
wearer/channel-1 mapping was confirmed on the original author's hardware, and must
still be checked on other firmware/models.

## Requirements

- RayNeo iO glasses and Android 13+ phone.
- Windows PC, PowerShell, Git, Android SDK platform-tools/ADB, and `uv` on PATH.
- Codex CLI on PATH, installed and signed in using `codex login`. Check
  `codex app-server --help` supports `--listen`, `--ws-auth`, and `--ws-token-file`.
  Development used Codex CLI 0.164/0.165 alpha builds; future protocol compatibility
  must be verified rather than assumed.
- For source builds: JDK 17, Android SDK Platform 36, Build Tools 36.0.0.
- Network access on first setup to download Python packages and the English model;
  Codex itself requires its normal service connection. Local ASR remains on the PC.

## Install the Android app

Download the fork APK from [this fork's prereleases](https://github.com/ruchirlives/OpenRayneo/releases).
The fork release is `1.0.0-english-voice.1`, Android versionCode 20.

Connect and authorize your phone in ADB, then install using Android's package
installer or:

```powershell
adb devices -l
adb -s YOUR_PHONE_SERIAL install -r .\OpenRayneo-English-Voice.apk
```

**Signing:** the current APK uses this fork's development signing certificate, not
the upstream author's certificate. Android may refuse an in-place update of an
upstream or independently built APK. Do not uninstall or clear app data as a
routine workaround: that removes local configuration/history. Back up what your
existing app supports and choose deliberately before changing signing identities.
The keystore/private key is not included in this repository. A source build uses
your own development signing identity. This is an experimental prerelease, not a
production distribution/signing policy.

## Set up the PC connection

```powershell
git clone https://github.com/ruchirlives/OpenRayneo.git
cd OpenRayneo
.\scripts\start-phone-voice.ps1
```

Use USB debugging, or connect the phone with Android's paired wireless debugging.
The launcher detects exactly one physical phone. For multiple devices use:

```powershell
.\scripts\start-phone-voice.ps1 -DeviceSerial YOUR_PHONE_SERIAL -Workspace C:\Path\To\Your\Project
```

The launcher creates an ignored Python 3.12 environment, installs faster-whisper,
generates/reuses a local capability token, starts background PC services, and
forwards ports 4500 (Codex) and 4501 (speech/switching). The model downloads on first
use; allow time for `Local transcription ready` in `.local-tools/phone-codex/speech.log`.
The default ADB path is `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`.

For the first import only, copy the generated configuration to the phone:

```powershell
adb -s YOUR_PHONE_SERIAL push .\.local-tools\phone-codex\OpenRayneo-setup.json /sdcard/Download/OpenRayneo-setup.json
```

In **Settings → Computer connection → Import computer configuration**, select it.
Delete the copy from Downloads after importing: it contains the bearer token.
Do not publish the JSON, token, `.local-tools`, logs, or model cache. No credentials
are bundled in the app or repository.

Go to **Assistant → Computer Codex → Connect / Resume session**. This creates its
own Codex conversation using the configured PC workspace; it is not a remote view
of an existing desktop app chat. Re-run the launcher after restarting the PC or
reconnecting ADB. Keep the PC awake.

## Glasses connection and voice

Keep the glasses awake and connect them in OpenRayneo. Use the full connection,
not BLE-only diagnostics. Confirm Android pairing prompts if shown. Do not unbind
the official RayNeo account, forget the bond, or reset the glasses to switch apps.

In **Assistant → Computer Codex**, press **Start glasses voice** once. Say:

> Codex, tell me a short joke.

Pause for about a second. Check **Last heard**, then the computer task status and
glasses answer. Ordinary speech without the prefix is not submitted. Say
**“Codex, stop listening”** or press Stop glasses voice to disable transcription.
Listening pauses while Codex is busy or requesting an approval. It does not
automatically re-enable after app/service restart, glasses disconnection, or an
external display takeover. Phone voice input is a separate button.

The PC receives detected channel-1 speech while enabled, including utterances
without a Codex prefix. This is a transcript-prefix filter, not an acoustic wake
word or speaker-authentication system. Raw audio is processed in memory and not
recorded by the helper. Final submitted text uses Codex's normal history/data
handling. Do not rely on voice alone for critical tasks or authorization.

## Quick app switching

In the **Glasses** tab:

- **Use official RayNeo:** stops OpenRayneo voice/display, disconnects, restores
  the official app's Bluetooth connection permission, and opens the app.
- **Use OpenRayneo:** uses the PC helper to revoke the official app's Bluetooth
  connection permission, force-stop it, and reconnect the selected glasses.

USB/wireless ADB and the PC helper must remain connected. The official app may
restart itself; revoking only its connection permission prevents it from reclaiming
the data channel. Android's grouped Nearby devices UI can be less specific than
the individual permission state. Pairing, account binding, and stored data remain.
Voice must be explicitly enabled again when wanted.

If the PC is unavailable, disconnect OpenRayneo and restore official RayNeo's
Nearby devices permission in Android Settings to return to the official app.

## Build and tests

Set `JAVA_HOME` to JDK 17. Configure your own Android SDK path in
`android/local.properties` (never commit it), or set `ANDROID_HOME`.

```powershell
cd android
.\gradlew.bat :app:assembleDebug :app:assembleRelease :app:lintRelease :app:testDebugUnitTest
cd ..
.\.local-tools\voice-env\Scripts\python.exe -m unittest discover -s scripts/tests -p test_glasses_transcribe.py -v
```

If Java reports `Unable to establish loopback connection` on Windows, a short local
socket directory was needed on the development machine:

```powershell
New-Item -ItemType Directory -Force .local-tmp | Out-Null
$socketPath = (Resolve-Path .local-tmp).Path.Replace('\', '/')
$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=$socketPath"
```

The untracked Android Studio-generated daemon JVM criteria file and machine-specific
Foojay settings from the development machine are not part of this fork's changes.

## Verified on 28 October 2026

- Samsung SM-G990B2, Android 16; one RayNeo iO unit.
- English UI installed and phone dictation requested English.
- Full BLE authentication and RFCOMM connection; glasses audio received.
- Real wearer voice request transcribed and Codex answer displayed, confirmed by
  the wearer. This is functional validation, not a measured accuracy/latency study.
- Official app switch opened RayNeo and restored permission; return switch
  re-established OpenRayneo BLE/RFCOMM and audio.
- Seven Python endpoint tests and eight Android unit tests pass. Release lint has
  zero errors and 15 existing warnings. Both build variants were built during
  development; see release notes for the final prerelease checks.

## Known limitations

- The glasses' built-in conversation screen can show a clipped **“understanding…”**
  placeholder while Codex answer text is fully visible. This fork does not control
  or fix that native placeholder. It is not a reliable Codex progress indicator.
- Windows/ADB-assisted prototype: no away-from-home VPN/WSS deployment, standalone
  phone ASR, wake-word engine, glasses gesture trigger, or spoken approvals.
- Channel mapping and speech thresholds need validation across devices and firmware.
- No long-duration, battery-life, locked-phone, loss/recovery, or broad model/phone
  compatibility guarantees. Reconnection may require re-running forwarding and
  explicitly reconnecting the glasses and Codex.

See [the detailed voice design](android-glasses-voice.md), upstream
[ASR documentation](asr.md), and [channel-mapping evidence](recording.md).
