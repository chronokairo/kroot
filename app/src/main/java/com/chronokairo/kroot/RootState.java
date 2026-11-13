package com.chronokairo.kroot;

/**
 * Estado centralizado do sistema Kairos Root.
 * Permite que UI, serviço e controladores observem o mesmo estado sem acoplamento direto.
 */
public class RootState {

    public enum BootstrapStatus {
        UNKNOWN,       // Ainda não verificado
        NOT_RUN,       // Bootstrap Mali não executado neste boot
        RUNNING,       // Em execução
        SUCCEEDED,     // Obteve UID 0 com sucesso
        FAILED,        // Falhou
        UNAVAILABLE    // Exploit não encontrado no dispositivo
    }

    public enum DaemonStatus {
        UNKNOWN, OFFLINE, STARTING, ONLINE, ERROR
    }

    public enum AuthorizationStatus {
        UNKNOWN, NOT_AUTHORIZED, AUTHORIZED
    }

    // Estado do bootstrap Mali UAF
    private volatile BootstrapStatus bootstrapStatus = BootstrapStatus.UNKNOWN;

    // Estado do daemon krootd
    private volatile DaemonStatus daemonStatus = DaemonStatus.UNKNOWN;

    // Se o pacote Kairos Root está autorizado na policy
    private volatile AuthorizationStatus authorizationStatus = AuthorizationStatus.UNKNOWN;

    // UID atual do processo Android
    private volatile int androidUid = -1;

    // Se root (UID 0) está disponível via su
    private volatile boolean rootAvailable = false;

    // Última mensagem de erro/diagnóstico
    private volatile String lastMessage = "";

    // Último log de evento
    private volatile String lastLog = "";

    public BootstrapStatus getBootstrapStatus() { return bootstrapStatus; }
    public void setBootstrapStatus(BootstrapStatus s) { this.bootstrapStatus = s; }

    public DaemonStatus getDaemonStatus() { return daemonStatus; }
    public void setDaemonStatus(DaemonStatus s) { this.daemonStatus = s; }

    public AuthorizationStatus getAuthorizationStatus() { return authorizationStatus; }
    public void setAuthorizationStatus(AuthorizationStatus s) { this.authorizationStatus = s; }

    public int getAndroidUid() { return androidUid; }
    public void setAndroidUid(int uid) { this.androidUid = uid; }

    public boolean isRootAvailable() { return rootAvailable; }
    public void setRootAvailable(boolean available) { this.rootAvailable = available; }

    public String getLastMessage() { return lastMessage; }
    public void setLastMessage(String msg) { this.lastMessage = msg; }

    public String getLastLog() { return lastLog; }
    public void setLastLog(String log) { this.lastLog = log; }

    @Override
    public String toString() {
        return "RootState{bootstrap=" + bootstrapStatus +
                ", daemon=" + daemonStatus +
                ", auth=" + authorizationStatus +
                ", uid=" + androidUid +
                ", root=" + rootAvailable + "}";
    }
}
