package com.chronokairo.kroot;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;
import android.util.Log;

/**
 * KrootService - Supervision service for Kairos Root.
 *
 * Responsibilities:
 *  - Initialize BinaryInstaller (embed binaries, run Mali UAF, start krootd)
 *  - Supervise krootd via DaemonManager
 *  - Manage auxiliary services (chroot, sshd, ttyd) via ServiceManager
 *  - Maintain foreground notification
 *  - Watchdog for periodic verification
 *
 * All binary paths use /data/local/xbin/ exclusively.
 */
public class KrootService extends Service {

    private static final String TAG = "KrootService";
    private static final String CHANNEL_ID = "kroot_channel";
    private static final int NOTIFICATION_ID = 1;

    // Components
    private RootState rootState;
    private LogManager logManager;
    private BinaryInstaller binaryInstaller;
    private DaemonManager daemonManager;
    private ServiceManager serviceManager;
    private RootProvider rootProvider;

    private volatile boolean running = false;

    @Override
    public void onCreate() {
        super.onCreate();
        rootState = new RootState();
        logManager = new LogManager();
        rootProvider = new MaliUafJavaProvider(this);
        binaryInstaller = new BinaryInstaller(this, rootState, logManager);
        daemonManager = new DaemonManager(rootState, rootProvider);
        serviceManager = new ServiceManager(rootState);

        rootState.setAndroidUid(android.os.Process.myUid());
        logManager.i(TAG, "Service created. UID=" + rootState.getAndroidUid());

        // Listen for state changes and notify UI via broadcast
        rootState.addListener(new RootState.StateListener() {
            @Override
            public void onStateChanged(RootState state) {
                Intent broadcast = new Intent("com.chronokairo.kroot.action.STATE_CHANGED");
                broadcast.putExtra("bootstrap", state.getBootstrapStatus().name());
                broadcast.putExtra("daemon", state.getDaemonStatus().name());
                broadcast.putExtra("root", state.isRootAvailable());
                broadcast.putExtra("message", state.getLastMessage());
                sendBroadcast(broadcast);
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        // BOOTSTRAP action: run install-and-use flow
        if ("com.chronokairo.kroot.action.BOOTSTRAP".equals(action)) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    Log.i(TAG, "Bootstrap requested via BOOTSTRAP action");
                    boolean rootOk = binaryInstaller.installAndUse();
                    if (rootOk) {
                        serviceManager.mountChroot();
                        serviceManager.startSshd();
                        serviceManager.startTtyd();
                        updateNotification("krootd online - services active");
                    } else {
                        updateNotification("Root unavailable - run Mali UAF bootstrap");
                    }
                }
            }).start();
            return START_STICKY;
        }

        if (running) {
            return START_STICKY;
        }
        running = true;

        createNotificationChannel();
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Kairos Root")
                .setContentText("Initializing...")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build();
        startForeground(NOTIFICATION_ID, notification);

        logManager.i(TAG, "Service started");

        // Run install-and-use in background thread (non-blocking UI)
        new Thread(new Runnable() {
            @Override
            public void run() {
                initServices();
            }
        }).start();

        return START_STICKY;
    }

    private void initServices() {
        try {
            // Initial diagnostics
            DiagnosticEngine.Result diag = new DiagnosticEngine().runDiagnostics();
            logManager.i(TAG, "Diagnostics: UID=" + diag.androidUid + " root=" + diag.isRoot
                    + " mali=" + diag.maliBinaryExists + " krootd=" + diag.krootdBinaryExists);
            rootState.setLastLog(diag.log.toString());

            // Install and use: copy binaries, run Mali UAF, install as root, start krootd
            boolean rootOk = binaryInstaller.installAndUse();

            if (rootOk) {
                serviceManager.mountChroot();
                serviceManager.startSshd();
                serviceManager.startTtyd();
                updateNotification("krootd online - services active");
            } else {
                updateNotification("Root unavailable - tap Activate KROOT");
            }

            // Start watchdog
            startWatchdog();

        } catch (Exception e) {
            logManager.e(TAG, "Initialization error: " + e.getMessage());
            Log.e(TAG, "Initialization error", e);
        }
    }

    private void startWatchdog() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                while (running) {
                    try {
                        Thread.sleep(30000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (!running) return;
                    checkAndHeal();
                }
            }
        }).start();
    }

    private void checkAndHeal() {
        logManager.d(TAG, "Watchdog: checking services...");

        boolean krootdOnline = daemonManager.isOnline();
        if (!krootdOnline) {
            logManager.w(TAG, "krootd offline, attempting restart...");
            if (rootState.isRootAvailable()) {
                daemonManager.start();
            } else {
                logManager.w(TAG, "Root unavailable to restart krootd");
            }
        }

        // Update notification with current status
        if (rootState.isRootAvailable() && krootdOnline) {
            updateNotification("Root active - krootd online");
        } else if (rootState.isRootAvailable()) {
            updateNotification("Root active - krootd offline");
        } else {
            updateNotification("Root unavailable");
        }
    }

    private void updateNotification(String text) {
        createNotificationChannel();
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Kairos Root")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build();
        startForeground(NOTIFICATION_ID, notification);
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

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (daemonManager != null) {
            daemonManager.stopRunning();
        }
        logManager.i(TAG, "Service destroyed");
        super.onDestroy();
    }

    public RootState getRootState() {
        return rootState;
    }

    public LogManager getLogManager() {
        return logManager;
    }
}
