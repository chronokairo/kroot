package com.chronokairo.kroot;

import android.util.Log;

/**
 * Gerencia o ciclo de vida do daemon krootd.
 *
 * O daemon SOMENTE pode ser iniciado com UID 0. O DaemonManager delega a
 * inicialização ao RootProvider (MaliUafJavaProvider), que garante a
 * elevação de privilégios antes de executar o krootd.
 *
 * Todos os caminhos de binário vêm do BinaryInstaller (diretório privado do app).
 */
public class DaemonManager {

    private static final String TAG = "DaemonManager";

    private final RootState state;
    private final RootProvider provider;
    private final String krootdPath;
    private final String suPath;
    private volatile boolean running = false;

    public DaemonManager(RootState state, RootProvider provider, BinaryInstaller installer) {
        this.state = state;
        this.provider = provider;
        this.krootdPath = installer.getKrootdPath();
        this.suPath = installer.getSuPath();
    }

    public boolean isOnline() {
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
            Log.e(TAG, "Erro verificando status do krootd", e);
            return false;
        }
    }

    /**
     * Inicia o krootd apenas se UID 0 estiver disponível.
     * Delegado ao RootProvider, que executa o bootstrap se necessário.
     */
    public boolean start() {
        if (isOnline()) {
            Log.i(TAG, "krootd já está online");
            state.setDaemonStatus(RootState.DaemonStatus.ONLINE);
            state.setRootAvailable(true);
            return true;
        }

        // Verificar se temos UID 0 antes de tentar iniciar o daemon
        if (!provider.hasRoot()) {
            Log.w(TAG, "UID 0 não disponível. Não é possível iniciar krootd.");
            state.setDaemonStatus(RootState.DaemonStatus.ERROR);
            state.setLastMessage("UID 0 não disponível para iniciar krootd");
            return false;
        }

        if (!new java.io.File(krootdPath).exists()) {
            Log.e(TAG, "krootd não encontrado em " + krootdPath);
            state.setDaemonStatus(RootState.DaemonStatus.ERROR);
            return false;
        }

        running = true;
        try {
            Log.i(TAG, "Iniciando krootd como UID 0...");
            state.setDaemonStatus(RootState.DaemonStatus.STARTING);

            // Delegar ao provedor, que tem contexto de privilégios
            boolean started = provider.startKrootd();
            if (!started) {
                state.setDaemonStatus(RootState.DaemonStatus.ERROR);
                state.setLastMessage("Falha ao iniciar krootd via provedor");
                Log.w(TAG, "krootd não iniciado pelo provedor");
                return false;
            }

            // Aguardar socket ficar online (máximo 10s)
            for (int i = 0; i < 20; i++) {
                if (!running) break;
                if (isOnline()) {
                    state.setDaemonStatus(RootState.DaemonStatus.ONLINE);
                    state.setRootAvailable(true);
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
            Process p = new ProcessBuilder(suPath, "-c", krootdPath + " --stop")
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
