package com.local.projremote;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONArray;
import org.json.JSONObject;

/** The device's launchable apps, for the phone's Apps tab: names, icons as small PNGs, and launching one. */
final class Apps {
    private final RemoteService context;
    /** Left out on top of the automatic rules, from config.xml. */
    private final Set<String> configured;
    private final Map<String, byte[]> icons = new ConcurrentHashMap<>();

    Apps(RemoteService context) {
        this.context = context;
        this.configured = new HashSet<>(java.util.Arrays.asList(
                context.getResources().getStringArray(R.array.hidden_packages)));
    }

    /**
     * Never offered: home screens and this remote (nothing to open from a phone), system settings (the phone has
     * its own button for them), and factory or calibration tools: opening one can flip a board into a factory mode.
     */
    private boolean hidden(String pkg) {
        return configured.contains(pkg) || pkg.equals(context.getPackageName())
                || pkg.equals("com.android.settings") || pkg.equals("com.android.tv.settings")
                || context.homes().contains(pkg) || looksLikeFactory(pkg);
    }

    /** [{"pkg","label"}], TV apps (leanback) and ordinary ones, each once, by name. */
    String listJson() {
        return listJson(null);
    }

    /** Only the packages in allowed, when it is not null (a kids phone). */
    String listJson(java.util.Set<String> allowed) {
        PackageManager pm = context.getPackageManager();
        Map<String, String> found = new java.util.TreeMap<>();
        for (String category : new String[] {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (found.containsKey(pkg) || hidden(pkg) || (allowed != null && !allowed.contains(pkg))) continue;
                found.put(pkg, String.valueOf(ri.loadLabel(pm)));
            }
        }
        List<Map.Entry<String, String>> sorted = new ArrayList<>(found.entrySet());
        sorted.sort((a, b) -> a.getValue().toLowerCase(Locale.ROOT).compareTo(b.getValue().toLowerCase(Locale.ROOT)));
        JSONArray out = new JSONArray();
        try {
            for (Map.Entry<String, String> e : sorted) out.put(new JSONObject().put("pkg", e.getKey()).put("label", e.getValue()));
        } catch (Exception ignored) { }
        return out.toString();
    }

    private static boolean looksLikeFactory(String pkg) {
        String p = pkg.toLowerCase(Locale.ROOT);
        return p.contains("factory") || p.contains("calibration") || p.contains("autotest") || p.contains(".fac.");
    }

    String label(String pkg) {
        try {
            PackageManager pm = context.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Exception missing) {
            return pkg;
        }
    }

    /** The app's icon as a 96 px PNG, cached; null for an unknown package. */
    byte[] icon(String pkg) {
        if (!pkg.matches("[A-Za-z0-9_.]{1,200}")) return null;
        byte[] cached = icons.get(pkg);
        if (cached != null) return cached;
        try {
            Drawable d = context.getPackageManager().getApplicationIcon(pkg);
            int size = 96;
            Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, size, size);
            d.draw(new Canvas(b));
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.PNG, 100, png);
            b.recycle();
            byte[] bytes = png.toByteArray();
            icons.put(pkg, bytes);
            return bytes;
        } catch (Exception missing) {
            return null;
        }
    }

    /** Opens the app on the projector: its TV entry if it has one, else its ordinary one. */
    boolean launch(String pkg) {
        if (!pkg.matches("[A-Za-z0-9_.]{1,200}") || hidden(pkg)) return false;
        PackageManager pm = context.getPackageManager();
        Intent intent = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (intent == null) intent = pm.getLaunchIntentForPackage(pkg);
        if (intent == null) return false;
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            context.startActivity(intent);
            return true;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    /** The device's own settings screen (picture, sound, network; on projectors usually keystone and focus too). */
    boolean openProjectorSettings() {
        Intent intent = new Intent(android.provider.Settings.ACTION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
            return true;
        } catch (RuntimeException failure) {
            return false;
        }
    }
}
