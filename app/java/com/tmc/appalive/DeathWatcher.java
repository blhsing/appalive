package com.tmc.appalive;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;

import java.util.List;

/**
 * Detects that a target app's process has died, and why.
 *
 * ActivityManager.getHistoricalProcessExitReasons() reads other packages'
 * death records provided we hold android.permission.DUMP — per its javadoc,
 * "If this package belongs to another UID, you must hold DUMP in order to
 * retrieve it". DUMP is signature|privileged|development, so the installer can
 * grant it with `adb shell pm grant`.
 *
 * There is no positive liveness query for other apps without root:
 * REAL_GET_TASKS is signature|privileged and /proc is hidepid-restricted. Death
 * events are therefore the authoritative signal, and the Tether's binder
 * disconnect is the low-latency one.
 */
public final class DeathWatcher {

    public static final class Exit {
        public final long timestamp;
        public final int reason;
        public final int pid;

        Exit(long timestamp, int reason, int pid) {
            this.timestamp = timestamp;
            this.reason = reason;
            this.pid = pid;
        }
    }

    private final Context ctx;
    private volatile boolean available;
    private boolean warned;

    public DeathWatcher(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.available = Build.VERSION.SDK_INT >= 30;
    }

    public boolean available() {
        return available;
    }

    public String unavailableReason() {
        if (Build.VERSION.SDK_INT < 30) {
            return "API " + Build.VERSION.SDK_INT + " < 30 (ApplicationExitInfo needs Android 11)";
        }
        return available ? "" : "android.permission.DUMP not granted";
    }

    /** Newest death record for {@code pkg}, or null if none / unavailable. */
    public Exit newest(String pkg) {
        if (!available) {
            return null;
        }
        try {
            return Api30.newest(ctx, pkg);
        } catch (SecurityException se) {
            available = false;
            if (!warned) {
                warned = true;
                EventLog.w("DUMP not granted - falling back to blind periodic revival. "
                        + "Fix with: adb shell pm grant " + ctx.getPackageName()
                        + " android.permission.DUMP");
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Isolated so the API-30 classes are not resolved on older devices. */
    private static final class Api30 {
        static Exit newest(Context ctx, String pkg) {
            ActivityManager am =
                    (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) {
                return null;
            }
            List<ApplicationExitInfo> list = am.getHistoricalProcessExitReasons(pkg, 0, 5);
            if (list == null || list.isEmpty()) {
                return null;
            }
            // Documented as sorted most-recent-first, but do not depend on it.
            ApplicationExitInfo best = null;
            for (int i = 0; i < list.size(); i++) {
                ApplicationExitInfo e = list.get(i);
                if (best == null || e.getTimestamp() > best.getTimestamp()) {
                    best = e;
                }
            }
            return best == null ? null
                    : new Exit(best.getTimestamp(), best.getReason(), best.getPid());
        }
    }

    public static String reasonName(int reason) {
        switch (reason) {
            case 0: return "UNKNOWN";
            case 1: return "EXIT_SELF";
            case 2: return "SIGNALED";
            case 3: return "LOW_MEMORY";
            case 4: return "CRASH";
            case 5: return "CRASH_NATIVE";
            case 6: return "ANR";
            case 7: return "INITIALIZATION_FAILURE";
            case 8: return "PERMISSION_CHANGE";
            case 9: return "EXCESSIVE_RESOURCE_USAGE";
            case 10: return "USER_REQUESTED";
            case 11: return "USER_STOPPED";
            case 12: return "DEPENDENCY_DIED";
            case 13: return "OTHER";
            case 14: return "FREEZER";
            case 15: return "PACKAGE_STATE_CHANGE";
            case 16: return "PACKAGE_UPDATED";
            default: return "reason:" + reason;
        }
    }

    /** REASON_USER_REQUESTED / REASON_USER_STOPPED: a deliberate force-stop. */
    public static boolean isUserInitiated(int reason) {
        return reason == 10 || reason == 11;
    }

    /** Deaths we should not fight: the package is being installed or removed. */
    public static boolean isPackageChurn(int reason) {
        return reason == 15 || reason == 16;
    }
}
