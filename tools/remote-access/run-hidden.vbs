' Zero-window launcher for the DSH Pal remote-access gateway.
' Per the workspace rule: NEVER use Start-Process -WindowStyle Hidden (creates then
' hides => flashes). WScript.Shell.Run with intWindowStyle=0 never maps a window.
' ASCII only: WSH reads .vbs as ANSI, so non-ASCII paths break compilation silently.
Option Explicit
Dim sh, fso, here, cmd
Set sh  = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")
here = fso.GetParentFolderName(WScript.ScriptFullName)
cmd  = "cmd /c cd /d """ & here & """ && ""C:\Program Files\nodejs\node.exe"" run.mjs >> """ & here & "\gateway-restart.log"" 2>&1"
sh.Run cmd, 0, False
