# Kairos Root (kroot)

Root persistente, com controle por aplicativo, para Android com **bootloader bloqueado** — sem
desbloquear, sem gravar imagem de boot, sem tocar em `/system`, sem quebrar o dm-verity.

Este repositório é a continuação prática do processo descrito no gist
[Processo de Root, Engenharia Reversa e Persistência 24/7 no Motorola Moto E32 (hawaii)](docs/processo-root-e32.md).

---

## O motivo

O Moto E32 (`hawaii`, Unisoc T606, kernel 4.14.199) tem `/dev/mali0` com permissão
`crw-rw-rw-` e um **UAF no driver Mali** que permite escrita arbitrária em memória física.
Com isso é possível localizar a `task_struct` do processo e sobrescrever o `struct cred`
(`uid=0`, `cap_eff=0x3fffffffff`) — root em tempo de execução, com o bootloader travado
(`ro.boot.flash.locked=1`, `verifiedbootstate=green`).

O problema começa depois:

- cada execução do exploit leva **10–20 s**, aloca **~128 MB** e mexe em PTE do kernel;
- rodar isso a cada comando de root é lento e **instável** (o aparelho já caiu em teste);
- não existe Magisk: ele exige bootloader desbloqueado e boot patchado, o que aqui quebraria
  o dm-verity e levaria a bootloop.

Ou seja: tínhamos root, mas não tínhamos **um root usável**. O kroot resolve isso separando
o exploit (bootstrap) do serviço (daemon):

```
boot → kroot (exploit, 1x por boot) → krootd (uid 0, persistente)
                                          │ socket @krootd
                        ┌─────────────────┼──────────────────┐
                        ▼                 ▼                  ▼
                     su (CLI)      app Kairos Root      scripts/serviços
```

O exploit roda **uma vez por boot** e o processo que herdou `uid=0` nunca morre. A partir daí:

- `su -c id` custa **menos de 50 ms** (antes: 10–20 s);
- cada app que pede root é autenticado por `SO_PEERCRED` (o kernel entrega uid/pid, não dá
  para falsificar) e pela policy em `/data/local/kroot/policy.tsv`;
- todo pedido fica registrado em `/data/local/kroot/krootd.log`;
- se o daemon cair, o `su` roda o bootstrap e reergue sozinho.

Em resumo: **o exploit dá o root; o krootd transforma esse root em um serviço controlável.**

---

## Componentes

| Binário | Função |
|---|---|
| `krootd` | daemon root; aceita conexões no socket abstrato `@krootd`, autentica por `SO_PEERCRED`, aplica a policy e entrega um PTY/comando com os uid/gid pedidos |
| `su` | cliente compatível (`su`, `su -c "cmd"`, `su <uid>`); conecta no daemon, com bootstrap automático se ele não estiver no ar |
| `krootctl` | gerencia a policy (`allow`, `deny`, `list`, `status`, `log`) — só root |

O app **Kairos Root** (`com.chronokairo.kroot`) é a interface gráfica (prompt de permissão,
lista de apps, logs, status) e vive em milestone separado.

### Protocolo

```
EXEC <flags> <uid> <gid> <cols> <rows> <term_len> <cmd_len>\n
<term><cmd>
```

- resposta `OK\n` + 1 byte com 2 fds (`SCM_RIGHTS`): master do PTY e pipe de status;
- resposta `DENY <motivo>\n` quando a policy nega.

O status de saída do comando volta pelo pipe. `flags`: bit 0 = PTY, bit 1 = shell de login.

### Policy

`/data/local/kroot/policy.tsv`, uma linha por uid:

```
# uid   acao    pacote   expira_epoch
2000    allow   shell    0
10246   allow   termux   0
10087   deny    foo      0
```

`expira_epoch = 0` significa "para sempre". Qualquer uid sem linha é **negado** e registrado
no log. O daemon recarrega o arquivo sozinho quando o mtime muda.

---

## Build

Requisitos: Android NDK 27 (ou ajuste `ANDROID_NDK`), `sh`, `scp`.

```sh
./build.sh
```

Saída em `out/` (`krootd`, `su`, `krootctl`), estáticos, `arm64-v8a`, API 26.

### Testar no aparelho

```sh
KROOT_HOST=root@192.168.0.20 KROOT_PORT=2222 ./scripts/deploy.sh
```

O deploy copia para `/data/local/tmp/kroot-build`, chama `scripts/install-device.sh` como root,
cria a policy inicial (`shell` e `termux`), sobe o daemon e mostra o status.

### Uso

```sh
/data/local/xbin/su -c id            # root em < 50 ms
/data/local/xbin/su                  # shell interativo
/data/local/xbin/krootctl list       # policy
/data/local/xbin/krootctl allow 10123 60   # libera o uid por 60 min
/data/local/xbin/krootctl log 50     # ultimas linhas
```

---

## Status

- [x] M1 — `krootd`, `su`, `krootctl`, policy, log, self-heal
- [ ] M2 — app `com.chronokairo.kroot` (prompt, apps, logs, status, autostart)
- [ ] M3 — assinatura pinada do gerenciador, timeout de sessão, fila de pedidos
- [ ] M4 — módulos systemless via overlayfs + mount namespace

---

## Segurança e avisos

Isto é **root real** (uid 0 + todas as capabilities). Não é sandbox: qualquer uid marcado como
`allow` vira root total, exatamente como no Magisk. O kernel 4.14 do aparelho **não tem**
`USER_NS`, `PID_NS`, `CGROUP_DEVICE` nem `CGROUP_PIDS`, então containers e isolamento por
namespace não existem.

- o socket é abstrato e o SELinux está `Permissive` — a fronteira de segurança é a policy;
- publique/instale apenas em aparelhos seus;
- o exploit pode instabilizar o kernel (UAF em PTE); manter o daemon evita repeti-lo;
- se o SELinux voltar a `Enforcing`, socket/exec podem parar de funcionar.

## Compatibilidade

Validado em **Motorola Moto E32 (XT2227, `hawaii`)**, Android 11, kernel `4.14.199+-ab612c2`,
aarch64, com `/dev/mali0` acessível por apps. Outros aparelhos com o mesmo driver Mali e kernel
podem funcionar ajustando os offsets do exploit — o `krootd` em si é independente do modelo.

## Créditos

- Processo de root, engenharia reversa e persistência 24/7 no Moto E32:
  [gist original](https://gist.github.com/lu4nn3ry/b2cb310ecf3530caa3537133bdc647f6)
  (agregado em [`docs/processo-root-e32.md`](docs/processo-root-e32.md))
- `mali-kroot` — exploit UAF original usado como bootstrap.
- `mali_kroot.c` — mesma cadeia de exploit (UAF Mali → leitura/escrita física → patch de
  `cred`/`selinux_state`) para o **mesmo Moto E32 (hawaii)**, por
  [CreitinGameplays](https://github.com/CreitinGameplays/motorola-moto-e32-root)
  ([`scripts/mali/mali_kroot.c`](https://github.com/CreitinGameplays/motorola-moto-e32-root/blob/main/scripts/mali/mali_kroot.c), MIT).
  Encontrado **depois** da nossa implementação — chegou ao mesmo resultado, exatamente para o
  mesmo modelo.
- Exploit obtido com o harness do **DeepSeek** e o modelo **Muse Glimmer 30B** (provider
  **NVIDIA NIM**).

## Licença

GPL-3.0. Veja [LICENSE](LICENSE).
