@echo off
title ARES Simulator Launcher
echo ==============================================
echo   ARES 23247 - Simulator Launcher
echo ==============================================
echo.

:: Configure JDK 17 environment if present (matches daemon settings)
if exist "C:\Program Files\Java\jdk-17" (
    set "JAVA_HOME=C:\Program Files\Java\jdk-17"
    set "PATH=C:\Program Files\Java\jdk-17\bin;%PATH%"
    echo [Launcher] Configured local JDK 17 environment.
)

:: Check if the Sim Launcher Daemon is already running on port 8080
netstat -ano | findstr LISTENING | findstr :8080 >nul
if %errorlevel% neq 0 (
    echo [Launcher] Sim Launcher Daemon is not running. Starting daemon in background...
    start /min "ARES Sim Daemon" node "%~dp0tools\sim-launcher-daemon\daemon.js"
    timeout /t 2 >nul
) else (
    echo [Launcher] Sim Launcher Daemon is already running.
)

echo Starting ARES Desktop Simulation...
cd /d "%~dp0"
call gradlew.bat :simulator:run
echo.
echo Simulator closed.
pause
