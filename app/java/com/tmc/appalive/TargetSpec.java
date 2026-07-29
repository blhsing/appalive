package com.tmc.appalive;

import org.json.JSONObject;

/** One app we are asked to keep running. */
public final class TargetSpec {

    /** When the silent revival tiers fail, may we launch the app's activity? */
    public static final int ESCALATE_NEVER = 0;
    /** Default: only while the screen is off or the keyguard is locked. */
    public static final int ESCALATE_SCREEN_OFF = 1;
    public static final int ESCALATE_ALWAYS = 2;

    public final String pkg;
    public final int escalate;
    public final boolean enabled;

    public TargetSpec(String pkg, int escalate, boolean enabled) {
        this.pkg = pkg;
        this.escalate = escalate;
        this.enabled = enabled;
    }

    public static int parseEscalate(String s) {
        if (s == null) {
            return ESCALATE_SCREEN_OFF;
        }
        String v = s.trim().toLowerCase(java.util.Locale.US);
        if ("never".equals(v) || "silent".equals(v)) {
            return ESCALATE_NEVER;
        }
        if ("always".equals(v)) {
            return ESCALATE_ALWAYS;
        }
        return ESCALATE_SCREEN_OFF;
    }

    public static String escalateName(int mode) {
        switch (mode) {
            case ESCALATE_NEVER:
                return "never";
            case ESCALATE_ALWAYS:
                return "always";
            default:
                return "screen_off";
        }
    }

    /** Accepts "com.foo" or "com.foo:always" / "com.foo:never". */
    public static TargetSpec parse(String token) {
        String t = token.trim();
        if (t.length() == 0) {
            return null;
        }
        int colon = t.indexOf(':');
        if (colon < 0) {
            return new TargetSpec(t, ESCALATE_SCREEN_OFF, true);
        }
        return new TargetSpec(t.substring(0, colon).trim(),
                parseEscalate(t.substring(colon + 1)), true);
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("pkg", pkg);
            o.put("escalate", escalateName(escalate));
            o.put("enabled", enabled);
        } catch (Throwable ignored) {
            // JSONObject.put only throws on NaN/Infinity values; none here.
        }
        return o;
    }

    public static TargetSpec fromJson(JSONObject o) {
        String p = o.optString("pkg", null);
        if (p == null || p.length() == 0) {
            return null;
        }
        return new TargetSpec(p, parseEscalate(o.optString("escalate", null)),
                o.optBoolean("enabled", true));
    }

    @Override
    public String toString() {
        return pkg + " (escalate=" + escalateName(escalate) + (enabled ? "" : ", disabled") + ")";
    }
}
