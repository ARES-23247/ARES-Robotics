#!/bin/bash
echo "=============================================="
echo "ARESLib-Kotlin Deploy Script"
echo "=============================================="
echo ""

echo "Checking ADB device connectivity..."
if ! adb devices | grep -q "[0-9a-zA-Z.:-]*[[:space:]]\+device"; then
    echo "No devices currently connected. Attempting wireless connection to REV Control Hub (192.168.43.1:5555)..."
    adb connect 192.168.43.1:5555
    if ! adb devices | grep -q "[0-9a-zA-Z.:-]*[[:space:]]\+device"; then
        echo ""
        echo "[ERROR] No active Android devices or Control Hubs found!"
        echo "Please ensure:"
        echo "  1. Your laptop is connected to the Control Hub's Wi-Fi network (SSID: FTC-XXXX)."
        echo "  2. Or the Control Hub is connected to this computer via a USB cable."
        exit 1
    fi
fi

if [ -d "ftc-app" ]; then
    cd ftc-app || exit
    echo "Compiling and pushing to Control Hub from ftc-app..."
    ./gradlew installDebug
    BUILD_STATUS=$?
    cd ..
elif [ -f "./gradlew" ]; then
    echo "Compiling and pushing to Control Hub via :TeamCode:installDebug..."
    ./gradlew :TeamCode:installDebug
    BUILD_STATUS=$?
else
    echo "[ERROR] Neither ftc-app directory nor root gradlew wrapper found!"
    exit 1
fi

if [ $BUILD_STATUS -eq 0 ]; then
    echo ""
    echo "[SUCCESS] APK pushed to the robot."
    echo "Open the Driver Station, select 'ARES Mecanum', and run!"
else
    echo ""
    echo "[FAILED] Failed to build or deploy. Make sure your laptop is connected to the Control Hub's Wi-Fi."
fi
