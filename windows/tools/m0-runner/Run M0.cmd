@echo off
rem SkyWing M0 handheld runs: section 4 of windows\docs\modernise\M0-RESULTS.md. Read README.txt first.
rem "Run M0.cmd trial" does a short check of the runner instead. SKYWING_M0_NO_PAUSE=1 skips the pause at the end
rem (for a start from a script).
setlocal
title SkyWing M0 runs
chcp 65001 >nul 2>&1
cd /d "%~dp0"
if not exist "data" mkdir "data"
if not defined SKYWING_TEST_IMAGE set "SKYWING_TEST_IMAGE=%ProgramFiles%\SkyWing"
"%~dp0runtime\bin\java.exe" -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -Djava.util.prefs.PreferencesFactory=com.skywing.testsupport.IsolatedPreferencesFactory "-Dskywing.test.preferencesFile=%~dp0data\preferences.properties" -cp "%~dp0lib\*" com.skywing.desktop.automation.M0HandheldKt %*
set "code=%errorlevel%"
echo.
if not defined SKYWING_M0_NO_PAUSE pause
exit /b %code%
