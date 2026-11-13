package com.chronokairo.kroot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Estado centralizado do sistema Kairos Root.
 *
 * Suporta observers para que a UI (MainActivity) receba atualizações
 * quando o estado mudar (ex: krootd online, root disponível).
 */
public class RootState {

    public enum BootstrapStatus {
        UNKNOWN, NOT_RUN, RUNNING, SUCCEEDED, FAILED, UNAVAILABLE
    }

    public enum DaemonStatus {
        UNKNOWN, OFFLINE, STARTING, ONLINE, ERROR
    }

    public enum AuthorizationStatus {
        UNKNOWN, NOT_AUTHORIZED, AUTHORIZED
    }

    public interface StateListener {
        void onStateChanged(RootState state);
    }

    private final List<StateListener> listeners = Collections.synchronizedList(new ArrayList<>());

    private volatile BootstrapStatus bootstrapStatus = BootstrapStatus.UNKNOWN;
    private volatile DaemonStatus daemonStatus = DaemonStatus.UNKNOWN;
    private volatile AuthorizationStatus authorizationStatus = AuthorizationStatus.UNKNOWN;
    private volatile int androidUid = -1;
    private volatile boolean rootAvailable = false;
    private volatile String lastMessage = "";
    private volatile String lastLog = "";

    public void addListener(StateListener listener) {
        synchronized (listeners) {
            listeners.add(listener);
        }
    }

    public void removeListener(StateListener listener) {
        synchronized (listeners) {
            listeners.remove(listener);
        }
    }

    private void notifyListeners() {
        List<StateListener> copy;
        synchronized (listeners) {
            copy = new ArrayList<>(listeners);
        }
        for (StateListener l : copy) {
            try {
                l.onStateChanged(this);
            } catch (Exception ignored) {
            }
        }
    }

    public BootstrapStatus getBootstrapStatus() { return bootstrapStatus; }
    public void setBootstrapStatus(BootstrapStatus s) {
        this.bootstrapStatus = s;
        notifyListeners();
    }

    public DaemonStatus getDaemonStatus() { return daemonStatus; }
    public void setDaemonStatus(DaemonStatus s) {
        this.daemonStatus = s;
        notifyListeners();
    }

    public AuthorizationStatus getAuthorizationStatus() { return authorizationStatus; }
    public void setAuthorizationStatus(AuthorizationStatus s) {
        this.authorizationStatus = s;
        notifyListeners();
    }

    public int getAndroidUid() { return androidUid; }
    public void setAndroidUid(int uid) { this.androidUid = uid; }

    public boolean isRootAvailable() { return rootAvailable; }
    public void setRootAvailable(boolean available) {
        this.rootAvailable = available;
        notifyListeners();
    }

    public String getLastMessage() { return lastMessage; }
    public void setLastMessage(String msg) {
        this.lastMessage = msg;
        notifyListeners();
    }

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
