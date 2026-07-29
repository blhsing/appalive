package com.tmc.appalive;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * The configuration screen.
 *
 * Everything the service needs can be set here, so a user who was handed the
 * device never has to touch adb. The installer's --targets flag remains the
 * way to script a fleet, but it is no longer the only way in.
 *
 * One thing this screen is careful to be honest about: adding a target here
 * gets it watched and revived, but it does NOT get the battery exemptions.
 * Those need app ops the app cannot set for another package (they are
 * signature-level), so only the installer can apply them. Targets missing the
 * exemption are flagged, with the exact command to fix it.
 */
public final class MainActivity extends Activity {

    private LinearLayout root;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroller = new ScrollView(this);
        scroller.setBackgroundColor(Ui.BG);
        root = Ui.column(this);
        scroller.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroller);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Launching this activity is also what clears the freshly-installed
        // stopped state, which is why install.sh opens it once.
        if (Config.get(this).enabled()) {
            Starter.start(this, AliveService.class, true);
        }
        rebuild();
    }

    // --------------------------------------------------------------- screen

    private void rebuild() {
        root.removeAllViews();
        Config cfg = Config.get(this);

        root.addView(header());
        root.addView(statusCard(cfg));

        root.addView(Ui.sectionHeader(this, "Apps being kept alive"));
        root.addView(targetsCard(cfg), Ui.wide(this, 14));

        root.addView(Ui.spacer(this, 10));
        root.addView(Ui.button(this, "+  Choose apps", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, AppPickerActivity.class));
            }
        }), Ui.wide(this, 14));

        root.addView(Ui.sectionHeader(this, "Behaviour"));
        root.addView(behaviourCard(cfg), Ui.wide(this, 14));

        root.addView(Ui.sectionHeader(this, "Protection"));
        root.addView(protectionCard(cfg), Ui.wide(this, 14));

        root.addView(Ui.sectionHeader(this, "Manufacturer settings"));
        root.addView(oemCard(), Ui.wide(this, 14));

        root.addView(Ui.spacer(this, 16));
        root.addView(Ui.button(this, "Diagnostics", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, DiagnosticsActivity.class));
            }
        }), Ui.wide(this, 14));
        root.addView(Ui.spacer(this, 28));
    }

    private View header() {
        LinearLayout bar = Ui.row(this);
        bar.setBackgroundColor(Ui.BRAND_DARK);
        bar.setPadding(Ui.dp(this, 18), Ui.dp(this, 22), Ui.dp(this, 18), Ui.dp(this, 22));

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.mipmap.ic_launcher);
        int s = Ui.dp(this, 40);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(s, s);
        ip.rightMargin = Ui.dp(this, 14);
        bar.addView(icon, ip);

        LinearLayout titles = Ui.column(this);
        titles.addView(Ui.text(this, "AppAlive", 21, Color.WHITE, true));
        titles.addView(Ui.text(this, "Keeps your apps running", 13, 0xFF9FB6BF, false));
        bar.addView(titles);
        return bar;
    }

    private View statusCard(Config cfg) {
        boolean running = AliveService.isRunning();
        LinearLayout card = Ui.card(this);

        Switch master = new Switch(this);
        master.setChecked(cfg.enabled());
        master.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                Config c = Config.get(MainActivity.this);
                c.setBool(Config.KEY_ENABLED, on);
                if (on) {
                    Starter.start(MainActivity.this, AliveService.class, true);
                    Heartbeat.schedule(MainActivity.this);
                    Heartbeat.scheduleJob(MainActivity.this);
                } else {
                    Heartbeat.cancel(MainActivity.this);
                    Heartbeat.cancelJob(MainActivity.this);
                    AliveService.requestStop(MainActivity.this);
                }
                root.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        rebuild();
                    }
                }, 800L);
            }
        });
        card.addView(Ui.controlRow(this, "Keep apps alive",
                running ? "Service is running" : "Service is stopped", master));
        card.addView(Ui.divider(this));

        Watchdog w = AliveService.watchdog();
        String detection = w == null ? "unknown"
                : (w.shortStatus().contains("exit-records")
                ? "Process-death detection active"
                : "Limited: falling back to periodic checks");
        card.addView(Ui.controlRow(this, "Detection", detection, null));

        LinearLayout wrap = Ui.column(this);
        wrap.addView(card, Ui.wide(this, 14));
        wrap.setPadding(0, Ui.dp(this, 14), 0, 0);
        return wrap;
    }

    private View targetsCard(Config cfg) {
        LinearLayout card = Ui.card(this);
        final List<TargetSpec> specs = cfg.targets();

        if (specs.isEmpty()) {
            TextView empty = Ui.text(this,
                    "No apps chosen yet.\n\nPick the apps that keep getting killed, and "
                            + "AppAlive will watch them and bring them back.", 14, Ui.MUTED, false);
            empty.setPadding(Ui.dp(this, 16), Ui.dp(this, 18), Ui.dp(this, 16), Ui.dp(this, 18));
            card.addView(empty);
            return card;
        }

        Watchdog w = AliveService.watchdog();
        // Snapshot once: targetLines() walks every target and hits PackageManager,
        // so calling it per row would be quadratic.
        java.util.Map<String, String> lines =
                w == null ? java.util.Collections.<String, String>emptyMap() : w.targetLines();
        for (int i = 0; i < specs.size(); i++) {
            if (i > 0) {
                card.addView(Ui.divider(this));
            }
            card.addView(targetRow(specs.get(i), lines));
        }
        return card;
    }

    private View targetRow(final TargetSpec spec, java.util.Map<String, String> lines) {
        PackageManager pm = getPackageManager();
        boolean installed = Pkgs.isInstalled(this, spec.pkg);

        LinearLayout r = Ui.row(this);
        r.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12));

        ImageView icon = new ImageView(this);
        int s = Ui.dp(this, 38);
        try {
            Drawable d = pm.getApplicationIcon(spec.pkg);
            icon.setImageDrawable(d);
        } catch (Throwable t) {
            icon.setImageResource(R.mipmap.ic_launcher);
        }
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(s, s);
        ip.rightMargin = Ui.dp(this, 12);
        r.addView(icon, ip);

        LinearLayout labels = Ui.column(this);
        labels.addView(Ui.text(this, label(spec.pkg), 15.5f, Ui.TEXT, true));

        LinearLayout badges = Ui.row(this);
        if (!installed) {
            badges.addView(Ui.pill(this, "NOT INSTALLED", Ui.DANGER));
        } else {
            String line = lines.get(spec.pkg);
            if (line != null && line.startsWith("ALIVE")) {
                badges.addView(Ui.pill(this, "ALIVE", 0xFF2E9E6B));
            } else {
                badges.addView(Ui.pill(this, "WATCHING", Ui.MUTED));
            }
        }
        if (installed && !isBatteryExempt(spec.pkg)) {
            View sp = new View(this);
            sp.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 6), 1));
            badges.addView(sp);
            badges.addView(Ui.pill(this, "NOT EXEMPT", Ui.WARN));
        }
        badges.setPadding(0, Ui.dp(this, 4), 0, 0);
        labels.addView(badges);

        TextView sub = Ui.text(this, spec.pkg + "  ·  escalate: "
                + escalateLabel(spec.escalate), 11.5f, Ui.MUTED, false);
        sub.setPadding(0, Ui.dp(this, 4), 0, 0);
        labels.addView(sub);

        r.addView(labels, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView chev = Ui.text(this, "›", 22, Ui.MUTED, false);
        chev.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), 0);
        r.addView(chev);

        r.setClickable(true);
        r.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showTargetDialog(spec);
            }
        });
        return r;
    }

    private void showTargetDialog(final TargetSpec spec) {
        final String[] modes = {
                "Never show the app",
                "Only when the screen is off",
                "Always, even while I'm using the phone",
        };
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle(label(spec.pkg));
        b.setSingleChoiceItems(modes, spec.escalate, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int which) {
                Config.get(MainActivity.this).setTargetEscalate(spec.pkg, which);
                AliveService.notifyConfigChanged(MainActivity.this);
            }
        });
        b.setPositiveButton("Done", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int which) {
                rebuild();
            }
        });
        b.setNeutralButton("Revive now", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int which) {
                Watchdog w = AliveService.watchdog();
                if (w != null) {
                    w.reviveNow(spec.pkg);
                    toast("Revive requested");
                } else {
                    Starter.start(MainActivity.this, AliveService.class, true);
                    toast("Service was down - starting it");
                }
            }
        });
        b.setNegativeButton("Remove", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int which) {
                Config.get(MainActivity.this).removeTarget(spec.pkg);
                AliveService.notifyConfigChanged(MainActivity.this);
                rebuild();
            }
        });
        AlertDialog dialog = b.create();
        dialog.setMessage("When the quiet methods fail, may AppAlive open this app "
                + "to bring it back?");
        dialog.show();
    }

    private View behaviourCard(final Config cfg) {
        LinearLayout card = Ui.card(this);
        card.addView(switchRow(cfg, Config.KEY_TETHER_ENABLED, true,
                "Hold apps in memory",
                "Strongest protection. Keeps chosen apps resident, so it uses more RAM."));
        card.addView(Ui.divider(this));
        card.addView(switchRow(cfg, Config.KEY_REVIVE_ON_USER_REQUESTED, true,
                "Restart after a manual Force Stop",
                "Turn off if you want Force Stop to actually stick."));
        card.addView(Ui.divider(this));
        card.addView(switchRow(cfg, Config.KEY_GUARD_FOREGROUND, true,
                "Watchdog helper notification",
                "A second notification from the backup process. Turning it off makes "
                        + "that process easier for the system to kill."));
        card.addView(Ui.divider(this));
        card.addView(switchRow(cfg, Config.KEY_HOLD_WAKELOCK, false,
                "Keep the CPU awake",
                "Last resort. Noticeably worse battery life."));
        return card;
    }

    private View protectionCard(final Config cfg) {
        LinearLayout card = Ui.card(this);
        boolean owner = DeviceOwnerManager.isDeviceOwner(this);

        TextView state = Ui.text(this,
                owner ? "Device owner active - full protection available"
                        : "Not device owner. Uninstall protection and the Force Stop "
                        + "block are unavailable. Re-run install.sh to provision it.",
                13, owner ? 0xFF2E9E6B : Ui.WARN, false);
        state.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 12));
        card.addView(state);

        if (owner) {
            card.addView(Ui.divider(this));
            card.addView(switchRow(cfg, Config.KEY_BLOCK_UNINSTALL, true,
                    "Block uninstall",
                    "Stops AppAlive and the chosen apps being uninstalled."));
            card.addView(Ui.divider(this));
            card.addView(switchRow(cfg, Config.KEY_DISALLOW_APPS_CONTROL, true,
                    "Disable Force Stop in Settings",
                    "Applies to every app on the device, not just the chosen ones."));
        }
        return card;
    }

    private View oemCard() {
        LinearLayout card = Ui.card(this);
        TextView why = Ui.text(this,
                "Phone makers keep their own auto-start list that no app or adb command "
                        + "can change. If something still gets killed, allow it here.",
                13, Ui.MUTED, false);
        why.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 12));
        card.addView(why);

        final List<Intent> oem = OemAutoStart.candidates(this);
        card.addView(Ui.divider(this));
        card.addView(actionRow("Open auto-start settings",
                oem.isEmpty() ? "No known screen on this device" : "Allow AppAlive and each app",
                oem.isEmpty() ? null : new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (!OemAutoStart.open(MainActivity.this)) {
                            toast("Could not open that screen");
                        }
                    }
                }));
        card.addView(Ui.divider(this));
        card.addView(actionRow("Battery optimisation", "Exempt AppAlive from Doze",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        start(OemAutoStart.batteryOptimizationSettings(MainActivity.this));
                    }
                }));
        card.addView(Ui.divider(this));
        card.addView(actionRow("Display over other apps",
                "Needed to reopen apps in the background",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        start(OemAutoStart.overlayPermissionSettings(MainActivity.this));
                    }
                }));
        return card;
    }

    // ---------------------------------------------------------------- pieces

    private View switchRow(final Config cfg, final String key, boolean def,
                           String title, String caption) {
        Switch sw = new Switch(this);
        sw.setChecked(readBool(cfg, key, def));
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                cfg.setBool(key, on);
                AliveService.notifyConfigChanged(MainActivity.this);
            }
        });
        return Ui.controlRow(this, title, caption, sw);
    }

    private boolean readBool(Config cfg, String key, boolean def) {
        if (Config.KEY_TETHER_ENABLED.equals(key)) return cfg.tetherEnabled();
        if (Config.KEY_REVIVE_ON_USER_REQUESTED.equals(key)) return cfg.reviveOnUserRequested();
        if (Config.KEY_GUARD_FOREGROUND.equals(key)) return cfg.guardForeground();
        if (Config.KEY_HOLD_WAKELOCK.equals(key)) return cfg.holdWakeLock();
        if (Config.KEY_BLOCK_UNINSTALL.equals(key)) return cfg.blockUninstall();
        if (Config.KEY_DISALLOW_APPS_CONTROL.equals(key)) return cfg.disallowAppsControl();
        return def;
    }

    private View actionRow(String title, String caption, View.OnClickListener l) {
        TextView chev = Ui.text(this, "›", 22, Ui.MUTED, false);
        chev.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), 0);
        LinearLayout r = Ui.controlRow(this, title, caption, chev);
        if (l != null) {
            r.setClickable(true);
            r.setOnClickListener(l);
        } else {
            r.setAlpha(0.5f);
        }
        return r;
    }

    private void start(Intent i) {
        if (i == null) {
            toast("Not available on this Android version");
            return;
        }
        try {
            startActivity(i);
        } catch (Throwable t) {
            toast("Could not open that screen");
        }
    }

    private String label(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Throwable t) {
            return pkg;
        }
    }

    private boolean isBatteryExempt(String pkg) {
        if (Build.VERSION.SDK_INT < 23) {
            return true;
        }
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(pkg);
        } catch (Throwable t) {
            return true; // unknown; do not cry wolf
        }
    }

    static String escalateLabel(int mode) {
        switch (mode) {
            case TargetSpec.ESCALATE_NEVER:
                return "never open the app";
            case TargetSpec.ESCALATE_ALWAYS:
                return "always";
            default:
                return "when screen is off";
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
