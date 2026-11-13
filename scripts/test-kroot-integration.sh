#!/bin/bash
# test-kroot-integration.sh - Teste de integração real via adb no Moto E32
# Testa: conectividade, pacote, binários, /dev/mali0, su, krootd, launcher, logcat
set -e

DEVICE="${KROOT_DEVICE:-192.168.0.20:5555}"
PASS=0
FAIL=0

log_pass() { echo "  [PASS] $1"; PASS=$((PASS+1)); }
log_fail() { echo "  [FAIL] $1"; FAIL=$((FAIL+1)); }
log_info() { echo "  [INFO] $1"; }

adb_cmd() {
    adb -s "$DEVICE" "$@"
}

echo "========================================"
echo "  TESTE DE INTEGRAÇÃO KROOT - MOTO E32"
echo "  Device: $DEVICE"
echo "========================================"
echo ""

# -------------------------------------------------
# 1. Conectividade
# -------------------------------------------------
echo "[1/10] Conectividade adb"
if adb_cmd get-state | grep -q device; then
    log_pass "Device respondendo via adb"
else
    log_fail "Device não respondeu via adb"
    echo "Erro: não foi possível conectar no device $DEVICE"
    exit 1
fi

# -------------------------------------------------
# 2. Pacote instalado
# -------------------------------------------------
echo "[2/10] Pacote Kairos Root instalado"
PKG_INFO=$(adb_cmd shell pm list packages -U com.chronokairo.kroot 2>/dev/null || true)
if echo "$PKG_INFO" | grep -q "com.chronokairo.kroot"; then
    PKG_UID=$(echo "$PKG_INFO" | sed -n 's/.*uid:\([0-9]*\).*/\1/p')
    log_pass "Pacote instalado (uid=$PKG_UID)"
else
    log_fail "Pacote com.chronokairo.kroot não encontrado"
fi

# -------------------------------------------------
# 3. Binários nativos
# -------------------------------------------------
echo "[3/10] Binários nativos em /data/local/xbin/"
for bin in krootd su krootctl; do
    PERMS=$(adb_cmd shell ls -l "/data/local/xbin/$bin" 2>/dev/null || true)
    if echo "$PERMS" | grep -q root; then
        log_pass "$bin instalado (root:root)"
    else
        log_fail "$bin não encontrado ou sem permissão root"
    fi
done

# -------------------------------------------------
# 4. /dev/mali0 (pré-requisito do exploit)
# -------------------------------------------------
echo "[4/10] /dev/mali0 (pré-requisito exploit Mali)"
MALI_PERMS=$(adb_cmd shell ls -l /dev/mali0 2>/dev/null || true)
if echo "$MALI_PERMS" | grep -q "crw-rw-rw-"; then
    log_pass "/dev/mali0 acessível (crw-rw-rw-)"
else
    log_fail "/dev/mali0 não acessível ou não encontrado"
fi

# -------------------------------------------------
# 5. su -c id (teste de root real)
# -------------------------------------------------
echo "[5/10] Teste su -c id (root real)"
SU_OUT=$(adb_cmd shell "/data/local/xbin/su -c id" 2>&1 || true)
SU_EXIT=$?
if echo "$SU_OUT" | grep -q "uid=0"; then
    log_pass "su -c id retornou uid=0 (root funcional)"
elif echo "$SU_OUT" | grep -q "daemon ausente\|krootd indisponível"; then
    log_info "su -c id: krootd não está rodando (exploit Mali não executado ainda)"
    log_fail "Root não disponível - krootd offline"
else
    log_fail "su -c id falhou inesperadamente: $SU_OUT"
fi

# -------------------------------------------------
# 6. krootd status
# -------------------------------------------------
echo "[6/10] Status do krootd"
KROOTD_STATUS=$(adb_cmd shell "/data/local/xbin/krootd --status" 2>&1 || true)
KROOTD_PROC=$(adb_cmd shell ps 2>/dev/null | grep -c krootd || true)
if echo "$KROOTD_STATUS" | grep -qi online; then
    log_pass "krootd online (socket)"
elif [ "$KROOTD_PROC" -gt 0 ]; then
    log_pass "krootd processo rodando (PID via ps) — socket check indireto"
else
    log_fail "krootd offline (necessário executar exploit Mali primeiro)"
fi

# -------------------------------------------------
# 7. Launcher app rodando
# -------------------------------------------------
echo "[7/10] Launcher Kairos Root"
APP_PID=$(adb_cmd shell ps 2>/dev/null | grep "com.chronokairo.kroot" | awk '{print $2}' | head -1 || true)
if [ -n "$APP_PID" ]; then
    log_pass "App rodando (PID=$APP_PID)"
else
    log_fail "App não está rodando"
fi

# Verificar se é o launcher padrão (categoria HOME)
HOME_PKG=$(adb_cmd shell cmd settings get secure default_home_package 2>/dev/null || true)
if echo "$HOME_PKG" | grep -q "com.chronokairo.kroot"; then
    log_pass "Launcher definido como padrão (HOME)"
else
    log_info "Launcher não definido como padrão (opcional)"
fi

# -------------------------------------------------
# 8. Logcat - erros críticos
# -------------------------------------------------
echo "[8/10] Logcat - erro crítico do app"
adb_cmd logcat -c >/dev/null 2>&1
# Forçar reinício do app para gerar logs
adb_cmd shell am force-stop com.chronokairo.kroot >/dev/null 2>&1
sleep 1
adb_cmd shell am start -n com.chronokairo.kroot/.MainActivity >/dev/null 2>&1
sleep 5

CRASH=$(adb_cmd logcat -d -s AndroidRuntime 2>/dev/null | grep -i "com.chronokairo.kroot" | grep -i "FATAL\|RuntimeException" || true)
if [ -z "$CRASH" ]; then
    log_pass "Sem FATAL EXCEPTION no app"
else
    log_fail "FATAL EXCEPTION detectado: $CRASH"
fi

RESOURCES_ERR=$(adb_cmd logcat -d -s AndroidRuntime 2>/dev/null | grep "Resources\$NotFoundException" || true)
if [ -z "$RESOURCES_ERR" ]; then
    log_pass "Sem Resources\$NotFoundException"
else
    log_fail "Resources\$NotFoundException: $RESOURCES_ERR"
fi

# -------------------------------------------------
# 9. Diagnóstico do dispositivo
# ---------------------------------================
echo "[9/10] Diagnóstico do dispositivo"
echo "  --- Versão Android ---"
adb_cmd shell getprop ro.build.version.release 2>/dev/null || true
echo "  --- Modelo ---"
adb_cmd shell getprop ro.product.model 2>/dev/null || true
echo "  --- UID do shell (adb) ---"
adb_cmd shell id -u 2>/dev/null || true
echo "  --- Processo kroot ---"
adb_cmd shell ps 2>/dev/null | grep -E 'chronokairo|kroot' || log_info "Nenhum processo kroot encontrado"

# -------------------------------------------------
# 10. Resumo
# -------------------------------------------------
echo ""
echo "========================================"
echo "  RESUMO DO TESTE"
echo "========================================"
echo "  PASS: $PASS"
echo "  FAIL: $FAIL"
echo ""

if [ "$FAIL" -eq 0 ]; then
    echo "  Resultado: TODOS OS TESTES PASSARAM"
    exit 0
else
    echo "  Resultado: $FAIL teste(s) falharam"
    echo ""
    echo "  Ações recomendadas:"
    echo "    1. Se krootd offline: executar exploit Mali UAF uma vez por boot"
    echo "    2. Se su -c id falhar: verificar se krootd está rodando como root"
    echo "    3. Se app crashar: verificar logcat para erro detalhado"
    exit 1
fi
