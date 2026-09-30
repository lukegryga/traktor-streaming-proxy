@echo off
rem Starts the proxy.
rem
rem With no argument it starts detached and without a console window, which is what the desktop
rem shortcut, the startup entry and the in-app restart all use. The window this script itself runs
rem in closes within a moment. Pass "console" to run it in the foreground instead and watch the
rem output; TraktorProxy-console.cmd does exactly that.
rem
rem This build carries no runtime of its own, so it looks for an installed one: JAVA_HOME first,
rem then PATH.
setlocal
set "HERE=%~dp0"
set "APPDIR=%HERE:~0,-1%"

if /i "%~1"=="console" (set "CONSOLE=1") else (set "CONSOLE=")

rem The directory rather than the executable: the version below is probed with java.exe even when
rem the launch uses javaw.exe, because javaw has no console to write its version to.
set "JAVA_DIR="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" for %%d in ("%JAVA_HOME%\bin\") do set "JAVA_DIR=%%~fd"
if not defined JAVA_DIR for %%j in (java.exe) do if not "%%~$PATH:j"=="" set "JAVA_DIR=%%~dp$PATH:j"

if not defined JAVA_DIR (
    echo Java was not found.
    echo.
    echo This build has no runtime of its own and needs Java 17 or newer installed:
    echo.
    echo     winget install -e --id EclipseAdoptium.Temurin.21.JRE
    echo.
    echo or download one from https://adoptium.net/ and start this again.
    echo.
    pause
    exit /b 1
)

rem Without this an older Java fails on the class file version with a stack trace and no hint,
rem and in silent mode with no visible window at all.
set "VERSION="
for /f "tokens=3" %%v in ('cd /d "%JAVA_DIR%" ^&^& java.exe -version 2^>^&1 ^| findstr /i version') do if not defined VERSION set "VERSION=%%v"
set "VERSION=%VERSION:"=%"
for /f "delims=.-+_ " %%m in ("%VERSION%") do set "MAJOR=%%m"

if defined MAJOR if %MAJOR% LSS 17 (
    echo Java %VERSION% is too old; 17 or newer is needed.
    echo.
    echo Found in %JAVA_DIR%
    echo.
    echo     winget install -e --id EclipseAdoptium.Temurin.21.JRE
    echo.
    pause
    exit /b 1
)

set "LAUNCH=%JAVA_DIR%javaw.exe"
if not exist "%LAUNCH%" set "LAUNCH=%JAVA_DIR%java.exe"

if defined CONSOLE (
    "%JAVA_DIR%java.exe" -cp "%HERE%lib\*" MainKt
    echo.
    echo The proxy has stopped.
    pause
    exit /b 0
)

start "" /d "%APPDIR%" "%LAUNCH%" -cp "%HERE%lib\*" MainKt
