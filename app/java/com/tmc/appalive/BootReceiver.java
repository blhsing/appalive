package com.tmc.appalive;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Brings the service up after a reboot or a self-update.
 *
 * Marked directBootAware in the manifest, so LOCKED_BOOT_COMPLETED reaches us
 * before the user has unlocked for the first time. Everything we touch on that
 * path lives in device-protected storage (see Config).
 */
public final class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent == null ? "(null)" : intent.getAction();
        EventLog.i("boot event: " + action);

        if (!Config.get(ctx).enabled()) {
            EventLog.i("boot: disabled by config, not starting");
            return;
        }
        Starter.start(ctx, AliveService.class, true);
        Heartbeat.schedule(ctx);
        Heartbeat.scheduleJob(ctx);
    }
}
