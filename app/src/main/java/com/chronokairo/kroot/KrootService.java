package com.chronokairo.kroot;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStreamReader;

public class KrootService extends Service {

    private static final String TAG = "KrootService";
    private static final String CHANNEL_ID = "kroot_channel";
    private static final int NOTIFICATION_ID = 1;

    private static final String SU_KROOT = "/data/local/xbin/su";
    private static final String CHROOT = "/data/local/ubuntu";
    private static final String POLICY_DIR = "/data/local/kroot";

    private Handler handler;
    private Runnable watchdogRunnable;
    private boolean running = false;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (running) return START_STICKY;
        running = true;

        createNotificationChannel();
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Kairos Root")
                .setContentText("krootd + chroot Ubuntu rodando")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build();
        startForeground(NOTIFICATION_ID, notification);

        Log.i(TAG, "Kairos Root service iniciado");

        // 1. Garantir que krootd está no ar
        startKrootd();

        // 2. Montar chroot Ubuntu (proc, sys, dev)
        mountChroot();

        // 3. Iniciar sshd 22/2222 dentro do chroot
        startSshd();

        // 4. Iniciar ttyd 7681 dentro do chroot (se existir)
        startTtyd();

        // 5. Watchdog: verificar a cada 30s se tudo está vivo
        handler = new Handler(Looper.getMainLooper());
        watchdogRunnable = new Runnable() {
            @Override
            public void run() {
                if (!running) return;
                checkAndHeal();
                handler.postDelayed(this, 30000);
            }
        };
        handler.postDelayed(watchdogRunnable, 30000);

        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (handler != null && watchdogRunnable != null) {
            handler.removeCallbacks(watchdogRunnable);
        }
        super.onDestroy();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Kairos Root",
                    NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private void startKrootd() {
        Log.i(TAG, "Iniciando krootd...");
        execCmd(new String[]{SU_KROOT, "--daemon"});
        sleep(1000);
    }

    private void mountChroot() {
        Log.i(TAG, "Montando chroot Ubuntu...");
        String[] mounts = {
            "mount -t proc proc " + CHROOT + "/proc 2>/dev/null",
            "mount -t sysfs sys " + CHROOT + "/sys 2>/dev/null",
            "mount --bind /dev " + CHROOT + "/dev 2>/dev/null",
            "mount --bind /dev/pts " + CHROOT + "/dev/pts 2>/dev/null",
            "mount --bind /system " + CHROOT + "/system 2>/dev/null",
        };
        for (String m : mounts) {
            execCmd(new String[]{SU_KROOT, "-c", m});
        }
        // Criar policy inicial se não existir
        execCmd(new String[]{SU_KROOT, "-c",
            "mkdir -p " + POLICY_DIR + "; touch " + POLICY_DIR + "/policy.tsv; " +
            "grep -q '^10246' " + POLICY_DIR + "/policy.tsv 2>/dev/null || " +
            "printf '10246\\tallow\\ttermux\\t0\\n' >> " + POLICY_DIR + "/policy.tsv"});
    }

    private void startSshd() {
        Log.i(TAG, "Iniciando sshd 22/2222 dentro do chroot...");
        execCmd(new String[]{SU_KROOT, "-c",
            "chroot " + CHROOT + " /bin/bash -c '" +
            "mkdir -p /run/sshd; " +
            "sshd -p 22; " +
            "sshd -p 2222; " +
            "echo sshd_ok'"});
    }

    private void startTtyd() {
        Log.i(TAG, "Verificando ttyd...");
        // ttyd pode não estar instalado; tentar iniciar se existir
        execCmd(new String[]{SU_KROOT, "-c",
            "if command -v ttyd >/dev/null 2>&1; then " +
            "chroot " + CHROOT + " ttyd -p 7681 /bin/bash & " +
            "fi"});
    }

    private void checkAndHeal() {
        Log.i(TAG, "Watchdog: verificando serviços...");

        // Verificar krootd
        String krootdStatus = execCmdCapture(new String[]{SU_KROOT, "--status"});
        if (krootdStatus == null || !krootdStatus.contains("online")) {
            Log.w(TAG, "krootd offline, reiniciando...");
            execCmd(new String[]{SU_KROOT, "--daemon"});
            sleep(1000);
        }

        // Verificar sshd (porta 2222)
        String sshdCheck = execCmdCapture(new String[]{SU_KROOT, "-c",
            "chroot " + CHROOT + " /bin/bash -c 'ss -tlnp 2>/dev/null | grep -q 2222 && echo sshd_ok || echo sshd_down'"});
        if (sshdCheck == null || !sshdCheck.contains("sshd_ok")) {
            Log.w(TAG, "sshd 2222 offline, reiniciando...");
            execCmd(new String[]{SU_KROOT, "-c",
                "chroot " + CHROOT + " /bin/bash -c 'sshd -p 2222'"});
        }

        // Verificar mounts
        String mountCheck = execCmdCapture(new String[]{SU_KROOT, "-c",
            "mountpoint -q " + CHROOT + "/proc 2>/dev/null && echo mounted || echo umounted"});
        if (mountCheck == null || mountCheck.contains("umounted")) {
            Log.w(TAG, "Chroot desmontado, remontando...");
            mountChroot();
        }
    }

    private void execCmd(String[] cmd) {
        try {
            Process p = Runtime.getRuntime().exec(cmd);
            p.waitFor();
        } catch (Exception e) {
            Log.e(TAG, "Erro executando comando", e);
        }
    }

    private String execCmdCapture(String[] cmd) {
        try {
            Process p = Runtime.getRuntime().exec(cmd);
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append("\n");
            p.waitFor();
            return sb.toString().trim();
        } catch (Exception e) {
            Log.e(TAG, "Erro capturando comando", e);
            return null;
        }
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) {}
    }
}
