# ADR 0001 — Kairos Root: Launcher Kairos com Chroot Ubuntu Integrado

## Status

Aceito

## Contexto

O Moto E32 (hawaii, Unisoc T606, kernel 4.14.199) não suporta Docker/Podman/LXC por falta de `CONFIG_PID_NS`, `CONFIG_USER_NS`, `CONFIG_CGROUP_DEVICE` e `CONFIG_CGROUP_PIDS`. O bootloader está travado, então não é possível instalar Magisk nem fazer boot patchado sem quebrar o dm-verity.

A elevação de privilégio é feita via exploit UAF no driver Mali (`/dev/mali0`, `crw-rw-rw-`), que roda uma vez por boot para obter `uid=0`. Rodar o exploit a cada comando é lento (10–20 s), consome ~128 MB e é instável.

## Decisão

Criar o **Kairos Root** (`com.chronokairo.kroot`) como launcher padrão do Android que:

1. **Separa o exploit do serviço**: o exploit roda 1x por boot (via Termux:Boot ou BootReceiver do app) e levanta o daemon `krootd` persistente em `uid=0`.
2. **`su.kroot` cliente**: conecta ao `krootd` via socket abstrato `@krootd`, autenticando clientes com `SO_PEERCRED` (uid/pid do kernel, infalsificável). `su -c id` custa < 100 ms.
3. **Launcher estilo Windows 10 Mobile**: tiles dos apps do usuário, tile destacado do Ubuntu Chroot, barra inferior de navegação, fullscreen imersivo.
4. **Chroot Ubuntu integrado**: montagem automática de proc/sys/dev, sshd 22/2222, ttyd 7681, watchdog de 30s para manter serviços vivos.
5. **Ponte ADB interna**: `adb-bridge` dentro do chroot conecta ao adbd do Android host (`127.0.0.1:45415`), permitindo controlar o aparelho a partir do chroot.

## Alternativas consideradas

| Alternativa | Por que rejeitada |
|-------------|-------------------|
| Termux como launcher | Termux não é launcher HOME; não pode ser definido como padrão no Android |
| Magisk | Exige bootloader desbloqueado e boot patchado; quebraria dm-verity |
| Docker/Podman/LXC | Kernel sem namespaces de PID/USER/cgroups |
| Web app / PWA | Não teria acesso direto a chroot/root do sistema |

## Consequências

### Positivas
- `su -c id` instantâneo (< 100 ms) via daemon
- Root controlável por policy (`/data/local/kroot/policy.tsv`) com allow/deny/temporário
- Log completo de todas as elevações em `/data/local/kroot/krootd.log`
- Chroot Ubuntu acessível via SSH (`ssh -p 2222 root@192.168.0.20`) e via launcher
- Self-heal: se `krootd` cair, o `su.kroot` roda o bootstrap e reergue

### Negativas / Riscos
- Root é real (uid 0 + capabilities); qualquer allow = confiança total
- Kernel sem `USER_NS`: não há sandbox real; mitigar com policy restritiva
- SELinux em `Permissive`; se voltar a `Enforcing`, socket/exec podem quebrar
- O exploit pode instabilizar o kernel; manter o daemon evita repeti-lo

## Implementação

### Componentes

| Componente | Caminho | Função |
|------------|---------|--------|
| `krootd` | `/data/local/xbin/krootd` | Daemon uid 0 persistente, socket `@krootd` |
| `su.kroot` | `/data/local/xbin/su.kroot` | Cliente, conecta ao daemon, bootstrap automático |
| `krootctl` | `/data/local/xbin/krootctl` | Gerencia policy (allow/deny/list/status/log) |
| Kairos Root APK | `app/src/main/...` | Launcher HOME, estilo Windows 10 Mobile |
| `KrootService` | `.../KrootService.java` | Foreground service: krootd, chroot, sshd, watchdog |
| `BootReceiver` | `.../BootReceiver.java` | Autostart no BOOT_COMPLETED |
| `adb-bridge` | `/usr/local/bin/adb-bridge` (chroot) | Ponte ADB do chroot para o Android host |

### Policy inicial

```
10246	allow	termux	0
```

### Build

- Host: `build-pc` (WSL Ubuntu, Android SDK, NDK 27)
- Binários C: `aarch64-linux-android26-clang -static`
- APK: `aapt2` + `javac --release 8` + `d8` + `apksigner` + `zipalign`
- Deploy: `adb install -r kroot.apk`

### Definir como launcher padrão

```bash
adb shell cmd settings set secure default_home_package com.chronokairo.kroot
```

## Migração do Termux

O Termux é mantido como fallback de boot durante a transição. Quando o Kairos Root estiver estável por 24h+ (boot automático + sshd + watchdog), o Termux pode ser removido:

1. `BootReceiver` + `KrootService` substituem `Termux:Boot` + `ubuntu-boot.sh`
2. Watchdog interno (30s) substitui `ubuntu-watchdog.sh`
3. Launcher substitui a entrada manual no Termux

## Referências

- Repositório: https://github.com/chronokairo/kroot
- Gist original (engenharia reversa): https://gist.github.com/lu4nn3ry/b2cb310ecf3530caa3537133bdc647f6
- Dispositivo: Motorola Moto E32 (XT2227, `hawaii`), Android 11, kernel 4.14.199
