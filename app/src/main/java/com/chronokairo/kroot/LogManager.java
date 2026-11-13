package com.chronokairo.kroot;

import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Gerencia histórico de eventos do sistema Kairos Root.
 * Mantém os últimos N logs em memória para exibição na UI.
 */
public class LogManager {

    private static final String TAG = "LogManager";
    private static final int MAX_LOGS = 500;

    private final List<String> logs = Collections.synchronizedList(new ArrayList<>());

    public void log(String level, String tag, String message) {
        String entry = java.text.SimpleDateFormat.getDateTimeInstance()
                .format(new java.util.Date()) + " [" + level + "] " + tag + ": " + message;
        synchronized (logs) {
            logs.add(entry);
            if (logs.size() > MAX_LOGS) {
                logs.remove(0);
            }
        }
        Log.println(
                "V".equals(level) ? android.util.Log.VERBOSE :
                        "D".equals(level) ? android.util.Log.DEBUG :
                                "I".equals(level) ? android.util.Log.INFO :
                                        "W".equals(level) ? android.util.Log.WARN :
                                                "E".equals(level) ? android.util.Log.ERROR : android.util.Log.INFO,
                tag, message);
    }

    public void v(String tag, String msg) { log("V", tag, msg); }
    public void d(String tag, String msg) { log("D", tag, msg); }
    public void i(String tag, String msg) { log("I", tag, msg); }
    public void w(String tag, String msg) { log("W", tag, msg); }
    public void e(String tag, String msg) { log("E", tag, msg); }

    public List<String> getLogs() {
        synchronized (logs) {
            return new ArrayList<>(logs);
        }
    }

    public List<String> getLastN(int n) {
        synchronized (logs) {
            int start = Math.max(0, logs.size() - n);
            return new ArrayList<>(logs.subList(start, logs.size()));
        }
    }

    public void clear() {
        synchronized (logs) {
            logs.clear();
        }
    }
}
