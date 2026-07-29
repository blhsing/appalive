package com.tmc.appalive;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The raw view: full watchdog state, and the exact command needed to give the
 * chosen apps their battery exemptions.
 *
 * That command exists because the app genuinely cannot do it itself. Setting
 * app ops or the power allowlist for *another* package needs signature-level
 * permissions, so only adb can. Rather than quietly under-deliver, the app
 * hands you the line to run.
 */
public final class DiagnosticsActivity extends Activity {

    private TextView dump;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);

        LinearLayout bar = Ui.column(this);
        bar.setBackgroundColor(Ui.BRAND_DARK);
        bar.setPadding(Ui.dp(this, 18), Ui.dp(this, 20), Ui.dp(this, 18), Ui.dp(this, 18));
        bar.addView(Ui.text(this, "Diagnostics", 20, Color.WHITE, true));
        root.addView(bar);

        LinearLayout actions = Ui.column(this);
        actions.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), 0);
        actions.addView(Ui.button(this, "Copy exemption command", false,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        copy(hardeningCommand());
                    }
                }), Ui.wide());
        actions.addView(Ui.spacer(this, 8));
        actions.addView(Ui.button(this, "Refresh", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refresh();
            }
        }), Ui.wide());
        root.addView(actions);

        TextView note = Ui.text(this,
                "Apps added on this screen are watched and revived, but battery "
                        + "exemptions can only be granted over adb - no app can set them "
                        + "for another package. Run the copied command to apply them.",
                12.5f, Ui.MUTED, false);
        note.setPadding(Ui.dp(this, 18), Ui.dp(this, 14), Ui.dp(this, 18), Ui.dp(this, 6));
        root.addView(note);

        dump = new TextView(this);
        dump.setTypeface(Typeface.MONOSPACE);
        dump.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        dump.setTextColor(Ui.TEXT);
        dump.setTextIsSelectable(true);
        dump.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 24));

        ScrollView scroller = new ScrollView(this);
        scroller.addView(dump, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroller, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        try {
            dump.setText(AliveService.statusText(this));
        } catch (Throwable t) {
            dump.setText("status unavailable: " + t);
        }
    }

    private String hardeningCommand() {
        String csv = Config.get(this).targetsCsv();
        if (csv.length() == 0) {
            return "./install.sh   # no apps chosen yet";
        }
        return "./install.sh --targets " + csv;
    }

    private void copy(String s) {
        try {
            ClipboardManager cm =
                    (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("AppAlive", s));
                Toast.makeText(this, "Copied:\n" + s, Toast.LENGTH_LONG).show();
                return;
            }
        } catch (Throwable ignored) {
        }
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
