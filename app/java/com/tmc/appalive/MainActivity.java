package com.tmc.appalive;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * A status dashboard, built in code so the build stays free of AndroidX and
 * layout inflation.
 *
 * Everything here is diagnostic or hands you to a screen only you can act on:
 * the manufacturer auto-start allowlist is the one lever neither adb nor
 * Device Owner can pull.
 */
public final class MainActivity extends Activity {

    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText(getString(R.string.app_name));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Keeps a configured set of apps running, and itself with them.");
        subtitle.setPadding(0, dp(4), 0, dp(12));
        root.addView(subtitle);

        addButton(root, "Refresh", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refresh();
            }
        });

        addButton(root, "Revive all targets now", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Watchdog w = AliveService.watchdog();
                if (w == null) {
                    Starter.start(MainActivity.this, AliveService.class, true);
                    toast("Service was down - starting it");
                } else {
                    w.reviveNow(null);
                    toast("Revive requested");
                }
                refresh();
            }
        });

        addButton(root, "Manufacturer auto-start settings", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!OemAutoStart.open(MainActivity.this)) {
                    toast("No known auto-start screen on this device");
                }
            }
        });

        addButton(root, "Battery optimisation settings", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startIfPossible(OemAutoStart.batteryOptimizationSettings(MainActivity.this));
            }
        });

        addButton(root, "Display-over-other-apps permission", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startIfPossible(OemAutoStart.overlayPermissionSettings(MainActivity.this));
            }
        });

        TextView hint = new TextView(this);
        hint.setText("If a target is still being killed, the manufacturer auto-start "
                + "allowlist above is almost always the cause. It cannot be set over "
                + "adb or by Device Owner - only by tapping it here.");
        hint.setPadding(0, dp(12), 0, dp(12));
        root.addView(hint);

        status = new TextView(this);
        status.setTypeface(Typeface.MONOSPACE);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        status.setTextIsSelectable(true);
        status.setTextColor(Color.DKGRAY);
        root.addView(status);

        ScrollView scroller = new ScrollView(this);
        scroller.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroller);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Launching the activity is also what clears the initial stopped state
        // after `adb install`, which is why the installer opens it once.
        if (Config.get(this).enabled()) {
            Starter.start(this, AliveService.class, true);
        }
        refresh();
    }

    private void refresh() {
        try {
            status.setText(AliveService.statusText(this));
        } catch (Throwable t) {
            status.setText("status unavailable: " + t);
        }
    }

    private void startIfPossible(Intent i) {
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

    private void addButton(LinearLayout parent, String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        parent.addView(b, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }
}
