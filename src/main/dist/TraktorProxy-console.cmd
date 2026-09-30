@echo off
rem The same launcher, in the foreground, so the log goes to this window. For when something is
rem wrong and the tray icon never appears.
call "%~dp0TraktorProxy.cmd" console
