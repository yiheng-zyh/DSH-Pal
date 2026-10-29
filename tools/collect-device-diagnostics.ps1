param(
    [Parameter(Mandatory=$true)][string]$OutputDirectory,
    [Parameter(Mandatory=$true)][string]$Serial,
    [string]$Adb = 'adb',
    [int]$LocalPort = 0
)
$ErrorActionPreference = 'Stop'
# Read-only collection: no install, restart, config writes, log clearing or app data access.
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$appPid = ((& $Adb -s $Serial shell pidof com.dsh.mobile) -join '').Trim()
if (-not $appPid) { throw 'DSH Mobile is not running; no other app logs were collected.' }
$tags = @('App','Network','Permission','WebView','Bridge','Load','Connection','Service','Task','Reply','Notification','Console','Crash','Export','Pair','Motion','Keyboard','Layout','Activity','Cache','Probe','File','Download') | ForEach-Object { "DshRun/${_}:V" }
$lines = & $Adb -s $Serial logcat -d -v threadtime --pid $appPid -s @tags
$lines | Select-Object -Last 1000 | Set-Content -LiteralPath (Join-Path $OutputDirectory 'app-log.txt') -Encoding utf8
& $Adb -s $Serial shell dumpsys gfxinfo com.dsh.mobile | Set-Content -LiteralPath (Join-Path $OutputDirectory 'frames.txt') -Encoding utf8
$findings = @($lines | Select-String 'load timeout|stale approval|no-composer|draft-occupied|queue full|not sent|failed|rejected|DshRun/Crash' | ForEach-Object { $_.Line })
$keyboardSamples = @($lines | ForEach-Object {
    if ($_ -match 'keyboard targetCss=(\d+) frames=(\d+) maxGapMs=(\d+) slowFrames=(\d+) viewportChanged=(true|false)') {
        @{ targetCss=[int]$Matches[1]; frames=[int]$Matches[2]; maxGapMs=[int]$Matches[3]; slowFrames=[int]$Matches[4]; viewportChanged=$Matches[5] -eq 'true' }
    }
} | Select-Object -Last 20)
$motionSamples = @($lines | ForEach-Object {
    if ($_ -match 'motion (tab|right) frames=(\d+) maxGapMs=(\d+) durationMs=(\d+)') {
        @{ kind=$Matches[1]; frames=[int]$Matches[2]; maxGapMs=[int]$Matches[3]; durationMs=[int]$Matches[4] }
    }
} | Select-Object -Last 20)
$performanceSpikes = @($keyboardSamples | Where-Object { $_.maxGapMs -ge 100 -or $_.viewportChanged })
$connection = $null
try {
    if ($LocalPort -le 0) { throw 'Local status not requested' }
    $status = (Invoke-WebRequest -Uri "http://127.0.0.1:$LocalPort/api/pair/status" -UseBasicParsing -TimeoutSec 3).Content | ConvertFrom-Json
    $connection = @{phase=$status.phase; onlineCount=$status.onlineCount}
} catch { $connection = @{phase='local status unavailable'} }
$report = @{
    time = [TimeZoneInfo]::ConvertTimeBySystemTimeZoneId([datetime]::UtcNow, 'China Standard Time').ToString('yyyy-MM-dd HH:mm:ss')
    running = $true
    connection = $connection
    activity = @($lines | Select-String 'DshRun/Activity' | Select-Object -Last 12 | ForEach-Object { $_.Line })
    pageLoadsInCurrentProcess = @($lines | Select-String 'onPageStarted').Count
    unchangedTargetReturns = @($lines | Select-String 'new intent targetChanged=false').Count
    findings = $findings
    keyboardTransitions = $keyboardSamples
    pluginTransitions = $motionSamples
    performanceSpikes = $performanceSpikes
    note = 'Findings include earlier attempts in this process; they do not alone prove the current attempt failed.'
}
$report | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $OutputDirectory 'summary.json') -Encoding utf8
Write-Output "Collected sanitized app log and frame statistics. Findings=$($findings.Count); performanceSpikes=$($performanceSpikes.Count); phase=$($connection.phase)"
