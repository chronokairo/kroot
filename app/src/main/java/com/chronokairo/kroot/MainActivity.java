package com.chronokairo.kroot;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Launcher principal do Kairos Root.
 *
 * Responsabilidades:
 *  - Exibir interface imediatamente (não espera por root)
 *  - Observar estado do RootState via callbacks
 *  - Iniciar KrootService (supervisão) em background
 *  - Exibir botão de ativação para bootstrap
 *  - Carregar apps do usuário como tiles
 *
 * Não executa operações de bootstrap diretamente; delega ao serviço.
 */
public class MainActivity extends Activity {

    private TextView statusView;
    private TextView clockView;
    private TextView rootStateView;
    private GridLayout appsGrid;
    private Button btnActivate;
    private Handler handler;

    // Recebe atualizações de estado do KrootService
    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("com.chronokairo.kroot.action.STATE_CHANGED".equals(intent.getAction())) {
                String daemon = intent.getStringExtra("daemon");
                boolean root = intent.getBooleanExtra("root", false);
                String message = intent.getStringExtra("message");
                updateUiFromState(daemon, root, message);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Fullscreen imersivo
        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN);
        setContentView(R.layout.activity_main);

        statusView = findViewById(R.id.status);
        clockView = findViewById(R.id.clock);
        rootStateView = findViewById(R.id.root_state);
        appsGrid = findViewById(R.id.apps_grid);
        btnActivate = findViewById(R.id.btn_activate);

        // Relógio
        handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            @Override
            public void run() {
                updateClock();
                handler.postDelayed(this, 1000);
            }
        });

        // Botões da barra inferior
        findViewById(R.id.btn_ubuntu).setOnClickListener(v -> openUbuntuTerminal());
        findViewById(R.id.btn_apps).setOnClickListener(v -> {
            startActivity(new Intent(this, AppListActivity.class));
        });
        findViewById(R.id.btn_status).setOnClickListener(v -> checkStatus());

        // Botão principal de ativação do root
        btnActivate.setOnClickListener(v -> activateRoot());

        // Botão: Tile Ubuntu Chroot
        findViewById(R.id.tile_ubuntu).setOnClickListener(v -> openUbuntuTerminal());

        // Botão: Todos os Apps
        findViewById(R.id.btn_all_apps).setOnClickListener(v -> {
            startActivity(new Intent(this, AppListActivity.class));
        });

        // Carregar apps do usuário como tiles
        loadUserApps();

        // Status inicial (não bloqueia UI)
        updateStatusView("Inicializando...");

        // Iniciar serviço de supervisão (em background)
        startService(new Intent(this, KrootService.class));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Ouvir atualizações de estado do serviço
        IntentFilter filter = new IntentFilter("com.chronokairo.kroot.action.STATE_CHANGED");
        registerReceiver(stateReceiver, filter);
        // Solicitar status atual
        checkStatus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(stateReceiver);
        } catch (Exception ignored) {
        }
    }

    private void updateUiFromState(String daemon, boolean root, String message) {
        if (rootStateView != null) {
            StringBuilder sb = new StringBuilder();
            sb.append("Daemon: ").append(daemon != null ? daemon : "desconhecido").append("\n");
            sb.append("Root: ").append(root ? "DISPONÍVEL" : "indisponível").append("\n");
            if (message != null) {
                sb.append(message);
            }
            rootStateView.setText(sb.toString());
        }
        if (statusView != null && message != null) {
            statusView.setText(message);
        }

        // Atualizar botão de ativação
        if (btnActivate != null) {
            if (root) {
                btnActivate.setEnabled(false);
                btnActivate.setText("Root Ativo");
            } else {
                btnActivate.setEnabled(true);
                btnActivate.setText("Ativar KROOT");
            }
        }
    }

    private void activateRoot() {
        // Solicitar ao serviço que execute o bootstrap
        // O serviço roda em thread separada, não bloqueia a UI
        btnActivate.setEnabled(false);
        btnActivate.setText("Ativando...");

        new Thread(new Runnable() {
            @Override
            public void run() {
                // Enviar broadcast para o serviço tentar o bootstrap
                // (implementado via intent com action)
                Intent intent = new Intent(MainActivity.this, KrootService.class);
                intent.setAction("com.chronokairo.kroot.action.BOOTSTRAP");
                startService(intent);

                // Aguardar e atualizar UI
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {}
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        btnActivate.setEnabled(true);
                        btnActivate.setText("Verificar Root");
                        updateStatusView("Verifique o status acima");
                    }
                });
            }
        }).start();
    }

    private void updateClock() {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("HH:mm");
        java.text.SimpleDateFormat dateFmt = new java.text.SimpleDateFormat("dd MMM");
        java.util.Locale ptBr = new java.util.Locale("pt", "BR");
        clockView.setText(sdf.format(new java.util.Date()));
        TextView dateView = findViewById(R.id.date);
        if (dateView != null) {
            dateView.setText(dateFmt.format(new java.util.Date()).toUpperCase(ptBr));
        }
    }

    private void loadUserApps() {
        PackageManager pm = getPackageManager();
        Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);

        List<ResolveInfo> apps = pm.queryIntentActivities(mainIntent, 0);
        Collections.sort(apps, (a, b) -> {
            String la = a.loadLabel(pm) != null ? a.loadLabel(pm).toString() : "";
            String lb = b.loadLabel(pm) != null ? b.loadLabel(pm).toString() : "";
            return la.compareToIgnoreCase(lb);
        });

        int index = 0;
        for (ResolveInfo info : apps) {
            String pkg = info.activityInfo.packageName;
            if (pkg.equals("com.chronokairo.kroot")) continue;

            View tile = createAppTile(info, pm);
            GridLayout.Spec rowSpec = GridLayout.spec(index / 3);
            GridLayout.Spec colSpec = GridLayout.spec(index % 3);
            GridLayout.LayoutParams params = new GridLayout.LayoutParams(rowSpec, colSpec);
            params.width = ViewGroup.LayoutParams.MATCH_PARENT;
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            params.setGravity(Gravity.FILL);
            params.setMargins(4, 4, 4, 4);
            appsGrid.addView(tile, params);
            index++;
        }
    }

    private View createAppTile(ResolveInfo info, PackageManager pm) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        tile.setPadding(8, 8, 8, 8);
        tile.setBackgroundColor(Color.parseColor("#FFFFFF"));
        tile.setClickable(true);
        tile.setFocusable(true);

        ImageView icon = new ImageView(this);
        icon.setImageDrawable(info.loadIcon(pm));
        int px = (int) (48 * getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(px, px);
        iconParams.gravity = Gravity.CENTER_HORIZONTAL;
        icon.setLayoutParams(iconParams);

        TextView label = new TextView(this);
        label.setText(info.loadLabel(pm));
        label.setTextSize(12f);
        label.setTextColor(Color.parseColor("#000000"));
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(2);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);

        tile.addView(icon);
        tile.addView(label);

        final String launchPkg = info.activityInfo.packageName;
        tile.setOnClickListener(v -> {
            Intent launchIntent = pm.getLaunchIntentForPackage(launchPkg);
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(launchIntent);
            }
        });

        return tile;
    }

    private void openUbuntuTerminal() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Process p = Runtime.getRuntime().exec(new String[]{
                            "/data/local/xbin/su", "-c",
                            "mount -t proc proc /data/local/ubuntu/proc 2>/dev/null; " +
                                    "mount -t sysfs sys /data/local/ubuntu/sys 2>/dev/null; " +
                                    "mount --bind /dev /data/local/ubuntu/dev 2>/dev/null; " +
                                    "mount --bind /dev/pts /data/local/ubuntu/dev/pts 2>/dev/null; " +
                                    "chroot /data/local/ubuntu /bin/bash --login"});
                    p.waitFor();
                } catch (Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(MainActivity.this,
                                    "Erro: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }

    private void checkStatus() {
        if (statusView == null) return;

        // Executar verificações fora da UI thread
        new Thread(new Runnable() {
            @Override
            public void run() {
                StringBuilder sb = new StringBuilder();
                try {
                    Process p1 = Runtime.getRuntime().exec(new String[]{"/data/local/xbin/su", "--status"});
                    java.io.BufferedReader br1 = new java.io.BufferedReader(
                            new java.io.InputStreamReader(p1.getInputStream()));
                    String line;
                    while ((line = br1.readLine()) != null) sb.append(line).append("\n");
                    p1.waitFor();
                } catch (Exception e) {
                    sb.append("Erro ao verificar status: ").append(e.getMessage()).append("\n");
                }

                final String result = sb.toString();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        updateStatusView(result);
                    }
                });
            }
        }).start();
    }

    private void updateStatusView(String text) {
        if (statusView != null) {
            statusView.setText(text);
        }
        if (rootStateView != null) {
            rootStateView.setText(text);
        }
    }
}
