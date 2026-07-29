package com.tmc.appalive;

import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.PowerManager;

import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The policy engine: decides when a target is dead and what to do about it.
 *
 * Failure is only ever counted from *evidence* - a tether disconnect, or a new
 * process-death record arriving after we already tried to revive. Without
 * evidence we never escalate, because an unconfirmable target (no exported
 * service, no DUMP permission) would otherwise look like a permanent failure
 * and trigger an escalation storm.
 */
public final class Watchdog implements Tether.Listener {

    /** How long after a silent attempt we wait before judging it. */
    private static final int VERDICT_TICKS = 2;
    /** A target dying this soon after a receiver poke implicates that receiver. */
    private static final long CRASH_ATTRIBUTION_MS = 15_000L;

    static final class State {
        long handledExitTs;
        int consecutiveFailures;
        long nextAttemptAt;
        long lastAttemptAt;
        long lastConfirmedAliveAt;
        long awaitingVerdictUntil;
        boolean deathSinceAttempt;
        boolean pendingRevive;
        boolean escalationPending;
        boolean missing;
        int reviveCount;
        int escalationCount;
        String lastTier = "-";
        String lastDetail = "";
        String lastExitReason = "-";
    }

    private final Context ctx;
    private final Config cfg;
    private final ComponentScout scout;
    private final DeathWatcher deaths;
    private final Tether tether;
    private final Reviver reviver;
    private final Handler handler;

    private final Map<String, State> states = new LinkedHashMap<String, State>();

    private volatile boolean running;
    private long tickCount;
    private long startedAt;

    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                tick();
            } catch (Throwable t) {
                EventLog.w("watchdog tick failed", t);
            }
            if (running) {
                handler.postDelayed(this, cfg.pollIntervalMs());
            }
        }
    };

    public Watchdog(Context ctx, Config cfg, Handler handler) {
        this.ctx = ctx.getApplicationContext();
        this.cfg = cfg;
        this.handler = handler;
        this.scout = new ComponentScout(this.ctx);
        this.deaths = new DeathWatcher(this.ctx);
        this.tether = new Tether(this.ctx, scout, this);
        this.reviver = new Reviver(this.ctx, scout, tether, cfg);
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        startedAt = System.currentTimeMillis();
        EventLog.i("watchdog starting; death detection = "
                + (deaths.available() ? "exit records (DUMP granted)"
                : "UNAVAILABLE: " + deaths.unavailableReason()));
        handler.post(tickRunnable);
    }

    public void stop() {
        running = false;
        handler.removeCallbacks(tickRunnable);
        tether.stop();
        EventLog.i("watchdog stopped");
    }

    public boolean isRunning() {
        return running;
    }

    public void tickSoon(long delayMs) {
        handler.removeCallbacks(tickRunnable);
        handler.postDelayed(tickRunnable, delayMs);
    }

    public void invalidateScout(String pkg) {
        if (pkg == null) {
            scout.invalidateAll();
        } else {
            scout.invalidate(pkg);
        }
    }

    /** Control-channel "revive" command: force an attempt now. */
    public void reviveNow(final String pkg) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                List<TargetSpec> specs = cfg.targets();
                for (int i = 0; i < specs.size(); i++) {
                    TargetSpec s = specs.get(i);
                    if (pkg == null || pkg.equals(s.pkg)) {
                        State st = state(s.pkg);
                        st.pendingRevive = true;
                        st.nextAttemptAt = 0;
                    }
                }
                tick();
            }
        });
    }

    // ------------------------------------------------------------- main loop

    private void tick() {
        tickCount++;
        if (!cfg.enabled()) {
            return;
        }
        List<TargetSpec> specs = cfg.targets();

        List<String> live = new ArrayList<String>();
        for (int i = 0; i < specs.size(); i++) {
            if (specs.get(i).enabled) {
                live.add(specs.get(i).pkg);
            }
        }
        if (cfg.tetherEnabled()) {
            tether.setTargets(live);
        } else {
            tether.stop();
        }

        long now = System.currentTimeMillis();
        for (int i = 0; i < specs.size(); i++) {
            TargetSpec spec = specs.get(i);
            if (!spec.enabled) {
                continue;
            }
            try {
                evaluate(spec, now);
            } catch (Throwable t) {
                EventLog.w("evaluate(" + spec.pkg + ") failed", t);
            }
        }
    }

    private void evaluate(TargetSpec spec, long now) {
        final String pkg = spec.pkg;
        State st = state(pkg);

        if (!Pkgs.isInstalled(ctx, pkg)) {
            if (!st.missing) {
                st.missing = true;
                EventLog.w("target not installed: " + pkg);
            }
            return;
        }
        if (st.missing) {
            st.missing = false;
            scout.invalidate(pkg);
            EventLog.i("target reappeared: " + pkg);
        }

        // --- 1. did the last unconfirmed attempt work out? -------------------
        if (st.awaitingVerdictUntil > 0 && now >= st.awaitingVerdictUntil) {
            if (st.deathSinceAttempt) {
                st.consecutiveFailures++;
                EventLog.i("verdict " + pkg + ": still dying after revive (failures="
                        + st.consecutiveFailures + ")");
                if (st.consecutiveFailures >= cfg.escalateAfterFailures()) {
                    st.escalationPending = true;
                }
            } else {
                if (st.consecutiveFailures > 0) {
                    EventLog.i("verdict " + pkg + ": recovered");
                }
                st.consecutiveFailures = 0;
                st.escalationPending = false;
                st.lastConfirmedAliveAt = now;
            }
            st.awaitingVerdictUntil = 0;
            st.deathSinceAttempt = false;
        }

        // --- 2. tether says it is definitely up ------------------------------
        if (tether.isConnected(pkg)) {
            st.consecutiveFailures = 0;
            st.escalationPending = false;
            st.pendingRevive = false;
            st.lastConfirmedAliveAt = now;
            syncExitHighWater(pkg, st);
            return;
        }

        // --- 3. new death record? --------------------------------------------
        boolean deadEvent = false;
        DeathWatcher.Exit exit = deaths.newest(pkg);
        if (exit != null && exit.timestamp > st.handledExitTs) {
            st.handledExitTs = exit.timestamp;
            cfg.setHandledExitTs(pkg, exit.timestamp);
            st.lastExitReason = DeathWatcher.reasonName(exit.reason);
            deadEvent = true;
            if (st.awaitingVerdictUntil > 0) {
                st.deathSinceAttempt = true;
            }
            EventLog.i("death " + pkg + " reason=" + st.lastExitReason + " pid=" + exit.pid);

            // A crash right after we poked a receiver implicates that receiver.
            if (exit.reason == 4 || exit.reason == 5) {
                ComponentName suspect =
                        reviver.suspectFor(pkg, exit.timestamp, CRASH_ATTRIBUTION_MS);
                if (suspect != null) {
                    scout.quarantine(suspect);
                }
            }
            if (DeathWatcher.isPackageChurn(exit.reason)) {
                // Being installed/updated. Fighting that is pointless and racy.
                scout.invalidate(pkg);
                EventLog.i("skip revive " + pkg + ": package is being updated");
                return;
            }
            if (DeathWatcher.isUserInitiated(exit.reason) && !cfg.reviveOnUserRequested()) {
                EventLog.i("skip revive " + pkg + ": user-initiated stop and "
                        + Config.KEY_REVIVE_ON_USER_REQUESTED + "=false");
                return;
            }
            st.pendingRevive = true;
        }

        // --- 4. should we act this tick? -------------------------------------
        boolean firstSight = st.lastAttemptAt == 0 && st.lastConfirmedAliveAt == 0;
        boolean ensureDue = !deaths.available()
                && now - st.lastAttemptAt >= cfg.ensureIntervalMs();

        boolean act = st.pendingRevive || ensureDue || firstSight;
        if (!act) {
            maybeEscalate(spec, st, now);
            return;
        }
        if (now < st.nextAttemptAt) {
            return;
        }

        // --- 5. silent ladder -------------------------------------------------
        Reviver.Result r = reviver.reviveSilently(pkg);
        st.lastAttemptAt = now;
        st.pendingRevive = false;
        st.reviveCount++;
        st.lastTier = r.tier;
        st.lastDetail = r.detail;

        if (r.confirmed) {
            st.consecutiveFailures = 0;
            st.escalationPending = false;
            st.awaitingVerdictUntil = 0;
            st.deathSinceAttempt = false;
            st.lastConfirmedAliveAt = now;
            st.nextAttemptAt = now + cfg.minReviveIntervalMs();
            EventLog.i("revived " + pkg + " [" + r.tier + "] " + r.detail);
            return;
        }

        if (r.attempted) {
            // No proof either way; judge it in a couple of ticks.
            st.awaitingVerdictUntil = now + VERDICT_TICKS * cfg.pollIntervalMs();
            st.deathSinceAttempt = false;
            st.nextAttemptAt = now + backoff(st.consecutiveFailures + 1);
            EventLog.i("poked " + pkg + " [" + r.tier + "] " + r.detail);
        } else {
            // Nothing pokeable at all: only the activity route is left.
            st.consecutiveFailures++;
            st.nextAttemptAt = now + backoff(st.consecutiveFailures);
            if (st.consecutiveFailures >= cfg.escalateAfterFailures()) {
                st.escalationPending = true;
            }
            EventLog.w("no silent route to revive " + pkg + " (" + r.detail + ")");
        }

        maybeEscalate(spec, st, now);
    }

    private void maybeEscalate(TargetSpec spec, State st, long now) {
        if (!st.escalationPending || spec.escalate == TargetSpec.ESCALATE_NEVER) {
            return;
        }
        if (spec.escalate == TargetSpec.ESCALATE_SCREEN_OFF && !screenGateOpen()) {
            return; // wait for the screen to go off; ScreenState nudges us then
        }
        Reviver.Result r = reviver.escalateActivity(spec.pkg);
        st.escalationCount++;
        st.lastTier = r.tier;
        st.lastDetail = r.detail;
        st.lastAttemptAt = now;
        if (r.confirmed) {
            st.escalationPending = false;
            st.consecutiveFailures = 0;
            st.awaitingVerdictUntil = 0;
            st.deathSinceAttempt = false;
            st.lastConfirmedAliveAt = now;
            st.nextAttemptAt = now + cfg.minReviveIntervalMs();
        } else {
            st.nextAttemptAt = now + backoff(st.consecutiveFailures + 1);
        }
    }

    private void syncExitHighWater(String pkg, State st) {
        DeathWatcher.Exit e = deaths.newest(pkg);
        if (e != null && e.timestamp > st.handledExitTs) {
            st.handledExitTs = e.timestamp;
            cfg.setHandledExitTs(pkg, e.timestamp);
        }
    }

    /** Screen off or keyguard locked: escalation is invisible to the user. */
    public boolean screenGateOpen() {
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            boolean interactive = pm != null && pm.isInteractive();
            if (!interactive) {
                return true;
            }
            KeyguardManager km =
                    (KeyguardManager) ctx.getSystemService(Context.KEYGUARD_SERVICE);
            return km != null && km.isKeyguardLocked();
        } catch (Throwable t) {
            return false;
        }
    }

    private long backoff(int failures) {
        long v = cfg.minReviveIntervalMs();
        long max = cfg.maxReviveIntervalMs();
        for (int i = 1; i < failures && v < max; i++) {
            v *= 2;
        }
        return v > max ? max : v;
    }

    private State state(String pkg) {
        synchronized (states) {
            State s = states.get(pkg);
            if (s == null) {
                s = new State();
                s.handledExitTs = cfg.handledExitTs(pkg);
                states.put(pkg, s);
            }
            return s;
        }
    }

    // ------------------------------------------------------- Tether.Listener

    @Override
    public void onTargetDied(final String pkg) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                State st = state(pkg);
                st.pendingRevive = true;
                if (st.awaitingVerdictUntil > 0) {
                    st.deathSinceAttempt = true;
                }
                // Small delay: let the framework finish tearing the process down
                // before we try to bring it back.
                tickSoon(1_500L);
            }
        });
    }

    @Override
    public void onTargetConnected(final String pkg) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                State st = state(pkg);
                st.consecutiveFailures = 0;
                st.escalationPending = false;
                st.awaitingVerdictUntil = 0;
                st.deathSinceAttempt = false;
                st.lastConfirmedAliveAt = System.currentTimeMillis();
            }
        });
    }

    // ------------------------------------------------------------- reporting

    public String shortStatus() {
        List<TargetSpec> specs = cfg.targets();
        int connected = tether.connectedPackages().size();
        return "targets=" + specs.size() + ", tethered=" + connected
                + ", detection=" + (deaths.available() ? "exit-records" : "blind")
                + ", ticks=" + tickCount;
    }

    public Map<String, String> targetLines() {
        Map<String, String> out = new LinkedHashMap<String, String>();
        List<TargetSpec> specs = cfg.targets();
        Map<String, State> snapshot;
        synchronized (states) {
            snapshot = new HashMap<String, State>(states);
        }
        for (int i = 0; i < specs.size(); i++) {
            TargetSpec spec = specs.get(i);
            State st = snapshot.get(spec.pkg);
            StringBuilder sb = new StringBuilder();
            if (!Pkgs.isInstalled(ctx, spec.pkg)) {
                sb.append("NOT INSTALLED");
            } else if (tether.isConnected(spec.pkg)) {
                sb.append("ALIVE (tethered)");
            } else if (st != null && st.lastConfirmedAliveAt > 0) {
                sb.append("last confirmed ").append(ago(st.lastConfirmedAliveAt));
            } else {
                sb.append("unconfirmed");
            }
            if (st != null) {
                sb.append(" | revives=").append(st.reviveCount);
                if (st.escalationCount > 0) {
                    sb.append(" escalations=").append(st.escalationCount);
                }
                if (st.consecutiveFailures > 0) {
                    sb.append(" failures=").append(st.consecutiveFailures);
                }
                if (st.escalationPending) {
                    sb.append(" [ESCALATION PENDING");
                    if (spec.escalate == TargetSpec.ESCALATE_SCREEN_OFF) {
                        sb.append(", waiting for screen off");
                    }
                    sb.append("]");
                }
                sb.append(" | lastExit=").append(st.lastExitReason);
                sb.append(" | last=").append(st.lastTier);
                if (st.lastDetail != null && st.lastDetail.length() > 0) {
                    sb.append(" (").append(st.lastDetail).append(")");
                }
            }
            sb.append(" | escalate=").append(TargetSpec.escalateName(spec.escalate));
            sb.append(" | ").append(tether.describe(spec.pkg));
            out.put(spec.pkg, sb.toString());
        }
        return out;
    }

    private static String ago(long then) {
        long s = (System.currentTimeMillis() - then) / 1000L;
        if (s < 60) {
            return s + "s ago";
        }
        if (s < 3600) {
            return (s / 60) + "m ago";
        }
        return (s / 3600) + "h ago";
    }

    public void dump(PrintWriter pw) {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        pw.println("  running=" + running);
        pw.println("  startedAt=" + (startedAt == 0 ? "-" : fmt.format(new Date(startedAt))));
        pw.println("  ticks=" + tickCount);
        pw.println("  deathDetection=" + (deaths.available()
                ? "getHistoricalProcessExitReasons (DUMP granted)"
                : "UNAVAILABLE - " + deaths.unavailableReason()));
        pw.println("  screenGateOpen=" + screenGateOpen());
        pw.println();
        pw.println("  config: " + cfg.toJson().toString());
        pw.println();
        pw.println("  targets:");
        Map<String, String> lines = targetLines();
        if (lines.isEmpty()) {
            pw.println("    (none configured)");
        }
        for (Map.Entry<String, String> e : lines.entrySet()) {
            pw.println("    " + e.getKey());
            pw.println("        " + e.getValue());
        }
    }
}
