package com.example.telegramawgproxy;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            Log.w(ProxyService.TAG, "ignored receiver action=" + action);
            return;
        }
        Log.i(ProxyService.TAG, "receiver action=" + action);
        if (!ConfigStore.proxyEnabled(context) || !ConfigStore.hasConfig(context)) {
            Log.i(ProxyService.TAG, "receiver skipped: proxy disabled or config missing");
            return;
        }
        ProxyService.requestStart(context, ProxyService.ACTION_START);
    }
}
