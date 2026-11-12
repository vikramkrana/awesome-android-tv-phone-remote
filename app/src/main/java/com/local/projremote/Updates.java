package com.local.projremote;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.JSONObject;

/**
 * Optional, off until the owner switches it on: once a day, asks GitHub for the project's latest release and
 * remembers it when it is newer than this build. Only works when project_url (strings.xml) is a GitHub repository.
 * The request carries nothing about the device or its phones; GitHub sees the device's public IP address.
 */
final class Updates {
    private static final long EVERY_MS = 24L * 3_600_000L;

    private Updates() { }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("updates", Context.MODE_PRIVATE);
    }

    /** "owner/repo" from project_url, or null. */
    static String repo(Context c) {
        String url = c.getString(R.string.project_url).trim();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^https://github\\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\\.git)?/?$").matcher(url);
        return m.find() ? m.group(1) + "/" + m.group(2) : null;
    }

    static boolean possible(Context c) {
        return repo(c) != null;
    }

    static boolean enabled(Context c) {
        return possible(c) && prefs(c).getBoolean("on", false);
    }

    static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean("on", on).putLong("checked", 0).apply();
        if (on) checkSoon(c);
    }

    /** The newer version found ("1.2"), or null. */
    static String available(Context c) {
        if (!enabled(c)) return null;
        String v = prefs(c).getString("latest", null);
        return v != null && newer(v, version(c)) ? v : null;
    }

    static String page(Context c) {
        return prefs(c).getString("page", c.getString(R.string.project_url));
    }

    /** Checks in the background when the last check is more than a day old. */
    static void checkSoon(Context c) {
        if (!enabled(c)) return;
        Context app = c.getApplicationContext();
        if (System.currentTimeMillis() - prefs(app).getLong("checked", 0) < EVERY_MS) return;
        new Thread(() -> check(app), "update-check").start();
    }

    private static void check(Context c) {
        HttpURLConnection http = null;
        try {
            http = (HttpURLConnection) new URL("https://api.github.com/repos/" + repo(c) + "/releases/latest").openConnection();
            http.setConnectTimeout(10_000);
            http.setReadTimeout(10_000);
            http.setRequestProperty("Accept", "application/vnd.github+json");
            http.setRequestProperty("User-Agent", "phone-remote-update-check");
            if (http.getResponseCode() != 200) return;
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            try (InputStream in = http.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0 && body.size() < 1_000_000) body.write(buf, 0, n);
            }
            JSONObject release = new JSONObject(body.toString("UTF-8"));
            String tag = release.optString("tag_name", "").replaceFirst("^[vV]", "");
            String page = release.optString("html_url", "");
            if (!tag.matches("\\d+(\\.\\d+){0,3}")) return;
            if (!page.startsWith("https://github.com/")) page = c.getString(R.string.project_url);
            prefs(c).edit().putString("latest", tag).putString("page", page)
                    .putLong("checked", System.currentTimeMillis()).apply();
        } catch (Exception offline) {
            // try again at the next check
        } finally {
            if (http != null) http.disconnect();
        }
    }

    static String version(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception unknown) {
            return "0";
        }
    }

    /** Whether version a is later than b ("1.10" > "1.9"). */
    static boolean newer(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? parse(x[i]) : 0, q = i < y.length ? parse(y[i]) : 0;
            if (p != q) return p > q;
        }
        return false;
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s.replaceAll("\\D", ""));
        } catch (NumberFormatException bad) {
            return 0;
        }
    }
}
