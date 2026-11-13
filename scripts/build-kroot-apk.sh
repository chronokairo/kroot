#!/bin/bash
# build-kroot-apk.sh - compila o APK Kairos Root launcher
# Rodar no build host 0.16 (WSL Ubuntu)

set -e

ANDROID_HOME="$HOME/android-sdk"
BUILD_TOOLS="$ANDROID_HOME/build-tools/34.0.0"
PLATFORM="$ANDROID_HOME/platforms/android-34"

SRC="/mnt/c/Users/luann/kroot/app/src/main"
OUT="/mnt/c/Users/luann/kroot/out-apk"

AAPT2="$BUILD_TOOLS/aapt2"
JAVAC=$(which javac)
D8="$BUILD_TOOLS/d8"
APKSIGNER="$BUILD_TOOLS/apksigner"
ZIPALIGN="$BUILD_TOOLS/zipalign"
KEYTOOL=$(which keytool)

echo "[*] Verificando ferramentas..."
for t in "$AAPT2" "$JAVAC" "$D8" "$APKSIGNER" "$ZIPALIGN" "$KEYTOOL"; do
    if [ ! -x "$t" ]; then
        echo "ERRO: ferramenta não encontrada: $t"
        exit 1
    fi
done
echo "[*] Todas ferramentas OK"

rm -rf "$OUT"
mkdir -p "$OUT"

echo "[*] Compilando recursos Android com aapt2..."
# Compilar cada arquivo de recurso individualmente
$AAPT2 compile -o "$OUT/res" "$SRC/res/layout/activity_main.xml"
$AAPT2 compile -o "$OUT/res" "$SRC/res/values/strings.xml"
for d in mipmap-mdpi mipmap-hdpi mipmap-xhdpi mipmap-xxhdpi; do
    $AAPT2 compile -o "$OUT/res" "$SRC/res/$d/ic_launcher.png"
done

echo "[*] Linkando recursos..."
$AAPT2 link -o "$OUT/kroot-unsigned.apk" \
    -I "$PLATFORM/android.jar" \
    --manifest "$SRC/AndroidManifest.xml" \
    -R "$OUT/res/activity_main.flat" \
    -R "$OUT/res/strings.flat" \
    -R "$OUT/res/ic_launcher.flat" \
    --auto-add-overlay

echo "[*] Compilando Java para classes..."
$JAVAC -source 8 -target 8 -bootclasspath "$PLATFORM/android.jar" \
    -d "$OUT/classes" \
    "$SRC/java/com/chronokairo/kroot/*.java"

echo "[*] Compilando classes para DEX com d8..."
$D8 --release --output "$OUT/dex" "$OUT/classes/com/chronokairo/kroot/*.class"

echo "[*] Injetando classes.dex no APK..."
cd "$OUT"
# Extrair APK, adicionar classes.dex, recriar
mkdir -p apk_tmp
cd apk_tmp
unzip -o ../kroot-unsigned.apk > /dev/null 2>&1
cp ../dex/classes.dex .
# Recriar APK com classes.dex
zip -r -X ../kroot-unsigned.apk classes.dex AndroidManifest.xml res/ resources.arsc > /dev/null 2>&1
cd ..
rm -rf apk_tmp

echo "[*] Gerando keystore de assinatura..."
if [ ! -f "$OUT/kroot.keystore" ]; then
    $KEYTOOL -genkeypair -v -keystore "$OUT/kroot.keystore" -alias kroot \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Kairos Root, O=Chronokairo, C=BR" \
        -storepass kroot123 -keypass kroot123 2>&1 | tail -3
fi

echo "[*] Assinando APK..."
$APKSIGNER sign --ks "$OUT/kroot.keystore" --ks-key-alias kroot \
    --ks-pass pass:kroot123 --key-pass pass:kroot123 \
    --out "$OUT/kroot-signed.apk" "$OUT/kroot-unsigned.apk"

echo "[*] Alinhando APK com zipalign..."
$ZIPALIGN -f 4 "$OUT/kroot-signed.apk" "$OUT/kroot.apk"

echo ""
echo "========================================"
echo "[*] APK KAIROS ROOT PRONTO!"
echo "========================================"
echo "Caminho: $OUT/kroot.apk"
ls -lh "$OUT/kroot.apk"
echo ""
echo "Para instalar no Moto E32:"
echo "  adb install -r $OUT/kroot.apk"
echo ""
echo "Para definir como launcher padrão:"
echo "  adb shell cmd settings set secure default_home_package com.chronokairo.kroot"
