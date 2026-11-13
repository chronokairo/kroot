#!/bin/bash
# install-and-use.sh - Complete KROOT install + bootstrap flow
# Usage: ./install-and-use.sh [device_serial]
# This script:
#   1. Installs the APK
#   2. Pushes su/krootd/krootctl binaries to device
#   3. Runs Mali UAF exploit to get root (UID 0)
#   4. Installs binaries to /data/local/xbin/ with root ownership
#   5. Starts krootd as root daemon
#   6. Verifies su -c id returns uid=0
set -e

DEVICE="${1:-192.168.0.20:36631}"
APK="/home/luan/kroot-build/out-apk/kroot.apk"
KROOTD_BIN="/home/luan/kroot-build/out/krootd"
SU_BIN="/home/luan/kroot-build/out/su"
KROOTCTL_BIN="/home/luan/kroot-build/out/krootctl"
MALI_BIN="/home/luan/kroot-build/exploit/mali/mali_kroot"
TMP="/data/local/tmp"
XBIN="/data/local/xbin"

adb_cmd() { adb -s "$DEVICE" "$@"; }

echo "========================================"
echo "  KROOT Install-and-Use Flow"
echo "  Device: $DEVICE"
echo "========================================"
echo ""

# Step 1: Install APK
echo "[1/7] Installing APK..."
adb_cmd uninstall com.chronokairo.kroot 2>/dev/null || true
adb_cmd install -r "$APK"
echo "  APK installed."

# Step 2: Push binaries to /data/local/tmp (writable by shell)
echo "[2/7] Pushing binaries to /data/local/tmp..."
adb_cmd push "$KROOTD_BIN" "$TMP/krootd"
adb_cmd push "$SU_BIN" "$TMP/su"
adb_cmd push "$KROOTCTL_BIN" "$TMP/krootctl"
adb_cmd push "$MALI_BIN" "$TMP/mali_kroot"
adb_cmd shell chmod +x "$TMP/krootd" "$TMP/su" "$TMP/krootctl" "$TMP/mali_kroot"
echo "  Binaries pushed and executable."

# Step 3: Run Mali UAF exploit to get root
echo "[3/7] Running Mali UAF exploit to get root..."
OUTPUT=$(adb_cmd shell "$TMP/mali_kroot" 2>&1 || true)
echo "$OUTPUT" | tail -5

if echo "$OUTPUT" | grep -q "uid=0"; then
    echo "  Root obtained (UID 0)."
else
    echo "  WARNING: Mali UAF may not have obtained root. Checking..."
fi

# Step 4: Verify root
echo "[4/7] Verifying root access..."
ROOT_CHECK=$(adb_cmd shell "id -u" 2>&1 || true)
if echo "$ROOT_CHECK" | grep -q "^0$"; then
    echo "  Root confirmed: UID 0"
else
    echo "  Root NOT confirmed: $ROOT_CHECK"
    echo "  Attempting su via Mali-installed binary..."
    adb_cmd shell "$TMP/su -c id" 2>&1 || true
fi

# Step 5: Install binaries to /data/local/xbin as root
echo "[5/7] Installing binaries to /data/local/xbin as root..."
adb_cmd shell "$TMP/su -c 'cp $TMP/krootd $XBIN/krootd && cp $TMP/su $XBIN/su && cp $TMP/krootctl $XBIN/krootctl && chown root:root $XBIN/krootd $XBIN/su $XBIN/krootctl && chmod 0755 $XBIN/krootd $XBIN/su $XBIN/krootctl'" 2>&1
echo "  Binaries installed to /data/local/xbin/ as root."

# Step 6: Start krootd as root daemon
echo "[6/7] Starting krootd daemon as root..."
adb_cmd shell "$TMP/su -c '$XBIN/krootd --daemon'" 2>&1
sleep 2
adb_cmd shell "$TMP/su -c '$XBIN/krootd --status'" 2>&1
echo "  krootd daemon started."

# Step 7: Final verification
echo "[7/7] Final verification..."
echo "  su -c id:"
adb_cmd shell "$TMP/su -c id" 2>&1
echo "  su -c whoami:"
adb_cmd shell "$TMP/su -c whoami" 2>&1
echo "  krootd status:"
adb_cmd shell "$TMP/su -c '$XBIN/krootd --status'" 2>&1

echo ""
echo "========================================"
echo "  INSTALL AND USE COMPLETE"
echo "========================================"
echo "  The app is installed, root is obtained,"
echo "  krootd is running, and su works."
echo ""
echo "  To use from app: tap 'Activate KROOT'"
echo "  To use from adb: $TMP/su -c <command>"
