package com.chronokairo.kroot;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AppListActivity extends Activity {

    private ListView appsList;
    private ArrayAdapter<ResolveInfo> adapter;
    private List<ResolveInfo> appList;

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
        setContentView(R.layout.activity_app_list);

        Button btnBack = findViewById(R.id.btn_back);
        btnBack.setOnClickListener(v -> finish());

        appsList = findViewById(R.id.apps_list);
        appList = new ArrayList<>();

        // Carrega todos os apps instalados
        loadApps();

        adapter = new ArrayAdapter<ResolveInfo>(this, R.layout.list_item_app, appList) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                if (convertView == null) {
                    convertView = getLayoutInflater().inflate(R.layout.list_item_app, parent, false);
                }
                ResolveInfo info = getItem(position);
                ImageView icon = convertView.findViewById(R.id.app_icon);
                TextView label = convertView.findViewById(R.id.app_label);
                icon.setImageDrawable(info.loadIcon(getPackageManager()));
                label.setText(info.loadLabel(getPackageManager()));
                return convertView;
            }
        };
        appsList.setAdapter(adapter);

        appsList.setOnItemClickListener((parent, view, position, id) -> {
            ResolveInfo info = appList.get(position);
            Intent launchIntent = getPackageManager().getLaunchIntentForPackage(info.activityInfo.packageName);
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(launchIntent);
            }
        });
    }

    private void loadApps() {
        PackageManager pm = getPackageManager();
        Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);

        List<ResolveInfo> allApps = pm.queryIntentActivities(mainIntent, 0);
        Collections.sort(allApps, (a, b) -> {
            String la = a.loadLabel(pm) != null ? a.loadLabel(pm).toString() : "";
            String lb = b.loadLabel(pm) != null ? b.loadLabel(pm).toString() : "";
            return la.compareToIgnoreCase(lb);
        });

        appList.addAll(allApps);
    }
}
