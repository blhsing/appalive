package com.tmc.appalive;

import android.content.ComponentName;
import android.content.ContentProviderClient;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Brings a dead target back.
 *
 * The silent tiers all cold-start the target's process and run its
 * Application.onCreate(); the user sees nothing. They are ordered so that the
 * tiers which *confirm* a process start come first:
 *
 *   1. bind   - Tether.ensureBound(); confirmation arrives via onServiceConnected
 *   2. provider - acquireUnstableContentProviderClient(); a non-null client is
 *                 immediate proof the process is up
 *   3. broadcast - explicit-component intent + FLAG_INCLUDE_STOPPED_PACKAGES.
 *                 The widest net: with an explicit ComponentName the framework
 *                 starts the process to deliver the intent even though the
 *                 action matches no filter, and explicit broadcasts are exempt
 *                 from the Android 8+ implicit-broadcast ban.
 *   4. service - startService(); background start limits do not apply because
 *                the installer puts us on the power allowlist.
 *
 * Escalation to the target's launcher activity is separate and gated by policy,
 * because there is no supported way to start another app's activity invisibly.
 */
public final class Reviver {

    public static final class Result {
        public final String tier;
        public final boolean attempted;
        /** True only when we have positive evidence the process is running. */
        public final boolean confirmed;
        public final String detail;

        Result(String tier, boolean attempted, boolean confirmed, String detail) {
            this.tier = tier;
            this.attempted = attempted;
            this.confirmed = confirmed;
            this.detail = detail;
        }
    }

    private final Context ctx;
    private final ComponentScout scout;
    private final Tether tether;
    private final Config cfg;

    /** Rotating receiver index, so we do not hammer the same component. */
    private final Map<String, Integer> receiverCursor = new HashMap<String, Integer>();
    /** Last receiver poked per package, for crash attribution. */
    private final Map<String, ComponentName> lastPoked = new HashMap<String, ComponentName>();
    private final Map<String, Long> lastPokeAt = new HashMap<String, Long>();

    public Reviver(Context ctx, ComponentScout scout, Tether tether, Config cfg) {
        this.ctx = ctx.getApplicationContext();
        this.scout = scout;
        this.tether = tether;
        this.cfg = cfg;
    }

    public Result reviveSilently(String pkg) {
        StringBuilder trace = new StringBuilder();
        boolean attempted = false;

        // --- tier 1: bind (also the prevention mechanism) --------------------
        if (cfg.tetherEnabled()) {
            boolean requested = tether.ensureBound(pkg);
            append(trace, "bind=" + (requested ? "requested" : "unavailable"));
            attempted |= requested;
            if (tether.isConnected(pkg)) {
                return new Result("bind", true, true, trace.toString());
            }
        }

        // --- tier 2: content provider (immediate confirmation) ---------------
        String auth = pokeProvider(pkg, trace);
        if (auth != null) {
            return new Result("provider", true, true, trace.toString());
        }

        // --- tier 3: explicit broadcast (widest coverage) --------------------
        if (pokeReceiver(pkg, trace)) {
            attempted = true;
        }

        // --- tier 4: startService -------------------------------------------
        if (pokeService(pkg, trace)) {
            attempted = true;
        }

        String tier = attempted ? "silent" : "none";
        return new Result(tier, attempted, false, trace.toString());
    }

    private String pokeProvider(String pkg, StringBuilder trace) {
        List<String> auths = scout.get(pkg).providerAuthorities;
        for (int i = 0; i < auths.size(); i++) {
            String authority = auths.get(i);
            ContentProviderClient client = null;
            try {
                client = ctx.getContentResolver().acquireUnstableContentProviderClient(authority);
                if (client != null) {
                    append(trace, "provider=" + authority + " OK");
                    return authority;
                }
            } catch (Throwable t) {
                append(trace, "provider=" + authority + " failed(" + t.getClass().getSimpleName() + ")");
            } finally {
                closeQuietly(client);
            }
        }
        if (!auths.isEmpty()) {
            append(trace, "provider=none acquired");
        }
        return null;
    }

    private static void closeQuietly(ContentProviderClient client) {
        if (client == null) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                client.close();
            } else {
                releaseLegacy(client);
            }
        } catch (Throwable ignored) {
        }
    }

    @SuppressWarnings("deprecation")
    private static void releaseLegacy(ContentProviderClient client) {
        client.release();
    }

    private boolean pokeReceiver(String pkg, StringBuilder trace) {
        List<ComponentName> receivers = scout.get(pkg).receivers;
        if (receivers.isEmpty()) {
            return false;
        }
        Integer cursorBox = receiverCursor.get(pkg);
        int cursor = cursorBox == null ? 0 : cursorBox.intValue();

        // One receiver per attempt: it keeps side effects minimal and makes a
        // subsequent crash unambiguously attributable to this component.
        for (int attempt = 0; attempt < receivers.size(); attempt++) {
            int idx = (cursor + attempt) % receivers.size();
            ComponentName cn = receivers.get(idx);
            if (scout.isQuarantined(cn)) {
                continue;
            }
            Intent intent = new Intent(Const.ACTION_PING);
            intent.setComponent(cn);
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES
                    | Intent.FLAG_RECEIVER_FOREGROUND);
            try {
                ctx.sendBroadcast(intent);
                receiverCursor.put(pkg, Integer.valueOf(idx + 1));
                lastPoked.put(pkg, cn);
                lastPokeAt.put(pkg, Long.valueOf(System.currentTimeMillis()));
                append(trace, "broadcast=" + Pkgs.shortName(cn.flattenToShortString()));
                return true;
            } catch (Throwable t) {
                append(trace, "broadcast failed: " + t.getClass().getSimpleName());
            }
        }
        return false;
    }

    private boolean pokeService(String pkg, StringBuilder trace) {
        List<ComponentName> services = scout.get(pkg).services;
        for (int i = 0; i < services.size(); i++) {
            ComponentName cn = services.get(i);
            if (scout.isQuarantined(cn)) {
                continue;
            }
            Intent intent = new Intent();
            intent.setComponent(cn);
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            try {
                ComponentName started = ctx.startService(intent);
                if (started != null) {
                    append(trace, "startService=" + Pkgs.shortName(cn.flattenToShortString()));
                    return true;
                }
            } catch (IllegalStateException ise) {
                // Android 8+ background start limit. Should not happen while we
                // are on the power allowlist, but say so rather than hide it.
                append(trace, "startService blocked (background limit) - is the "
                        + "power allowlist applied?");
                return false;
            } catch (Throwable t) {
                append(trace, "startService failed: " + t.getClass().getSimpleName());
            }
        }
        return false;
    }

    /**
     * Last resort: launch the target the way a user tap would. Visible by
     * design; the caller is responsible for the screen-state gate.
     */
    public Result escalateActivity(String pkg) {
        Intent i = Pkgs.launchIntent(ctx, pkg);
        if (i == null) {
            return new Result("activity", false, false, "no launcher activity");
        }
        try {
            ctx.startActivity(i);
            EventLog.i("ESCALATED " + pkg + ": launched " + i.getComponent());
            return new Result("activity", true, true, "launched " + i.getComponent());
        } catch (Throwable t) {
            // Most likely a background-activity-start refusal, which should not
            // happen as Device Owner. Report it rather than swallow it.
            EventLog.w("escalation failed for " + pkg, t);
            return new Result("activity", true, false, "startActivity failed: " + t);
        }
    }

    /**
     * If a target died within this window of us poking a receiver, that
     * receiver is the prime suspect.
     */
    public ComponentName suspectFor(String pkg, long deathTs, long windowMs) {
        Long at = lastPokeAt.get(pkg);
        if (at == null) {
            return null;
        }
        long delta = deathTs - at.longValue();
        if (delta >= 0 && delta <= windowMs) {
            return lastPoked.get(pkg);
        }
        return null;
    }

    private static void append(StringBuilder sb, String s) {
        if (sb.length() > 0) {
            sb.append("; ");
        }
        sb.append(s);
    }
}
