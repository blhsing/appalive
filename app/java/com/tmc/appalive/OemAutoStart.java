package com.tmc.appalive;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.List;

/**
 * Deep links into the manufacturer's own auto-start / battery-protection
 * screens.
 *
 * This is the honest limit of what any adb-installed app can do. The allowlists
 * behind these screens are guarded by signature-level permissions or plain
 * vendor policy: they cannot be set with adb, and Device Owner does not help
 * either. Only a user tap works, so all we can do is take you straight there.
 *
 * If an app is still being killed after AppAlive is installed and provisioned,
 * this screen is almost always the reason.
 */
public final class OemAutoStart {

    private OemAutoStart() {
    }

    private static final String[][] COMPONENTS = {
            // Xiaomi / Redmi / POCO (MIUI, HyperOS)
            {"com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"},
            // Huawei / Honor (EMUI, MagicUI)
            {"com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
            {"com.huawei.systemmanager",
                    "com.huawei.systemmanager.optimize.process.ProtectActivity"},
            {"com.huawei.systemmanager",
                    "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"},
            // Oppo / Realme (ColorOS)
            {"com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
            {"com.coloros.safecenter",
                    "com.coloros.safecenter.startupapp.StartupAppListActivity"},
            {"com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"},
            // Vivo / iQOO (Funtouch, OriginOS)
            {"com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
            {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"},
            {"com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"},
            // OnePlus (OxygenOS)
            {"com.oneplus.security",
                    "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"},
            // Samsung (One UI)
            {"com.samsung.android.lool",
                    "com.samsung.android.sm.ui.battery.BatteryActivity"},
            {"com.samsung.android.lool",
                    "com.samsung.android.sm.battery.ui.BatteryActivity"},
            {"com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity"},
            // Meizu (Flyme)
            {"com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity"},
            {"com.meizu.safe", "com.meizu.safe.security.SHOW_APPSEC"},
            // Asus (ZenUI)
            {"com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity"},
            {"com.asus.mobilemanager", "com.asus.mobilemanager.entry.FunctionActivity"},
            // Letv / LeEco
            {"com.letv.android.letvsafe", "com.letv.android.letvsafe.AutobootManageActivity"},
            // HTC
            {"com.htc.pitroad", "com.htc.pitroad.landingpage.activity.LandingPageActivity"},
            // Nokia (Evenwell power saver)
            {"com.evenwell.powersaving.g3",
                    "com.evenwell.powersaving.g3.exception.PowerSaverExceptionActivity"},
    };

    /** Only those that actually resolve on this device. */
    public static List<Intent> candidates(Context ctx) {
        List<Intent> out = new ArrayList<Intent>();
        for (String[] c : COMPONENTS) {
            Intent i = new Intent();
            i.setComponent(new ComponentName(c[0], c[1]));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            List<ResolveInfo> hits = ctx.getPackageManager().queryIntentActivities(i, 0);
            if (hits != null && !hits.isEmpty()) {
                out.add(i);
            }
        }
        return out;
    }

    public static boolean open(Context ctx) {
        List<Intent> list = candidates(ctx);
        for (int i = 0; i < list.size(); i++) {
            try {
                ctx.startActivity(list.get(i));
                return true;
            } catch (Throwable ignored) {
                // try the next candidate
            }
        }
        return false;
    }

    /** Fallback that exists on stock Android. */
    public static Intent batteryOptimizationSettings(Context ctx) {
        if (Build.VERSION.SDK_INT < 23) {
            return null;
        }
        Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
        i.setData(Uri.parse("package:" + ctx.getPackageName()));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    public static Intent overlayPermissionSettings(Context ctx) {
        if (Build.VERSION.SDK_INT < 23) {
            return null;
        }
        Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
        i.setData(Uri.parse("package:" + ctx.getPackageName()));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }
}
