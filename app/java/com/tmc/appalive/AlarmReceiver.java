package com.tmc.appalive;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Heartbeat alarm: make sure the service is up, then re-arm. */
public final class AlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (!Config.get(ctx).enabled()) {
            return;
        }
        if (!AliveService.isRunning()) {
            EventLog.i("heartbeat: service was down, restarting");
        }
        Starter.start(ctx, AliveService.class, true);
        Heartbeat.schedule(ctx);
    }
}
