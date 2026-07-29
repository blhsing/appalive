package com.tmc.appalive;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * Starts our own services, coping with the Android 8+ background start limit.
 *
 * startForegroundService() obliges the service to call startForeground() within
 * a few seconds or the process is killed, so the caller's intent carries
 * EXTRA_MUST_FOREGROUND to tell the service which contract it is under. That
 * matters for GuardService, which is only foreground when configured to be.
 */
public final class Starter {

    public static final String EXTRA_MUST_FOREGROUND = "appalive.must_foreground";

    private Starter() {
    }

    public static void start(Context ctx, Class<?> cls, boolean preferForeground) {
        Intent i = new Intent(ctx, cls);

        if (Build.VERSION.SDK_INT < 26) {
            try {
                ctx.startService(i);
            } catch (Throwable t) {
                EventLog.w("startService(" + cls.getSimpleName() + ") failed", t);
            }
            return;
        }

        if (preferForeground) {
            i.putExtra(EXTRA_MUST_FOREGROUND, true);
            try {
                ctx.startForegroundService(i);
                return;
            } catch (Throwable t) {
                EventLog.w("startForegroundService(" + cls.getSimpleName() + ") failed", t);
                i.removeExtra(EXTRA_MUST_FOREGROUND);
            }
        }

        try {
            // Permitted from the background because the installer puts us on the
            // power allowlist. If that did not happen, fall through.
            ctx.startService(i);
        } catch (Throwable t) {
            i.putExtra(EXTRA_MUST_FOREGROUND, true);
            try {
                ctx.startForegroundService(i);
            } catch (Throwable t2) {
                EventLog.w("could not start " + cls.getSimpleName() + " at all", t2);
            }
        }
    }

    public static boolean mustForeground(Intent intent) {
        return intent != null && intent.getBooleanExtra(EXTRA_MUST_FOREGROUND, false);
    }
}
