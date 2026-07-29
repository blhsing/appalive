package com.tmc.appalive;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds the components of a target app that we are actually able to poke.
 *
 * Only components with no {@code android:permission} are kept: if a component
 * demands a permission we do not hold, the attempt fails anyway and we would
 * just be burning ticks on it.
 */
public final class ComponentScout {

    /** How long a scan stays valid absent an explicit package change. */
    private static final long TTL_MS = 10 * 60 * 1000L;

    public static final class Candidates {
        public final List<ComponentName> services = new ArrayList<ComponentName>();
        public final List<ComponentName> receivers = new ArrayList<ComponentName>();
        public final List<String> providerAuthorities = new ArrayList<String>();
        long builtAt;

        public boolean isEmpty() {
            return services.isEmpty() && receivers.isEmpty() && providerAuthorities.isEmpty();
        }

        public String summary() {
            return services.size() + " svc / " + receivers.size() + " rcv / "
                    + providerAuthorities.size() + " provider";
        }
    }

    private final Context ctx;
    private final Map<String, Candidates> cache = new HashMap<String, Candidates>();
    /** Components that appeared to crash the target when poked. */
    private final Set<String> quarantined = new HashSet<String>();

    public ComponentScout(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public synchronized Candidates get(String pkg) {
        Candidates c = cache.get(pkg);
        long now = System.currentTimeMillis();
        if (c != null && now - c.builtAt < TTL_MS) {
            return c;
        }
        c = scan(pkg);
        c.builtAt = now;
        cache.put(pkg, c);
        EventLog.i("scout " + pkg + ": " + c.summary());
        return c;
    }

    public synchronized void invalidate(String pkg) {
        cache.remove(pkg);
    }

    public synchronized void invalidateAll() {
        cache.clear();
    }

    /** Stop poking a component that looks like it makes the target crash. */
    public synchronized void quarantine(ComponentName cn) {
        if (quarantined.add(cn.flattenToShortString())) {
            EventLog.w("quarantined " + cn.flattenToShortString() + " (target died right after a poke)");
        }
    }

    public synchronized boolean isQuarantined(ComponentName cn) {
        return quarantined.contains(cn.flattenToShortString());
    }

    private Candidates scan(String pkg) {
        Candidates c = new Candidates();
        PackageManager pm = ctx.getPackageManager();
        PackageInfo pi;
        try {
            pi = pm.getPackageInfo(pkg, PackageManager.GET_SERVICES
                    | PackageManager.GET_RECEIVERS
                    | PackageManager.GET_PROVIDERS);
        } catch (Throwable t) {
            EventLog.w("scout " + pkg + ": cannot read package info", t);
            return c;
        }

        if (pi.services != null) {
            for (ServiceInfo si : pi.services) {
                if (si != null && si.exported && si.enabled && si.permission == null) {
                    c.services.add(new ComponentName(si.packageName, si.name));
                }
            }
        }
        if (pi.receivers != null) {
            for (android.content.pm.ActivityInfo ri : pi.receivers) {
                if (ri != null && ri.exported && ri.enabled && ri.permission == null) {
                    c.receivers.add(new ComponentName(ri.packageName, ri.name));
                }
            }
        }
        if (pi.providers != null) {
            for (ProviderInfo pr : pi.providers) {
                if (pr == null || !pr.exported || !pr.enabled || pr.authority == null) {
                    continue;
                }
                // acquireContentProviderClient performs a permission check, so
                // a guarded provider would just throw SecurityException.
                if (pr.readPermission != null || pr.writePermission != null) {
                    continue;
                }
                // An authority attribute may hold a semicolon-separated list.
                String[] auths = pr.authority.split(";");
                for (String a : auths) {
                    String trimmed = a.trim();
                    if (trimmed.length() > 0) {
                        c.providerAuthorities.add(trimmed);
                    }
                }
            }
        }
        return c;
    }
}
