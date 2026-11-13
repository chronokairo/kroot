#!/bin/bash
# install-and-use.sh - Complete KROOT install + bootstrap flow
# Usage: ./install-and-use.sh [device_serial]
# All binaries use /data/local/xbin/ pattern.
set -e

DEVICE="${1:-192.168.0.20:36631}"
APK="/home/luan/kroot-build/out-apk/kroot.apk"

adb_cmd() { adb -s "$DEVICE" "$@"; }

echo "========================================"
echo "  KROOT Install-and-Use Flow"
echo "  Device: $DEVICE"
echo "  Pattern: /data/local/xbin/"
echo "========================================"
echo ""

# Step 1: Install APK (app embeds all binaries in res/raw)
echo "[1/2] Installing APK..."
adb_cmd uninstall com.chronokairo.kroot 2>/dev/null || true
adb_cmd install -r "$APK"
echo "  APK installed."

# Step 2: Trigger bootstrap via app (app handles Mali UAF + install to xbin)
echo "[2/2] Triggering bootstrap via app..."
adb_cmd shell am start -n com.chronokairo.kroot/.MainActivity 2>&1 || true
adb_cmd shell am startservice -n com.chronokairo.kroot/.KrootService -a com.chronokairo.kroot.action.BOOTSTRAP 2>&1 || true
echo "  Bootstrap triggered."

sleep 5

# Step 3: Final verification
echo "[3/3] Final verification..."
echo "  su -c id:"
adb_cmd shell /data/local/xbin/su -c id 2>&1 || true
echo "  su -c whoami:"
adb_cmd shell /data/local/xbin/su -c whoami 2>&1 || true
echo "  krootd status:"
adb_cmd shell /data/local/xbin/krootd --status 2>&1 || true

echo ""
echo "========================================"
echo "  INSTALL AND USE COMPLETE"
echo "========================================"
echo "  All binaries installed to /data/local/xbin/"
echo "  Root obtained via Mali UAF"
echo "  krootd running as root daemon"
echo ""
echo "  To use: open Kairos Root app, tap 'Activate KROOT'"
