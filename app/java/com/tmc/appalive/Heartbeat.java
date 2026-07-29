package com.tmc.appalive;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * Independent resurrection paths for our own service.
 *
 * The foreground service is the primary mechanism; these are the backstops for
 * when it is killed anyway:
 *
 *  - a self-rescheduling exact alarm (default every 2 minutes)
 *  - a persisted 15-minute JobScheduler job
 *
 * They are deliberately redundant: an OEM cleaner that blocks one often leaves
 * the other alone.
 */
public final class Heartbeat {

    /** Minimum period JobScheduler will honour on API 24+. */
    private static final long JOB_PERIOD_MS = 15 * 60 * 1000L;

    private Heartbeat() {
    }

    public static void schedule(Context ctx) {
        long interval = Config.get(ctx).alarmIntervalMs();
        at(ctx, System.currentTimeMillis() + interval);
    }

    public static void scheduleImmediate(Context ctx) {
        at(ctx, System.currentTimeMillis() + 3_000L);
    }

    private static void at(Context ctx, long triggerAtMs) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        PendingIntent pi = pending(ctx);
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                // Should not happen: power-allowlisted apps are granted this,
                // and the installer also sets the appop. Degrade rather than throw.
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi);
            } else if (Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi);
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, triggerAtMs, pi);
            }
        } catch (Throwable t) {
            try {
                am.set(AlarmManager.RTC_WAKEUP, triggerAtMs, pi);
            } catch (Throwable t2) {
                EventLog.w("could not schedule heartbeat alarm", t2);
            }
        }
    }

    public static void cancel(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
            try {
                am.cancel(pending(ctx));
            } catch (Throwable ignored) {
            }
        }
    }

    private static PendingIntent pending(Context ctx) {
        Intent i = new Intent(ctx, AlarmReceiver.class);
        i.setAction(Const.ACTION_HEARTBEAT);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(ctx, Const.ALARM_REQUEST, i, flags);
    }

    public static void scheduleJob(Context ctx) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) {
            return;
        }
        try {
            JobInfo.Builder b =
                    new JobInfo.Builder(Const.JOB_ID, new ComponentName(ctx, AliveJobService.class))
                            .setPersisted(true)
                            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                            .setPeriodic(JOB_PERIOD_MS);
            js.schedule(b.build());
        } catch (Throwable t) {
            EventLog.w("could not schedule backstop job", t);
        }
    }

    public static void cancelJob(Context ctx) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js != null) {
            try {
                js.cancel(Const.JOB_ID);
            } catch (Throwable ignored) {
            }
        }
    }
}
