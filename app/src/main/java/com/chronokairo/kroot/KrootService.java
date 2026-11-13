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

/**
 * Serviço de supervisão do Kairos Root.
 *
 * Responsabilidades:
 *  - Iniciar o BootstrapController em thread separada (não bloqueia UI)
 *  - Supervisionar o estado do krootd via DaemonManager
 *  - Gerenciar serviços auxiliares (chroot, sshd, ttyd) via ServiceManager
 *  - Manter notification de foreground
 *  - Watchdog para verificação periódica
 *
 * Não executa operações de bootstrap diretamente; delega ao BootstrapController.
 */
public class KrootService extends Service {

    private static final String TAG = "KrootService";
    private static final String CHANNEL_ID = "kroot_channel";
    private static final int NOTIFICATION_ID = 1;

    // Componentes da nova arquitetura
    private RootState rootState;
    private LogManager logManager;
    private BootstrapController bootstrapController;
    private DaemonManager daemonManager;
    private ServiceManager serviceManager;

    private Handler handler;
    private Runnable watchdogRunnable;
    private volatile boolean running = false;

    @Override
    public void onCreate() {
        super.onCreate();
        rootState = new RootState();
        logManager = new LogManager();
        daemonManager = new DaemonManager(rootState);
        serviceManager = new ServiceManager(rootState);
        RootProvider provider = new MaliUafJavaProvider(this);
        bootstrapController = new BootstrapController(rootState, provider, daemonManager);

        // Inicializar UID do Android
        rootState.setAndroidUid(android.os.Process.myUid());
        logManager.i(TAG, "Serviço criado. UID=" + rootState.getAndroidUid());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (running) {
            return START_STICKY;
        }
        running = true;

        createNotificationChannel();
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Kairos Root")
                .setContentText("Inicializando...")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build();
        startForeground(NOTIFICATION_ID, notification);

        logManager.i(TAG, "Serviço iniciado");

        // Configurar canal de notificação
        createNotificationChannel();

        // Executar bootstrap e serviços em thread separada (não bloqueia UI)
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
            // Executar diagnóstico inicial
            DiagnosticEngine.Result diag = new DiagnosticEngine().runDiagnostics();
            logManager.i(TAG, "Diagnóstico: UID=" + diag.androidUid + " root=" + diag.isRoot
                    + " mali=" + diag.maliBinaryExists + " krootd=" + diag.krootdBinaryExists);
            rootState.setLastLog(diag.log.toString());

            // Executar bootstrap (Mali UAF se necessário, depois iniciar krootd)
            boolean rootOk = bootstrapController.ensureRootAvailable();

            if (rootOk) {
                // Montar chroot e iniciar serviços auxiliares
                serviceManager.mountChroot();
                serviceManager.startSshd();
                serviceManager.startTtyd();
                updateNotification("krootd online - serviços ativos");
            } else {
                updateNotification("Root não disponível - aguardando bootstrap Mali");
            }

            // Iniciar watchdog
            startWatchdog();

        } catch (Exception e) {
            logManager.e(TAG, "Erro na inicialização: " + e.getMessage());
            Log.e(TAG, "Erro na inicialização", e);
        }
    }

    private void startWatchdog() {
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
    }

    private void checkAndHeal() {
        logManager.d(TAG, "Watchdog: verificando serviços...");

        // Verificar krootd
        boolean krootdOnline = daemonManager.isOnline();
        if (!krootdOnline) {
            logManager.w(TAG, "krootd offline, tentando reiniciar...");
            if (rootState.isRootAvailable()) {
                daemonManager.start();
            } else {
                logManager.w(TAG, "Root não disponível para reiniciar krootd");
            }
        }

        // Atualizar notificação com status atual
        if (rootState.isRootAvailable() && krootdOnline) {
            updateNotification("Root ativo - krootd online");
        } else if (rootState.isRootAvailable()) {
            updateNotification("Root ativo - krootd offline");
        } else {
            updateNotification("Root não disponível");
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
        if (handler != null && watchdogRunnable != null) {
            handler.removeCallbacks(watchdogRunnable);
        }
        if (daemonManager != null) {
            daemonManager.stopRunning();
        }
        logManager.i(TAG, "Serviço destruído");
        super.onDestroy();
    }

    // Getters para exposição de estado (útil para futura integração com ViewModel)
    public RootState getRootState() {
        return rootState;
    }

    public LogManager getLogManager() {
        return logManager;
    }
}
