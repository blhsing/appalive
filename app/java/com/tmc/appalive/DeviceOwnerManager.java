package com.tmc.appalive;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.os.Build;
import android.os.UserManager;

import java.util.List;
import java.util.Set;

/**
 * Device Owner privileges, and - just as importantly - giving them back.
 *
 * Provisioning happens over adb (`dpm set-device-owner`); this class only uses
 * the status once it exists. What it buys us:
 *
 *  - Background activity starts are permitted outright for the device owner
 *    uid (AOSP BackgroundActivityStartController: "don't abort if the callingUid
 *    is the device owner"), so the escalation tier always works.
 *  - setUninstallBlocked() for ourselves and the targets.
 *  - DISALLOW_APPS_CONTROL greys out Force Stop / Clear Data in Settings.
 *  - Being an active device admin separately disables Force Stop for us.
 *
 * Note we cannot call setApplicationExemptions(): it is @SystemApi behind
 * MANAGE_DEVICE_POLICY_APP_EXEMPTIONS. It only sets five ordinary app ops
 * though, and the installer sets those directly over adb instead.
 */
public final class DeviceOwnerManager {

    private DeviceOwnerManager() {
    }

    public static ComponentName admin(Context ctx) {
        return new ComponentName(ctx.getPackageName(), AdminReceiver.class.getName());
    }

    private static DevicePolicyManager dpm(Context ctx) {
        return (DevicePolicyManager) ctx.getSystemService(Context.DEVICE_POLICY_SERVICE);
    }

    public static boolean isDeviceOwner(Context ctx) {
        try {
            DevicePolicyManager d = dpm(ctx);
            return d != null && d.isDeviceOwnerApp(ctx.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isAdminActive(Context ctx) {
        try {
            DevicePolicyManager d = dpm(ctx);
            return d != null && d.isAdminActive(admin(ctx));
        } catch (Throwable t) {
            return false;
        }
    }

    public static String describe(Context ctx) {
        if (isDeviceOwner(ctx)) {
            return "device owner (full privileges)";
        }
        if (isAdminActive(ctx)) {
            return "device admin only (NOT device owner - reduced privileges)";
        }
        return "not provisioned (running in degraded mode)";
    }

    /** Idempotent; safe to call on every service start. */
    public static void applyHardening(Context ctx) {
        if (!isDeviceOwner(ctx)) {
            EventLog.i("device owner not active: " + describe(ctx));
            return;
        }
        Config cfg = Config.get(ctx);
        DevicePolicyManager d = dpm(ctx);
        ComponentName admin = admin(ctx);
        List<String> targets = cfg.targetPackages();

        if (cfg.blockUninstall()) {
            blockUninstall(ctx, d, admin, ctx.getPackageName());
            for (int i = 0; i < targets.size(); i++) {
                if (Pkgs.isInstalled(ctx, targets.get(i))) {
                    blockUninstall(ctx, d, admin, targets.get(i));
                }
            }
        }

        if (cfg.disallowAppsControl()) {
            try {
                d.addUserRestriction(admin, UserManager.DISALLOW_APPS_CONTROL);
                EventLog.i("DISALLOW_APPS_CONTROL set (Force Stop disabled in Settings)");
            } catch (Throwable t) {
                EventLog.w("could not set DISALLOW_APPS_CONTROL", t);
            }
        }

        if (cfg.grantTargetPermissions() && Build.VERSION.SDK_INT >= 23) {
            for (int i = 0; i < targets.size(); i++) {
                grantDangerousPermissions(ctx, d, admin, targets.get(i));
            }
        }
    }

    private static void blockUninstall(Context ctx, DevicePolicyManager d,
                                       ComponentName admin, String pkg) {
        try {
            d.setUninstallBlocked(admin, pkg, true);
            Config.get(ctx).addBlockedPackage(pkg);
        } catch (Throwable t) {
            EventLog.w("could not block uninstall of " + pkg, t);
        }
    }

    private static void grantDangerousPermissions(Context ctx, DevicePolicyManager d,
                                                  ComponentName admin, String pkg) {
        PackageManager pm = ctx.getPackageManager();
        PackageInfo pi;
        try {
            pi = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS);
        } catch (Throwable t) {
            return;
        }
        if (pi.requestedPermissions == null) {
            return;
        }
        for (String perm : pi.requestedPermissions) {
            try {
                PermissionInfo info = pm.getPermissionInfo(perm, 0);
                int base = info.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE;
                if (base != PermissionInfo.PROTECTION_DANGEROUS) {
                    continue;
                }
                d.setPermissionGrantState(admin, pkg, perm,
                        DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
            } catch (Throwable ignored) {
                // permission unknown to this platform, or not grantable
            }
        }
    }

    /**
     * Ordered teardown. The order is load-bearing: once device-owner status is
     * gone we can no longer clear the restrictions we set, so everything else
     * must be undone first.
     *
     * @return a human-readable report for the uninstaller.
     */
    public static String release(Context ctx) {
        StringBuilder report = new StringBuilder();
        DevicePolicyManager d = dpm(ctx);
        ComponentName admin = admin(ctx);
        Config cfg = Config.get(ctx);

        if (d == null) {
            return "no DevicePolicyManager";
        }

        // 1. user restrictions
        if (isDeviceOwner(ctx)) {
            try {
                d.clearUserRestriction(admin, UserManager.DISALLOW_APPS_CONTROL);
                report.append("cleared DISALLOW_APPS_CONTROL; ");
            } catch (Throwable t) {
                report.append("FAILED clearing DISALLOW_APPS_CONTROL: ").append(t).append("; ");
            }
        }

        // 2. uninstall blocking, for everything we ever blocked
        if (isDeviceOwner(ctx)) {
            Set<String> blocked = cfg.blockedPackages();
            blocked.add(ctx.getPackageName());
            int ok = 0;
            for (String pkg : blocked) {
                try {
                    d.setUninstallBlocked(admin, pkg, false);
                    ok++;
                } catch (Throwable t) {
                    report.append("FAILED unblocking ").append(pkg).append("; ");
                }
            }
            cfg.clearBlockedPackages();
            report.append("unblocked ").append(ok).append(" package(s); ");
        }

        // 3. device owner
        if (isDeviceOwner(ctx)) {
            try {
                clearDeviceOwner(d, ctx.getPackageName());
                report.append("cleared device owner; ");
            } catch (Throwable t) {
                report.append("FAILED clearing device owner: ").append(t).append("; ");
            }
        }

        // 4. device admin
        if (isAdminActive(ctx)) {
            try {
                d.removeActiveAdmin(admin);
                report.append("removed active admin; ");
            } catch (Throwable t) {
                report.append("FAILED removing admin: ").append(t).append("; ");
            }
        }

        report.append("final state: ").append(describe(ctx));
        String s = report.toString();
        EventLog.i("device owner release -> " + s);
        return s;
    }

    /** Deprecated since API 26 but still the only public self-release route. */
    @SuppressWarnings("deprecation")
    private static void clearDeviceOwner(DevicePolicyManager d, String pkg) {
        d.clearDeviceOwnerApp(pkg);
    }
}
