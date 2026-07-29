package com.tmc.appalive;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Device admin entry point. Provisioned over adb with
 *   adb shell dpm set-device-owner com.tmc.appalive/.AdminReceiver
 */
public final class AdminReceiver extends DeviceAdminReceiver {

    @Override
    public void onEnabled(Context ctx, Intent intent) {
        EventLog.i("device admin enabled; " + DeviceOwnerManager.describe(ctx));
        Starter.start(ctx, AliveService.class, true);
    }

    @Override
    public CharSequence onDisableRequested(Context ctx, Intent intent) {
        return "AppAlive will stop keeping your apps running, and the apps it "
                + "protects will become uninstallable again.";
    }

    @Override
    public void onDisabled(Context ctx, Intent intent) {
        EventLog.i("device admin disabled");
    }
}
