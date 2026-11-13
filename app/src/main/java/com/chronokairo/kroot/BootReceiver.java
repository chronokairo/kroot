package com.chronokairo.kroot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            // Inicia o serviço do krootd após o boot
            Intent service = new Intent(context, KrootService.class);
            context.startService(service);
        }
    }
}
