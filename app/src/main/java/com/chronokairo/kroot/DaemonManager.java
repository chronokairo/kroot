package com.chronokairo.kroot;

import android.util.Log;

/**
 * Gerencia o ciclo de vida do daemon krootd.
 * Responsável apenas por iniciar, parar e verificar o status do daemon.
 */
public class DaemonManager {

    private static final String TAG = "DaemonManager";
    private static final String KROOTD_PATH = "/data/local/xbin/krootd";
    private static final String SU_PATH = "/data/local/xbin/su";

    private final RootState state;
    private volatile boolean running = false;

    public DaemonManager(RootState state) {
        this.state = state;
    }

    public boolean isOnline() {
        try {
            Process p = new ProcessBuilder(SU_PATH, "--status")
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
            Log.e(TAG, "Erro verificando status do krootd", e);
            return false;
        }
    }

    public boolean start() {
        if (isOnline()) {
            Log.i(TAG, "krootd já está online");
            state.setDaemonStatus(RootState.DaemonStatus.ONLINE);
            return true;
        }

        if (!new java.io.File(KROOTD_PATH).exists()) {
            Log.e(TAG, "krootd não encontrado em " + KROOTD_PATH);
            state.setDaemonStatus(RootState.DaemonStatus.ERROR);
            return false;
        }

        running = true;
        try {
            Log.i(TAG, "Iniciando krootd...");
            state.setDaemonStatus(RootState.DaemonStatus.STARTING);

            Process p = new ProcessBuilder(KROOTD_PATH, "--daemon")
                    .redirectErrorStream(true)
                    .start();
            p.waitFor();

            // Aguardar socket ficar online (máximo 10s)
            for (int i = 0; i < 20; i++) {
                if (!running) break;
                if (isOnline()) {
                    state.setDaemonStatus(RootState.DaemonStatus.ONLINE);
                    state.setLastMessage("krootd iniciado com sucesso");
                    Log.i(TAG, "krootd online");
                    return true;
                }
                Thread.sleep(500);
            }

            state.setDaemonStatus(RootState.DaemonStatus.ERROR);
            state.setLastMessage("krootd não ficou online");
            Log.w(TAG, "krootd não ficou online após aguardar");
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Erro iniciando krootd", e);
            state.setDaemonStatus(RootState.DaemonStatus.ERROR);
            state.setLastMessage("Erro: " + e.getMessage());
            return false;
        } finally {
            running = false;
        }
    }

    public boolean stop() {
        try {
            Process p = new ProcessBuilder(SU_PATH, "-c", KROOTD_PATH + " --stop")
                    .redirectErrorStream(true)
                    .start();
            p.waitFor();
            state.setDaemonStatus(RootState.DaemonStatus.OFFLINE);
            state.setLastMessage("krootd parado");
            Log.i(TAG, "krootd parado");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Erro parando krootd", e);
            return false;
        }
    }

    public boolean isRunning() {
        return running;
    }

    public void stopRunning() {
        running = false;
    }
}
