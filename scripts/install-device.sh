#!/system/bin/sh
# install-device.sh - Instala o Kairos Root no aparelho (rodar como root)
# Todos os binários são gerenciados pelo app via diretório privado de dados.
# Este script apenas prepara o ambiente (policy, etc.).
set -e

RUN=/data/local/kroot
APP_PKG=com.chronokairo.kroot

mkdir -p "$RUN"
chmod 0700 "$RUN" 2>/dev/null || true

# Verificar se estamos rodando como root
if [ "$(id -u)" != "0" ]; then
    echo "AVISO: install-device.sh precisa ser executado como root (UID 0)." >&2
    echo "       Execute primeiro o bootstrap Mali UAF via app para obter UID 0." >&2
    exit 1
fi

# policy inicial (idempotente)
touch "$RUN/policy.tsv"
chmod 0600 "$RUN/policy.tsv"
grep -q '^2000' "$RUN/policy.tsv" || printf '2000\tallow\tshell\t0\n' >> "$RUN/policy.tsv"

# Autorizar o pacote Kairos Root na policy.
KROOT_UID=$(pm list packages -U "$APP_PKG" 2>/dev/null | sed -n 's/.*uid:\([0-9]*\).*/\1/p')
if [ -n "$KROOT_UID" ]; then
    grep -q "^${KROOT_UID}" "$RUN/policy.tsv" || \
        printf "${KROOT_UID}\tallow\t${APP_PKG}\t0\n" >> "$RUN/policy.tsv"
else
    grep -q '^10260' "$RUN/policy.tsv" || \
        printf '10260\tallow\tcom.chronokairo.kroot\t0\n' >> "$RUN/policy.tsv"
fi

echo "Ambiente preparado em $RUN."
echo "Os binários (su, krootd, krootctl, mali_kroot) sao instalados pelo app no diretorio privado de dados."
