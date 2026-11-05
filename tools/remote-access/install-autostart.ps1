param([switch]$DryRun)

<#
    安装「网关自启」+「看门狗」两个计划任务。

    ── 为什么用 wscript + .vbs，不用 powershell -WindowStyle Hidden ──
    `-WindowStyle Hidden` 是「先创建控制台窗口，再隐藏它」—— 每次触发都会闪一个
    黑窗口。项目红线明确禁止任何控制台窗口（含一闪而过的闪窗）。
    wscript.exe 是 GUI 子系统宿主，本身没有控制台；.vbs 里用 sh.Run(cmd, 0, False)
    以 SW_HIDE 启动子进程，窗口从头到尾不会被创建，所以不会闪。

    ── 两个任务分别干什么 ──
    1. DSH-Pal-Remote-Access         登录时启动网关（一次性触发）
    2. DSH-Pal-Remote-Access-Watchdog 每 5 分钟检查一次，掉了自动拉起

    只有第 1 个的话：睡眠唤醒 / 注销 / 崩溃之后就没人管了，
    公网入口会一直 ERR_CONNECTION_REFUSED 到用户下次登录。

    用法：
      .\install-autostart.ps1 -DryRun    # 只打印会建什么，不真装
      .\install-autostart.ps1            # 真装
#>

$ErrorActionPreference = 'Stop'

$taskName = 'DSH-Pal-Remote-Access'
$watchdogTaskName = 'DSH-Pal-Remote-Access-Watchdog'
$gwVbs = Join-Path $PSScriptRoot 'run-autostart-hidden.vbs'
$watchdogVbs = Join-Path $PSScriptRoot 'watchdog-hidden.vbs'
$wscriptPath = Join-Path $env:WINDIR 'System32\wscript.exe'

if (-not (Test-Path -LiteralPath $wscriptPath)) {
    throw "找不到 wscript.exe：$wscriptPath"
}
if (-not (Test-Path -LiteralPath $gwVbs)) {
    throw "找不到网关启动器：$gwVbs（应与本脚本同目录）"
}
if (-not (Test-Path -LiteralPath $watchdogVbs)) {
    throw "找不到看门狗启动器：$watchdogVbs（应与本脚本同目录）"
}

$gwArgs = '"' + $gwVbs + '"'
$wdArgs = '"' + $watchdogVbs + '"'
$identity = [Security.Principal.WindowsIdentity]::GetCurrent().Name

# ══════════════════════════════════════════════════════════════
# 任务 1：登录时启动网关
# ══════════════════════════════════════════════════════════════
$skipGateway = $false
$existing = Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
if ($existing) {
    $action = @($existing.Actions)[0]
    $sameAction = ($action.Execute -eq $wscriptPath) -and ($action.Arguments -eq $gwArgs)
    if (-not $sameAction) {
        # 名字被别的任务占了（可能是用户自己装的），不强行替换
        Write-Output "注意：已存在同名任务 '$taskName'，但动作不同，跳过不覆盖。"
        Write-Output ("  现有动作: " + $action.Execute + " " + $action.Arguments)
        Write-Output ("  本脚本要求: " + $wscriptPath + " " + $gwArgs)
        Write-Output ''
        if (-not $DryRun) {
            throw ("已存在同名任务 '$taskName' 但动作不同，为避免破坏现有配置未替换。" +
                   "如果确认要改用本脚本的管理方式，先手动删除该任务再重跑。")
        }
        $skipGateway = $true
    }
}

if ($DryRun) {
    Write-Output '=== DryRun：不会真的注册任何任务 ==='
    Write-Output ''
    if (-not $skipGateway) {
        Write-Output '[任务 1] 网关自启'
        Write-Output ("  任务名  : " + $taskName)
        Write-Output ("  动作    : " + $wscriptPath + " " + $gwArgs)
        Write-Output ("  工作目录: " + $PSScriptRoot)
        Write-Output "  触发器  : 当前用户登录时（AtLogOn）"
        Write-Output "  设置    : 隐藏、允许电池供电、执行时限无限、失败重试 3 次/1 分钟"
        Write-Output ''
    }
    Write-Output '[任务 2] 网关看门狗'
    Write-Output ("  任务名  : " + $watchdogTaskName)
    Write-Output ("  动作    : " + $wscriptPath + " " + $wdArgs)
    Write-Output "  触发器  : 登录时 + 每 5 分钟重复，持续无限"
    Write-Output "  行为    : 健康时静默（不写日志）；不健康才重启并记一行"
    Write-Output ("  日志    : " + (Join-Path $env:LOCALAPPDATA 'DSHPal\remote-access\watchdog.log'))
    Write-Output ''
    Write-Output '为什么用 wscript：powershell -WindowStyle Hidden 会闪黑窗，wscript 不会。'
    exit 0
}

if (-not $skipGateway) {
    $gwAction = New-ScheduledTaskAction -Execute $wscriptPath -Argument $gwArgs -WorkingDirectory $PSScriptRoot
    $gwTrigger = New-ScheduledTaskTrigger -AtLogOn -User $identity
    $gwPrincipal = New-ScheduledTaskPrincipal -UserId $identity -LogonType Interactive -RunLevel Limited
    $gwSettings = New-ScheduledTaskSettingsSet -Hidden -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
        -MultipleInstances IgnoreNew -ExecutionTimeLimit ([TimeSpan]::Zero) `
        -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1)

    $null = Register-ScheduledTask -TaskName $taskName -Action $gwAction -Trigger $gwTrigger `
        -Principal $gwPrincipal -Settings $gwSettings -Force

    Write-Output "已安装 [任务 1]：登录时自动启动网关（零窗口）。"
}

# ══════════════════════════════════════════════════════════════
# 任务 2：看门狗（每 5 分钟检查，掉了自动拉起）
# ══════════════════════════════════════════════════════════════
$wdAction = New-ScheduledTaskAction -Execute $wscriptPath -Argument $wdArgs -WorkingDirectory $PSScriptRoot

# 登录时触发 + 每 5 分钟重复。
# 注意 RepetitionDuration 必须给很长的值（这里 3650 天）：
# 用 PT23H 这类短值会在第二天就失效 —— 这是本项目踩过的坑。
$wdTrigger = New-ScheduledTaskTrigger -AtLogOn -User $identity
$onceTrigger = New-ScheduledTaskTrigger -Once -At (Get-Date) `
    -RepetitionInterval (New-TimeSpan -Minutes 5) `
    -RepetitionDuration (New-TimeSpan -Days 3650)
$wdTrigger.Repetition = $onceTrigger.Repetition

$wdPrincipal = New-ScheduledTaskPrincipal -UserId $identity -LogonType Interactive -RunLevel Limited
# 看门狗自己不该被杀；MultipleInstances=IgnoreNew 保证不并发
$wdSettings = New-ScheduledTaskSettingsSet -Hidden -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries -MultipleInstances IgnoreNew `
    -ExecutionTimeLimit ([TimeSpan]::Zero)

$null = Register-ScheduledTask -TaskName $watchdogTaskName -Action $wdAction `
    -Trigger $wdTrigger -Principal $wdPrincipal -Settings $wdSettings -Force

Write-Output "已安装 [任务 2]：看门狗，每 5 分钟检查一次（健康时完全静默）。"

Write-Output ''
Write-Output '怎么验证装对了（三条都要过）：'
Write-Output ''
Write-Output '① 两个任务的 Execute 都必须是 wscript.exe：'
Write-Output '   Get-ScheduledTask -TaskName "DSH-Pal-Remote-Access*" | ForEach-Object { $_.Actions }'
Write-Output '   ❌ 出现 powershell.exe / cmd.exe → 会闪黑窗，装错了'
Write-Output ''
Write-Output '② 状态都是 Ready：'
Write-Output '   Get-ScheduledTask -TaskName "DSH-Pal-Remote-Access*" | Select-Object TaskName,State'
Write-Output ''
Write-Output '③ 实测看门狗能识别健康状态（不改任何东西）：'
Write-Output ('   powershell -NoProfile -ExecutionPolicy Bypass -File "' + (Join-Path $PSScriptRoot 'remote-access-watchdog.ps1') + '" -DryRun')
Write-Output '   期望：健康：gateway pid=xxx, frpc 数量=N'
Write-Output ''
Write-Output '注意：本脚本只装网关自启和看门狗，不修改 DSH 或 VPN 的开机启动设置。'
