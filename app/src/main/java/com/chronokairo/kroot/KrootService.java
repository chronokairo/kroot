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

    private volatile boolean running = false;

    @Override
    public void onCreate() {
        super.onCreate();
        rootState = new RootState();
        logManager = new LogManager();
        RootProvider provider = new MaliUafJavaProvider(this);
        daemonManager = new DaemonManager(rootState, provider);
        serviceManager = new ServiceManager(rootState);
        bootstrapController = new BootstrapController(rootState, provider, daemonManager);

        // Inicializar UID do Android
        rootState.setAndroidUid(android.os.Process.myUid());
        logManager.i(TAG, "Serviço criado. UID=" + rootState.getAndroidUid());

        // Ouvir mudanças de estado e notificar a UI via broadcast
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

        // Se já estiver rodando e for uma solicitação de bootstrap, executar em nova thread
        if ("com.chronokairo.kroot.action.BOOTSTRAP".equals(action)) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    Log.i(TAG, "Solicitação de bootstrap recebida");
                    boolean rootOk = bootstrapController.ensureRootAvailable();
                    if (rootOk) {
                        serviceManager.mountChroot();
                        serviceManager.startSshd();
                        serviceManager.startTtyd();
                        updateNotification("krootd online - serviços ativos");
                    } else {
                        updateNotification("Root não disponível - aguardando bootstrap Mali");
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
        // Watchdog em thread separada para não bloquear a UI thread
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
