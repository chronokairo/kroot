#!/bin/sh
# deploy.sh - envia os binarios para o aparelho e instala (roda no build host)
# Instala direto no Android host via adb, nao pelo SSH do chroot Ubuntu (porta 2222).
set -e

DEVICE="${KROOT_DEVICE:-192.168.0.20:5555}"
DEST=/data/local/tmp/kroot-build

echo "[*] Conectando no device $DEVICE..."
adb -s "$DEVICE" shell "mkdir -p $DEST"

echo "[*] Enviando binarios..."
adb -s "$DEVICE" push out/krootd out/su out/krootctl scripts/install-device.sh "$DEST/"

echo "[*] Instalando no Android host (precisa de root via bootstrap Mali)..."
adb -s "$DEVICE" shell "sh $DEST/install-device.sh"

echo "[*] Deploy concluido."
