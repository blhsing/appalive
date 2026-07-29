package com.tmc.appalive;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * The adb control channel.
 *
 * Guarded in the manifest by android:permission="android.permission.DUMP".
 * com.android.shell holds DUMP, so `adb shell am broadcast` reaches us; an
 * ordinary third-party app cannot, because DUMP is
 * signature|privileged|development and is not obtainable by a normal app.
 * That is what keeps an exported control surface from being a back door.
 *
 * Example:
 *   adb shell am broadcast -a com.tmc.appalive.action.CONTROL \
 *     -n com.tmc.appalive/.ControlReceiver \
 *     --es cmd set-targets --es targets "com.example.one,com.example.two:always"
 */
public final class ControlReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String cmd = intent == null ? null : intent.getStringExtra(Const.EXTRA_CMD);
        String result;
        try {
            result = handle(ctx, intent, cmd);
        } catch (Throwable t) {
            result = "ERROR " + t;
            EventLog.w("control command '" + cmd + "' failed", t);
        }
        EventLog.i("control: " + cmd + " -> " + result);

        // `am broadcast` prints this back to the installer. Not all platforms
        // deliver it as an ordered broadcast, so dumpsys stays the primary
        // readback channel and this is a convenience.
        try {
            setResultCode(result.startsWith("ERROR") ? 1 : 0);
            setResultData(result);
        } catch (Throwable ignored) {
            // not an ordered broadcast on this platform
        }
    }

    private String handle(Context ctx, Intent intent, String cmd) {
        Config cfg = Config.get(ctx);

        if (cmd == null || cmd.length() == 0) {
            return "ERROR missing --es cmd";
        }

        if ("set-targets".equals(cmd)) {
            String csv = intent.getStringExtra(Const.EXTRA_TARGETS);
            cfg.setTargetsCsv(csv == null ? "" : csv);
            Starter.start(ctx, AliveService.class, true);
            Watchdog w = AliveService.watchdog();
            if (w != null) {
                w.invalidateScout(null);
                w.tickSoon(500L);
            }
            DeviceOwnerManager.applyHardening(ctx);
            return "OK targets=" + Pkgs.join(cfg.targetPackages(), ",");
        }

        if ("set".equals(cmd)) {
            String key = intent.getStringExtra(Const.EXTRA_KEY);
            String value = intent.getStringExtra(Const.EXTRA_VALUE);
            String r = cfg.set(key, value);
            Watchdog w = AliveService.watchdog();
            if (w != null) {
                w.tickSoon(500L);
            }
            return r;
        }

        if ("start".equals(cmd)) {
            cfg.set(Config.KEY_ENABLED, "true");
            Starter.start(ctx, AliveService.class, true);
            Heartbeat.schedule(ctx);
            Heartbeat.scheduleJob(ctx);
            return "OK started";
        }

        if ("stop".equals(cmd)) {
            cfg.set(Config.KEY_ENABLED, "false");
            Heartbeat.cancel(ctx);
            Heartbeat.cancelJob(ctx);
            AliveService.requestStop(ctx);
            return "OK stopped";
        }

        if ("revive".equals(cmd)) {
            Watchdog w = AliveService.watchdog();
            if (w == null) {
                Starter.start(ctx, AliveService.class, true);
                return "OK service was down, started it";
            }
            w.reviveNow(intent.getStringExtra(Const.EXTRA_PKG));
            return "OK revive requested";
        }

        if ("rescan".equals(cmd)) {
            Watchdog w = AliveService.watchdog();
            if (w != null) {
                w.invalidateScout(null);
                w.tickSoon(0);
            }
            return "OK rescanned";
        }

        if ("status".equals(cmd)) {
            Watchdog w = AliveService.watchdog();
            return "OK running=" + AliveService.isRunning()
                    + " owner=" + DeviceOwnerManager.describe(ctx)
                    + " " + (w == null ? "watchdog=down" : w.shortStatus());
        }

        if ("release".equals(cmd)) {
            // Ordered Device Owner teardown. Must succeed before the uninstaller
            // can remove the package at all.
            return "OK " + DeviceOwnerManager.release(ctx);
        }

        return "ERROR unknown cmd: " + cmd;
    }
}
