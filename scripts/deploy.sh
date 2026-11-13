#!/bin/sh
# deploy.sh - envia os binarios para o aparelho e instala (roda no build host)
set -e

HOST="${KROOT_HOST:-root@192.168.0.20}"
PORT="${KROOT_PORT:-2222}"
DEST=/data/local/tmp/kroot-build

ssh -p "$PORT" -o StrictHostKeyChecking=accept-new "$HOST" "mkdir -p $DEST"
scp -P "$PORT" out/krootd out/su out/krootctl scripts/install-device.sh "$HOST:$DEST/"
ssh -p "$PORT" "$HOST" "sh $DEST/install-device.sh"
