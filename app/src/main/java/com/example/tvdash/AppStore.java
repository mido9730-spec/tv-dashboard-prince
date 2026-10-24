package com.example.tvdash;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Finds installed apps, remembers which ones are hidden, launches them, and keeps the phone PIN. */
final class AppStore {

    static final class Entry {
        String label;
        String pkg;
        Drawable icon;
        boolean hidden;
    }

    private AppStore() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("tvdash", Context.MODE_PRIVATE);
    }

    /** 4-digit PIN shown on the TV; the phone must send it with every request. */
    static String pin(Context c) {
        SharedPreferences p = prefs(c);
        String s = p.getString("pin", null);
        if (s == null) {
            s = String.format(Locale.US, "%04d", new SecureRandom().nextInt(10000));
            p.edit().putString("pin", s).apply();
        }
        return s;
    }

    private static Set<String> hiddenSet(Context c) {
        return new HashSet<>(prefs(c).getStringSet("hidden", new HashSet<String>()));
    }

    static void setHidden(Context c, String pkg, boolean hide) {
        Set<String> h = hiddenSet(c);
        if (hide) h.add(pkg); else h.remove(pkg);
        prefs(c).edit().putStringSet("hidden", h).apply();
    }

    /** All launchable apps (TV apps first found, then normal apps), sorted by name. */
    static List<Entry> load(Context c, boolean includeHidden) {
        PackageManager pm = c.getPackageManager();
        Set<String> hidden = hiddenSet(c);
        Map<String, Entry> map = new LinkedHashMap<>();
        String[] categories = {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER};
        for (String cat : categories) {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(cat);
            for (ResolveInfo ri : pm.queryIntentActivities(i, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(c.getPackageName()) || map.containsKey(pkg)) continue;
                Entry e = new Entry();
                e.pkg = pkg;
                e.label = String.valueOf(ri.loadLabel(pm));
                e.hidden = hidden.contains(pkg);
                if (e.hidden && !includeHidden) continue;
                e.icon = ri.loadIcon(pm);
                map.put(pkg, e);
            }
        }
        List<Entry> list = new ArrayList<>(map.values());
        Collections.sort(list, new Comparator<Entry>() {
            @Override public int compare(Entry a, Entry b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        return list;
    }

    static boolean launch(Context c, String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (i == null) i = pm.getLaunchIntentForPackage(pkg);
            if (i == null) return false;
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
