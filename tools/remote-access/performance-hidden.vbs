' Zero-window performance sampler. Arguments contain only an installed script path and a random pipe name.
Option Explicit
If WScript.Arguments.Count <> 2 Then WScript.Quit 2
Dim shell, script, pipe
Set shell = CreateObject("WScript.Shell")
script = WScript.Arguments(0)
pipe = WScript.Arguments(1)
Function Quoted(value)
  Quoted = Chr(34) & Replace(value, Chr(34), Chr(34) & Chr(34)) & Chr(34)
End Function
shell.Run "powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File " & Quoted(script) & " -PipeName " & Quoted(pipe), 0, False
