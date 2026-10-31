param([switch]$DryRun)
$ErrorActionPreference='Stop'
$taskName='DSH-Pal-Remote-Access'
$nodePath=(Get-Command node.exe -ErrorAction Stop).Source
$launcher=Join-Path $PSScriptRoot 'start-hidden.ps1'
$arguments='-NoProfile -NonInteractive -WindowStyle Hidden -File "'+$launcher+'" -NodePath "'+$nodePath+'"'
$powershellPath=Join-Path $env:WINDIR 'System32\WindowsPowerShell\v1.0\powershell.exe'
$existing=Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
if($existing -and (@($existing.Actions).Count -ne 1 -or $existing.Actions[0].Arguments -ne $arguments -or $existing.Actions[0].Execute -ne $powershellPath)){
  throw 'A different task already uses this name; it was not replaced'
}
if($DryRun){Write-Output ('Task: '+$taskName);Write-Output ('Action: powershell.exe '+$arguments);exit 0}
$identity=[Security.Principal.WindowsIdentity]::GetCurrent().Name
$action=New-ScheduledTaskAction -Execute $powershellPath -Argument $arguments -WorkingDirectory $PSScriptRoot
$trigger=New-ScheduledTaskTrigger -AtLogOn -User $identity
$principal=New-ScheduledTaskPrincipal -UserId $identity -LogonType Interactive -RunLevel Limited
$settings=New-ScheduledTaskSettingsSet -Hidden -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -MultipleInstances IgnoreNew -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1)
$null=Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger -Principal $principal -Settings $settings -Force
Write-Output 'Current-user logon task installed. It does not modify DSH or VPN startup tasks.'
