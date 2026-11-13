package com.chronokairo.kroot;

import android.util.Log;

/**
 * Gerencia serviços auxiliares: chroot Ubuntu, SSH, ttyd.
 * Separado do DaemonManager para seguir responsabilidade única.
 */
public class ServiceManager {

    private static final String TAG = "ServiceManager";
    private static final String SU_PATH = "/data/local/xbin/su";
    private static final String CHROOT = "/data/local/ubuntu";
    private static final String POLICY_DIR = "/data/local/kroot";

    private final RootState state;

    public ServiceManager(RootState state) {
        this.state = state;
    }

    /**
     * Verifica se temos privilégios root executando 'su -c id'.
     * Isso verifica se uma sessão elevada está disponível, não apenas o UID do próprio APK.
     */
    private boolean hasRoot() {
        try {
            Process p = new ProcessBuilder(SU_PATH, "-c", "id -u")
                    .redirectErrorStream(true)
                    .start();
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            String uid = br.readLine();
            p.waitFor();
            return uid != null && uid.trim().equals("0");
        } catch (Exception e) {
            return false;
        }
    }

    private int exec(String[] cmd, long timeoutMs) {
        try {
            Process p = new ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .start();
            boolean finished = p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!finished) {
                p.destroyForcibly();
                Log.w(TAG, "Comando excedeu timeout: " + String.join(" ", cmd));
                return -1;
            }
            return p.exitValue();
        } catch (Exception e) {
            Log.e(TAG, "Erro executando comando", e);
            return -1;
        }
    }

    public boolean mountChroot() {
        if (!hasRoot()) {
            Log.w(TAG, "Root não disponível para montar chroot");
            return false;
        }

        Log.i(TAG, "Montando chroot Ubuntu...");
        String[] mounts = {
                "mount -t proc proc " + CHROOT + "/proc 2>/dev/null",
                "mount -t sysfs sys " + CHROOT + "/sys 2>/dev/null",
                "mount --bind /dev " + CHROOT + "/dev 2>/dev/null",
                "mount --bind /dev/pts " + CHROOT + "/dev/pts 2>/dev/null",
                "mount --bind /system " + CHROOT + "/system 2>/dev/null",
        };
        boolean allOk = true;
        for (String m : mounts) {
            int rc = exec(new String[]{SU_PATH, "-c", m}, 5000);
            if (rc != 0) allOk = false;
        }

        // Criar diretório de policy
        exec(new String[]{SU_PATH, "-c",
                "mkdir -p " + POLICY_DIR + "; touch " + POLICY_DIR + "/policy.tsv"}, 5000);

        state.setLastMessage(allOk ? "Chroot montado" : "Chroot parcialmente montado");
        return allOk;
    }

    public boolean startSshd() {
        if (!hasRoot()) {
            Log.w(TAG, "Root não disponível para iniciar sshd");
            return false;
        }

        Log.i(TAG, "Iniciando sshd (22/2222)...");
        int rc = exec(new String[]{SU_PATH, "-c",
                "chroot " + CHROOT + " /bin/bash -c 'mkdir -p /run/sshd; sshd -p 22; sshd -p 2222; echo sshd_ok'"}, 10000);
        return rc == 0;
    }

    public boolean startTtyd() {
        if (!hasRoot()) {
            Log.w(TAG, "Root não disponível para iniciar ttyd");
            return false;
        }

        Log.i(TAG, "Verificando ttyd...");
        exec(new String[]{SU_PATH, "-c",
                "if command -v ttyd >/dev/null 2>&1; then chroot " + CHROOT + " ttyd -p 7681 /bin/bash & fi"}, 5000);
        return true;
    }
}
