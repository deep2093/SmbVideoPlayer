@echo off
rem SkyWing checks: windows\docs\modernise\CHECKS-RUNNER.md. Read README.txt first.
rem With nothing after it, it does the full-screen and stress checks. "Run checks.cmd soak" does the 2-hour soak,
rem "Run checks.cmd scan "D:\Videos"" the folder scan, "Run checks.cmd trial" a short check of the runner instead.
rem SKYWING_CHECKS_NO_PAUSE=1 skips the pause at the end (for a start from a script).
setlocal
title SkyWing checks
chcp 65001 >nul 2>&1
cd /d "%~dp0"
if not exist "data" mkdir "data"
"%~dp0runtime\bin\java.exe" -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -Djava.util.prefs.PreferencesFactory=com.skywing.testsupport.IsolatedPreferencesFactory "-Dskywing.test.preferencesFile=%~dp0data\preferences.properties" -cp "%~dp0lib\*" com.skywing.desktop.automation.ChecksRunnerKt %*
set "code=%errorlevel%"
echo.
if not defined SKYWING_CHECKS_NO_PAUSE pause
exit /b %code%
