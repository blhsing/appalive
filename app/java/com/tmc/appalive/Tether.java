package com.tmc.appalive;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Holds a long-lived binding to each target that exposes a bindable service.
 *
 * This is the strongest keep-alive lever available without root, and it does
 * two jobs at once:
 *
 *  - Prevention. BIND_IMPORTANT raises the *target's* oom_adj to our own
 *    (foreground-service) level, so the low-memory killer picks other victims
 *    first. BIND_AUTO_CREATE also makes the framework restart the service if it
 *    does go down.
 *  - Detection. onServiceDisconnected fires the moment the target's process
 *    dies, which beats polling exit records by up to a full tick.
 *
 * The cost, stated plainly: tethered target processes stay resident, so this
 * trades RAM for survivability. Set tether_enabled=false to turn it off.
 */
public final class Tether {

    public interface Listener {
        void onTargetDied(String pkg);

        void onTargetConnected(String pkg);
    }

    private static final int BIND_FLAGS = Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT;
    private static final long MIN_REBIND_MS = 15_000L;

    private final Context ctx;
    private final ComponentScout scout;
    private final Listener listener;
    private final Map<String, Link> links = new HashMap<String, Link>();

    public Tether(Context ctx, ComponentScout scout, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.scout = scout;
        this.listener = listener;
    }

    private final class Link implements ServiceConnection {
        final String pkg;
        ComponentName boundTo;
        boolean bindRequested;
        volatile boolean connected;
        long lastBindAt;
        int candidateIndex;
        String note = "not attempted";

        Link(String pkg) {
            this.pkg = pkg;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            connected = true;
            note = "tethered to " + Pkgs.shortName(name.flattenToShortString());
            EventLog.i("tether: connected " + pkg + " via " + name.getShortClassName());
            listener.onTargetConnected(pkg);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            connected = false;
            note = "disconnected (process died)";
            EventLog.i("tether: DISCONNECTED " + pkg + " - process died");
            listener.onTargetDied(pkg);
        }

        @Override
        public void onBindingDied(ComponentName name) {
            connected = false;
            note = "binding died";
            EventLog.i("tether: binding died for " + pkg);
            // The binding is gone for good; drop it so the next tick rebinds.
            release();
            listener.onTargetDied(pkg);
        }

        @Override
        public void onNullBinding(ComponentName name) {
            // onBind() returned null: we cannot hold this one open, but the
            // process did start, which is all a revival needs.
            connected = false;
            note = "service returned null binding (process started, not tetherable)";
            EventLog.i("tether: null binding from " + pkg + ", trying next candidate");
            release();
            candidateIndex++;
        }

        void release() {
            if (bindRequested) {
                try {
                    ctx.unbindService(this);
                } catch (Throwable ignored) {
                    // not bound / already unbound
                }
                bindRequested = false;
            }
            boundTo = null;
        }
    }

    /** Reconcile the live link set with the configured targets. */
    public synchronized void setTargets(List<String> pkgs) {
        Iterator<Map.Entry<String, Link>> it = links.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Link> e = it.next();
            if (!pkgs.contains(e.getKey())) {
                e.getValue().release();
                it.remove();
            }
        }
        for (int i = 0; i < pkgs.size(); i++) {
            String p = pkgs.get(i);
            if (!links.containsKey(p)) {
                links.put(p, new Link(p));
            }
        }
    }

    public synchronized boolean isConnected(String pkg) {
        Link l = links.get(pkg);
        return l != null && l.connected;
    }

    public synchronized String describe(String pkg) {
        Link l = links.get(pkg);
        return l == null ? "no link" : l.note;
    }

    /**
     * Ensure a binding attempt is in flight for {@code pkg}.
     *
     * @return true if the framework accepted a bind (which starts the target's
     * process); false if there was nothing bindable or the bind was refused.
     */
    public synchronized boolean ensureBound(String pkg) {
        Link l = links.get(pkg);
        if (l == null) {
            l = new Link(pkg);
            links.put(pkg, l);
        }
        if (l.connected) {
            return true;
        }
        long now = System.currentTimeMillis();
        if (l.bindRequested && now - l.lastBindAt < MIN_REBIND_MS) {
            // A bind is already pending; give it time to land.
            return true;
        }
        l.release();

        List<ComponentName> candidates = scout.get(pkg).services;
        if (candidates.isEmpty()) {
            l.note = "no bindable exported service";
            return false;
        }
        if (l.candidateIndex >= candidates.size()) {
            l.candidateIndex = 0;
        }

        // Try each candidate once, starting where we left off.
        for (int attempt = 0; attempt < candidates.size(); attempt++) {
            int idx = (l.candidateIndex + attempt) % candidates.size();
            ComponentName cn = candidates.get(idx);
            if (scout.isQuarantined(cn)) {
                continue;
            }
            Intent intent = new Intent();
            intent.setComponent(cn);
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            try {
                if (ctx.bindService(intent, l, BIND_FLAGS)) {
                    l.bindRequested = true;
                    l.boundTo = cn;
                    l.lastBindAt = now;
                    l.candidateIndex = idx;
                    l.note = "bind requested -> " + Pkgs.shortName(cn.flattenToShortString());
                    return true;
                }
                // bindService returned false: it still leaves the connection
                // registered, so unbind before trying the next candidate.
                try {
                    ctx.unbindService(l);
                } catch (Throwable ignored) {
                }
            } catch (SecurityException se) {
                l.note = "bind denied: " + se.getMessage();
            } catch (Throwable t) {
                l.note = "bind failed: " + t;
            }
        }
        l.note = "no candidate service accepted a bind";
        return false;
    }

    public synchronized List<String> connectedPackages() {
        List<String> out = new ArrayList<String>();
        for (Map.Entry<String, Link> e : links.entrySet()) {
            if (e.getValue().connected) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    public synchronized void stop() {
        for (Link l : links.values()) {
            l.release();
        }
        links.clear();
    }
}
