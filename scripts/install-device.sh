#!/system/bin/sh
# install-device.sh - instala o Kairos Root no aparelho (rodar como root)
set -e

BIN=/data/local/xbin
RUN=/data/local/kroot
SRC=/data/local/tmp/kroot-build

mkdir -p "$BIN" "$RUN"
chmod 0700 "$RUN"

for f in krootd su krootctl; do
    cp "$SRC/$f" "$BIN/$f"
    chown root:root "$BIN/$f"
    chmod 0755 "$BIN/$f"
done

# policy inicial (idempotente)
touch "$RUN/policy.tsv"
chmod 0600 "$RUN/policy.tsv"
grep -q '^2000' "$RUN/policy.tsv" || printf '2000\tallow\tshell\t0\n' >> "$RUN/policy.tsv"
grep -q '^10246' "$RUN/policy.tsv" || printf '10246\tallow\ttermux\t0\n' >> "$RUN/policy.tsv"

# sobe o daemon se ainda nao estiver no ar
if ! "$BIN/krootd" --status; then
    "$BIN/krootd" --daemon
    sleep 1
fi

"$BIN/krootctl" status
