package com.chronokairo.kroot;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.os.AsyncTask;
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
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MainActivity extends Activity {

    // TODO: validar o caminho de instalação no dispositivo alvo.
    // README/docs indicam /data/local/xbin/su.kroot; o deploy atual usa /data/local/tmp/kroot-build/su.kroot.
    private static final String SU_KROOT = "/data/local/tmp/kroot-build/su.kroot";
    private static final String CHROOT = "/data/local/ubuntu";
    private static final String POLICY_DIR = "/data/local/kroot";

    private TextView statusView;
    private TextView clockView;
    private GridLayout appsGrid;
    private Handler handler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Fullscreen imersivo: oculta status bar e navigation bar
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
        appsGrid = findViewById(R.id.apps_grid);

        // Relógio atualizando a cada segundo
        handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            @Override
            public void run() {
                updateClock();
                handler.postDelayed(this, 1000);
            }
        });

        // Botão: Tile Ubuntu Chroot
        findViewById(R.id.tile_ubuntu).setOnClickListener(v -> openUbuntuTerminal());

        // Botão: Todos os Apps
        findViewById(R.id.btn_all_apps).setOnClickListener(v -> {
            startActivity(new Intent(this, AppListActivity.class));
        });

        // Botão inferior: Ubuntu
        findViewById(R.id.btn_ubuntu).setOnClickListener(v -> openUbuntuTerminal());

        // Botão inferior: Apps
        findViewById(R.id.btn_apps).setOnClickListener(v -> {
            startActivity(new Intent(this, AppListActivity.class));
        });

        // Botão inferior: Status
        findViewById(R.id.btn_status).setOnClickListener(v -> checkStatus());

        // Carrega apps do usuário como tiles
        loadUserApps();

        // Status inicial
        checkStatus();
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
            // Ignorar o próprio Kairos Root e apps do sistema
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

    // Executa comandos de root fora da UI thread para evitar ANR / bloqueio.
    private static class RootTask extends AsyncTask<String[], Void, String> {
        private final MainActivity activity;
        private final boolean formatStatus;

        RootTask(MainActivity activity, boolean formatStatus) {
            this.activity = activity;
            this.formatStatus = formatStatus;
        }

        @Override
        protected String doInBackground(String[]... cmds) {
            StringBuilder out = new StringBuilder();
            for (String[] cmd : cmds) {
                try {
                    Process p = Runtime.getRuntime().exec(cmd);
                    BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
                    String line;
                    while ((line = br.readLine()) != null) out.append(line).append("\n");
                    p.waitFor();
                    out.append("---CMD_END---\n");
                } catch (Exception e) {
                    out.append("Erro: ").append(e.getMessage()).append("\n---CMD_END---\n");
                }
            }
            if (formatStatus && out.length() > 0) {
                String[] parts = out.toString().split("---CMD_END---\n");
                String krootd = parts.length > 0 ? parts[0] : "";
                String uptime = parts.length > 1 ? parts[1] : "";
                String sshd = parts.length > 2 ? parts[2] : "";

                StringBuilder sb = new StringBuilder();
                sb.append("=== Kairos Root Status ===\n");
                sb.append("krootd: ").append(krootd.contains("online") ? "ONLINE" : "OFFLINE").append("\n");
                sb.append("chroot: ").append(uptime.trim()).append("\n");
                sb.append("sshd: ").append(sshd.contains("2222") ? "22/2222 OK" : sshd.trim()).append("\n");
                if (activity != null && activity.appsGrid != null) {
                    sb.append("Apps: ").append(activity.appsGrid.getChildCount()).append(" tiles\n");
                }
                sb.append("=========================\n");
                return sb.toString();
            }
            return out.toString().trim();
        }

        @Override
        protected void onPostExecute(String result) {
            if (activity != null && activity.statusView != null) {
                activity.statusView.setText(result);
            }
        }
    }

    private void runCmd(String[] cmd) {
        new RootTask(this, false).execute(cmd);
    }

    private void openUbuntuTerminal() {
        runCmd(new String[]{SU_KROOT, "-c",
            "mount -t proc proc " + CHROOT + "/proc 2>/dev/null; " +
            "mount -t sysfs sys " + CHROOT + "/sys 2>/dev/null; " +
            "mount --bind /dev " + CHROOT + "/dev 2>/dev/null; " +
            "mount --bind /dev/pts " + CHROOT + "/dev/pts 2>/dev/null; " +
            "chroot " + CHROOT + " /bin/bash --login"});
    }

    private void checkStatus() {
        if (statusView == null) {
            android.util.Log.e("KROOT", "StatusView não encontrado");
            return;
        }

        // Executa as três verificações totalmente fora da UI thread
        new RootTask(this, true).execute(
            new String[]{SU_KROOT, "--status"},
            new String[]{SU_KROOT, "-c",
                "chroot " + CHROOT + " /bin/bash -c 'uptime 2>/dev/null || echo n/a'"},
            new String[]{SU_KROOT, "-c",
                "chroot " + CHROOT + " /bin/bash -c 'ss -tlnp 2>/dev/null | grep -E \"22|2222\" || echo sshd_down'"}
        );
    }
}
