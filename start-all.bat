@echo off
title Get Jobs Launcher
echo ===================================================
echo           Starting Get Jobs Services
echo ===================================================

:: Configure Java 21 path
set JAVA_HOME=D:\java21
set PATH=%JAVA_HOME%\bin;%PATH%

:: Automatically kill any process occupying port 8888 to prevent boot failure
echo [0/2] Checking and clearing port 8888...
for /f "tokens=5" %%a in ('netstat -aon ^| findstr :8888 ^| findstr LISTENING') do (
    echo Terminating process %%a occupying port 8888...
    taskkill /f /pid %%a >nul 2>&1
)

echo [1/2] Launching backend (Spring Boot)...
start "Get Jobs - Backend" cmd /k "chcp 65001 >nul && cd /d %~dp0 && title Get Jobs - Backend && gradlew.bat bootRun"

echo [2/2] Launching frontend (Next.js)...
start "Get Jobs - Frontend" cmd /k "chcp 65001 >nul && cd /d %~dp0front && title Get Jobs - Frontend && npm run dev"

echo ===================================================
echo  Services have been launched in separate windows.
echo  You can close this launcher window now.
echo ===================================================
pause
