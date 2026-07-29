package com.tmc.appalive;

/** Shared constants. */
public final class Const {

    private Const() {
    }

    public static final String TAG = "AppAlive";

    /** Control channel; the receiver is guarded by android.permission.DUMP. */
    public static final String ACTION_CONTROL = "com.tmc.appalive.action.CONTROL";
    /**
     * Action used when poking a target's receiver awake. Deliberately our own
     * namespace: the target will not recognise it and will ignore the intent,
     * but the process still has to be started to deliver it.
     */
    public static final String ACTION_PING = "com.tmc.appalive.action.PING";
    public static final String ACTION_HEARTBEAT = "com.tmc.appalive.action.HEARTBEAT";
    public static final String ACTION_TICK_NOW = "com.tmc.appalive.action.TICK_NOW";

    public static final String EXTRA_CMD = "cmd";
    public static final String EXTRA_TARGETS = "targets";
    public static final String EXTRA_KEY = "key";
    public static final String EXTRA_VALUE = "value";
    public static final String EXTRA_PKG = "pkg";

    public static final String CHANNEL_MAIN = "appalive.main";
    public static final String CHANNEL_GUARD = "appalive.guard";
    public static final String NOTIF_GROUP = "appalive";

    public static final int NOTIF_MAIN = 1001;
    public static final int NOTIF_GUARD = 1002;

    public static final int JOB_ID = 47110815;

    public static final int ALARM_REQUEST = 7001;
}
