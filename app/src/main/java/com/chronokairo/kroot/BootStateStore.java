package com.chronokairo.kroot;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Armazena o estado de inicialização entre reinicializações.
 * Permite saber se o bootstrap já foi executado neste boot.
 */
public class BootStateStore {

    private static final String PREFS_NAME = "kroot_boot_state";
    private static final String KEY_BOOTSTRAP_DONE = "bootstrap_done";
    private static final String KEY_LAST_BOOT_TIME = "last_boot_time";
    private static final String KEY_ROOT_ACQUIRED = "root_acquired";
    private static final String KEY_DAEMON_STARTED = "daemon_started";

    private final SharedPreferences prefs;

    public BootStateStore(Context context) {
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public boolean isBootstrapDone() {
        return prefs.getBoolean(KEY_BOOTSTRAP_DONE, false);
    }

    public void setBootstrapDone(boolean done) {
        prefs.edit().putBoolean(KEY_BOOTSTRAP_DONE, done).apply();
    }

    public long getLastBootTime() {
        return prefs.getLong(KEY_LAST_BOOT_TIME, 0);
    }

    public void setLastBootTime(long time) {
        prefs.edit().putLong(KEY_LAST_BOOT_TIME, time).apply();
    }

    public boolean isRootAcquired() {
        return prefs.getBoolean(KEY_ROOT_ACQUIRED, false);
    }

    public void setRootAcquired(boolean acquired) {
        prefs.edit().putBoolean(KEY_ROOT_ACQUIRED, acquired).apply();
    }

    public boolean isDaemonStarted() {
        return prefs.getBoolean(KEY_DAEMON_STARTED, false);
    }

    public void setDaemonStarted(boolean started) {
        prefs.edit().putBoolean(KEY_DAEMON_STARTED, started).apply();
    }

    /**
     * Limpa o estado do boot atual (chamado em novo BOOT_COMPLETED).
     */
    public void resetForNewBoot() {
        prefs.edit()
                .putBoolean(KEY_BOOTSTRAP_DONE, false)
                .putBoolean(KEY_ROOT_ACQUIRED, false)
                .putBoolean(KEY_DAEMON_STARTED, false)
                .putLong(KEY_LAST_BOOT_TIME, System.currentTimeMillis())
                .apply();
    }
}
