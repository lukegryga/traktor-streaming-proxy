@echo off
rem Makes a desktop and a Start menu shortcut for this folder, with the app icon, which is the one
rem thing the old installer did that unzipping does not. Run it again after moving the folder; it
rem overwrites what it made before.
setlocal
set "APPDIR=%~dp0"
set "APPDIR=%APPDIR:~0,-1%"

rem WindowStyle 7 is minimized, so the launcher's own console window never shows on screen.
powershell -NoProfile -Command "$w = New-Object -ComObject WScript.Shell; foreach ($dir in @([Environment]::GetFolderPath('Desktop'), (Join-Path $env:APPDATA 'Microsoft\Windows\Start Menu\Programs'))) { $link = $w.CreateShortcut((Join-Path $dir 'Traktor Streaming Proxy.lnk')); $link.TargetPath = '%APPDIR%\TraktorProxy.cmd'; $link.WorkingDirectory = '%APPDIR%'; $link.IconLocation = '%APPDIR%\traktor-forwarder.ico'; $link.Description = 'Stream Spotify, YouTube and Tidal in Traktor DJ'; $link.WindowStyle = 7; $link.Save() }"

if errorlevel 1 (
    echo Could not create the shortcuts.
) else (
    echo Created "Traktor Streaming Proxy" on the desktop and in the Start menu.
)
echo.
pause
