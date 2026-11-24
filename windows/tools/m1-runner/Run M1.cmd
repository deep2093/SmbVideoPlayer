@echo off
rem SkyWing M1 before/after measurements: windows\docs\modernise\M1-RESULTS.md. Read README.txt first.
rem "Run M1.cmd trial" does a short check of the runner instead. SKYWING_M1_NO_PAUSE=1 skips the pause at the end
rem (for a start from a script).
setlocal
title SkyWing M1 measurements
chcp 65001 >nul 2>&1
cd /d "%~dp0"
if not exist "data" mkdir "data"
"%~dp0runtime\bin\java.exe" -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -Djava.util.prefs.PreferencesFactory=com.skywing.testsupport.IsolatedPreferencesFactory "-Dskywing.test.preferencesFile=%~dp0data\preferences.properties" -cp "%~dp0lib\*" com.skywing.desktop.automation.M1MeasurementKt %*
set "code=%errorlevel%"
echo.
if not defined SKYWING_M1_NO_PAUSE pause
exit /b %code%
