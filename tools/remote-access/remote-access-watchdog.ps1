# remote-access-watchdog.ps1
#
# 静默、幂等、自愈的健康检查：网关（18772）或 frpc 掉了就拉起来。
# 只在真的动手时写一行日志；健康时完全静默（exit 0，不写任何东西）。
#
# ── 为什么需要它 ────────────────────────────────────────────────
# 网关是 `node run.mjs` 加它的 frpc 子进程。run.mjs 自己会重试 frpc，
# 所以脆弱的是**启动器本身**：计划任务只在登录时触发一次，
# 睡眠唤醒 / 注销 / 崩溃之后就没人管了 —— 公网入口会一直 ERR_CONNECTION_REFUSED
# 直到用户下次登录。
#
# 本脚本只重启**同一套**已安装的栈。不改 DSH 配置、不碰其他启动项、健康时什么都不做。
#
# ── 零窗口（硬要求） ────────────────────────────────────────────
# 拉起子进程必须走 wscript + .vbs，不能用 Start-Process -WindowStyle Hidden
# （那是「先创建窗口再隐藏」，会闪）。所以这里调 run-autostart-hidden.vbs。
#
# 用法：
#   .\remote-access-watchdog.ps1 -DryRun     # 只报告状态，不动手
#   .\remote-access-watchdog.ps1             # 检查并自愈
#
# 退出码：0 = 健康或已恢复；1 = 需要人工处理

param(
    [switch]$DryRun,
    [int]$GatewayPort = 18772,
    [string]$ExpectedFrpcName = ''      # 留空 = 自动探测（不写死版本号）
)

$ErrorActionPreference = 'Stop'

# 全部相对本脚本所在目录，不写死任何用户路径
$toolDir = $PSScriptRoot
$vbsLauncher = Join-Path $toolDir 'run-autostart-hidden.vbs'
$dataDir = Join-Path $env:LOCALAPPDATA 'DSHPal\remote-access'
$logFile = Join-Path $dataDir 'watchdog.log'
$logMaxBytes = 262144   # 256 KB，超过就只留尾部

function Write-WatchdogLog([string]$message) {
    if ($DryRun) { Write-Output "[dry-run] $message"; return }
    try {
        if (-not (Test-Path $dataDir)) { New-Item -ItemType Directory -Path $dataDir -Force | Out-Null }
        # 日志轮转：超过上限就只留最后 400 行，避免无限增长
        if ((Test-Path $logFile) -and (Get-Item $logFile).Length -gt $logMaxBytes) {
            $tail = Get-Content $logFile -Tail 400
            Set-Content -Path $logFile -Value $tail -Encoding UTF8
        }
        Add-Content -Path $logFile -Value ((Get-Date -Format 'yyyy-MM-dd HH:mm:ss') + '  ' + $message) -Encoding UTF8
    } catch { }
}

function Get-GatewayPid {
    try {
        $conn = Get-NetTCPConnection -State Listen -LocalPort $GatewayPort -ErrorAction Stop |
                Select-Object -First 1
        if ($conn) { return [int]$conn.OwningProcess }
    } catch { }
    return 0
}

function Get-FrpcProcesses {
    # 不写死版本号：找名字以 frpc 开头、且可执行文件在本工具目录下的进程
    $all = Get-Process -ErrorAction SilentlyContinue | Where-Object {
        $_.ProcessName -like 'frpc*'
    }
    if ($ExpectedFrpcName) {
        $all = $all | Where-Object { $_.ProcessName -eq $ExpectedFrpcName }
    }
    return @($all)
}

# ── 健康判定 ───────────────────────────────────────────────────
$gatewayPid = Get-GatewayPid
$frpcProcs = Get-FrpcProcesses
$gatewayUp = $gatewayPid -gt 0
$frpcUp = $frpcProcs.Count -gt 0

if ($gatewayUp -and $frpcUp) {
    # 健康：完全静默退出，连日志都不写（这才是「安静」）
    if ($DryRun) {
        Write-Output "健康：gateway pid=$gatewayPid, frpc 数量=$($frpcProcs.Count)"
    }
    exit 0
}

Write-WatchdogLog ("不健康：gateway={0} frpc={1}；准备重启" -f $gatewayUp, $frpcUp)

if (-not (Test-Path $vbsLauncher)) {
    Write-WatchdogLog "中止：找不到启动器 $vbsLauncher（它应和本脚本同目录）"
    exit 1
}

if ($DryRun) {
    Write-Output "不健康：gateway=$gatewayUp frpc=$frpcUp"
    Write-Output "将会：清掉残留进程 → 用 wscript 拉起 $vbsLauncher"
    exit 0
}

# ── 只清自己的残留（不碰别的 node 进程）─────────────────────────
# 网关 PID 确认是 node 才杀：避免误杀用户其他 node 程序
if ($gatewayUp) {
    $proc = Get-Process -Id $gatewayPid -ErrorAction SilentlyContinue
    if ($proc -and $proc.ProcessName -eq 'node') {
        Stop-Process -Id $gatewayPid -Force -ErrorAction SilentlyContinue
        Write-WatchdogLog "已停止残留网关 node pid=$gatewayPid"
    } else {
        Write-WatchdogLog "端口 $GatewayPort 被非 node 进程占用（$($proc.ProcessName)），不动它"
    }
}
# frpc 只在「网关也挂了」时才清 —— 它是 run.mjs 的子进程，父进程没了它才没用。
# 单独网关挂掉（frpc 还活着）时**不要**杀它：那个 frpc 可能仍在正常转发，
# 杀掉只是平白多一次隧道重连（免费节点重连要几秒到几十秒）。
if (-not $gatewayUp) {
    foreach ($p in $frpcProcs) {
        Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue
        Write-WatchdogLog "已停止孤儿 frpc pid=$($p.Id) name=$($p.ProcessName)（网关已不在，父进程没了）"
    }
} elseif ($frpcProcs.Count -gt 0) {
    Write-WatchdogLog "保留现存 frpc（$($frpcProcs.Count) 个）：网关只是没在 $GatewayPort 监听，frpc 可能仍在工作"
}
Start-Sleep -Milliseconds 700

# ── 零窗口拉起 ─────────────────────────────────────────────────
# 关键：用 wscript 跑 .vbs，里面 sh.Run(...,0,False)（SW_HIDE，建都不建）。
#
# 这里调的是 wscript.exe 本身 —— GUI 子系统程序，本身没有控制台，
# 所以不存在 powershell -WindowStyle Hidden 那种「先建窗再藏」的问题。
# 真正的零窗口保证在 .vbs 的 Run(...,0,False) 里，这里只是双保险。
try {
    $wscript = Join-Path $env:WINDIR 'System32\wscript.exe'
    if (-not (Test-Path -LiteralPath $wscript)) { throw '找不到 wscript.exe' }
    Start-Process -FilePath $wscript -ArgumentList @('"' + $vbsLauncher + '"') -WindowStyle Hidden
    Write-WatchdogLog '已发出重启指令（wscript 零窗口）'
} catch {
    Write-WatchdogLog ('重启失败：' + $_.Exception.Message)
    exit 1
}

# ── 20 秒内确认恢复 ────────────────────────────────────────────
$deadline = (Get-Date).AddSeconds(20)
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 3
    if ((Get-GatewayPid) -gt 0 -and (Get-FrpcProcesses).Count -gt 0) {
        Write-WatchdogLog '已恢复：网关与 frpc 都在'
        exit 0
    }
}

Write-WatchdogLog ("恢复未完成：gateway={0} frpc={1}" -f ((Get-GatewayPid) -gt 0), ((Get-FrpcProcesses).Count -gt 0))
exit 1
