param([string]$DeviceSerial, [string]$Workspace)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
if (!$Workspace) { $Workspace = $repo }
$Workspace = (Resolve-Path -LiteralPath $Workspace).Path
$local = Join-Path $repo '.local-tools'
$state = Join-Path $local 'phone-codex'
$python = Join-Path $local 'voice-env\Scripts\python.exe'
$sdkAdb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
if (!(Test-Path -LiteralPath $python)) {
    & uv venv (Join-Path $local 'voice-env') --python 3.12
    if ($LASTEXITCODE) { throw 'Could not create speech environment' }
    & uv pip install --python $python 'faster-whisper==1.2.1'
    if ($LASTEXITCODE) { throw 'Could not install local speech recognition' }
}
New-Item -ItemType Directory -Force -Path $state | Out-Null
$identity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
& icacls.exe $state /inheritance:r /grant:r "${identity}:(OI)(CI)F" 'SYSTEM:(OI)(CI)F' | Out-Null
if ($LASTEXITCODE) { throw 'Could not protect the token directory' }
& $python (Join-Path $PSScriptRoot 'phone-codex.py') --workspace $Workspace --config-dir $state --phone-config (Join-Path $state 'OpenRayneo-setup.json') --prepare-only
if ($LASTEXITCODE) { throw 'Could not prepare Codex connection' }
if (!(Get-NetTCPConnection -LocalPort 4500 -State Listen -ErrorAction SilentlyContinue)) {
    $arguments = '"' + (Join-Path $PSScriptRoot 'phone-codex.py') + '" --workspace "' + $Workspace + '" --config-dir "' + $state + '"'
    Start-Process -FilePath $python -ArgumentList $arguments -WorkingDirectory $repo -WindowStyle Hidden -RedirectStandardOutput (Join-Path $state 'server.log') -RedirectStandardError (Join-Path $state 'server-error.log') | Out-Null
}
if (!(Get-NetTCPConnection -LocalPort 4501 -State Listen -ErrorAction SilentlyContinue)) {
    $arguments = '"' + (Join-Path $PSScriptRoot 'glasses-transcribe.py') + '" --token-file "' + (Join-Path $state 'phone-token') + '" --model-dir "' + (Join-Path $local 'speech-models') + '"'
    Start-Process -FilePath $python -ArgumentList $arguments -WorkingDirectory $repo -WindowStyle Hidden -RedirectStandardOutput (Join-Path $state 'speech.log') -RedirectStandardError (Join-Path $state 'speech-error.log') | Out-Null
}
if (!$DeviceSerial) {
    $phones = @(& $sdkAdb devices | Select-String '^([^\s]+)\s+device$' | ForEach-Object { $_.Matches[0].Groups[1].Value } | Where-Object { $_ -notlike 'emulator-*' })
    if ($phones.Count -ne 1) { throw 'Connect exactly one phone in ADB, or provide -DeviceSerial, then run this script again. The PC services were started.' }
    $DeviceSerial = $phones[0]
}
foreach ($port in @(4500, 4501)) {
    & $sdkAdb -s $DeviceSerial reverse "tcp:$port" "tcp:$port"
    if ($LASTEXITCODE) { throw "Forwarding port $port failed" }
}
Write-Output 'Both ports forwarded. Connect Computer Codex, then tap Start glasses voice. First model load may take a minute; logs are in .local-tools/phone-codex.'
Write-Output 'If this is the first setup, import .local-tools/phone-codex/OpenRayneo-setup.json on the phone. Do not share that file.'
