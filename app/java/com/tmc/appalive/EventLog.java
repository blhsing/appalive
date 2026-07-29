package com.tmc.appalive;

import android.util.Log;

import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * In-memory ring buffer mirrored to logcat.
 *
 * Deliberately no file logging: the boot path runs before first unlock, where
 * credential-encrypted storage is unavailable, and external storage would drag
 * in permissions this app has no other reason to hold. Everything is readable
 * with `adb logcat -s AppAlive:V` or via AliveService.dump() through
 * `adb shell dumpsys activity service com.tmc.appalive/.AliveService`.
 *
 * Each process keeps its own ring; the :guard process therefore only shows up
 * in logcat, not in dumpsys.
 */
public final class EventLog {

    private EventLog() {
    }

    private static final int CAPACITY = 300;

    private static final ArrayDeque<String> RING = new ArrayDeque<String>(CAPACITY);
    private static final SimpleDateFormat STAMP =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    public static void i(String msg) {
        Log.i(Const.TAG, msg);
        add(msg);
    }

    public static void w(String msg) {
        Log.w(Const.TAG, msg);
        add("W " + msg);
    }

    public static void w(String msg, Throwable t) {
        Log.w(Const.TAG, msg, t);
        add("W " + msg + " :: " + t);
    }

    private static void add(String msg) {
        String line;
        synchronized (STAMP) {
            line = STAMP.format(new Date()) + "  " + msg;
        }
        synchronized (RING) {
            if (RING.size() >= CAPACITY) {
                RING.removeFirst();
            }
            RING.addLast(line);
        }
    }

    public static void dump(PrintWriter pw) {
        synchronized (RING) {
            for (String line : RING) {
                pw.print("    ");
                pw.println(line);
            }
        }
    }
}
