package com.tmc.appalive;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.List;

/** Small package / platform-compat helpers. */
public final class Pkgs {

    private Pkgs() {
    }

    /**
     * Device-protected ("DE") storage context, so config survives into the
     * pre-unlock boot window that our directBootAware components run in.
     * Falls back to the normal context below API 24, where direct boot does
     * not exist anyway.
     */
    public static Context de(Context ctx) {
        if (Build.VERSION.SDK_INT >= 24) {
            Context d = ctx.createDeviceProtectedStorageContext();
            if (d != null) {
                return d;
            }
        }
        return ctx;
    }

    public static boolean isInstalled(Context ctx, String pkg) {
        try {
            ctx.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static Intent launchIntent(Context ctx, String pkg) {
        PackageManager pm = ctx.getPackageManager();
        Intent i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) {
            return null;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                | Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        return i;
    }

    /**
     * registerReceiver with the exported flag that became mandatory for
     * targetSdk 34+. We target 28 so it is not enforced today, but passing it
     * keeps a future TARGET_SDK bump from throwing at runtime.
     */
    public static void registerReceiverCompat(Context ctx, BroadcastReceiver r,
                                              IntentFilter f, boolean exported) {
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.registerReceiver(r, f,
                    exported ? Context.RECEIVER_EXPORTED : Context.RECEIVER_NOT_EXPORTED);
        } else {
            ctx.registerReceiver(r, f);
        }
    }

    /** String.join is API 26; minSdk is 21. */
    public static String join(List<String> parts, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                sb.append(sep);
            }
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    public static String shortName(String componentFlattened) {
        int slash = componentFlattened.indexOf('/');
        return slash < 0 ? componentFlattened : componentFlattened.substring(slash + 1);
    }
}
