package com.chronokairo.kroot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Receptor de boot do Kairos Root.
 *
 * Ao receber BOOT_COMPLETED:
 *  - Registra o estado do boot no BootStateStore
 *  - Inicia o KrootService (que executará o bootstrap em thread separada)
 *
 * Não inicia serviços diretamente na thread do receiver; delega ao serviço.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            Log.w(TAG, "Intent nula recebida");
            return;
        }

        String action = intent.getAction();
        Log.d(TAG, "Recebido intent: " + action);

        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {

            // Registrar estado do boot
            BootStateStore store = new BootStateStore(context);
            store.resetForNewBoot();

            // Iniciar serviço de supervisão (executa bootstrap em thread separada)
            Intent service = new Intent(context, KrootService.class);
            context.startService(service);

            Log.i(TAG, "KrootService iniciado após boot");
        }
    }
}
