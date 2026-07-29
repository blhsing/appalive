package com.tmc.appalive;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pick which apps to keep alive.
 *
 * Shows launchable apps by default; system packages are behind a toggle,
 * because keeping a background system service alive is a legitimate use but
 * not what most people are here for.
 *
 * Labels are loaded on a worker thread (a few hundred binder calls), icons
 * lazily per row with a small cache, so the list opens immediately.
 */
public final class AppPickerActivity extends Activity {

    private static final class Entry {
        String pkg;
        String label;
        boolean system;
        boolean launchable;
    }

    private final List<Entry> all = new ArrayList<Entry>();
    private final List<Entry> shown = new ArrayList<Entry>();
    private final Map<String, Drawable> iconCache = new HashMap<String, Drawable>();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private Adapter adapter;
    private TextView status;
    private boolean showSystem;
    private String query = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout rootView = Ui.column(this);
        rootView.setBackgroundColor(Ui.BG);
        rootView.addView(header());
        rootView.addView(controls());

        status = Ui.text(this, "Loading apps…", 14, Ui.MUTED, false);
        status.setPadding(Ui.dp(this, 18), Ui.dp(this, 18), Ui.dp(this, 18), Ui.dp(this, 8));
        rootView.addView(status);

        adapter = new Adapter();
        ListView list = new ListView(this);
        list.setAdapter(adapter);
        list.setDivider(null);
        list.setBackgroundColor(Ui.BG);
        rootView.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(rootView);
        load();
    }

    private View header() {
        LinearLayout bar = Ui.column(this);
        bar.setBackgroundColor(Ui.BRAND_DARK);
        bar.setPadding(Ui.dp(this, 18), Ui.dp(this, 20), Ui.dp(this, 18), Ui.dp(this, 18));
        bar.addView(Ui.text(this, "Choose apps", 20, Color.WHITE, true));
        bar.addView(Ui.text(this, "Tick the apps that keep getting killed", 13,
                0xFF9FB6BF, false));
        return bar;
    }

    private View controls() {
        LinearLayout box = Ui.column(this);
        box.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), 0);

        EditText search = new EditText(this);
        search.setHint("Search apps");
        search.setSingleLine(true);
        search.setTextSize(15);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Ui.SURFACE);
        bg.setCornerRadius(Ui.dp(this, 10));
        bg.setStroke(Math.max(1, Ui.dp(this, 1)), Ui.LINE);
        search.setBackground(bg);
        search.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                query = s.toString().trim().toLowerCase(Locale.US);
                refilter();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        box.addView(search, Ui.wide());

        Switch sys = new Switch(this);
        sys.setChecked(false);
        sys.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                showSystem = on;
                refilter();
            }
        });
        LinearLayout r = Ui.row(this);
        r.setPadding(Ui.dp(this, 2), Ui.dp(this, 6), 0, 0);
        r.addView(Ui.text(this, "Show system apps", 14, Ui.MUTED, false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(sys);
        box.addView(r, Ui.wide());
        return box;
    }

    // ------------------------------------------------------------- loading

    private void load() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<Entry> found = scan();
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        all.clear();
                        all.addAll(found);
                        refilter();
                    }
                });
            }
        }, "appalive-app-scan").start();
    }

    private List<Entry> scan() {
        List<Entry> out = new ArrayList<Entry>();
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> apps;
        try {
            apps = pm.getInstalledApplications(0);
        } catch (Throwable t) {
            return out;
        }
        String self = getPackageName();
        for (int i = 0; i < apps.size(); i++) {
            ApplicationInfo ai = apps.get(i);
            if (ai == null || ai.packageName == null || self.equals(ai.packageName)) {
                continue;
            }
            Entry e = new Entry();
            e.pkg = ai.packageName;
            e.system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            try {
                e.label = pm.getApplicationLabel(ai).toString();
            } catch (Throwable t) {
                e.label = ai.packageName;
            }
            try {
                e.launchable = pm.getLaunchIntentForPackage(ai.packageName) != null;
            } catch (Throwable t) {
                e.launchable = false;
            }
            out.add(e);
        }
        Collections.sort(out, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        return out;
    }

    private void refilter() {
        shown.clear();
        for (int i = 0; i < all.size(); i++) {
            Entry e = all.get(i);
            if (!showSystem && !e.launchable) {
                continue;
            }
            if (query.length() > 0
                    && !e.label.toLowerCase(Locale.US).contains(query)
                    && !e.pkg.toLowerCase(Locale.US).contains(query)) {
                continue;
            }
            shown.add(e);
        }
        int selected = Config.get(this).targets().size();
        status.setText(shown.size() + " app" + (shown.size() == 1 ? "" : "s")
                + "  ·  " + selected + " selected");
        adapter.notifyDataSetChanged();
    }

    // ------------------------------------------------------------- adapter

    private final class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int position) {
            return shown.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final Entry e = shown.get(position);
            LinearLayout r = Ui.row(AppPickerActivity.this);
            r.setBackgroundColor(Ui.SURFACE);
            r.setPadding(Ui.dp(AppPickerActivity.this, 14),
                    Ui.dp(AppPickerActivity.this, 10),
                    Ui.dp(AppPickerActivity.this, 10),
                    Ui.dp(AppPickerActivity.this, 10));

            ImageView icon = new ImageView(AppPickerActivity.this);
            icon.setImageDrawable(icon(e.pkg));
            int s = Ui.dp(AppPickerActivity.this, 36);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(s, s);
            ip.rightMargin = Ui.dp(AppPickerActivity.this, 12);
            r.addView(icon, ip);

            LinearLayout labels = Ui.column(AppPickerActivity.this);
            labels.addView(Ui.text(AppPickerActivity.this, e.label, 15, Ui.TEXT, false));
            labels.addView(Ui.text(AppPickerActivity.this,
                    e.pkg + (e.system ? "  ·  system" : ""), 11.5f, Ui.MUTED, false));
            r.addView(labels, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            final CheckBox cb = new CheckBox(AppPickerActivity.this);
            cb.setChecked(Config.get(AppPickerActivity.this).hasTarget(e.pkg));
            cb.setClickable(false);
            cb.setFocusable(false);
            r.addView(cb);

            r.setClickable(true);
            r.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Config cfg = Config.get(AppPickerActivity.this);
                    if (cfg.hasTarget(e.pkg)) {
                        cfg.removeTarget(e.pkg);
                    } else {
                        cfg.addTarget(e.pkg);
                    }
                    AliveService.notifyConfigChanged(AppPickerActivity.this);
                    refilter();
                }
            });

            LinearLayout wrap = Ui.column(AppPickerActivity.this);
            wrap.addView(r, Ui.wide());
            View line = new View(AppPickerActivity.this);
            line.setBackgroundColor(Ui.LINE);
            wrap.addView(line, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Math.max(1, Ui.dp(AppPickerActivity.this, 0.5f))));
            wrap.setGravity(Gravity.CENTER_VERTICAL);
            return wrap;
        }
    }

    private Drawable icon(String pkg) {
        Drawable d = iconCache.get(pkg);
        if (d == null) {
            try {
                d = getPackageManager().getApplicationIcon(pkg);
            } catch (Throwable t) {
                d = getResources().getDrawable(R.mipmap.ic_launcher);
            }
            iconCache.put(pkg, d);
        }
        return d;
    }
}
