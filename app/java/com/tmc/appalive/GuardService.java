package com.tmc.appalive;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * The twin, running in the :guard process.
 *
 * Its only job is to notice AliveService dying and start it again. Because the
 * two processes bind each other with BIND_IMPORTANT, killing one raises the
 * other's oom_adj cost too, and whichever survives resurrects its peer.
 */
public final class GuardService extends Service {

    private final IBinder binder = new Binder();
    private PeerLink mainLink;

    @Override
    public void onCreate() {
        super.onCreate();
        Notifications.ensureChannels(this);
        if (Config.get(this).guardForeground()) {
            goForeground();
        }
        mainLink = new PeerLink(this, AliveService.class, true,
                new Handler(Looper.getMainLooper()));
        mainLink.start();
        EventLog.i("GuardService created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // If we were launched with startForegroundService we owe the system a
        // startForeground() regardless of configuration.
        if (Starter.mustForeground(intent) || Config.get(this).guardForeground()) {
            goForeground();
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        EventLog.i("GuardService destroyed");
        if (mainLink != null) {
            mainLink.stop();
        }
        // If the guard is going down, make sure something else will bring the
        // pair back up - unless we were stopped deliberately.
        if (Config.get(this).enabled()) {
            Heartbeat.scheduleImmediate(this);
        }
        super.onDestroy();
    }

    private void goForeground() {
        try {
            startForeground(Const.NOTIF_GUARD, Notifications.guard(this));
        } catch (Throwable t) {
            EventLog.w("guard startForeground failed", t);
        }
    }
}
