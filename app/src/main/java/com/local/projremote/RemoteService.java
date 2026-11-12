package com.local.projremote;

import android.accessibilityservice.AccessibilityService;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * The projector side of the phone remote. Android 13 lets an accessibility service press the D-pad and OK
 * (GLOBAL_ACTION_DPAD_*), Back and Home; volume and play/pause go through AudioManager, typed text into the
 * focused text box. It also runs the web server the phone talks to, answers "projector.local", and shows a
 * pairing QR card on Home until a phone connects. Window events are used only for the package name on screen
 * (to know when Home is showing); no screen content is read or sent.
 */
public class RemoteService extends AccessibilityService {
    private static final String TAG = "ProjRemote";
    static volatile RemoteService instance;
    /** Home screens (every app that can be the HOME app), found on the device: the QR card shows only there. */
    private volatile Set<String> homes = Collections.emptySet();
    private volatile long homesReadAt;
    /** The screensaver is showing: it often belongs to the launcher's package, and is no place for the QR card. */
    private volatile boolean dreaming;
    private final android.content.BroadcastReceiver dreams = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            dreaming = Intent.ACTION_DREAMING_STARTED.equals(intent.getAction());
            main.post(RemoteService.this::updateQrCard);
        }
    };

    private final Handler main = new Handler(Looper.getMainLooper());
    private WebServer server;
    private MdnsResponder mdns;
    private Peers peers;
    /** When the sleep timer goes off (elapsedRealtime), 0 when none is set. */
    private volatile long sleepAt;
    private final Runnable sleepNow = () -> {
        sleepAt = 0;
        key("sleep");
    };
    /** Each phone's last request, for "iPhone connected" on screen after a while away. */
    private final java.util.Map<String, Long> lastRequest = new java.util.concurrent.ConcurrentHashMap<>();
    private byte[] lastShot;
    private long lastShotAt;
    private int lastShotWidth;
    private Pairing pairing;
    private View pinView;
    private View qrCard;
    private volatile String shownPackage = "";
    private Apps apps;
    /** Phones that have used the remote since the projector started. */
    private final Set<String> seenThisBoot = Collections.synchronizedSet(new HashSet<>());
    private final Runnable hidePinLater = this::hidePin;
    private final Runnable retryQr = this::updateQrCard;
    private final Runnable refreshCard = this::updateQrCard;
    /** What the card on screen shows, to rebuild it only when that changes. */
    private String cardState = "";

    @Override
    protected void onServiceConnected() {
        instance = this;
        pairing = Pairing.get(this);
        apps = new Apps(this);
        server = new WebServer(this, pairing, getResources().getInteger(R.integer.port));
        server.start();
        mdns = new MdnsResponder(this, localName(this));
        mdns.start();
        android.content.IntentFilter filter = new android.content.IntentFilter(Intent.ACTION_DREAMING_STARTED);
        filter.addAction(Intent.ACTION_DREAMING_STOPPED);
        registerReceiver(dreams, filter);
        main.post(syncShown);
    }

    /** The web server bound its port: the QR can now carry the address. */
    void onServerReady() {
        Log.i(TAG, "remote ready on port " + server.port());
        main.post(this::updateQrCard);
        main.post(this::announce);
        Updates.checkSoon(this);
    }

    /** Tells other devices with this remote on the network about this one (and finds them). */
    private void announce() {
        if (instance != this || server == null || server.port() <= 0) return;
        if (peers == null) peers = new Peers(this);
        peers.start(deviceName(), server.port());
    }

    int serverPort() {
        return server == null ? -1 : server.port();
    }

    int peerCount() {
        java.net.Inet4Address ip = NetUtil.ipv4(this);
        return peers == null ? 0 : peers.json(ip == null ? null : ip.getHostAddress()).length();
    }

    /** The page's address as the QR carries it; null while there is no network or the server is not up. */
    static String url(Context context) {
        RemoteService s = instance;
        return s == null || s.server == null ? null : Qr.url(context, s.server.port());
    }

    /**
     * Every package that can be the HOME app, plus any added in config.xml. Settings apps also declare HOME
     * (Android's "fallback home" shown before first unlock): those are not home screens.
     */
    Set<String> homes() {
        long now = SystemClock.uptimeMillis();
        if (!homes.isEmpty() && now - homesReadAt < 60_000) return homes;
        Set<String> found = new HashSet<>(java.util.Arrays.asList(getResources().getStringArray(R.array.extra_home_packages)));
        try {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            for (android.content.pm.ResolveInfo ri : getPackageManager().queryIntentActivities(home, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (!pkg.contains("settings") && !pkg.contains("setup") && !pkg.equals(getPackageName())) found.add(pkg);
            }
        } catch (RuntimeException ignored) { }
        homes = found;
        homesReadAt = now;
        return found;
    }

    // ---- what this device is called -----------------------------------------------------------------------------

    /** "projector" or "TV": the word the phone's texts use. From config.xml until changed on the app's own screen. */
    String kind() {
        return kind(this);
    }

    static String kind(Context context) {
        String k = context.getSharedPreferences("device", MODE_PRIVATE)
                .getString("kind", context.getString(R.string.default_kind));
        return "tv".equalsIgnoreCase(k) ? "TV" : "projector";
    }

    static void setKind(Context context, String kind) {
        context.getSharedPreferences("device", MODE_PRIVATE).edit().putString("kind", kind).apply();
        RemoteService s = instance;
        if (s != null && s.server != null) s.server.forgetPage();
    }

    /** Where the Home QR card sits: 0 top right, 1 bottom right, 2 bottom left, 3 top left, 4 off. */
    static int cardCorner(Context context) {
        return context.getSharedPreferences("device", MODE_PRIVATE).getInt("corner", 0);
    }

    static void setCardCorner(Context context, int corner) {
        context.getSharedPreferences("device", MODE_PRIVATE).edit().putInt("corner", corner).apply();
        RemoteService s = instance;
        if (s != null) s.refreshQrCard();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getPackageName() == null) return;
        String pkg = event.getPackageName().toString();
        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_VIEW_FOCUSED || type == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            // Home scrolled or moved focus: its rows may now run under the card. Check once the scroll settles.
            if (homes().contains(pkg) && qrCard != null) {
                main.removeCallbacks(checkCovered);
                main.postDelayed(checkCovered, 180);
                main.postDelayed(checkCovered, 520);
            }
            return;
        }
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        // Our own overlays (the card, the PIN), the volume bar and toasts are not "a new screen"; our Phone Remote
        // screen is one.
        CharSequence cls = event.getClassName();
        // The screensaver runs as an activity of the app that provides it, often the launcher itself.
        if (cls != null && cls.toString().endsWith("DreamActivity")) {
            dreaming = true;
            updateQrCard();
            return;
        }
        boolean ownScreen = pkg.equals(getPackageName()) && cls != null && cls.toString().endsWith("Activity");
        if ((pkg.equals(getPackageName()) && !ownScreen) || pkg.equals("com.android.systemui") || pkg.equals("android")) return;
        onPackageShown(pkg);
    }

    /**
     * The card floats above Home in its own window, so the launcher's rows scroll underneath it. Fade it out while
     * any tile or button of Home lies under it (the dock after scrolling down to the grid), back in when clear.
     */
    private final Runnable checkCovered = this::checkCovered;
    private final android.graphics.Rect cardRect = new android.graphics.Rect();
    private final android.graphics.Rect nodeRect = new android.graphics.Rect();

    private void checkCovered() {
        if (qrCard == null || qrCard.getWidth() == 0) return;
        int[] at = new int[2];
        qrCard.getLocationOnScreen(at);
        cardRect.set(at[0], at[1], at[0] + qrCard.getWidth(), at[1] + qrCard.getHeight());
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null || !homes().contains(root.getPackageName().toString())) return;
        boolean covered = focusableUnder(root, 0);
        float target = covered ? 0f : 1f;
        if (qrCard.getAlpha() != target) qrCard.animate().alpha(target).setDuration(covered ? 120 : 220).start();
        setCardTouchable(!covered);
    }

    /**
     * The card opens this app's screen when clicked with a pointer (a projector remote's mouse mode, a touchscreen,
     * an air mouse). Only while it is visible: a faded-out card must not swallow clicks meant for the tile under it.
     * It never takes D-pad focus; that would steal the arrow keys from the launcher.
     */
    private void setCardTouchable(boolean touchable) {
        if (qrCard == null || !(qrCard.getLayoutParams() instanceof WindowManager.LayoutParams)) return;
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) qrCard.getLayoutParams();
        int flags = touchable ? lp.flags & ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                : lp.flags | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        if (flags == lp.flags) return;
        lp.flags = flags;
        try {
            wm().updateViewLayout(qrCard, lp);
        } catch (RuntimeException gone) {
            // removed meanwhile
        }
    }

    private void openOwnScreen() {
        try {
            startActivity(new Intent(this, InfoActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
        } catch (RuntimeException failure) {
            Log.w(TAG, "cannot open own screen: " + failure);
        }
    }

    private boolean focusableUnder(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 30) return false;
        if (node.isFocusable() && node.isVisibleToUser()) {
            node.getBoundsInScreen(nodeRect);
            // Only tiles and buttons: the scrolling list that holds them is focusable too and spans the screen.
            android.util.DisplayMetrics screen = getResources().getDisplayMetrics();
            if (nodeRect.width() > screen.widthPixels / 3 || nodeRect.height() > screen.heightPixels / 3) {
                return childrenUnder(node, depth);
            }
            // A few pixels of slack: a tile's focus glow and scale-up reach a little past its bounds.
            nodeRect.inset(-8, -8);
            if (android.graphics.Rect.intersects(nodeRect, cardRect)) return true;
        }
        return childrenUnder(node, depth);
    }

    private boolean childrenUnder(AccessibilityNodeInfo node, int depth) {
        for (int i = 0; i < node.getChildCount(); i++) {
            if (focusableUnder(node.getChild(i), depth + 1)) return true;
        }
        return false;
    }

    private void onPackageShown(String pkg) {
        shownPackage = pkg;
        updateQrCard();
    }

    /*
     * Which app is in front comes from window events. When the service starts while Home is already showing (after
     * power-on, or when it is switched back on), no event comes until the screen changes, and right after start the
     * window often cannot be read yet: the card would only appear after leaving Home and coming back. So the front
     * window is also read directly: every second for the first half minute, then every 5 seconds.
     */
    private long connectedAt;
    private final Runnable syncShown = new Runnable() {
        @Override public void run() {
            if (instance != RemoteService.this) return;
            if (connectedAt == 0) connectedAt = SystemClock.uptimeMillis();
            String pkg = frontPackage();
            if (pkg != null && !pkg.equals(shownPackage)) onPackageShown(pkg);
            else if (pkg != null && qrCard == null && homes().contains(pkg)) updateQrCard();
            boolean early = SystemClock.uptimeMillis() - connectedAt < 30_000L;
            main.postDelayed(this, early ? 1_000L : 5_000L);
        }
    };

    /**
     * A home screen always has something to select; a screensaver has nothing. Catches a screensaver that was
     * already running when the service started, which it was never told about.
     */
    private boolean looksLikeScreensaver() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            return root != null && root.getPackageName() != null && homes().contains(root.getPackageName().toString())
                    && !root.isFocusable() && firstFocusable(root, 0) == null;
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

    /** The package of the window in front, or null when it cannot be read or is the system UI or our overlays. */
    private String frontPackage() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null || root.getPackageName() == null) return null;
            String pkg = root.getPackageName().toString();
            if (pkg.equals("com.android.systemui") || pkg.equals("android")) return null;
            return pkg;
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    @Override
    public void onInterrupt() { }

    @Override
    public void onDestroy() {
        instance = null;
        if (server != null) server.stop();
        if (mdns != null) mdns.stop();
        if (peers != null) peers.stop();
        sleepAt = 0;
        try { unregisterReceiver(dreams); } catch (RuntimeException notRegistered) { }
        // Nothing of this instance may run after it is switched off (a queued card refresh would fail).
        main.removeCallbacksAndMessages(null);
        // Switched off within minutes of power-on: that is not a person in Settings, it is firmware rewriting the
        // list of accessibility services at boot (and such firmware may also block the boot broadcast SelfEnable
        // listens for). If the owner allowed it, switch back on; a few tries, the rewrite can repeat.
        if (SystemClock.elapsedRealtime() < 5 * 60_000L && SelfEnable.allowed(this)) {
            Context app = getApplicationContext();
            for (long delay : new long[] {1_500L, 8_000L, 30_000L}) main.postDelayed(() -> SelfEnable.ensure(app), delay);
        }
        main.post(() -> {
            hidePin();
            removeQrCard();
        });
        super.onDestroy();
    }

    // ---- phones -------------------------------------------------------------------------------------------------

    /** Called from the web server after a successful pairing: the card shows the new phone and a fresh QR code. */
    void onPaired(String name) {
        main.post(() -> {
            hidePin();
            updateQrCard();
            notice(name + " is paired");
        });
    }

    /** A short note at the bottom of the big screen, when the owner wants them (on by default). */
    private void notice(String text) {
        if (!announceConnections(this)) return;
        try {
            android.widget.Toast.makeText(this, "📱  " + text, android.widget.Toast.LENGTH_SHORT).show();
        } catch (RuntimeException ignored) { }
    }

    static boolean announceConnections(Context context) {
        return context.getSharedPreferences("device", MODE_PRIVATE).getBoolean("announce", true);
    }

    static void setAnnounceConnections(Context context, boolean on) {
        context.getSharedPreferences("device", MODE_PRIVATE).edit().putBoolean("announce", on).apply();
    }

    /** A phone was removed from the phone itself: refresh the Home card's list. */
    void onPhonesChanged() {
        main.post(this::updateQrCard);
    }

    /** Called on every request from a paired phone: its chip turns green the first time it is seen. */
    void onPhoneSeen(Pairing.Phone phone) {
        long now = SystemClock.elapsedRealtime();
        Long before = lastRequest.put(phone.id, now);
        // Back after ten minutes or more (or the first time since the remote started): say so on screen.
        if (before == null || now - before > 10 * 60_000L) main.post(() -> notice(phone.name + " connected"));
        if (seenThisBoot.add(phone.id) || !cardState.contains(";" + phone.name + "+;")) main.post(this::updateQrCard);
    }

    // ---- state for the phone -------------------------------------------------------------------------------------

    Apps apps() {
        return apps;
    }

    /** The name the phones show for this device: "Projector" or "TV" until renamed (firmware names are often fake). */
    String deviceName() {
        return deviceName(this);
    }

    static String deviceName(Context context) {
        String kind = kind(context);
        return context.getSharedPreferences("device", MODE_PRIVATE)
                .getString("name", Character.toUpperCase(kind.charAt(0)) + kind.substring(1));
    }

    boolean rename(String name) {
        return rename(this, name);
    }

    /** Renames the device for the phones (and its .local name); works from the app's screen while the remote is off. */
    static boolean rename(Context context, String name) {
        String n = name == null ? "" : name.replaceAll("[\\p{Cntrl}<>\"&]", "").trim();
        if (n.isEmpty() || n.length() > 40) return false;
        context.getSharedPreferences("device", MODE_PRIVATE).edit().putString("name", n).apply();
        RemoteService live = instance;
        if (live != null && live.mdns != null) live.mdns.setName(localName(context));
        if (live != null) live.main.post(live::announce);
        return true;
    }

    /** Off: no new phone can pair (already paired phones keep working). For when the network is not only yours. */
    static boolean pairingAllowed(Context context) {
        return context.getSharedPreferences("device", MODE_PRIVATE).getBoolean("allow_pairing", true);
    }

    static void setPairingAllowed(Context context, boolean allowed) {
        context.getSharedPreferences("device", MODE_PRIVATE).edit().putBoolean("allow_pairing", allowed).apply();
    }

    /**
     * This device's Bonjour name, e.g. "living-room-3f2a.local": its name made web-safe and a short random part
     * of its own, so two devices in one home never answer to the same name. config.xml mdns_name overrides it.
     */
    // ---- screen preview, links, timer, volume ---------------------------------------------------------------------

    /** The owner's switch (off by default): paired phones that are not guests may see a small picture of the screen. */
    static boolean previewAllowed(Context context) {
        return context.getSharedPreferences("device", MODE_PRIVATE).getBoolean("preview", false);
    }

    static void setPreviewAllowed(Context context, boolean on) {
        context.getSharedPreferences("device", MODE_PRIVATE).edit().putBoolean("preview", on).apply();
    }

    /**
     * A JPEG of what is on screen, at most 640 px wide (Android 11+, and only while the owner allows it). Apps that
     * protect their picture (most paid video) come out black. At most one new picture per second for all phones.
     */
    byte[] screenshot(int width) {
        if (android.os.Build.VERSION.SDK_INT < 30 || !previewAllowed(this)) return null;
        final int w = Math.max(320, Math.min(1280, width));
        synchronized (this) {
            // Android allows an accessibility screenshot about every third of a second; phones share them.
            if (lastShot != null && lastShotWidth >= w && SystemClock.elapsedRealtime() - lastShotAt < 340) return lastShot;
        }
        final byte[][] result = new byte[1][];
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        try {
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
                @Override public void onSuccess(ScreenshotResult shot) {
                    try {
                        Bitmap hw = Bitmap.wrapHardwareBuffer(shot.getHardwareBuffer(), shot.getColorSpace());
                        shot.getHardwareBuffer().close();
                        if (hw != null) {
                            float scale = Math.min(1f, (float) w / hw.getWidth());
                            Bitmap small = Bitmap.createScaledBitmap(hw.copy(Bitmap.Config.ARGB_8888, false),
                                    Math.round(hw.getWidth() * scale), Math.round(hw.getHeight() * scale), true);
                            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                            small.compress(Bitmap.CompressFormat.JPEG, w > 700 ? 70 : 60, out);
                            result[0] = out.toByteArray();
                        }
                    } catch (RuntimeException broken) {
                        Log.w(TAG, "screenshot: " + broken);
                    }
                    done.countDown();
                }
                @Override public void onFailure(int error) {
                    done.countDown();
                }
            });
            done.await(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception failure) {
            return lastShot;
        }
        synchronized (this) {
            if (result[0] != null) {
                lastShot = result[0];
                lastShotWidth = w;
                lastShotAt = SystemClock.elapsedRealtime();
            }
            return result[0] != null ? result[0] : lastShot;
        }
    }

    /**
     * Opens a web link sent from a phone: YouTube links in the YouTube app, others in whatever app handles them
     * (Netflix, Prime Video, a browser). Only http and https.
     */
    boolean openLink(String link) {
        String url = link == null ? "" : link.trim();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("https?://[^\\s<>\"]+").matcher(url);
        if (!m.find()) return false;
        url = m.group();
        if (url.length() > 2000) return false;
        Uri uri = Uri.parse(url);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(java.util.Locale.ROOT);
        Intent intent = new Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        String youtube = youtubePackage();
        if (youtube != null && (host.endsWith("youtube.com") || host.equals("youtu.be"))) {
            try {
                startActivity(new Intent(intent).setPackage(youtube));
                return true;
            } catch (RuntimeException fallThrough) {
                // any other app that takes it
            }
        }
        try {
            startActivity(intent);
            return true;
        } catch (RuntimeException none) {
            Log.w(TAG, "nothing opens " + host);
            return false;
        }
    }

    /** Sleeps the device in this many minutes; 0 cancels. */
    boolean setSleepTimer(int minutes) {
        if (minutes < 0 || minutes > 600) return false;
        if (minutes > 0 && android.os.Build.VERSION.SDK_INT < 28) return false;
        main.removeCallbacks(sleepNow);
        sleepAt = 0;
        if (minutes > 0) {
            sleepAt = SystemClock.elapsedRealtime() + minutes * 60_000L;
            main.postDelayed(sleepNow, minutes * 60_000L);
        }
        return true;
    }

    /** Seconds until the sleep timer, 0 when none. */
    long sleepIn() {
        long at = sleepAt;
        return at == 0 ? 0 : Math.max(0, (at - SystemClock.elapsedRealtime()) / 1000);
    }

    /** Guests may turn the volume up to this share of the maximum. */
    static final float GUEST_VOLUME = 0.7f;

    boolean atGuestVolumeCap() {
        AudioManager audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC) >= (int) Math.floor(max * GUEST_VOLUME);
    }

    /** Sets the media volume to level (0 to the device's maximum). */
    boolean setVolume(int level, boolean guest) {
        AudioManager audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        if (audio.isVolumeFixed() || level < 0 || level > max) return false;
        if (guest) level = Math.min(level, (int) Math.floor(max * GUEST_VOLUME));
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, level, AudioManager.FLAG_SHOW_UI);
        return true;
    }

    static String localName(Context context) {
        String fixed = context.getString(R.string.mdns_name).trim();
        if (!fixed.isEmpty()) return fixed;
        android.content.SharedPreferences prefs = context.getSharedPreferences("device", MODE_PRIVATE);
        String tag = prefs.getString("tag", null);
        if (tag == null) {
            tag = String.format("%04x", new java.security.SecureRandom().nextInt(0x10000));
            prefs.edit().putString("tag", tag).apply();
        }
        String kind = kind(context);
        String name = prefs.getString("name", kind);
        String slug = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD)
                .toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.isEmpty()) slug = kind.toLowerCase(java.util.Locale.ROOT);
        if (slug.length() > 40) slug = slug.substring(0, 40).replaceAll("-+$", "");
        return slug + "-" + tag + ".local";
    }

    /**
     * {"name", "phone", "pointer", "youtube", "current": {"pkg","label"} or absent when Home is showing}.
     */
    String stateJson(Pairing.Phone phone) {
        try {
            org.json.JSONObject s = new org.json.JSONObject().put("name", deviceName()).put("phone", phone.name)
                    .put("pointer", pointerMode()).put("youtube", youtubePackage() != null)
                    .put("host", localName(this) + (server == null ? "" : ":" + server.port()));
            // The window in front right now; the last screen seen in events when that cannot be read.
            String pkg = shownPackage;
            try {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root != null && root.getPackageName() != null) pkg = root.getPackageName().toString();
            } catch (RuntimeException ignored) { }
            if (phone.guest()) {
                s.put("guest", true).put("guestLeft", Math.max(0, (phone.guestUntil - System.currentTimeMillis()) / 1000));
            }
            AudioManager audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (!audio.isVolumeFixed()) {
                int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                s.put("vol", audio.getStreamVolume(AudioManager.STREAM_MUSIC)).put("volMax", max)
                        .put("volCap", phone.guest() ? (int) Math.floor(max * GUEST_VOLUME) : max)
                        .put("muted", audio.isStreamMute(AudioManager.STREAM_MUSIC));
            }
            s.put("canSleep", android.os.Build.VERSION.SDK_INT >= 28).put("sleepIn", sleepIn());
            s.put("preview", !phone.guest() && previewAllowed(this) && android.os.Build.VERSION.SDK_INT >= 30);
            s.put("media", NowPlaying.allowed(this) ? NowPlaying.json(this, apps) : null);
            s.put("mediaAllowed", NowPlaying.allowed(this));
            java.net.Inet4Address ip = NetUtil.ipv4(this);
            if (peers != null) s.put("others", peers.json(ip == null ? null : ip.getHostAddress()));
            String update = Updates.available(this);
            if (update != null) s.put("update", update).put("updateUrl", Updates.page(this));
            if (!pkg.isEmpty() && !homes().contains(pkg) && !pkg.equals(getPackageName())
                    && !pkg.equals("com.android.systemui") && !pkg.equals("android")) {
                s.put("current", new org.json.JSONObject().put("pkg", pkg).put("label", apps.label(pkg)));
            }
            return s.toString();
        } catch (Exception impossible) {
            return "{}";
        }
    }

    // ---- actions, called from the web server's threads ----------------------------------------------------------

    /** One remote button. Returns false for an unknown name. */
    boolean key(String name) {
        switch (name) {
            case "up": return dpad(GLOBAL_ACTION_DPAD_UP, View.FOCUS_UP);
            case "down": return dpad(GLOBAL_ACTION_DPAD_DOWN, View.FOCUS_DOWN);
            case "left": return dpad(GLOBAL_ACTION_DPAD_LEFT, View.FOCUS_LEFT);
            case "right": return dpad(GLOBAL_ACTION_DPAD_RIGHT, View.FOCUS_RIGHT);
            case "ok": return keysAsFocus() ? clickFocused() : performGlobalAction(GLOBAL_ACTION_DPAD_CENTER);
            case "back": return performGlobalAction(GLOBAL_ACTION_BACK);
            case "home": return performGlobalAction(GLOBAL_ACTION_HOME);
            // Android 9+: the same as the power button's short press on most TVs and projectors, the screen goes to
            // sleep. Waking it needs the device's own remote: asleep, it no longer hears the phone.
            case "sleep": return android.os.Build.VERSION.SDK_INT >= 28 && performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
            case "volup": return volume(AudioManager.ADJUST_RAISE);
            case "voldown": return volume(AudioManager.ADJUST_LOWER);
            case "mute": return volume(AudioManager.ADJUST_TOGGLE_MUTE);
            case "playpause": return media(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
            case "forward": return media(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD);
            case "rewind": return media(KeyEvent.KEYCODE_MEDIA_REWIND);
            // Touchpad: a long press holds OK (context menus), two fingers scroll a page at a time.
            case "longok": return longPressFocused();
            case "pageup": return page(View.FOCUS_UP, GLOBAL_ACTION_DPAD_UP);
            case "pagedown": return page(View.FOCUS_DOWN, GLOBAL_ACTION_DPAD_DOWN);
            default: return false;
        }
    }

    private boolean longPressFocused() {
        AccessibilityNodeInfo node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        for (int i = 0; node != null && i < 6; i++) {
            if (node.isLongClickable()) return node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK);
            node = node.getParent();
        }
        return false;
    }

    /** A page: the list around the selection scrolls; without one, five steps. */
    private boolean page(int direction, int globalAction) {
        AccessibilityNodeInfo focus = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focus != null && scroll(focus, direction)) return true;
        for (int i = 0; i < 5; i++) dpad(globalAction, direction);
        return true;
    }

    private boolean volume(int direction) {
        AudioManager audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI);
        return true;
    }

    private boolean media(int keyCode) {
        AudioManager audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        long now = SystemClock.uptimeMillis();
        audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
        audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
        return true;
    }

    /** Puts text into the focused text box (replacing what is there). False when no text box has focus. */
    boolean text(String value) {
        AccessibilityNodeInfo box = editableFocus();
        if (box == null) return false;
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        return box.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
    }

    // ---- arrows and OK -----------------------------------------------------------------------------------------

    /*
     * Android 13+ lets an accessibility service press the real D-pad keys. Two cases need another way:
     *
     * 1. Android 8 to 12 has no such keys for a service. There the arrows move the focus themselves: the nearest
     *    focusable element in that direction gets focus, a list scrolls when its edge is reached, OK clicks.
     * 2. Some projector remotes have a mouse button (KEYCODE_MOUSE_SWITCH). While its mode is on, the firmware
     *    turns every arrow key, from any source, into cursor moves. The mode is mirrored in a system property
     *    (config.xml), and while it is on the same focus moves are used instead of keys.
     */
    private volatile boolean pointer;
    private volatile long pointerReadAt;

    private boolean keysAsFocus() {
        return android.os.Build.VERSION.SDK_INT < 33 || pointerMode();
    }

    boolean pointerMode() {
        String prop = getString(R.string.mouse_mode_property);
        if (prop.isEmpty()) return false;
        long now = SystemClock.uptimeMillis();
        if (now - pointerReadAt < 1000) return pointer;
        pointerReadAt = now;
        try {
            Process p = new ProcessBuilder("getprop", prop).redirectErrorStream(true).start();
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
                String line = r.readLine();
                pointer = line != null && line.trim().equals("on");
            }
            p.waitFor();
        } catch (Exception unreadable) {
            pointer = false;
        }
        return pointer;
    }

    private boolean dpad(int globalAction, int direction) {
        if (!keysAsFocus()) return performGlobalAction(globalAction);
        AccessibilityNodeInfo focus = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focus == null) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            AccessibilityNodeInfo first = root == null ? null : firstFocusable(root, 0);
            return first != null && first.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        }
        // A focused container with nothing chosen inside it yet (a grid): choose its first item.
        AccessibilityNodeInfo inner = firstFocusable(focus, 0);
        if (inner != null && !inner.equals(focus)) return inner.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        AccessibilityNodeInfo next = nearest(focus, direction);
        if (next == null) next = focus.focusSearch(direction);
        if (next != null) return next.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        // Nothing further on screen that way: scroll the list the focus is in, then step onto what came into view.
        if (!scroll(focus, direction)) return false;
        main.postDelayed(() -> {
            AccessibilityNodeInfo now = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            AccessibilityNodeInfo after = now == null ? null : nearest(now, direction);
            if (after != null) after.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        }, 250);
        return true;
    }

    private static boolean scroll(AccessibilityNodeInfo from, int direction) {
        AccessibilityNodeInfo.AccessibilityAction specific;
        int general;
        switch (direction) {
            case View.FOCUS_UP: specific = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP;
                general = AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD; break;
            case View.FOCUS_DOWN: specific = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN;
                general = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD; break;
            case View.FOCUS_LEFT: specific = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT;
                general = AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD; break;
            default: specific = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT;
                general = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD; break;
        }
        // The nearest list that scrolls that way; a list that only knows forward/backward as a second choice.
        AccessibilityNodeInfo node = from.getParent();
        AccessibilityNodeInfo loose = null;
        for (int i = 0; node != null && i < 12; i++) {
            if (node.isScrollable()) {
                if (node.getActionList().contains(specific)) return node.performAction(specific.getId());
                if (loose == null) loose = node;
            }
            node = node.getParent();
        }
        return loose != null && loose.performAction(general);
    }

    /** The closest focusable element on screen in that direction, the way a D-pad would move. */
    private AccessibilityNodeInfo nearest(AccessibilityNodeInfo from, int direction) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        android.graphics.Rect a = new android.graphics.Rect();
        from.getBoundsInScreen(a);
        java.util.List<AccessibilityNodeInfo> all = new java.util.ArrayList<>();
        collectFocusable(root, all, 0);
        AccessibilityNodeInfo best = null;
        long bestScore = Long.MAX_VALUE;
        android.graphics.Rect b = new android.graphics.Rect();
        for (AccessibilityNodeInfo n : all) {
            if (n.equals(from)) continue;
            n.getBoundsInScreen(b);
            if (b.contains(a) || a.contains(b)) continue;
            int major, minor;
            switch (direction) {
                case View.FOCUS_UP: major = a.top - b.bottom; minor = Math.abs(a.centerX() - b.centerX()); break;
                case View.FOCUS_DOWN: major = b.top - a.bottom; minor = Math.abs(a.centerX() - b.centerX()); break;
                case View.FOCUS_LEFT: major = a.left - b.right; minor = Math.abs(a.centerY() - b.centerY()); break;
                default: major = b.left - a.right; minor = Math.abs(a.centerY() - b.centerY()); break;
            }
            if (major < -8) continue;   // not in that direction
            long score = 13L * Math.max(0, major) * Math.max(0, major) + (long) minor * minor;
            if (score < bestScore) {
                bestScore = score;
                best = n;
            }
        }
        return best;
    }

    private static void collectFocusable(AccessibilityNodeInfo node, java.util.List<AccessibilityNodeInfo> out, int depth) {
        if (node == null || depth > 30) return;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null || !child.isVisibleToUser()) continue;
            if (child.isFocusable() && child.getChildCount() == 0 || child.isFocusable() && firstFocusable(child, 0) == null) {
                out.add(child);
            } else {
                collectFocusable(child, out, depth + 1);
            }
        }
    }

    private static AccessibilityNodeInfo firstFocusable(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 30) return null;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null || !child.isVisibleToUser()) continue;
            if (child.isFocusable()) return child;
            AccessibilityNodeInfo deeper = firstFocusable(child, depth + 1);
            if (deeper != null) return deeper;
        }
        return null;
    }

    private boolean clickFocused() {
        AccessibilityNodeInfo node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        for (int i = 0; node != null && i < 6; i++) {
            if (node.isClickable()) return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            node = node.getParent();
        }
        return performGlobalAction(GLOBAL_ACTION_DPAD_CENTER);
    }

    /** The keyboard's Enter/Search key on the focused text box (Android 11+; older versions have no such action). */
    boolean enter() {
        if (android.os.Build.VERSION.SDK_INT < 30) return false;
        AccessibilityNodeInfo box = editableFocus();
        if (box == null) return false;
        return box.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
    }

    private AccessibilityNodeInfo editableFocus() {
        AccessibilityNodeInfo focus = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focus != null && focus.isEditable()) return focus;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        return root == null ? null : firstEditable(root, 0);
    }

    /** A search screen whose box is not focused yet (the on-screen keyboard has focus): its only text box. */
    private static AccessibilityNodeInfo firstEditable(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 25) return null;
        if (node.isEditable() && node.isVisibleToUser()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = firstEditable(node.getChild(i), depth + 1);
            if (found != null) return found;
        }
        return null;
    }

    /** Opens an app straight at its search results. app: youtube or play. */
    boolean search(String app, String query) {
        Intent intent;
        String q = Uri.encode(query);
        switch (app) {
            case "youtube":
                String youtube = youtubePackage();
                if (youtube == null) return false;
                intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + q));
                intent.setPackage(youtube);
                break;
            case "play":
                intent = new Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=" + q + "&c=apps"));
                intent.setPackage("com.android.vending");
                break;
            default:
                return false;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException failure) {
            Log.w(TAG, "search failed: " + failure);
            return false;
        }
    }

    /** The installed app that opens YouTube searches (config.xml lists the candidates), or null. */
    private String youtubePackage() {
        for (String pkg : getResources().getStringArray(R.array.youtube_packages)) {
            try {
                getPackageManager().getApplicationInfo(pkg, 0);
                return pkg;
            } catch (android.content.pm.PackageManager.NameNotFoundException missing) {
                // next
            }
        }
        return null;
    }

    // ---- on-screen overlays --------------------------------------------------------------------------------------

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private WindowManager wm() {
        return (WindowManager) getSystemService(Context.WINDOW_SERVICE);
    }

    private WindowManager.LayoutParams overlayParams(int gravity, int x, int y) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = gravity;
        lp.x = x;
        lp.y = y;
        return lp;
    }

    private GradientDrawable glass(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(22));
        g.setStroke(Math.round(dp(1)), 0x33FFFFFF);
        return g;
    }

    private TextView label(String text, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        return t;
    }

    /**
     * The remote card on Home, styled like the launcher's glass pills: a small pairing QR, and beside it every paired
     * phone as a chip whose dot is green while that phone is connected. It is a Home widget, hidden over apps.
     */
    private void updateQrCard() {
        if (instance != this) return;   // switched off: the next instance draws its own card
        int corner = cardCorner(this);
        boolean want = corner != 4 && !dreaming && homes().contains(shownPackage) && pinView == null
                && !looksLikeScreensaver();
        main.removeCallbacks(refreshCard);
        if (!want) {
            removeQrCard();
            return;
        }
        String url = url(this);
        if (url == null) {
            // Right after power-on Wi-Fi may not be up yet: look again shortly.
            removeQrCard();
            main.removeCallbacks(retryQr);
            main.postDelayed(retryQr, 5000);
            return;
        }
        // Rebuilt only when what it shows changed: the QR's code, or a phone's name or connected state.
        String state = url + "|" + phoneState();
        if (qrCard != null && state.equals(cardState)) {
            main.postDelayed(refreshCard, 15_000);   // a phone that went quiet turns grey
            return;
        }
        removeQrCard();
        View card = buildCard(url);
        if (card == null) return;
        card.setOnClickListener(v -> openOwnScreen());
        try {
            // Top right sits under a launcher's clock/settings row; the other corners keep a plain margin.
            int gravity = (corner == 0 || corner == 3 ? Gravity.TOP : Gravity.BOTTOM)
                    | (corner == 0 || corner == 1 ? Gravity.END : Gravity.START);
            int y = corner == 0 || corner == 3 ? 112 : 52;
            wm().addView(card, overlayParams(gravity, Math.round(dp(52)), Math.round(dp(y))));
            qrCard = card;
            cardState = state;
            card.setAlpha(0f);
            card.post(this::checkCovered);   // fades in only if nothing of Home is under it
        } catch (RuntimeException failure) {
            Log.w(TAG, "cannot show remote card: " + failure);
        }
        main.postDelayed(refreshCard, 15_000);
    }

    private String phoneState() {
        StringBuilder s = new StringBuilder();
        s.append(cardCorner(this)).append(';').append(pairingAllowed(this)).append(';').append(pairing.limit()).append(';');
        for (Pairing.Phone p : pairing.phones()) s.append(p.name).append(p.connected() ? '+' : '-').append(';');
        return s.toString();
    }

    private View buildCard(String url) {
        int qrSize = Math.round(dp(74));
        Bitmap code = Qr.bitmap(url, qrSize, true);
        if (code == null) return null;

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Math.round(dp(10));
        card.setPadding(pad, pad, Math.round(dp(16)), pad);
        card.setBackground(pillGlass(dp(22)));

        ImageView qr = new ImageView(this);
        GradientDrawable white = new GradientDrawable();
        white.setColor(0xFFFFFFFF);
        white.setCornerRadius(dp(14));
        qr.setBackground(white);
        int inset = Math.round(dp(5));
        qr.setPadding(inset, inset, inset, inset);
        qr.setImageBitmap(code);
        card.addView(qr, new LinearLayout.LayoutParams(qrSize + 2 * inset, qrSize + 2 * inset));

        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setPadding(Math.round(dp(14)), 0, 0, 0);
        card.addView(side);

        java.util.List<Pairing.Phone> phones = pairing.phones();
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView mark = new ImageView(this);
        mark.setImageResource(R.drawable.ic_launcher);
        int markSize = Math.round(dp(22));
        LinearLayout.LayoutParams markLp = new LinearLayout.LayoutParams(markSize, markSize);
        markLp.setMarginEnd(Math.round(dp(7)));
        head.addView(mark, markLp);
        TextView title = label(getString(R.string.app_name), 17, 0xFFFFFFFF);
        title.setTypeface(font(R.font.outfit_medium));
        head.addView(title);
        side.addView(head);
        // When no new phone can pair, the card says so rather than invite a scan that will be refused.
        String hintText = !pairingAllowed(this) ? "New phones are off"
                : pairing.full("") ? "Full · " + phones.size() + " of " + pairing.limit() + (pairing.limit() == 1 ? " phone" : " phones")
                : phones.isEmpty() ? "Scan with your phone camera" : "Scan to add a phone";
        TextView hint = label(hintText, 12, 0xA6FFFFFF);
        hint.setTypeface(font(R.font.outfit_regular));
        hint.setPadding(0, Math.round(dp(2)), 0, 0);
        side.addView(hint);

        if (!phones.isEmpty()) {
            LinearLayout chips = new LinearLayout(this);
            chips.setOrientation(LinearLayout.HORIZONTAL);
            chips.setPadding(0, Math.round(dp(8)), 0, 0);
            int shown = Math.min(3, phones.size());
            for (int i = 0; i < shown; i++) chips.addView(chip(phones.get(i).name, phones.get(i).connected()));
            if (phones.size() > shown) chips.addView(chip("+" + (phones.size() - shown), null));
            side.addView(chips);
        }
        return card;
    }

    /** A small glass chip: a status dot (green connected, grey not; none when connected is null) and a name. */
    private View chip(String name, Boolean connected) {
        TextView t = label((connected == null ? "" : "●  ") + name, 11, 0xE6FFFFFF);
        t.setTypeface(font(R.font.outfit_regular));
        t.setSingleLine(true);
        t.setMaxWidth(Math.round(dp(124)));
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (connected != null) {
            android.text.SpannableString s = new android.text.SpannableString(t.getText());
            s.setSpan(new android.text.style.ForegroundColorSpan(connected ? 0xFF3DDC97 : 0x66FFFFFF), 0, 1, 0);
            t.setText(s);
        }
        int h = Math.round(dp(9)), v = Math.round(dp(3));
        t.setPadding(h, v, h, v);
        t.setBackground(pillGlass(dp(12)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(Math.round(dp(6)));
        t.setLayoutParams(lp);
        return t;
    }

    /** The launcher's pill glass (pill_bg.xml): a soft white gradient over the backdrop and a thin light rim. */
    private GradientDrawable pillGlass(float radius) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0x2EFFFFFF, 0x14FFFFFF});
        g.setCornerRadius(radius);
        g.setStroke(Math.max(1, Math.round(dp(1))), 0x38FFFFFF);
        return g;
    }

    private android.graphics.Typeface font(int res) {
        try {
            return getResources().getFont(res);
        } catch (RuntimeException missing) {
            return android.graphics.Typeface.DEFAULT;
        }
    }

    private void removeQrCard() {
        if (qrCard == null) return;
        try { wm().removeView(qrCard); } catch (RuntimeException ignored) { }
        qrCard = null;
        cardState = "";
    }

    void showPin(String pin, long forMs) {
        main.post(() -> {
            hidePin();
            removeQrCard();
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER_HORIZONTAL);
            int pad = Math.round(dp(36));
            box.setPadding(pad, pad, pad, pad);
            box.setBackground(glass(0xE60B0D18));
            box.addView(label("Phone remote pairing", 22, 0xFFFFFFFF));
            TextView code = label(pin.replaceAll("(\\d{3})(\\d{3})", "$1 $2"), 56, 0xFFFFFFFF);
            code.setPadding(0, Math.round(dp(14)), 0, Math.round(dp(14)));
            box.addView(code);
            box.addView(label("Enter this PIN on your phone", 16, 0xB3FFFFFF));
            try {
                wm().addView(box, overlayParams(Gravity.CENTER, 0, 0));
                pinView = box;
                main.removeCallbacks(hidePinLater);
                main.postDelayed(hidePinLater, forMs);
            } catch (RuntimeException failure) {
                Log.w(TAG, "cannot show PIN: " + failure);
            }
        });
    }

    void hidePin() {
        if (pinView == null) return;
        try { wm().removeView(pinView); } catch (RuntimeException ignored) { }
        pinView = null;
        updateQrCard();
    }

    /** The app's own screen changed the list of phones: refresh the card's count. */
    void refreshQrCard() {
        main.post(() -> {
            removeQrCard();
            updateQrCard();
        });
    }
}
