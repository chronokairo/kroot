package com.chronokairo.kroot;

/**
 * Interface para o mecanismo de obtenção de privilégios root (UID 0).
 *
 * Implementações podem ser:
 *  - MaliUafProvider: executa o exploit Mali UAF do dispositivo
 *  - SuProvider: usa um su existente já com privilégios
 *  - MockProvider: para testes
 */
public interface RootProvider {

    /**
     * Nome descritivo do provedor (ex: "Mali UAF hawaii", "su existente").
     */
    String getName();

    /**
     * Verifica se o provedor está disponível neste dispositivo.
     * Ex: o binário mali_kroot existe em /data/local/tmp/mali_kroot.
     */
    boolean isAvailable();

    /**
     * Tenta obter UID 0. Retorna true se sucesso.
     * Deve ser executado fora da UI thread.
     */
    boolean acquireRoot();

    /**
     * Verifica se atualmente temos UID 0.
     */
    boolean hasRoot();

    /**
     * Inicia o krootd como root (depois de acquireRoot bem-sucedido).
     */
    boolean startKrootd();

    /**
     * Verifica se o krootd está online.
     */
    boolean isKrootdOnline();

    /**
     * Para o krootd.
     */
    boolean stopKrootd();
}
