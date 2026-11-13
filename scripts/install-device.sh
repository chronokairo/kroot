#!/system/bin/sh
# install-device.sh - instala o Kairos Root no aparelho (rodar como root)
set -e

BIN=/data/local/xbin
RUN=/data/local/kroot
SRC=/data/local/tmp/kroot-build

mkdir -p "$BIN" "$RUN"
chmod 0700 "$RUN"

# Verificar se estamos rodando como root
if [ "$(id -u)" != "0" ]; then
    echo "AVISO: install-device.sh precisa ser executado como root (UID 0)." >&2
    echo "       Execute primeiro o bootstrap Mali UAF para obter UID 0." >&2
    exit 1
fi

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

# Autorizar o pacote Kairos Root (com.chronokairo.kroot) na policy.
# Descobre o UID dinamicamente via pm. Se falhar, tenta 10260 (exemplo).
KROOT_UID=$(pm list packages -U com.chronokairo.kroot 2>/dev/null | sed -n 's/.*uid:\([0-9]*\).*/\1/p')
if [ -n "$KROOT_UID" ]; then
    grep -q "^${KROOT_UID}" "$RUN/policy.tsv" || \
        printf "${KROOT_UID}\tallow\tcom.chronokairo.kroot\t0\n" >> "$RUN/policy.tsv"
else
    grep -q '^10260' "$RUN/policy.tsv" || \
        printf '10260\tallow\tcom.chronokairo.kroot\t0\n' >> "$RUN/policy.tsv"
fi

# sobe o daemon (só funciona se executado como root)
if ! "$BIN/krootd" --status; then
    "$BIN/krootd" --daemon
    sleep 1
fi

"$BIN/krootctl" status
