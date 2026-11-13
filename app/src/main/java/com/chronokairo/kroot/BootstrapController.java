package com.chronokairo.kroot;

import android.util.Log;

/**
 * Coordena a sequência de inicialização do root:
 *
 * 1. Verificar se krootd já está online
 * 2. Se não, verificar se root está disponível via su
 * 3. Se não, executar bootstrap Mali UAF (uma vez por boot)
 * 4. Confirmar UID 0
 * 5. Iniciar krootd como root
 * 6. Validar socket e autorização
 *
 * Todas as operações são executadas em background, nunca bloqueando a UI.
 */
public class BootstrapController {

    private static final String TAG = "BootstrapController";

    private final RootState state;
    private final RootProvider provider;
    private final DaemonManager daemonManager;
    private volatile boolean running = false;

    public BootstrapController(RootState state, RootProvider provider, DaemonManager daemonManager) {
        this.state = state;
        this.provider = provider;
        this.daemonManager = daemonManager;
    }

    /**
     * Executa a sequência completa de bootstrap. Deve ser chamado em thread separada.
     * Retorna true se o root ficou disponível e o krootd está online.
     */
    public boolean ensureRootAvailable() {
        if (running) {
            Log.w(TAG, "Bootstrap já em execução");
            return state.isRootAvailable();
        }
        running = true;

        try {
            // Passo 1: krootd já está online?
            if (provider.isKrootdOnline()) {
                Log.i(TAG, "krootd já está online");
                state.setDaemonStatus(RootState.DaemonStatus.ONLINE);
                state.setRootAvailable(true);
                state.setBootstrapStatus(RootState.BootstrapStatus.SUCCEEDED);
                state.setLastMessage("Root disponível (krootd online)");
                return true;
            }

            // Passo 2: Root já disponível via su?
            if (provider.hasRoot()) {
                Log.i(TAG, "UID 0 já disponível via su");
                state.setRootAvailable(true);
                state.setBootstrapStatus(RootState.BootstrapStatus.SUCCEEDED);
                state.setLastMessage("UID 0 disponível via su");
            } else {
                // Passo 3: Executar bootstrap Mali UAF
                Log.i(TAG, "UID 0 não disponível. Executando bootstrap Mali UAF...");
                state.setBootstrapStatus(RootState.BootstrapStatus.RUNNING);
                state.setLastMessage("Executando bootstrap Mali UAF...");

                if (!provider.isAvailable()) {
                    Log.w(TAG, "Provedor " + provider.getName() + " não disponível");
                    state.setBootstrapStatus(RootState.BootstrapStatus.UNAVAILABLE);
                    state.setLastMessage("Exploit Mali não encontrado no dispositivo");
                    return false;
                }

                boolean acquired = provider.acquireRoot();
                if (!acquired) {
                    Log.w(TAG, "Falha ao obter UID 0 via " + provider.getName());
                    state.setBootstrapStatus(RootState.BootstrapStatus.FAILED);
                    state.setLastMessage("Falha no bootstrap: sem UID 0");
                    return false;
                }

                state.setRootAvailable(true);
                state.setBootstrapStatus(RootState.BootstrapStatus.SUCCEEDED);
                state.setLastMessage("UID 0 obtido via " + provider.getName());
                Log.i(TAG, "UID 0 obtido com sucesso");
            }

            // Passo 4: Iniciar krootd
            Log.i(TAG, "Iniciando krootd como root...");
            state.setDaemonStatus(RootState.DaemonStatus.STARTING);
            boolean started = provider.startKrootd();
            if (!started) {
                state.setDaemonStatus(RootState.DaemonStatus.ERROR);
                state.setLastMessage("Falha ao iniciar krootd");
                return false;
            }

            state.setDaemonStatus(RootState.DaemonStatus.ONLINE);
            state.setLastMessage("krootd online - root disponível");
            Log.i(TAG, "Bootstrap concluído com sucesso");
            return true;

        } finally {
            running = false;
        }
    }

    public boolean isRunning() {
        return running;
    }
}
