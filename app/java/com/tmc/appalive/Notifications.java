package com.tmc.appalive;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * Foreground-service notifications.
 *
 * We target SDK 28, so on Android 8+ devices the platform routes our
 * notifications through a legacy default channel and largely ignores the
 * channel id. Channels are still created and referenced so that raising
 * TARGET_SDK does not silently change behaviour; PRIORITY_MIN and a null sound
 * are what actually keep these quiet today.
 */
public final class Notifications {

    private Notifications() {
    }

    public static void ensureChannels(Context ctx) {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        NotificationManager nm =
                (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        try {
            NotificationChannel main = new NotificationChannel(Const.CHANNEL_MAIN,
                    ctx.getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW);
            main.setDescription(ctx.getString(R.string.channel_desc));
            main.setShowBadge(false);
            main.setSound(null, null);
            main.enableVibration(false);
            nm.createNotificationChannel(main);

            NotificationChannel guard = new NotificationChannel(Const.CHANNEL_GUARD,
                    ctx.getString(R.string.guard_channel_name), NotificationManager.IMPORTANCE_MIN);
            guard.setDescription(ctx.getString(R.string.guard_channel_desc));
            guard.setShowBadge(false);
            guard.setSound(null, null);
            guard.enableVibration(false);
            nm.createNotificationChannel(guard);
        } catch (Throwable t) {
            EventLog.w("could not create notification channels", t);
        }
    }

    public static Notification main(Context ctx, String text) {
        return build(ctx, Const.CHANNEL_MAIN, ctx.getString(R.string.app_name), text, true);
    }

    public static Notification guard(Context ctx) {
        return build(ctx, Const.CHANNEL_GUARD, ctx.getString(R.string.app_name),
                "Watchdog process", false);
    }

    @SuppressWarnings("deprecation")
    private static Notification build(Context ctx, String channel, String title,
                                      String text, boolean withContentIntent) {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(ctx, channel);
        } else {
            b = new Notification.Builder(ctx);
        }
        b.setSmallIcon(R.drawable.ic_stat_alive)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setLocalOnly(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setPriority(Notification.PRIORITY_MIN)
                .setGroup(Const.NOTIF_GROUP);

        if (withContentIntent) {
            Intent open = new Intent(ctx, MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            b.setContentIntent(PendingIntent.getActivity(ctx, 0, open, flags));
        }
        return b.build();
    }
}
