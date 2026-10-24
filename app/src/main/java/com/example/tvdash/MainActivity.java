package com.example.tvdash;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements RemoteServer.Host {

    private static final int PORT = 8080;

    private final List<AppStore.Entry> apps = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private GridView grid;
    private TextView clock, date, hint;
    private BaseAdapter adapter;
    private RemoteServer server;
    private boolean serverOk;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            Date now = new Date();
            clock.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(now));
            date.setText(new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now));
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        buildUi();

        server = new RemoteServer(getApplicationContext(), this);
        try {
            server.start(PORT);
            serverOk = true;
        } catch (IOException e) {
            serverOk = false;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        updateHint();
        ui.post(tick);
        grid.requestFocus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(tick);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (server != null) server.stop();
    }

    // ---- RemoteServer.Host (called from server threads) ----

    @Override
    public void onKey(final String key) {
        ui.post(new Runnable() {
            @Override public void run() {
                int code;
                switch (key) {
                    case "up": code = KeyEvent.KEYCODE_DPAD_UP; break;
                    case "down": code = KeyEvent.KEYCODE_DPAD_DOWN; break;
                    case "left": code = KeyEvent.KEYCODE_DPAD_LEFT; break;
                    case "right": code = KeyEvent.KEYCODE_DPAD_RIGHT; break;
                    case "ok": code = KeyEvent.KEYCODE_DPAD_CENTER; break;
                    default: return;
                }
                if (!grid.hasFocus()) grid.requestFocus();
                dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
                dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
            }
        });
    }

    @Override
    public void onAppsChanged() {
        ui.post(new Runnable() {
            @Override public void run() { refresh(); }
        });
    }

    // ---- UI ----

    private void refresh() {
        apps.clear();
        apps.addAll(AppStore.load(this, false));
        adapter.notifyDataSetChanged();
    }

    private void updateHint() {
        String ip = localIp();
        if (!serverOk) {
            hint.setText("Phone remote could not start (port " + PORT + " is busy).");
        } else if (ip == null) {
            hint.setText("Connect the TV to Wi-Fi/Ethernet to use the phone remote.");
        } else {
            hint.setText("Phone remote:  http://" + ip + ":" + PORT + "     PIN: " + AppStore.pin(this));
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0E1116);
        root.setPadding(dp(48), dp(32), dp(48), dp(24));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.BOTTOM);

        clock = new TextView(this);
        clock.setTextColor(Color.WHITE);
        clock.setTextSize(64);
        clock.setTypeface(Typeface.DEFAULT_BOLD);

        date = new TextView(this);
        date.setTextColor(0xFF9AA4B2);
        date.setTextSize(22);
        date.setPadding(dp(24), 0, 0, dp(14));

        header.addView(clock);
        header.addView(date);
        root.addView(header);

        grid = new GridView(this);
        grid.setNumColumns(5);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(dp(16));
        grid.setVerticalSpacing(dp(16));
        grid.setClipToPadding(false);
        grid.setPadding(dp(6), dp(24), dp(6), dp(6));
        grid.setSelector(focusDrawable());
        grid.setDrawSelectorOnTop(false);
        grid.setFocusable(true);
        grid.setVerticalScrollBarEnabled(false);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(grid, gp);

        adapter = new BaseAdapter() {
            @Override public int getCount() { return apps.size(); }
            @Override public Object getItem(int i) { return apps.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View v, ViewGroup parent) {
                AppStore.Entry e = apps.get(i);
                LinearLayout tile = new LinearLayout(MainActivity.this);
                tile.setOrientation(LinearLayout.VERTICAL);
                tile.setGravity(Gravity.CENTER);
                tile.setPadding(dp(12), dp(14), dp(12), dp(12));
                tile.setLayoutParams(new AbsListView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(150)));

                ImageView icon = new ImageView(MainActivity.this);
                icon.setImageDrawable(e.icon);
                icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                tile.addView(icon, new LinearLayout.LayoutParams(dp(84), dp(84)));

                TextView label = new TextView(MainActivity.this);
                label.setText(e.label);
                label.setTextColor(Color.WHITE);
                label.setTextSize(16);
                label.setGravity(Gravity.CENTER);
                label.setSingleLine(true);
                label.setPadding(0, dp(10), 0, 0);
                tile.addView(label);
                return tile;
            }
        };
        grid.setAdapter(adapter);
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                AppStore.launch(MainActivity.this, apps.get(pos).pkg);
            }
        });

        hint = new TextView(this);
        hint.setTextColor(0xFF9AA4B2);
        hint.setTextSize(18);
        hint.setPadding(0, dp(12), 0, 0);
        root.addView(hint);

        setContentView(root);
    }

    private GradientDrawable focusDrawable() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(0x33FFFFFF);
        d.setCornerRadius(dp(16));
        d.setStroke(dp(3), Color.WHITE);
        return d;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private static String localIp() {
        try {
            List<NetworkInterface> nis = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface ni : nis) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }
}
