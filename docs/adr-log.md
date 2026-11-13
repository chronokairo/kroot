# ADR-002 — Log de diagnóstico do device (Moto E32)

## Contexto

Após buildar e instalar o launcher Kairos Root (APK) e os binários C (krootd, su, krootctl), o `su` continua sem funcionar via `adb shell`. Foi necessário analisar o logcat e o estado do device para separar:

- problemas de **instalação/caminho** (su no lugar errado, UID do app não na policy)
- problemas de **código** (bootstrap não eleva privilégios, launcher não inicia o serviço)
- problemas **estruturais** (falta o exploit Mali UAF para obter uid 0)

## Estado observado no device

Comando | Resultado
--------|----------
`adb shell id` | `uid=2000(shell)` — adb roda como shell, sem root
`pm list packages -U \| grep kroot` | `com.chronokairo.kroot uid:10261`
`ls -l /data/local/xbin/` | `krootd`, `su`, `krootctl` — todos `root:root`, 0755
`ls -l /data/local/tmp/kroot-build/` | cópia dos binários + `install-device.sh` + `su.kroot` (versão antiga)
`ps \| grep kroot` | `u0_a261 … com.chronokairo.kroot` — launcher rodando
`/data/local/xbin/su -c id` | `kroot: daemon ausente, subindo…` → `su: krootd indisponível` → exit 1
`ls -l /data/local/kroot/` | `Permission denied` — diretório não acessível via adb (uid 2000)

## Problemas confirmados pelo log

### 1. Caminho do `su` inconsistente (CRÍTICO)

O launcher (`MainActivity.java`, `KrootService.java`) procurava:

```
/data/local/tmp/kroot-build/su.kroot
```

Mas o instalador (`install-device.sh`) coloca:

```
/data/local/xbin/su
```

Resultado: `ENOENT` quando o Android tenta executar o cliente. O log do `su` confirmava que o caminho antigo não existia.

### 2. O launcher não inicia o `KrootService` (ALTA)

O `MainActivity` declarava o serviço no Manifest e o `BootReceiver` o iniciaria no boot, mas ao abrir o launcher não havia chamada para `startService(new Intent(this, KrootService.class))`. Sem isso, o `krootd` nunca sobe pelo launcher.

### 3. `--daemon` não executa bootstrap (CRÍTICO)

No `su.c`, o `--daemon` apenas fazia `execl("/data/local/xbin/krootd", ...)`. O `krootd`, por sua vez, verifica `getuid() != 0` e encerra com `"krootd: precisa de root"`. Ou seja: chamar `su --daemon` como usuário normal nunca sobe o daemon, porque não há elevação de privilégios.

### 4. Bootstrap dependia de `/data/local/tmp/su` (CRÍTICO)

Quando não era root, `su.c` fazia:

```
system("echo '/data/local/xbin/krootd --daemon' | /data/local/tmp/su …")
```

Mas o deploy padrão instala `/data/local/xbin/su`, não `/data/local/tmp/su`. O bootstrap nunca funcionaria no fluxo normal.

### 5. UID do app não está na policy inicial (CRÍTICA)

O app tem `uid:10261`. A policy inicial do `install-device.sh` só incluía:

```
2000    allow   shell    0
10246   allow   termux   0
```

Mesmo com o `krootd` rodando como root, o `krootd.authorize()` usaria `SO_PEERCRED` e encontraria o peer `uid=10261`, que não está na policy → `DENY uid 10261 sem permissao`.

### 6. Falta o exploit Mali UAF (estrutural)

O `krootd` só aceita ser iniciado se `getuid() == 0`. O adb é uid 2000. Não há Magisk, não há su setuid, não há ssh na 2222. O único caminho para uid 0 é o exploit UAF do driver Mali (`/dev/mali0`, que existe com `crw-rw-rw-`), mas esse exploit não está neste repositório (é o `mali-kroot`, separado, citado nos créditos do README).

## Correções aplicadas

| Arquivo | Mudança |
|---------|---------|
| `app/src/main/java/.../MainActivity.java` | `SU_KROOT` → `/data/local/xbin/su`; adicionado `startService(new Intent(this, KrootService.class))` no `onCreate` |
| `app/src/main/java/.../KrootService.java` | `SU_KROOT` → `/data/local/xbin/su` |
| `su.c` | `bootstrap()` usa `/data/local/xbin/su` consistentemente (substituído `/data/local/tmp/su`) |
| `scripts/install-device.sh` | Adiciona o UID do pacote `com.chronokairo.kroot` à policy dinamicamente via `pm list packages -U` (fallback 10260) |
| `app/src/main/AndroidManifest.xml` | `versionCode` 1 → 2 (reinstalação) |

## O que ainda falta (não resolvido por código)

- Executar o exploit Mali UAF uma vez por boot para obter uid 0.
- A partir daquele processo com uid 0, rodar `/data/local/xbin/krootd --daemon`.
- Só então `/data/local/xbin/su -c id` responde em < 50 ms.

## Fluxo correto (documentado)

```
adb shell (uid 2000)
     |
     v
abre launcher (uid 10261)
     |
     v
MainActivity inicia KrootService
     |
     v
KrootService tenta su --daemon
     |
     v
su → krootd --daemon → falha (getuid != 0)
     |
     v
[PRECISA DO EXPLOIT MALI AQUI]
     |
     v
processo com uid 0 → krootd --daemon (socket @krootd sobe)
     |
     v
su -c id → conecta @krootd → SO_PEERCRED uid=0 → autorizado → root
```

## Conclusão

O log confirma que o launcher está instalado e rodando, mas o `su` não funciona porque o `krootd` nunca sobe (sem uid 0). As correções de caminho, serviço e policy removem as barreiras de código; o passo restante é o exploit de bootstrap (fora deste repositório).
