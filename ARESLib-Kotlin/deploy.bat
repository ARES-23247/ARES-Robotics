@echo off
echo ==============================================
echo ARESLib-Kotlin Deploy Script
echo ==============================================
echo.

:: Add local Android SDK platform-tools to path
set PATH=%PATH%;%LOCALAPPDATA%\Android\Sdk\platform-tools

echo Checking ADB device connectivity...
adb devices > temp_adb.txt
findstr /c:"	device" temp_adb.txt > nul
if %ERRORLEVEL% EQU 0 goto device_ok

echo No devices currently connected. Attempting wireless connection to REV Control Hub [192.168.43.1:5555]...
adb connect 192.168.43.1:5555
adb devices > temp_adb.txt
findstr /c:"	device" temp_adb.txt > nul
if %ERRORLEVEL% EQU 0 goto device_ok

echo.
echo [ERROR] No active Android devices or Control Hubs found!
echo Please ensure:
echo   1. Your laptop is connected to the Control Hub's Wi-Fi network [SSID: FTC-XXXX].
echo   2. Or the Control Hub is connected to this computer via a USB cable.
del temp_adb.txt
exit /b 1

:device_ok
del temp_adb.txt

echo Compiling and pushing to Control Hub...
call gradlew.bat :TeamCode:installDebug

if %ERRORLEVEL% EQU 0 (
    echo.
    echo [SUCCESS] APK pushed to the robot.
    echo Open the Driver Station, select "ARES Mecanum", and run!
) else (
    echo.
    echo [FAILED] Failed to build or deploy. Make sure your laptop is connected to the Control Hub's Wi-Fi.
)
