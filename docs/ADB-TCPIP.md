# ADB TCP/IP — Moto E32 (hawaii)

Instruções para vincular o dispositivo Moto E32 via ADB em modo TCP/IP (Wi-Fi), tanto no Windows local quanto no WSL do build-pc.

## Pré-requisito

O dispositivo deve estar conectado por USB com a depuração USB habilitada e autorizada.

## 1. Habilitar modo TCP/IP no dispositivo

No Windows local, execute:

```bash
C:\Users\luann\Downloads\platform-tools\adb.exe tcpip 5555
```

Saída esperada: `restarting in TCP mode port: 5555`

## 2. Conectar via Wi-Fi

### No Windows local

```bash
C:\Users\luann\Downloads\platform-tools\adb.exe connect 192.168.0.20:5555
C:\Users\luann\Downloads\platform-tools\adb.exe devices -l
```

### No WSL (build-pc, via SSH)

O Android SDK está instalado em `~/android-sdk` no WSL.

```bash
ssh build-pc
wsl -- bash -c '~/android-sdk/platform-tools/adb connect 192.168.0.20:5555'
wsl -- bash -c '~/android-sdk/platform-tools/adb devices -l'
```

Saída esperada:

```
List of devices attached
192.168.0.20:5555      device product:hawaii model:moto_e32 device:hawaii transport_id:3
```

## 3. Verificar o su.kroot no dispositivo

```bash
adb -s 192.168.0.20:5555 shell /data/local/tmp/kroot-build/su.kroot --status
```

Saída esperada:

```
krootd: offline
```

## 4. Desconectar modo TCP/IP (opcional)

Para voltar ao modo USB:

```bash
C:\Users\luann\Downloads\platform-tools\adb.exe usb
```

## Notas

- O IP `192.168.0.20` pode mudar se o dispositivo reiniciar ou mudar de rede. Verifique com `adb shell ip addr show wlan0` no dispositivo.
- A porta `5555` é a padrão do adb tcpip.
- No WSL, o caminho completo do adb é `~/android-sdk/platform-tools/adb`.
