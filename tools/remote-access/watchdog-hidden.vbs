' Zero-window launcher for remote-access-watchdog.ps1.
'
' Why this file exists: Task Scheduler running powershell.exe directly creates
' a console window FIRST and only then hides it, so the user sees a black box
' flash every cycle. wscript.exe is a GUI-subsystem host with no console at all;
' Run(...,0,False) starts the child with SW_HIDE, so nothing is shown.
'
' ASCII ONLY: WSH reads .vbs as ANSI(GBK). Everything resolves from
' WScript.ScriptFullName so the folder can live anywhere.
Option Explicit

Dim sh, fso, here, script, ps, cmd
Set sh  = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")

here = fso.GetParentFolderName(WScript.ScriptFullName)
script = here & "\remote-access-watchdog.ps1"
ps = sh.ExpandEnvironmentStrings("%WINDIR%") & "\System32\WindowsPowerShell\v1.0\powershell.exe"

If Not fso.FileExists(script) Then WScript.Quit 1
If Not fso.FileExists(ps) Then WScript.Quit 2

' Style 0 = SW_HIDE: no window is ever created.
cmd = """" & ps & """ -NoProfile -NonInteractive -ExecutionPolicy Bypass -File """ & script & """"
sh.Run cmd, 0, False
