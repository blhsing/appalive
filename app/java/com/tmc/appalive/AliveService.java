package com.tmc.appalive;

import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Map;

/**
 * The main foreground service: owns the watchdog and everything that keeps us
 * alive.
 *
 * Readback for the installer goes through dump(), so
 *   adb shell dumpsys activity service com.tmc.appalive/.AliveService
 * prints the full state with no extra permission plumbing (the shell already
 * holds DUMP).
 */
public final class AliveService extends Service {

    private static volatile AliveService sInstance;

    private static final long NOTIF_REFRESH_MS = 60_000L;

    private final IBinder binder = new Binder();

    private HandlerThread thread;
    private Handler handler;
    private Watchdog watchdog;
    private PeerLink guardLink;
    private PowerManager.WakeLock wakeLock;
    private BroadcastReceiver screenReceiver;
    private BroadcastReceiver packageReceiver;
    private long startedAt;

    public static Watchdog watchdog() {
        AliveService s = sInstance;
        return s == null ? null : s.watchdog;
    }

    public static boolean isRunning() {
        AliveService s = sInstance;
        return s != null && s.watchdog != null && s.watchdog.isRunning();
    }

    /**
     * Tear the pair down deliberately (control channel "stop").
     *
     * Order matters: AliveService and GuardService bind each other with
     * BIND_AUTO_CREATE, so a plain stopService() on either one is a no-op while
     * the other's binding is still held. We drop our binding first, stop the
     * guard, then stop ourselves once its onDestroy has had a moment to release
     * its own binding to us.
     *
     * Config.enabled is already false by the time this runs, which is what
     * stops onDestroy from immediately arming a heartbeat to undo all this.
     */
    public static void requestStop(Context ctx) {
        AliveService s = sInstance;
        if (s != null) {
            if (s.guardLink != null) {
                s.guardLink.stop();
            }
            if (s.watchdog != null) {
                s.watchdog.stop();
            }
        }
        try {
            ctx.stopService(new Intent(ctx, GuardService.class));
        } catch (Throwable ignored) {
        }
        if (s != null) {
            s.stopSelfSoon();
        } else {
            try {
                ctx.stopService(new Intent(ctx, AliveService.class));
            } catch (Throwable ignored) {
            }
        }
    }

    private void stopSelfSoon() {
        Handler h = handler != null ? handler : new Handler(Looper.getMainLooper());
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    stopForegroundCompat();
                    stopSelf();
                } catch (Throwable t) {
                    EventLog.w("stopSelf failed", t);
                }
            }
        }, 1_200L);
    }

    @SuppressWarnings("deprecation")
    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startedAt = System.currentTimeMillis();

        // First thing: we may have been launched via startForegroundService and
        // owe the system a startForeground() within seconds.
        Notifications.ensureChannels(this);
        goForeground("starting");

        Config cfg = Config.get(this);

        thread = new HandlerThread("appalive-watchdog");
        thread.start();
        handler = new Handler(thread.getLooper());

        watchdog = new Watchdog(this, cfg, handler);
        watchdog.start();

        guardLink = new PeerLink(this, GuardService.class, cfg.guardForeground(), handler);
        guardLink.start();

        registerScreenReceiver();
        registerPackageReceiver();

        acquireWakeLockIfConfigured(cfg);

        Heartbeat.schedule(this);
        Heartbeat.scheduleJob(this);

        handler.post(new Runnable() {
            @Override
            public void run() {
                DeviceOwnerManager.applyHardening(AliveService.this);
            }
        });
        handler.postDelayed(notifRefresh, NOTIF_REFRESH_MS);

        sInstance = this;
        EventLog.i("AliveService created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Idempotent, and mandatory when we arrived via startForegroundService.
        goForeground(watchdog == null ? "starting" : watchdog.shortStatus());

        if (intent != null && Const.ACTION_TICK_NOW.equals(intent.getAction())) {
            if (watchdog != null) {
                watchdog.tickSoon(0);
            }
        }
        Heartbeat.schedule(this);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Swiping the app out of Recents must not stop it. stopWithTask=false
        // already covers this; the alarm is belt and braces.
        EventLog.i("task removed - scheduling immediate heartbeat");
        Heartbeat.scheduleImmediate(this);
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        EventLog.i("AliveService destroyed - arming heartbeat to come back");
        sInstance = null;
        try {
            if (watchdog != null) {
                watchdog.stop();
            }
            if (guardLink != null) {
                guardLink.stop();
            }
            unregister(screenReceiver);
            unregister(packageReceiver);
            releaseWakeLock();
            if (handler != null) {
                handler.removeCallbacks(notifRefresh);
            }
            if (thread != null) {
                thread.quit();
            }
        } catch (Throwable t) {
            EventLog.w("cleanup during onDestroy failed", t);
        }
        // START_STICKY should bring us back; the alarm covers the case where
        // the process was killed hard enough that it does not. Skipped when we
        // were stopped on purpose, or "stop" would never stick.
        if (Config.get(this).enabled()) {
            Heartbeat.scheduleImmediate(this);
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------- internals

    private final Runnable notifRefresh = new Runnable() {
        @Override
        public void run() {
            try {
                if (watchdog != null) {
                    updateNotification(watchdog.shortStatus());
                }
            } catch (Throwable ignored) {
            }
            if (handler != null) {
                handler.postDelayed(this, NOTIF_REFRESH_MS);
            }
        }
    };

    private void goForeground(String text) {
        try {
            startForeground(Const.NOTIF_MAIN, Notifications.main(this, text));
        } catch (Throwable t) {
            EventLog.w("startForeground failed", t);
        }
    }

    private void updateNotification(String text) {
        NotificationManager nm =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            try {
                nm.notify(Const.NOTIF_MAIN, Notifications.main(this, text));
            } catch (Throwable ignored) {
            }
        }
    }

    private void registerScreenReceiver() {
        screenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                // Screen going off is exactly when a pending escalation becomes
                // invisible, so re-evaluate promptly.
                if (watchdog != null) {
                    watchdog.tickSoon(1_000L);
                }
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_USER_PRESENT);
        f.addAction(Intent.ACTION_POWER_CONNECTED);
        Pkgs.registerReceiverCompat(this, screenReceiver, f, false);
    }

    private void registerPackageReceiver() {
        packageReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String pkg = intent.getData() == null
                        ? null : intent.getData().getSchemeSpecificPart();
                if (watchdog != null) {
                    watchdog.invalidateScout(pkg);
                    watchdog.tickSoon(2_000L);
                }
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addDataScheme("package");
        Pkgs.registerReceiverCompat(this, packageReceiver, f, true);
    }

    private void unregister(BroadcastReceiver r) {
        if (r != null) {
            try {
                unregisterReceiver(r);
            } catch (Throwable ignored) {
            }
        }
    }

    private void acquireWakeLockIfConfigured(Config cfg) {
        if (!cfg.holdWakeLock()) {
            return;
        }
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AppAlive::keepalive");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
                EventLog.i("partial wake lock held (hold_wakelock=true)");
            }
        } catch (Throwable t) {
            EventLog.w("could not acquire wake lock", t);
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (Throwable ignored) {
            }
        }
        wakeLock = null;
    }

    // ------------------------------------------------------------------ dump

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("AppAlive");
        pw.println("  package=" + getPackageName());
        pw.println("  uptimeMs=" + (System.currentTimeMillis() - startedAt));
        pw.println("  deviceOwner=" + DeviceOwnerManager.describe(this));
        pw.println("  guardForeground=" + Config.get(this).guardForeground());
        pw.println("  wakeLockHeld=" + (wakeLock != null && wakeLock.isHeld()));
        pw.println();
        if (watchdog != null) {
            watchdog.dump(pw);
        } else {
            pw.println("  watchdog: not started");
        }
        pw.println();
        pw.println("  recent events:");
        EventLog.dump(pw);
    }

    /**
     * Call after the UI edits config: make sure the service is up, drop cached
     * component scans, re-apply Device Owner hardening for any new target, and
     * tick promptly so the change is visible straight away.
     */
    public static void notifyConfigChanged(Context ctx) {
        if (Config.get(ctx).enabled()) {
            Starter.start(ctx, AliveService.class, true);
        }
        Watchdog w = watchdog();
        if (w != null) {
            w.invalidateScout(null);
            w.tickSoon(400L);
        }
        DeviceOwnerManager.applyHardening(ctx);
    }

    /** Plain-text status for the diagnostics screen. */
    public static String statusText(Context ctx) {
        StringBuilder sb = new StringBuilder();
        Config cfg = Config.get(ctx);
        Watchdog w = watchdog();
        sb.append("Service: ").append(isRunning() ? "running" : "not running").append('\n');
        sb.append("Device owner: ").append(DeviceOwnerManager.describe(ctx)).append('\n');
        if (w != null) {
            sb.append("Watchdog: ").append(w.shortStatus()).append('\n');
            sb.append("Screen gate open: ").append(w.screenGateOpen()).append('\n');
        }
        sb.append('\n');
        if (cfg.targets().isEmpty()) {
            sb.append("No targets configured.\n\nSet them over adb:\n")
                    .append("  adb shell am broadcast -a ").append(Const.ACTION_CONTROL)
                    .append(" \\\n    -n ").append(ctx.getPackageName())
                    .append("/.ControlReceiver \\\n    --es cmd set-targets --es targets ")
                    .append("\"com.example.one,com.example.two\"\n");
        } else if (w != null) {
            for (Map.Entry<String, String> e : w.targetLines().entrySet()) {
                sb.append(e.getKey()).append('\n').append("    ")
                        .append(e.getValue()).append("\n\n");
            }
        }
        return sb.toString();
    }
}
