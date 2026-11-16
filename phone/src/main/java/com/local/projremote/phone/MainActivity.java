package com.local.projremote.phone;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * The phone app: one full-screen WebView showing the remote page of the projector or TV it last used. It finds
 * devices on the Wi-Fi itself, so there is no address to type and no QR to scan after the first time, and it
 * says so plainly when the device is off instead of showing a blank page.
 */
public class MainActivity extends Activity implements Finder.Listener {
    private static final int SPEAK = 1;
    private static final long PROBE_MS = 2500, PICK_AFTER_MS = 2500, GIVE_UP_MS = 9000;
    private static final int BG = 0xFF141416, TEXT = 0xFFF4F2F8, DIM = 0xFF9A98A3, CARD = 0xFF222226, ACCENT = 0xFF5B8CFF;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Finder.Device> found = new ArrayList<>();
    private SharedPreferences prefs;
    private Finder finder;
    private WebView web;
    private LinearLayout overlay, list;
    private TextView title, subtitle;
    private ProgressBar spinner;
    private Button retry;
    private TextView scan;
    private boolean loaded;
    private String pendingShare;

    private final Runnable pick = this::pickOrList;
    private final Runnable giveUp = this::giveUp;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("app", MODE_PRIVATE);
        finder = new Finder(this, this);
        buildUi();
        pendingShare = sharedText(getIntent());
        if (state != null) web.restoreState(state);
        if (web.getUrl() != null) { loaded = true; hideOverlay(); } else connect();
    }

    // ---- finding the device -----------------------------------------------------------------------------------------

    /** Opens the device used last if it answers; otherwise looks for it (or any) on the Wi-Fi. */
    private void connect() {
        loaded = false;
        found.clear();
        main.removeCallbacks(pick);
        main.removeCallbacks(giveUp);
        String name = prefs.getString("name", null);
        showOverlay(name == null ? "Looking for your projector or TV" : "Connecting to " + name,
                "Phone and device on the same Wi-Fi", true);
        finder.start();
        main.postDelayed(giveUp, GIVE_UP_MS);
        String last = prefs.getString("url", null);
        if (last != null) {
            new Thread(() -> {
                boolean up = answers(last);
                main.post(() -> { if (up && !loaded) open(last); });
            }).start();
        }
    }

    private static boolean answers(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url + "api/hello").openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout((int) PROBE_MS);
            c.setReadTimeout((int) PROBE_MS);
            c.setDoOutput(true);
            c.getOutputStream().close();
            return c.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    @Override
    public void onFound(Finder.Device d) {
        for (int i = 0; i < found.size(); i++) {
            Finder.Device f = found.get(i);
            if (f.url.equals(d.url) || (d.id != null && d.id.equals(f.id))) { found.set(i, d); return; }
        }
        found.add(d);
        if (loaded) return;
        String lastId = prefs.getString("id", null), lastName = prefs.getString("name", null);
        // The device used last, at whatever address it has now.
        if ((lastId != null && lastId.equals(d.id)) || (lastId == null && lastName != null && lastName.equals(d.name))) {
            open(d);
            return;
        }
        if (overlay.getVisibility() == View.VISIBLE && list.getChildCount() > 0) drawList();
        else if (found.size() == 1) main.postDelayed(pick, PICK_AFTER_MS);
    }

    /** First run (or the last device is gone): one device opens straight away, several are listed. */
    private void pickOrList() {
        if (loaded) return;
        if (found.size() == 1 && prefs.getString("url", null) == null) open(found.get(0));
        else if (!found.isEmpty()) showDevices();
    }

    private void giveUp() {
        if (loaded) return;
        if (!found.isEmpty()) { showDevices(); return; }
        String name = prefs.getString("name", null);
        showOverlay(name == null ? "No projector or TV found" : name + " is not answering",
                "Is it switched on, with Phone Remote running?\nIs this phone on the same Wi-Fi?", false);
        retry.setVisibility(View.VISIBLE);
    }

    void showDevices() {
        main.removeCallbacks(pick);
        showOverlay(found.isEmpty() ? "Looking for devices" : "Choose a device", "On this Wi-Fi", found.isEmpty());
        drawList();
        retry.setVisibility(View.VISIBLE);
    }

    private void drawList() {
        list.removeAllViews();
        for (Finder.Device d : found) {
            TextView row = new TextView(this);
            row.setText(d.name + "\n" + d.url.replaceAll("^http://|/$", ""));
            row.setTextColor(TEXT);
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            row.setPadding(dp(18), dp(14), dp(18), dp(14));
            row.setBackground(round(CARD, 16));
            row.setOnClickListener(v -> open(d));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(10);
            list.addView(row, lp);
        }
    }

    private void open(Finder.Device d) {
        SharedPreferences.Editor e = prefs.edit().putString("name", d.name);
        if (d.id != null) e.putString("id", d.id);
        e.apply();
        open(d.url);
    }

    private void open(String url) {
        loaded = true;
        main.removeCallbacks(pick);
        main.removeCallbacks(giveUp);
        String target = url;
        if (pendingShare != null) {
            target = url + "?text=" + encode(pendingShare);
            pendingShare = null;
        }
        web.loadUrl(target);
    }

    /** Called by the page once it is up: this is the device to open next time. */
    void remember(String id, String name, String origin) {
        if (origin == null || !origin.startsWith("http://")) return;
        prefs.edit().putString("url", origin.endsWith("/") ? origin : origin + "/")
                .putString("id", id == null || id.isEmpty() ? null : id).putString("name", name).apply();
        hideOverlay();
        finder.stop();
    }

    // ---- QR -----------------------------------------------------------------------------------------------------------

    /** The QR on the big screen (Home card or the Phone Remote screen) holds the page's address. */
    private void scanQr() {
        com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions options =
                new com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder()
                        .setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE).build();
        com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(this, options).startScan()
                .addOnSuccessListener(code -> useCode(code.getRawValue()))
                .addOnFailureListener(e -> toast("The QR scanner is not available on this phone"));
    }

    private void useCode(String value) {
        Uri u = value == null ? null : Uri.parse(value.trim());
        if (u == null || !"http".equals(u.getScheme()) || !onThisNetwork(u.getHost())) {
            toast("That is not a Phone Remote code");
            return;
        }
        // Another device than the one used last: forget its id so the page sets the new one.
        prefs.edit().remove("id").remove("name").putString("url", u.getScheme() + "://" + u.getAuthority() + "/").apply();
        finder.stop();
        showOverlay("Connecting", u.getAuthority(), true);
        open(value.trim());
    }

    private void toast(String message) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show();
    }

    // ---- voice ------------------------------------------------------------------------------------------------------

    boolean canListen() {
        return new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).resolveActivity(getPackageManager()) != null;
    }

    void listen() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak");
        try {
            startActivityForResult(i, SPEAK);
        } catch (ActivityNotFoundException none) {
            web.evaluateJavascript("window.appHeard && appHeard(null)", null);
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != SPEAK) return;
        ArrayList<String> words = result == RESULT_OK && data != null
                ? data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS) : null;
        String heard = words == null || words.isEmpty() ? null : words.get(0);
        web.evaluateJavascript("window.appHeard && appHeard(" + (heard == null ? "null" : JSONObject.quote(heard)) + ")", null);
    }

    // ---- sharing, back, lifecycle ------------------------------------------------------------------------------------

    private static String sharedText(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return null;
        String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        return text == null || text.trim().isEmpty() ? null : text.trim();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String shared = sharedText(intent);
        if (shared == null) return;
        String last = prefs.getString("url", null);
        if (loaded && last != null) {
            web.loadUrl(last + "?text=" + encode(shared));
        } else {
            pendingShare = shared;
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (!loaded || overlay.getVisibility() == View.VISIBLE) { moveTaskToBack(true); return; }
        // The page closes what is open (a sheet, the full-screen picture, another tab) before the app goes away.
        web.evaluateJavascript("window.appBack ? appBack() : false", v -> {
            if (!"true".equals(v)) moveTaskToBack(true);
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onDestroy() {
        finder.stop();
        main.removeCallbacksAndMessages(null);
        web.destroy();
        super.onDestroy();
    }

    // ---- views ------------------------------------------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);

        web = new WebView(this);
        web.setBackgroundColor(BG);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUserAgentString(s.getUserAgentString() + " PhoneRemoteApp/1");
        web.addJavascriptInterface(new Bridge(this, getSharedPreferences("store", MODE_PRIVATE)), "PhoneRemoteApp");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if ("http".equals(u.getScheme()) && onThisNetwork(u.getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (ActivityNotFoundException ignored) { }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                // Up. (A device with an older remote page never calls here(): remember it from its address.)
                if (url == null || !url.startsWith("http")) return;
                Uri u = Uri.parse(url);
                if (prefs.getString("url", null) == null || overlay.getVisibility() == View.VISIBLE) {
                    String origin = u.getScheme() + "://" + u.getAuthority() + "/";
                    if (!origin.equals(prefs.getString("url", null))) prefs.edit().putString("url", origin).apply();
                }
                hideOverlay();
                finder.stop();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (!request.isForMainFrame()) return;
                // The device went away (switched off, new address): find it again.
                view.loadUrl("about:blank");
                connect();
            }
        });
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL);
        overlay.setPadding(dp(28), dp(28), dp(28), dp(28));
        overlay.setBackgroundColor(BG);
        overlay.setClickable(true);

        ImageView mark = new ImageView(this);
        mark.setImageResource(R.drawable.ic_mark);
        mark.setBackground(round(CARD, 28));
        mark.setPadding(dp(14), dp(14), dp(14), dp(14));
        overlay.addView(mark, new LinearLayout.LayoutParams(dp(88), dp(88)));

        title = text(22, TEXT, Typeface.BOLD);
        title.setPadding(0, dp(22), 0, dp(6));
        overlay.addView(title);
        subtitle = text(15, DIM, Typeface.NORMAL);
        overlay.addView(subtitle);

        spinner = new ProgressBar(this);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(30), dp(30));
        sp.topMargin = dp(22);
        overlay.addView(spinner, sp);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        overlay.addView(list, lp);

        retry = new Button(this);
        retry.setText("Try again");
        retry.setAllCaps(false);
        retry.setTextColor(Color.WHITE);
        retry.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        retry.setBackground(round(ACCENT, 24));
        retry.setOnClickListener(v -> connect());
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(dp(180), dp(48));
        rp.topMargin = dp(24);
        overlay.addView(retry, rp);

        scan = text(16, ACCENT, Typeface.BOLD);
        scan.setText("Scan the QR code");
        scan.setPadding(dp(18), dp(14), dp(18), dp(14));
        scan.setOnClickListener(v -> scanQr());
        LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        qp.topMargin = dp(8);
        overlay.addView(scan, qp);

        root.addView(overlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
    }

    private void showOverlay(String head, String sub, boolean busy) {
        title.setText(head);
        subtitle.setText(sub);
        spinner.setVisibility(busy ? View.VISIBLE : View.GONE);
        retry.setVisibility(View.GONE);
        list.removeAllViews();
        overlay.setVisibility(View.VISIBLE);
    }

    private void hideOverlay() {
        overlay.setVisibility(View.GONE);
    }

    /** Private (home network) addresses and .local names: the remote's devices. Anything else opens in the browser. */
    private static boolean onThisNetwork(String host) {
        if (host == null) return false;
        if (host.endsWith(".local")) return true;
        if (host.startsWith("10.") || host.startsWith("192.168.")) return true;
        if (host.startsWith("172.")) {
            String[] p = host.split("\\.");
            try {
                int b = Integer.parseInt(p[1]);
                return b >= 16 && b <= 31;
            } catch (RuntimeException e) {
                return false;
            }
        }
        return false;
    }

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException impossible) {
            return "";
        }
    }

    private TextView text(int sp, int color, int style) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create("sans-serif", style));
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        return t;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
