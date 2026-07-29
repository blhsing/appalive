package com.tmc.appalive;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration, held in device-protected storage so the boot path can read it
 * before the user unlocks for the first time.
 *
 * Written over adb through {@link ControlReceiver}; there is no file to push,
 * which avoids the /sdcard/Android/data access restrictions on Android 11+.
 */
public final class Config {

    public static final String KEY_TARGETS = "targets";
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_POLL_INTERVAL_MS = "poll_interval_ms";
    public static final String KEY_ENSURE_INTERVAL_MS = "ensure_interval_ms";
    public static final String KEY_MIN_REVIVE_INTERVAL_MS = "min_revive_interval_ms";
    public static final String KEY_MAX_REVIVE_INTERVAL_MS = "max_revive_interval_ms";
    public static final String KEY_ESCALATE_AFTER_FAILURES = "escalate_after_failures";
    public static final String KEY_REVIVE_ON_USER_REQUESTED = "revive_on_user_requested";
    public static final String KEY_TETHER_ENABLED = "tether_enabled";
    public static final String KEY_ALARM_INTERVAL_MS = "alarm_interval_ms";
    public static final String KEY_HOLD_WAKELOCK = "hold_wakelock";
    public static final String KEY_GUARD_FOREGROUND = "guard_foreground";
    public static final String KEY_BLOCK_UNINSTALL = "block_uninstall";
    public static final String KEY_DISALLOW_APPS_CONTROL = "disallow_apps_control";
    public static final String KEY_GRANT_TARGET_PERMISSIONS = "grant_target_permissions";

    private static final String PREFS = "appalive";
    private static final String EXIT_TS_PREFIX = "exit_ts:";

    private static volatile Config sInstance;

    private final SharedPreferences prefs;

    private Config(Context ctx) {
        this.prefs = Pkgs.de(ctx).getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static Config get(Context ctx) {
        Config c = sInstance;
        if (c == null) {
            synchronized (Config.class) {
                c = sInstance;
                if (c == null) {
                    c = new Config(ctx.getApplicationContext());
                    sInstance = c;
                }
            }
        }
        return c;
    }

    // ---------------------------------------------------------------- tunables

    public boolean enabled() {
        return prefs.getBoolean(KEY_ENABLED, true);
    }

    public long pollIntervalMs() {
        return clamp(prefs.getLong(KEY_POLL_INTERVAL_MS, 30_000L), 5_000L, 3_600_000L);
    }

    public long ensureIntervalMs() {
        return clamp(prefs.getLong(KEY_ENSURE_INTERVAL_MS, 300_000L), 30_000L, 6 * 3_600_000L);
    }

    public long minReviveIntervalMs() {
        return clamp(prefs.getLong(KEY_MIN_REVIVE_INTERVAL_MS, 45_000L), 5_000L, 3_600_000L);
    }

    public long maxReviveIntervalMs() {
        return clamp(prefs.getLong(KEY_MAX_REVIVE_INTERVAL_MS, 900_000L), 30_000L, 12 * 3_600_000L);
    }

    public int escalateAfterFailures() {
        return (int) clamp(prefs.getInt(KEY_ESCALATE_AFTER_FAILURES, 3), 1, 50);
    }

    public boolean reviveOnUserRequested() {
        return prefs.getBoolean(KEY_REVIVE_ON_USER_REQUESTED, true);
    }

    public boolean tetherEnabled() {
        return prefs.getBoolean(KEY_TETHER_ENABLED, true);
    }

    public long alarmIntervalMs() {
        return clamp(prefs.getLong(KEY_ALARM_INTERVAL_MS, 120_000L), 30_000L, 6 * 3_600_000L);
    }

    public boolean holdWakeLock() {
        return prefs.getBoolean(KEY_HOLD_WAKELOCK, false);
    }

    public boolean guardForeground() {
        return prefs.getBoolean(KEY_GUARD_FOREGROUND, true);
    }

    /** Device Owner: block uninstall of ourselves and of every target. */
    public boolean blockUninstall() {
        return prefs.getBoolean(KEY_BLOCK_UNINSTALL, true);
    }

    /**
     * Device Owner: set DISALLOW_APPS_CONTROL, which greys out Force Stop and
     * Clear Data in Settings. This is the main defence against a deliberate
     * force-stop, but it applies to *every* app on the device, not just our
     * targets - hence a separate switch.
     */
    public boolean disallowAppsControl() {
        return prefs.getBoolean(KEY_DISALLOW_APPS_CONTROL, true);
    }

    /**
     * Device Owner: auto-grant targets' declared dangerous permissions.
     * Off by default - it is not needed to keep an app running, and silently
     * granting camera/location/etc on the user's behalf is not our call to make.
     */
    public boolean grantTargetPermissions() {
        return prefs.getBoolean(KEY_GRANT_TARGET_PERMISSIONS, false);
    }

    private static long clamp(long v, long lo, long hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // ----------------------------------------------------------------- targets

    public List<TargetSpec> targets() {
        List<TargetSpec> out = new ArrayList<TargetSpec>();
        String raw = prefs.getString(KEY_TARGETS, null);
        if (raw == null || raw.length() == 0) {
            return out;
        }
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                TargetSpec s = TargetSpec.fromJson(o);
                if (s != null) {
                    out.add(s);
                }
            }
        } catch (Throwable t) {
            EventLog.w("config: unreadable target list, treating as empty", t);
        }
        return out;
    }

    public List<String> targetPackages() {
        List<TargetSpec> specs = targets();
        List<String> out = new ArrayList<String>(specs.size());
        for (int i = 0; i < specs.size(); i++) {
            out.add(specs.get(i).pkg);
        }
        return out;
    }

    public void saveTargets(List<TargetSpec> specs) {
        JSONArray arr = new JSONArray();
        for (int i = 0; i < specs.size(); i++) {
            arr.put(specs.get(i).toJson());
        }
        prefs.edit().putString(KEY_TARGETS, arr.toString()).commit();
    }

    public boolean hasTarget(String pkg) {
        List<TargetSpec> specs = targets();
        for (int i = 0; i < specs.size(); i++) {
            if (specs.get(i).pkg.equals(pkg)) {
                return true;
            }
        }
        return false;
    }

    public void addTarget(String pkg) {
        if (hasTarget(pkg)) {
            return;
        }
        List<TargetSpec> specs = targets();
        specs.add(new TargetSpec(pkg, TargetSpec.ESCALATE_SCREEN_OFF, true));
        saveTargets(specs);
    }

    public void removeTarget(String pkg) {
        List<TargetSpec> specs = targets();
        for (int i = specs.size() - 1; i >= 0; i--) {
            if (specs.get(i).pkg.equals(pkg)) {
                specs.remove(i);
            }
        }
        saveTargets(specs);
    }

    public void setTargetEscalate(String pkg, int mode) {
        List<TargetSpec> specs = targets();
        for (int i = 0; i < specs.size(); i++) {
            TargetSpec s = specs.get(i);
            if (s.pkg.equals(pkg)) {
                specs.set(i, new TargetSpec(s.pkg, mode, s.enabled));
            }
        }
        saveTargets(specs);
    }

    /** The form install.sh takes, for showing the user a copyable command. */
    public String targetsCsv() {
        List<TargetSpec> specs = targets();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < specs.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            TargetSpec s = specs.get(i);
            sb.append(s.pkg);
            if (s.escalate != TargetSpec.ESCALATE_SCREEN_OFF) {
                sb.append(':').append(TargetSpec.escalateName(s.escalate));
            }
        }
        return sb.toString();
    }

    public void setBool(String key, boolean value) {
        prefs.edit().putBoolean(key, value).commit();
    }

    /** Accepts "com.foo,com.bar:always,com.baz:never". */
    public void setTargetsCsv(String csv) {
        JSONArray arr = new JSONArray();
        if (csv != null) {
            String[] parts = csv.split(",");
            for (int i = 0; i < parts.length; i++) {
                TargetSpec s = TargetSpec.parse(parts[i]);
                if (s != null) {
                    arr.put(s.toJson());
                }
            }
        }
        prefs.edit().putString(KEY_TARGETS, arr.toString()).commit();
    }

    // ------------------------------------------------------- exit high-water

    /**
     * Newest process-death timestamp we have already acted on for this package.
     * Persisted so a restart of our own service does not re-revive on a death
     * that we already handled.
     */
    public long handledExitTs(String pkg) {
        return prefs.getLong(EXIT_TS_PREFIX + pkg, 0L);
    }

    public void setHandledExitTs(String pkg, long ts) {
        prefs.edit().putLong(EXIT_TS_PREFIX + pkg, ts).apply();
    }

    // -------------------------------------------- uninstall-block bookkeeping

    /**
     * Every package we have ever called setUninstallBlocked(true) on.
     *
     * Tracked separately from the target list so that a package dropped from
     * the config later is still unblocked at release time - otherwise it would
     * be stranded as un-uninstallable with no record of why.
     */
    public java.util.Set<String> blockedPackages() {
        java.util.Set<String> s = prefs.getStringSet(KEY_BLOCKED_PKGS, null);
        return s == null ? new java.util.HashSet<String>() : new java.util.HashSet<String>(s);
    }

    public void addBlockedPackage(String pkg) {
        java.util.Set<String> s = blockedPackages();
        if (s.add(pkg)) {
            prefs.edit().putStringSet(KEY_BLOCKED_PKGS, s).commit();
        }
    }

    public void clearBlockedPackages() {
        prefs.edit().remove(KEY_BLOCKED_PKGS).commit();
    }

    private static final String KEY_BLOCKED_PKGS = "blocked_pkgs";

    // ------------------------------------------------------- generic setter

    /**
     * Used by the adb control channel. Returns a human-readable result rather
     * than throwing, because the caller is a shell script.
     */
    public String set(String key, String value) {
        if (key == null || value == null) {
            return "ERROR missing key or value";
        }
        SharedPreferences.Editor ed = prefs.edit();
        try {
            if (KEY_TARGETS.equals(key)) {
                setTargetsCsv(value);
                return "OK targets=" + value;
            }
            if (KEY_ENABLED.equals(key) || KEY_REVIVE_ON_USER_REQUESTED.equals(key)
                    || KEY_TETHER_ENABLED.equals(key) || KEY_HOLD_WAKELOCK.equals(key)
                    || KEY_GUARD_FOREGROUND.equals(key) || KEY_BLOCK_UNINSTALL.equals(key)
                    || KEY_DISALLOW_APPS_CONTROL.equals(key)
                    || KEY_GRANT_TARGET_PERMISSIONS.equals(key)) {
                ed.putBoolean(key, parseBool(value));
            } else if (KEY_ESCALATE_AFTER_FAILURES.equals(key)) {
                ed.putInt(key, Integer.parseInt(value.trim()));
            } else if (KEY_POLL_INTERVAL_MS.equals(key) || KEY_ENSURE_INTERVAL_MS.equals(key)
                    || KEY_MIN_REVIVE_INTERVAL_MS.equals(key)
                    || KEY_MAX_REVIVE_INTERVAL_MS.equals(key)
                    || KEY_ALARM_INTERVAL_MS.equals(key)) {
                ed.putLong(key, Long.parseLong(value.trim()));
            } else {
                return "ERROR unknown key: " + key;
            }
        } catch (NumberFormatException nfe) {
            return "ERROR bad value for " + key + ": " + value;
        }
        ed.commit();
        return "OK " + key + "=" + value;
    }

    private static boolean parseBool(String v) {
        String s = v.trim().toLowerCase(java.util.Locale.US);
        return "1".equals(s) || "true".equals(s) || "yes".equals(s) || "on".equals(s);
    }

    // -------------------------------------------------------------- reporting

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put(KEY_ENABLED, enabled());
            o.put(KEY_POLL_INTERVAL_MS, pollIntervalMs());
            o.put(KEY_ENSURE_INTERVAL_MS, ensureIntervalMs());
            o.put(KEY_MIN_REVIVE_INTERVAL_MS, minReviveIntervalMs());
            o.put(KEY_MAX_REVIVE_INTERVAL_MS, maxReviveIntervalMs());
            o.put(KEY_ESCALATE_AFTER_FAILURES, escalateAfterFailures());
            o.put(KEY_REVIVE_ON_USER_REQUESTED, reviveOnUserRequested());
            o.put(KEY_TETHER_ENABLED, tetherEnabled());
            o.put(KEY_ALARM_INTERVAL_MS, alarmIntervalMs());
            o.put(KEY_HOLD_WAKELOCK, holdWakeLock());
            o.put(KEY_GUARD_FOREGROUND, guardForeground());
            o.put(KEY_BLOCK_UNINSTALL, blockUninstall());
            o.put(KEY_DISALLOW_APPS_CONTROL, disallowAppsControl());
            o.put(KEY_GRANT_TARGET_PERMISSIONS, grantTargetPermissions());
            JSONArray arr = new JSONArray();
            List<TargetSpec> specs = targets();
            for (int i = 0; i < specs.size(); i++) {
                arr.put(specs.get(i).toJson());
            }
            o.put(KEY_TARGETS, arr);
        } catch (Throwable ignored) {
            // as above: put() only throws on non-finite doubles
        }
        return o;
    }
}
