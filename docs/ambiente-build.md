# Notas de ambiente de build

## WSL

Há **dois** executáveis `wsl.exe` no host:

1. O `wsl.exe` padrão que inicia a distribuição Ubuntu em que este build roda.
2. Um segundo `wsl.exe` invocado via `wsl -d ubuntu` (distribuição explícita `ubuntu`).

Ao compartilhar scripts ou caminhos entre distribuições WSL, confirme qual `wsl.exe` está sendo usado, pois os montamentos de filesystem (`/mnt/c`, etc.) e o `$HOME` podem diferir entre elas.

## Build do launcher (Kairos Root APK)

O script original `scripts/build-kroot-apk.sh` aponta para caminhos Windows (`/mnt/c/Users/luann/...`) e não funciona neste host Linux. Foi criado um script adaptado em `/tmp/opencode/build-kroot-apk-linux.sh` com:

- `ANDROID_HOME=$HOME/android-sdk`
- `SRC=/home/luan/kroot-build/app/src/main`
- `OUT=/home/luan/kroot-build/out-apk`

Ferramentas usadas: `aapt2`, `d8`, `apksigner`, `zipalign`, `javac` (JDK 17, `--release 11`), `keytool`.

Ordem correta para preservar a assinatura APK Signature Scheme v2:
1. `aapt2 compile` + `aapt2 link` (sem classes.dex)
2. Injetar `classes.dex` via `zip`
3. `zipalign` (antes de assinar)
4. `apksigner sign` (v1+v2+v3)

O `R.java` não é criado manualmente. É gerado pelo `aapt2 link` com a opção `--java "$OUT/gen"`, que produz um `R.java` com IDs de recurso corretos (bate com o `resources.arsc` empacotado). Criar um `R.java` manual com IDs inventados causa `Resources$NotFoundException` em runtime.

Saída: `out-apk/kroot.apk` — instalado com sucesso em `192.168.0.20` (Moto E32).
