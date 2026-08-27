#!/bin/bash
# Move to the directory containing this script
cd "$(dirname "$0")"
echo "=============================================="
echo "  ARES 23247 - macOS Simulator Launcher"
echo "=============================================="
echo ""

# Check if the Sim Launcher Daemon is already running on port 8080
if ! lsof -i :8080 -sTCP:LISTEN -t >/dev/null 2>&1; then
    echo "[Launcher] Sim Launcher Daemon is not running. Starting daemon in background..."
    node ./tools/sim-launcher-daemon/daemon.js >/dev/null 2>&1 &
    sleep 2
else
    echo "[Launcher] Sim Launcher Daemon is already running."
fi

echo "Starting ARES Desktop Simulation..."
chmod +x ./gradlew
./gradlew :simulator:run
echo ""
echo "Simulator closed. Press Enter to exit..."
read
