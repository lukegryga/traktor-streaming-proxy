' Starts the proxy with no console window, which the .bat launcher cannot do.
' Used both for manual starts and by the "Start with Windows" tray option.
Option Explicit

Dim fso, sh, appDir, javaw, candidate
Set fso = CreateObject("Scripting.FileSystemObject")
Set sh = CreateObject("WScript.Shell")

appDir = fso.GetParentFolderName(WScript.ScriptFullName)

javaw = "javaw.exe"
candidate = sh.ExpandEnvironmentStrings("%JAVA_HOME%")
If candidate <> "%JAVA_HOME%" Then
  If fso.FileExists(candidate & "\bin\javaw.exe") Then javaw = candidate & "\bin\javaw.exe"
End If

sh.CurrentDirectory = appDir
sh.Run """" & javaw & """ -cp """ & appDir & "\lib\*"" MainKt", 0, False
