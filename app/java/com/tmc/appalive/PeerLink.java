package com.tmc.appalive;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;

/**
 * Two-process mutual resurrection.
 *
 * AliveService (main process) and GuardService (:guard) each hold a PeerLink to
 * the other. The peer is both started and bound, so we learn about its death
 * two ways - onServiceDisconnected and a binder death recipient - and restart
 * it immediately.
 *
 * This defends against a single process being killed. It cannot help when the
 * whole app is killed at once; AlarmManager, JobScheduler and the boot receiver
 * cover that case.
 */
public final class PeerLink implements ServiceConnection, IBinder.DeathRecipient {

    private static final long MIN_RESTART_INTERVAL_MS = 5_000L;

    private final Context ctx;
    private final Class<?> peerClass;
    private final boolean peerPrefersForeground;
    private final Handler handler;

    private boolean bindRequested;
    private long lastRestartAt;
    private IBinder peerBinder;
    private volatile boolean stopped;

    public PeerLink(Context ctx, Class<?> peerClass, boolean peerPrefersForeground,
                    Handler handler) {
        this.ctx = ctx.getApplicationContext();
        this.peerClass = peerClass;
        this.peerPrefersForeground = peerPrefersForeground;
        this.handler = handler;
    }

    public void start() {
        stopped = false;
        Starter.start(ctx, peerClass, peerPrefersForeground);
        bind();
    }

    private void bind() {
        if (stopped || bindRequested) {
            return;
        }
        try {
            Intent i = new Intent(ctx, peerClass);
            bindRequested = ctx.bindService(i, this,
                    Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT);
            if (!bindRequested) {
                try {
                    ctx.unbindService(this);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            EventLog.w("peer bind to " + peerClass.getSimpleName() + " failed", t);
        }
    }

    public void stop() {
        stopped = true;
        unlink();
        if (bindRequested) {
            try {
                ctx.unbindService(this);
            } catch (Throwable ignored) {
            }
            bindRequested = false;
        }
    }

    private void unlink() {
        IBinder b = peerBinder;
        peerBinder = null;
        if (b != null) {
            try {
                b.unlinkToDeath(this, 0);
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        peerBinder = service;
        try {
            service.linkToDeath(this, 0);
        } catch (Throwable t) {
            EventLog.w("linkToDeath on " + name.getShortClassName() + " failed", t);
        }
        EventLog.i("peer up: " + name.getShortClassName());
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        EventLog.w("peer DOWN: " + name.getShortClassName() + " - restarting");
        unlink();
        restartPeer();
    }

    @Override
    public void onBindingDied(ComponentName name) {
        EventLog.w("peer binding died: " + name.getShortClassName());
        unlink();
        if (bindRequested) {
            try {
                ctx.unbindService(this);
            } catch (Throwable ignored) {
            }
            bindRequested = false;
        }
        restartPeer();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                bind();
            }
        }, 1_000L);
    }

    @Override
    public void binderDied() {
        EventLog.w("peer binder died: " + peerClass.getSimpleName() + " - restarting");
        unlink();
        restartPeer();
    }

    private void restartPeer() {
        if (stopped) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastRestartAt < MIN_RESTART_INTERVAL_MS) {
            return; // do not spin if the peer is failing to come up
        }
        lastRestartAt = now;
        Starter.start(ctx, peerClass, peerPrefersForeground);
    }
}
