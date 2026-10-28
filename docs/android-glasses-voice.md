# Glasses voice control for computer Codex (Windows prototype)

This addition uses the existing Android glasses audio session and Codex client.
It does not use the phone microphone or Google's speech dialog. Enable it explicitly
in **Assistant → Computer Codex → Start glasses voice** after connecting both glasses
and Codex. It is off on every service restart and is not automatically rearmed after
a glasses disconnect or an external page takes the display.

Say **“Codex, tell me what you can help with”**, then pause for about one second.
The final transcript must begin with Codex (optionally Hey Codex). The remaining
text becomes a normal Codex request. Ordinary speech without that prefix is not
submitted. Say **“Codex, stop listening”** or use Stop glasses voice to stop
transcription. This is a transcript prefix, not a dedicated acoustic wake-word engine:
while enabled, detected speech goes to the PC even when it does not contain Codex.
Recognition errors remain possible. Review important commands and approvals on
the phone. Voice never answers command/file approvals or user-input requests.

## Pipeline and privacy

Glasses prompt audio → existing session/SID-checked Opus decoder → channel 1 →
16 kHz mono PCM → bounded speech chunks → authenticated local Whisper endpoint →
final English transcript → existing Codex conversation → existing glasses answer path.

The Mac implementation in `Sources/OpenRayneoBridge/SpeechController.swift` and
`docs/asr.md` establishes the audio transport/decoder pattern. Mac uses Apple Speech;
this Windows prototype uses faster-whisper base.en. Channel 1 was identified as the
wearer's microphone in the upstream owner's experiment (`docs/recording.md`); this
still needs confirmation on each hardware/firmware combination. Navigation mono
audio is not used for voice control. The stereo prompt session must be active.

Raw speech is sent only to your paired computer, held in memory, and not written to
recordings or request logs. Only the transcript is submitted to Codex and is subject
to its normal conversation/history handling. While enabled, anyone close enough to
be captured could potentially issue a prefixed request; this is not voice identity
authentication. The app shows the most recent transcript. Speech capture is bounded
to 20 seconds per utterance; longer speech is discarded. Silence and low-confidence
Whisper segments are filtered. Listening pauses during Codex tasks and approvals;
requests captured before a pause/session change are discarded. No automatic retry
or queued resubmission occurs after transcription failure.

## Start on this Windows checkout

### Quick switching between companion apps

The Glasses tab has **Use official RayNeo** and **Use OpenRayneo** buttons. Both
use the same authenticated PC helper on port 4501, so USB or wireless ADB must
remain connected. The official direction stops voice/display, disconnects
OpenRayneo, restores the official app's Bluetooth connection permission, and
opens it. The OpenRayneo direction revokes only that permission and force-stops
the official app, then reconnects the last selected glasses (or the only known
device). If the device is ambiguous, choose it using Connect. No account unbinding,
Bluetooth bond deletion, app storage clearing, or firmware reset is performed.

Switching back does not automatically enable voice capture. Connect Computer
Codex and press Start glasses voice when desired. After restarting the PC, run
the launcher below again. Phone permissions show the official connection permission
disabled while OpenRayneo owns the glasses; the official switch restores it.

On the development Samsung phone, both buttons were exercised: official app launch
and permission restoration succeeded, and the return to OpenRayneo established BLE
authentication and RFCOMM and received glasses audio. Official app functionality
beyond opening it remains subject to the app's own connection state.

Run PowerShell from the repository:

```powershell
.\scripts\start-phone-voice.ps1
```

This uses the locally ignored `.local-tools/voice-env` environment, downloads the
English model on first use, and starts Codex on loopback port 4500 plus local
transcription on loopback port 4501. Both require the same existing phone bearer
token. The token directory has a Windows ACL for the current user and SYSTEM.
Both ports are forwarded over ADB; a USB cable or already connected wireless ADB
works. Re-run after reconnecting or restarting. Keep the PC awake. The script does
not expose a LAN listener, create a scheduled task, or change firewall rules.
Default inference is CPU int8 for reliable installation without CUDA DLL changes.
The server also supports `--device cuda` when compatible CTranslate2 CUDA libraries
are already configured; GPU execution is not required or assumed.

If needed, specify `-DeviceSerial <adb-serial>` and `-Workspace <computer-folder>`.
The optional workspace changes new exported configuration, not an existing phone
conversation. Existing imports continue to use port 4500; no new token is needed.
Only first-time setup needs the JSON import. Never share the token or import file.

## Verification and limits

Build and lint the app, run `scripts/tests/test_glasses_transcribe.py` in the voice
environment, and verify real transcription using synthetic speech before live testing.
On the phone, check Last heard while speaking. No transcript means inspect channel-1
audio levels, prompt session, port 4501 forwarding, and speech server logs. A transcript
without Codex is intentionally not sent. Confirm a real wearer request and visible
answer, stop/restart, glasses disconnect, background/locked-phone behavior, and
reconnection on hardware before calling the end-to-end feature validated.

There is no glasses gesture trigger, phone-only/offline transcription, away-from-home
TLS/VPN route, continuous conversation during Codex processing, or spoken approval.
This does not replace the upstream Mac recognizer or add a virtual microphone driver.
