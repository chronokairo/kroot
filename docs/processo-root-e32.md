# Processo de root, engenharia reversa e persistência 24/7 no Moto E32 (hawaii)

> Documento original: https://gist.github.com/lu4nn3ry/b2cb310ecf3530caa3537133bdc647f6
> Agregado aqui como referência do que motivou o Kairos Root (kroot).

---
Processo de Root, Engenharia Reversa e Persistencia 24/7 no Motorola Moto E32 (hawaii)

# Processo de Root e Persistencia 24/7 no Motorola Moto E32 (hawaii)

Este documento descreve a analise tecnica, engenharia reversa e implementacao de elevacao de privilegios (root) em tempo de execucao e persistencia de servicos no **Motorola Moto E32** (codinome `hawaii`), mantendo o bootloader bloqueado e sem violar a verificacao de integridade dm-verity.

---

## 1. Identificacao do Dispositivo

- **Modelo**: Motorola Moto E32 (XT2227 / codinome `hawaii`)
- **SoC**: Unisoc T606 (2x Cortex-A75 + 6x Cortex-A55)
- **GPU**: Arm Mali-G57 MP1 (driver Bifrost/Valhall)
- **Kernel**: Linux 4.14.199+-ab612c2 aarch64
- **Estado do Bootloader**: Bloqueado (`ro.boot.flash.locked = 1`, `ro.boot.verifiedbootstate = green`)
- **SELinux**: Permissive apos elevacao

---

## 2. Vetor de Elevacao: Exploit Mali Kernel UAF (`mali-kroot`)

O dispositivo expoem o no de dispositivo `/dev/mali0` com permissoes mundiais de leitura e escrita (`crw-rw-rw-`), exigidas para renderizacao grafica OpenGL/Vulkan por aplicacoes em espaco de usuario.

O vetor de elevacao baseia-se em Use-After-Free (UAF) no subsistema de memoria do driver de kernel Mali:
1. **Preparacao de memoria e UAF**: O exploit aloca e corrompe estruturas de pagina PTE (Page Table Entry) no kernel atraves de pressoes controladas de memoria.
2. **Primitivas de Leitura/Escrita Fisica**: Com uma pagina PTE controlada em espaco de usuario, o exploit ganha primitivas arbitrarias de leitura (`phys_read`) e escrita (`phys_write`) na memoria fisica do kernel.
3. **Varredura da Task List**: O exploit localiza o simbolo `init_task` e percorre a lista encadeada circular de tarefas (`tasks.next`) ate encontrar a `task_struct` do processo atual e do processo pai (`adbd` ou shell).
4. **Patch de Credenciais**:
   - Sobrescreve a estrutura `struct cred`:
     - `uid = 0`, `gid = 0`
     - `euid = 0`, `egid = 0`
     - `fsuid = 0`, `fsgid = 0`
     - `cap_effective = 0x3fffffffff` (todas as capacidades POSIX habilitadas)
5. **Spawn de Shell Root**: Com as credenciais do processo modificadas para root, o binario gera um shell interativo com `uid=0`.

---

## 3. Engenharia Reversa e Patch Binario para Universalizacao

### 3.1 O Problema do UID Restrito
Originalmente, o binario `su` (`mali-kroot`) foi compilado com uma verificacao que exigia que o chamador fosse o usuario `shell` (UID 2000 do ADB):
```
[*] kroot pid=23044
[i] task[1] cred uid=10246 gid=0
[-] unexpected uid 10246, abort
```
Quando invocado a partir de aplicacoes como Termux (`u0_a246`, UID 10246), o exploit abortava a execucao.

### 3.2 Analise em Assembly ARM64
A descompilacao da funcao `patch_cred` revelou o seguinte bloco condicional:

```assembly
000000000021c1dc <patch_cred+0x198>:
  21c1dc: 34000b74    cbz   w20, 21c348          ; if (uid == 0) pular (ja e root)
  21c1e0: 711f429f    cmp   w20, #0x7d0          ; if (uid == 2000) (0x7d0 = ADB shell)
  21c1e4: 54000bc1    b.ne  21c35c               ; if != 2000, saltar para logmsg abort
  21c1e8: b24097e8    mov   x8, #0x3fffffffff    ; capacidades maximas
```

No offset `21c1e4` (offset fisico no arquivo: `0x1b1e4`), a instrucao `54000bc1` (`b.ne 21c35c`) desvia o fluxo para o bloco que imprime:
`[-] unexpected uid %u, abort`.

### 3.3 Aplicacao do Patch Binario
Substitui-se a instrucao condicional `b.ne` por uma instrucao neutra `nop` (`d503201f` em ARM64 little-endian: `\x1f\x20\x03\xd5`).

Script Python para aplicar o patch:
```python
with open("su", "rb") as f:
    data = bytearray(f.read())

# Offset da instrucao b.ne no ELF
target_offset = 0x1b1e4
assert data[target_offset:target_offset+4] == b"\xc1\x0b\x00\x54"

# Substitui por nop (ARM64: 0xd503201f)
data[target_offset:target_offset+4] = b"\x1f\x20\x03\xd5"

with open("su_universal", "wb") as f:
    f.write(data)

print("[OK] su_universal gerado com sucesso.")
```

Com este patch, qualquer contexto do Android (ADB shell, Termux, aplicacoes de servico) passa com sucesso pela rotina de elevacao, recebendo UID 0 e `cap_eff = 0x3fffffffff`.

---

## 4. Persistencia 24/7 sem Modificar Particoes de Sistema

Como o bootloader esta bloqueado (`ro.boot.flash.locked = 1`), a particao `/system` e protegida por dm-verity. Qualquer modificacao em `/system` causara falha de verificacao criptografica e bootloop.

A persistencia foi alcancada em nivel de espaco de usuario e ciclo de vida do Android:

### 4.1 Modulo Termux:Boot
1. Instalou-se o aplicativo complementar `Termux:Boot` (`com.termux.boot`).
2. Criou-se o diretorio de scripts de inicializacao:
   `/data/data/com.termux/files/home/.termux/boot/`
3. Gravou-se o script `start-ubuntu.sh`:
   ```bash
   #!/data/data/com.termux/files/usr/bin/sh
   sleep 5
   echo "/system/bin/sh /data/local/ubuntu-boot.sh" | /data/local/tmp/su
   ```

### 4.2 Script de Orquestracao do Servidor (`/data/local/ubuntu-boot.sh`)
Ao receber o evento `BOOT_COMPLETED`, o script:
1. **Desativa restricoes de segundo plano**:
   - `settings put global settings_enable_monitor_phantom_procs false`
   - `device_config put activity_manager max_phantom_processes 2147483647`
   - `settings put global wifi_sleep_policy 2` (Wi-Fi ativo 100% do tempo)
   - `settings put global stay_on_while_plugged_in 3`
2. **Segura o Wakelock do Kernel**:
   - `echo "ubuntu_server" > /sys/power/wake_lock` (impede a CPU de entrar em Deep Sleep)
3. **Monta o ambiente chroot Ubuntu 24.04 LTS**:
   - Monta `/proc`, `/sys`, `/dev`, `/dev/pts`, `/dev/shm` e `/data`
   - Configura servidores DNS (`1.1.1.1` e `8.8.8.8`)
   - Mapeia os GIDs de rede do Android (`aid_inet`, `aid_net_raw`) no `/etc/group` do Ubuntu
4. **Inicia o Daemon OpenSSH**:
   - `/data/local/ubuntu/usr/sbin/sshd -p 2222`

---

## 5. Validacao e Conexao

Apos reiniciar o aparelho, o ambiente inicializa de forma autonoma sem conexao com o computador. O acesso SSH e imediato:

```bash
ssh -p 2222 root@192.168.0.20
```

Saida de verificacao:
```
Linux localhost 4.14.199+-ab612c2 #1 SMP PREEMPT Tue Feb 20 11:32:22 CST 2024 aarch64
Ubuntu 24.04.5 LTS (Noble Numbat)
Node.js: v22.23.4
NPM: 10.9.9
Python: 3.12.3
```
