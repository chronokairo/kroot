#!/bin/sh
# build.sh - compila krootd, su e krootctl para arm64 Android
set -e

NDK="${ANDROID_NDK:-$HOME/android-sdk/ndk/27.0.12077973}"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
CC="$TOOLCHAIN/aarch64-linux-android26-clang"

if [ ! -x "$CC" ]; then
    echo "erro: NDK nao encontrado em $NDK" >&2
    echo "defina ANDROID_NDK ou instale o NDK 27" >&2
    exit 1
fi

CFLAGS="-O2 -Wall -Wextra -static -fno-stack-protector -ffunction-sections -fdata-sections"
LDFLAGS="-Wl,--gc-sections"

mkdir -p out

echo "[*] krootd"
$CC $CFLAGS $LDFLAGS -o out/krootd krootd.c common.c

echo "[*] su"
$CC $CFLAGS $LDFLAGS -o out/su su.c common.c

echo "[*] krootctl"
$CC $CFLAGS $LDFLAGS -o out/krootctl krootctl.c common.c

echo "[*] ok:"
ls -lh out
