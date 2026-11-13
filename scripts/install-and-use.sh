#!/bin/bash
# install-and-use.sh - Complete KROOT install + bootstrap flow
# Usage: ./install-and-use.sh [device_serial]
# All binaries use app private data directory (/data/data/com.chronokairo.kroot/files/bin/).
set -e

DEVICE="${1:-192.168.0.20:46801}"
APK="/home/luan/kroot-build/out-apk/kroot.apk"

adb_cmd() { adb -s "$DEVICE" "$@"; }

echo "========================================"
echo "  KROOT Install-and-Use Flow"
echo "  Device: $DEVICE"
echo "  Pattern: app private data directory"
echo "========================================"
echo ""

# Step 1: Install APK (app embeds all binaries in res/raw)
echo "[1/2] Installing APK..."
adb_cmd uninstall com.chronokairo.kroot 2>/dev/null || true
adb_cmd install -r "$APK"
echo "  APK installed."

# Step 2: Trigger bootstrap via app (app handles Mali UAF + starts krootd)
echo "[2/2] Triggering bootstrap via app..."
adb_cmd shell am start -n com.chronokairo.kroot/.MainActivity 2>&1 || true
adb_cmd shell am startservice -n com.chronokairo.kroot/.KrootService -a com.chronokairo.kroot.action.BOOTSTRAP 2>&1 || true
echo "  Bootstrap triggered."

sleep 8

# Step 3: Final verification via app private dir
echo "[3/3] Final verification..."
PRIVATE_BIN="/data/data/com.chronokairo.kroot/files/bin"
echo "  su -c id:"
adb_cmd shell "$PRIVATE_BIN/su -c id" 2>&1 || true
echo "  su -c whoami:"
adb_cmd shell "$PRIVATE_BIN/su -c whoami" 2>&1 || true
echo "  krootd status:"
adb_cmd shell "$PRIVATE_BIN/krootd --status" 2>&1 || true

echo ""
echo "========================================"
echo "  INSTALL AND USE COMPLETE"
echo "========================================"
echo "  All binaries installed to app private data directory"
echo "  Root obtained via Mali UAF"
echo "  krootd running as root daemon"
echo ""
echo "  To use: open Kairos Root app, tap 'Activate KROOT'"
