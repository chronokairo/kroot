#!/bin/sh
# remote-build.sh - roda no WSL do build host (0.16)
set -e

SRC=/mnt/c/Users/luann/kroot
DST=$HOME/kroot

rm -rf "$DST"
cp -r "$SRC" "$DST"
cd "$DST"

# normaliza CRLF vindo do Windows
find . -type f \( -name '*.c' -o -name '*.h' -o -name '*.sh' -o -name '*.md' \) \
    -exec sed -i 's/\r$//' {} +

chmod +x build.sh scripts/*.sh
./build.sh
