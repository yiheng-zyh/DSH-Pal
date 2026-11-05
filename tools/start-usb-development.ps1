param(
    [Parameter(Mandatory=$true)][string]$Serial,
    [int]$LocalPort = 0,
    [string]$Adb = 'D:\AndroidSdk\platform-tools\adb.exe',
    [switch]$AttachOnly,
    [switch]$Inspect,
    [switch]$Pair,
    [switch]$Stop
)
$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$proxy = Join-Path $PSScriptRoot 'usb-development-proxy.mjs'
function Stop-OwnedProxy {
    $listener = Get-NetTCPConnection -LocalAddress 127.0.0.1 -LocalPort 18766 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $listener) { return }
    $helper = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)"
    if ($helper.Name -ne 'node.exe' -or -not $helper.CommandLine.Contains($proxy)) { throw 'Port belongs to another process; no process was stopped.' }
    Stop-Process -Id $listener.OwningProcess -ErrorAction Stop
}
if ($Stop) {
    Stop-OwnedProxy
    & $Adb -s $Serial reverse --remove tcp:18765
    Write-Output 'USB development proxy stopped. Public DSH settings are preserved.'
    return
}
# No DSH configuration writes, LAN exposure, process stops, uninstall, or data clearing.
if ((& $Adb -s $Serial get-state 2>$null) -ne 'device') { throw 'USB device is not available.' }
$candidates = if ($LocalPort -gt 0) { @($LocalPort) } else { @(65358,56398,55793) }
$port = 0
foreach ($candidate in $candidates) {
    try {
        $status = Invoke-RestMethod "http://127.0.0.1:$candidate/api/pair/status" -TimeoutSec 2
        if ($null -ne $status.phase) { $port = $candidate; break }
    } catch {}
}
if ($port -eq 0) { throw 'Local DSH port not found. Supply its current loopback port with -LocalPort.' }
if (-not $AttachOnly) {
    $env:JAVA_HOME = 'D:\JDK21'
    $env:ANDROID_HOME = 'D:\AndroidSdk'
    $env:GRADLE_USER_HOME = 'D:\gradle-home'
    Write-Output 'Building USB development APK (public connection settings are preserved).'
    & 'D:\AndroidSdk\gradle\gradle-8.14.4\bin\gradle.bat' -p $project --no-daemon '-PdshUsbDevelopment=true' :app:assembleRelease --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'USB development build failed.' }
    & $Adb -s $Serial install -r (Join-Path $project 'app\build\outputs\apk\release\app-release.apk')
    if ($LASTEXITCODE -ne 0) { throw 'Cover install failed.' }
} else {
    $package = (& $Adb -s $Serial shell dumpsys package com.dsh.mobile) -join "`n"
    if ($package -notmatch '\bDEBUGGABLE\b') { throw '-AttachOnly requires an already-installed USB development APK.' }
}
$health = $null
try { $health = Invoke-RestMethod 'http://127.0.0.1:18766/__dsh_usb_health' -TimeoutSec 1 } catch {}
if ($health -and $health.service -ne 'dsh-usb-development') { throw 'Debug proxy port is owned by another service.' }
if ($health -and ($health.targetPort -ne $port -or $health.version -ne 5)) {
    Stop-OwnedProxy
    $health = $null
}
if (-not $health) {
    $node = (Get-Command node -ErrorAction Stop).Source
    Start-Process -FilePath $node -ArgumentList @(('"' + $proxy + '"'), [string]$port) -WindowStyle Hidden | Out-Null
    for ($attempt=0; $attempt -lt 20; $attempt++) {
        try { $health = Invoke-RestMethod 'http://127.0.0.1:18766/__dsh_usb_health' -TimeoutSec 1; break } catch { Start-Sleep -Milliseconds 200 }
    }
    if ($health.service -ne 'dsh-usb-development') { throw 'USB debug proxy did not start.' }
}
& $Adb -s $Serial reverse tcp:18765 tcp:18766
if ($LASTEXITCODE -ne 0) { throw 'adb reverse failed.' }
# Reuse an already-paired USB entry. Repeated runs must not replace the active
# page with a fresh one-shot token, or mint unused devices on every attachment.
$knownUsb = $false
try {
    [xml]$devicePrefs = ((& $Adb -s $Serial shell run-as com.dsh.mobile cat shared_prefs/dsh_mobile_settings.xml 2>$null) -join '')
    $profileJson = ($devicePrefs.map.string | Where-Object name -eq 'usb_connection_profiles').InnerText
    $pending = ($devicePrefs.map.string | Where-Object name -eq 'usb_pairing_url').InnerText
    $knownUsb = -not $pending -and @($profileJson | ConvertFrom-Json | Where-Object baseUrl -eq 'http://dsh-usb.localhost:18765').Count -gt 0
} catch { $knownUsb = $false }
$usbUrl = 'http://dsh-usb.localhost:18765/pair-app'
if ($Pair -or -not $knownUsb) {
    # Issue locally, redeem only through our verified loopback proxy. No token output.
    $issued = Invoke-RestMethod "http://127.0.0.1:$port/api/pair/issue" -Method Post -ContentType 'application/json' -Body '{}' -TimeoutSec 5
    $original = [uri]$issued.url
    if ($original.AbsolutePath -ne '/pair-accept') { throw 'Unexpected pairing endpoint; no link was opened.' }
    $usbUrl = 'http://dsh-usb.localhost:18765' + $original.PathAndQuery
}
$encoded = [uri]::EscapeDataString($usbUrl)
& $Adb -s $Serial shell "am start --display 0 -a android.intent.action.VIEW -d 'dshmobile://open?url=$encoded' -n com.dsh.mobile/com.dsh.mobile.MainActivity" | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'USB pairing launch failed.' }
if ($Inspect) {
    $appPid = ((& $Adb -s $Serial shell pidof com.dsh.mobile) -join '').Trim()
    if ($appPid -notmatch '^\d+$') { throw 'App process not available for inspection.' }
    & $Adb -s $Serial forward tcp:9228 "localabstract:webview_devtools_remote_$appPid"
    if ($LASTEXITCODE -ne 0) { throw 'WebView inspection forwarding failed.' }
    Write-Output 'WebView inspection is available on computer loopback port 9228.'
}
Write-Output $(if($Pair -or -not $knownUsb){'USB mapping ready; first pairing launched.'}else{'USB mapping ready; saved pairing reused (use -Pair only to repair authentication).'})
Write-Output 'UI and live requests use the cable; no DSH bind was changed.'
