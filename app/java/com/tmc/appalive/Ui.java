package com.tmc.appalive;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Minimal view-building helpers.
 *
 * The app deliberately has no AndroidX dependency (it keeps build.sh free of
 * Gradle and the APK under 100 KB), so there is no MaterialCardView and no
 * layout inflation. Everything here draws with explicit colours rather than
 * theme attributes, so the screens look the same across OEM skins.
 */
public final class Ui {

    private Ui() {
    }

    public static final int BG = 0xFFF2F4F6;
    public static final int SURFACE = 0xFFFFFFFF;
    public static final int BRAND_DARK = 0xFF10333F;
    public static final int BRAND = 0xFF34D399;
    public static final int TEXT = 0xFF14202A;
    public static final int MUTED = 0xFF5B6B76;
    public static final int LINE = 0xFFE3E7EB;
    public static final int DANGER = 0xFFC4453B;
    public static final int WARN = 0xFFA8710F;

    public static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()));
    }

    public static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static LinearLayout.LayoutParams wide() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public static LinearLayout.LayoutParams wide(Context c, int marginDp) {
        LinearLayout.LayoutParams p = wide();
        int m = dp(c, marginDp);
        p.setMargins(m, 0, m, 0);
        return p;
    }

    public static TextView text(Context c, CharSequence s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) {
            t.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return t;
    }

    public static TextView sectionHeader(Context c, String s) {
        TextView t = text(c, s.toUpperCase(java.util.Locale.US), 12, MUTED, true);
        t.setLetterSpacing(0.06f);
        t.setPadding(dp(c, 20), dp(c, 20), dp(c, 20), dp(c, 8));
        return t;
    }

    /** A white rounded panel. */
    public static LinearLayout card(Context c) {
        LinearLayout l = column(c);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(SURFACE);
        bg.setCornerRadius(dp(c, 14));
        l.setBackground(bg);
        return l;
    }

    public static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(LINE);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 0.5f)));
        p.setMargins(dp(c, 16), 0, 0, 0);
        v.setLayoutParams(p);
        return v;
    }

    public static View spacer(Context c, int heightDp) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(c, heightDp)));
        return v;
    }

    public static Button button(Context c, String label, boolean primary,
                                View.OnClickListener l) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(c, 10));
        if (primary) {
            bg.setColor(BRAND_DARK);
            b.setTextColor(Color.WHITE);
        } else {
            bg.setColor(SURFACE);
            bg.setStroke(Math.max(1, dp(c, 1)), LINE);
            b.setTextColor(BRAND_DARK);
        }
        b.setBackground(bg);
        b.setOnClickListener(l);
        return b;
    }

    /** Small coloured pill, used for per-target state. */
    public static TextView pill(Context c, String label, int color) {
        TextView t = text(c, label, 11, Color.WHITE, true);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(c, 8));
        t.setBackground(bg);
        t.setPadding(dp(c, 7), dp(c, 2), dp(c, 7), dp(c, 3));
        return t;
    }

    /** Row with a title, optional caption, and a control pinned to the right. */
    public static LinearLayout controlRow(Context c, String title, String caption,
                                          View control) {
        LinearLayout r = row(c);
        r.setPadding(dp(c, 16), dp(c, 12), dp(c, 12), dp(c, 12));

        LinearLayout labels = column(c);
        labels.addView(text(c, title, 15, TEXT, false));
        if (caption != null) {
            TextView cap = text(c, caption, 12.5f, MUTED, false);
            cap.setPadding(0, dp(c, 2), 0, 0);
            labels.addView(cap);
        }
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        r.addView(labels, lp);
        if (control != null) {
            r.addView(control);
        }
        return r;
    }
}
