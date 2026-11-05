' Zero-window launcher for the DSH Pal remote-access gateway (run.mjs).
'
' WHY THIS EXISTS (and why the old install-autostart.ps1 was wrong):
' The previous task action was
'     powershell.exe -NoProfile -WindowStyle Hidden -File start-hidden.ps1
' `-WindowStyle Hidden` CREATES the console window and only then hides it --
' so every logon flashed a black window. The project rule forbids ANY console
' window, including a flash. WScript.Shell.Run with intWindowStyle=0 never
' maps a window at all, so that is the only correct way.
'
' ASCII ONLY: WSH reads .vbs as ANSI(GBK); non-ASCII paths would break it
' silently. Everything below resolves from WScript.ScriptFullName.
Option Explicit

Dim sh, fso, here, nodePath, runner, cmd, logFile
Set sh  = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")

here = fso.GetParentFolderName(WScript.ScriptFullName)
runner = here & "\run.mjs"
logFile = here & "\gateway-autostart.log"

If Not fso.FileExists(runner) Then WScript.Quit 3

' Resolve node.exe: scheduled tasks may not inherit the interactive PATH.
nodePath = ""
Dim candidates, candidate
candidates = Array( _
  sh.ExpandEnvironmentStrings("%ProgramFiles%\nodejs\node.exe"), _
  sh.ExpandEnvironmentStrings("%ProgramFiles(x86)%\nodejs\node.exe"), _
  sh.ExpandEnvironmentStrings("%LOCALAPPDATA%\Programs\nodejs\node.exe") )
For Each candidate In candidates
  If nodePath = "" And fso.FileExists(candidate) Then nodePath = candidate
Next

' Fall back to whatever is on PATH.
If nodePath = "" Then
  On Error Resume Next
  nodePath = sh.RegRead("HKLM\SOFTWARE\Node.js\InstallPath") & "node.exe"
  On Error GoTo 0
End If
If nodePath = "" Or Not fso.FileExists(nodePath) Then WScript.Quit 4

' Style 0 = SW_HIDE: the window is never created, so nothing can flash.
cmd = "cmd /c cd /d """ & here & """ && """ & nodePath & """ """ & runner & """ >> """ & logFile & """ 2>&1"
sh.Run cmd, 0, False
