package com.chronokairo.kroot;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Kairos Root Launcher — Dopamine-inspired minimal interface.
 *
 * Responsibilities:
 *  - Show interface immediately (never waits for root)
 *  - Observe RootState via broadcast from KrootService
 *  - Start KrootService (supervision) in background
 *  - Show big activation button for bootstrap
 *  - Secondary actions: Status, Apps, Ubuntu
 */
public class MainActivity extends Activity {

    private TextView statusValue;
    private TextView statusDetail;
    private Button btnActivate;
    private Button btnStatus;
    private Button btnApps;
    private Button btnUbuntu;

    // Receives state updates from KrootService
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

        // Immersive fullscreen
        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN);
        setContentView(R.layout.activity_main);

        statusValue = findViewById(R.id.status_value);
        statusDetail = findViewById(R.id.status_detail);
        btnActivate = findViewById(R.id.btn_activate);
        btnStatus = findViewById(R.id.btn_status);
        btnApps = findViewById(R.id.btn_apps);
        btnUbuntu = findViewById(R.id.btn_ubuntu);

        // Secondary actions
        btnStatus.setOnClickListener(v -> checkStatus());
        btnApps.setOnClickListener(v -> {
            startActivity(new Intent(this, AppListActivity.class));
        });
        btnUbuntu.setOnClickListener(v -> openUbuntuTerminal());

        // Main activation button
        btnActivate.setOnClickListener(v -> activateRoot());

        // Initial status
        updateStatus("Checking...", "Tap Activate to start");

        // Start supervision service (background)
        startService(new Intent(this, KrootService.class));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Listen for state updates from service
        IntentFilter filter = new IntentFilter("com.chronokairo.kroot.action.STATE_CHANGED");
        registerReceiver(stateReceiver, filter);
        // Request current status
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
        if (statusValue != null) {
            statusValue.setText(root ? "ROOT ACTIVE" : "ROOT OFF");
        }
        if (statusDetail != null) {
            statusDetail.setText(message != null ? message : "Tap Activate to start");
        }

        // Update activation button
        if (btnActivate != null) {
            if (root) {
                btnActivate.setEnabled(false);
                btnActivate.setText("Root Active");
                btnActivate.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                        android.graphics.Color.parseColor("#007AFF")));
            } else {
                btnActivate.setEnabled(true);
                btnActivate.setText("Activate KROOT");
                btnActivate.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                        android.graphics.Color.parseColor("#007AFF")));
            }
        }
    }

    private void activateRoot() {
        // Ask service to run bootstrap (thread-separated, non-blocking UI)
        btnActivate.setEnabled(false);
        btnActivate.setText("Activating...");
        updateStatus("Activating...", "Running Mali UAF bootstrap...");

        new Thread(new Runnable() {
            @Override
            public void run() {
                // Ask service to try bootstrap via intent action
                Intent intent = new Intent(MainActivity.this, KrootService.class);
                intent.setAction("com.chronokairo.kroot.action.BOOTSTRAP");
                startService(intent);

                // Wait and refresh UI
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {}
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        btnActivate.setEnabled(true);
                        btnActivate.setText("Check Root");
                        updateStatus("Check status above", "Tap Status for details");
                    }
                });
            }
        }).start();
    }

    private void updateStatus(String value, String detail) {
        if (statusValue != null) {
            statusValue.setText(value);
        }
        if (statusDetail != null) {
            statusDetail.setText(detail);
        }
    }

    private void checkStatus() {
        // Run checks off UI thread
        new Thread(new Runnable() {
            @Override
            public void run() {
                StringBuilder sb = new StringBuilder();
                try {
                    Process p = Runtime.getRuntime().exec(new String[]{
                            "/data/local/xbin/su", "--status"});
                    java.io.BufferedReader br = new java.io.BufferedReader(
                            new java.io.InputStreamReader(p.getInputStream()));
                    String line;
                    while ((line = br.readLine()) != null) {
                        sb.append(line).append("\n");
                    }
                    p.waitFor();
                } catch (Exception e) {
                    sb.append("Error checking status: ").append(e.getMessage()).append("\n");
                }

                final String result = sb.toString().trim();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (result.contains("online")) {
                            updateStatus("ROOT ACTIVE", "krootd online — root available");
                        } else {
                            updateStatus("ROOT OFF", "krootd offline — tap Activate");
                        }
                    }
                });
            }
        }).start();
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
                                    "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }
}
