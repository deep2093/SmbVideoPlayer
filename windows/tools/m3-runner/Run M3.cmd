@echo off
rem SkyWing M3 checks: windows\docs\modernise\M3-RUNNER.md. Read README.txt first.
rem "Run M3.cmd trial" does a short check of the runner instead. Words name the checks to run, for example
rem "Run M3.cmd pages window" (numbers, pages, window, settings; all of them when none is named).
rem SKYWING_M3_NO_PAUSE=1 skips the pause at the end (for a start from a script).
setlocal
title SkyWing M3 checks
chcp 65001 >nul 2>&1
cd /d "%~dp0"
if not exist "data" mkdir "data"
"%~dp0runtime\bin\java.exe" -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -Djava.util.prefs.PreferencesFactory=com.skywing.testsupport.IsolatedPreferencesFactory "-Dskywing.test.preferencesFile=%~dp0data\preferences.properties" -cp "%~dp0lib\*" com.skywing.desktop.automation.M3RunnerKt %*
set "code=%errorlevel%"
echo.
if not defined SKYWING_M3_NO_PAUSE pause
exit /b %code%
