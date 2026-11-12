package com.local.projremote;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;

/**
 * The app's own screen, in the launcher's glass style. Left: the pairing QR, how to pair, and whether the remote
 * is on (with Turn on when it is not). Right: paired phones (OK twice removes one), then the settings: where the
 * Home QR card sits, projector or TV, Developer options; then Remove all phones.
 */
public class InfoActivity extends Activity {
    private static final String[] CORNERS = {"Top right", "Bottom right", "Bottom left", "Top left", "Off"};
    private static final int TEXT = 0xFFFFFFFF, DIM = 0xA6FFFFFF, FAINT = 0x73FFFFFF;
    private static final int GREEN = 0xFF3DDC97, RED = 0xFFFF8A80;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout phoneList;
    private TextView phoneCount;
    private ImageView qr;
    private TextView statusLine, addressLine, scan, then;
    private View statusDot;
    private TextView enable;
    private TextView cornerValue, kindValue, nameValue, pairingValue, limitValue;
    private TextView guestsValue, announceValue, mediaValue, previewValue, updatesValue, aboutUpdate;
    private View removeAll;
    private TextView removeAllTitle;
    private String shownUrl = "";
    private String armedId;   // the phone (or "*all") whose remove was pressed once
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            render();
            handler.postDelayed(this, 5000);
        }
    };

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        FrameLayout screen = new FrameLayout(this);
        screen.setBackground(backdrop());

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setPadding(dp(48), dp(36), dp(48), dp(28));
        screen.addView(root);

        root.addView(pairPanel(), new LinearLayout.LayoutParams(dp(330), ViewGroup.LayoutParams.MATCH_PARENT));

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(dp(16), dp(8), dp(16), dp(24));
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setClipChildren(false);   // a selected row grows a little past its column
        scroll.setClipChildren(false);
        scroll.addView(right);
        LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        rightLp.leftMargin = dp(36);
        root.addView(scroll, rightLp);

        // Paired phones.
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(section("PAIRED PHONES"));
        phoneCount = text("", 12, DIM, false);
        phoneCount.setPadding(dp(8), dp(1), dp(8), dp(1));
        phoneCount.setBackground(round(0x1FFFFFFF, dp(10), 0));
        LinearLayout.LayoutParams countLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        countLp.leftMargin = dp(10);
        head.addView(phoneCount, countLp);
        right.addView(head);
        phoneList = new LinearLayout(this);
        phoneList.setOrientation(LinearLayout.VERTICAL);
        phoneList.setClipChildren(false);
        // One card, like the settings: a row per phone, then Remove all phones.
        phoneList.setBackground(glass(dp(20)));
        phoneList.setPadding(dp(4), dp(4), dp(4), dp(4));
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        listLp.topMargin = dp(10);
        right.addView(phoneList, listLp);
        removeAll = removeAllRow();

        // Settings.
        View settingsHead = section("SETTINGS");
        ((LinearLayout.LayoutParams) settingsHead.getLayoutParams()).topMargin = dp(26);
        right.addView(settingsHead);
        LinearLayout settings = card();
        right.addView(settings);
        nameValue = settingRow(settings, "name", "Name", "What phones call this device", this::editName);
        divider(settings);
        pairingValue = settingRow(settings, "pairing", "Allow new phones",
                "Off: no new phone can pair; paired phones keep working", () -> {
                    RemoteService.setPairingAllowed(this, !RemoteService.pairingAllowed(this));
                    render();
                });
        divider(settings);
        limitValue = settingRow(settings, "limit", "Phone limit",
                "When full, new phones are refused until you remove one", () -> {
                    Pairing pairing = Pairing.get(this);
                    int now = pairing.limit(), next = Pairing.LIMITS[0];
                    for (int n : Pairing.LIMITS) if (n > now) { next = n; break; }
                    pairing.setLimit(next);
                    render();
                });
        divider(settings);
        guestsValue = settingRow(settings, "guests", "New phones join as",
                "Guests: 24 hours, fewer rights, volume up to 70%", () -> {
                    Pairing pairing = Pairing.get(this);
                    pairing.setGuestsByDefault(!pairing.guestsByDefault());
                    render();
                });
        divider(settings);
        cornerValue = settingRow(settings, "corner", "QR card on Home",
                "Move it if it covers something; click it to open this screen", () -> {
                    RemoteService.setCardCorner(this, (RemoteService.cardCorner(this) + 1) % CORNERS.length);
                    render();
                });
        divider(settings);
        kindValue = settingRow(settings, "kind", "This device is a", "Only changes the words on the phone", () -> {
            RemoteService.setKind(this, RemoteService.kind(this).equals("TV") ? "projector" : "tv");
            render();
        });
        divider(settings);
        announceValue = settingRow(settings, "announce", "Show when phones connect",
                "A short note at the bottom of the screen", () -> {
                    RemoteService.setAnnounceConnections(this, !RemoteService.announceConnections(this));
                    render();
                });

        View featuresHead = section("ON THE PHONES");
        ((LinearLayout.LayoutParams) featuresHead.getLayoutParams()).topMargin = dp(26);
        right.addView(featuresHead);
        LinearLayout features = card();
        right.addView(features);
        mediaValue = settingRow(features, "media", "Now playing",
                "Title, artwork and seeking for what plays here", this::toggleMedia);
        divider(features);
        previewValue = settingRow(features, "preview", "Screen preview",
                android.os.Build.VERSION.SDK_INT >= 30 ? "A small live picture of this screen, for family phones"
                        : "Needs Android 11 or later", this::togglePreview);
        if (Updates.possible(this)) {
            divider(features);
            updatesValue = settingRow(features, "updates", "Check for updates",
                    "Once a day, asks GitHub for a newer version", () -> {
                        Updates.setEnabled(this, !Updates.enabled(this));
                        render();
                    });
        }

        View systemHead = section("SYSTEM");
        ((LinearLayout.LayoutParams) systemHead.getLayoutParams()).topMargin = dp(26);
        right.addView(systemHead);
        LinearLayout system = card();
        right.addView(system);
        settingRow(system, "a11y", "Accessibility", "Where the remote is switched on or off",
                this::openAccessibility).setText("›");
        divider(system);
        // USB debugging lives in Developer options, which many projector firmwares hide from their own settings.
        settingRow(system, "dev", "Developer options", "USB debugging, for installing updates",
                this::openDeveloperOptions).setText("›");
        divider(system);
        settingRow(system, "diag", "Diagnostics", "Device, Android and setup, for a bug report",
                this::showDiagnostics).setText("›");


        View aboutHead = section("ABOUT");
        ((LinearLayout.LayoutParams) aboutHead.getLayoutParams()).topMargin = dp(26);
        right.addView(aboutHead);
        right.addView(about());

        setContentView(screen);
    }

    // ---- left: pairing ---------------------------------------------------------------------------------------------

    private View pairPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.setPadding(dp(26), dp(26), dp(26), dp(24));
        panel.setBackground(glass(dp(28)));

        LinearLayout brand = new LinearLayout(this);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_launcher);
        brand.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setPadding(dp(12), 0, 0, 0);
        names.addView(text(getString(R.string.app_name), 22, TEXT, true));
        names.addView(text("Your phone as the remote", 13, DIM, false));
        brand.addView(names);
        panel.addView(brand, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        qr = new ImageView(this);
        qr.setBackground(round(0xFFFFFFFF, dp(18), 0));
        qr.setPadding(dp(10), dp(10), dp(10), dp(10));
        LinearLayout.LayoutParams qrLp = new LinearLayout.LayoutParams(dp(176), dp(176));
        qrLp.topMargin = dp(22);
        panel.addView(qr, qrLp);

        scan = text("Scan with your phone camera", 16, TEXT, true);
        scan.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams scanLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        scanLp.topMargin = dp(18);
        panel.addView(scan, scanLp);
        then = text("", 13, DIM, false);
        then.setGravity(Gravity.CENTER);
        then.setLineSpacing(0, 1.15f);
        panel.addView(then);

        View spacer = new View(this);
        panel.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));

        // Status: a pill with a dot, and the addresses under it.
        LinearLayout pill = new LinearLayout(this);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(14), dp(8), dp(16), dp(8));
        pill.setBackground(round(0x1AFFFFFF, dp(18), 0));
        statusDot = new View(this);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(8), dp(8));
        dotLp.rightMargin = dp(9);
        pill.addView(statusDot, dotLp);
        statusLine = text("", 13, TEXT, false);
        pill.addView(statusLine);
        panel.addView(pill);
        addressLine = text("", 12, FAINT, false);
        addressLine.setGravity(Gravity.CENTER);
        addressLine.setPadding(0, dp(8), 0, 0);
        panel.addView(addressLine);

        // Only while the service is off.
        enable = text("Turn on", 16, 0xFF111216, true);
        enable.setGravity(Gravity.CENTER);
        enable.setPadding(dp(28), dp(11), dp(28), dp(11));
        enable.setBackground(states(round(0xE6FFFFFF, dp(22), 0), round(0xFFFFFFFF, dp(22), 0)));
        focusable(enable, "enable", this::disclose);
        LinearLayout.LayoutParams enLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        enLp.topMargin = dp(14);
        panel.addView(enable, enLp);
        return panel;
    }

    // ---- refresh ---------------------------------------------------------------------------------------------------

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        armedId = null;   // a half-done "press OK again" never survives leaving the screen
        super.onPause();
    }

    private void render() {
        String url = RemoteService.url(this);
        if (!String.valueOf(url).equals(shownUrl)) {
            shownUrl = String.valueOf(url);
            qr.setImageBitmap(url == null ? null : Qr.bitmap(url, dp(156), true));
            qr.setAlpha(url == null ? 0.25f : 1f);
        }
        // Off, but allowed to switch itself on (the owner granted that over ADB): do it, the next refresh shows it.
        if (RemoteService.instance == null) SelfEnable.ensure(this);
        boolean on = RemoteService.instance != null;
        if (!on) {
            statusLine.setText("Remote is off");
            addressLine.setText("Turn it on to use your phone as the remote.");
        } else if (url == null) {
            statusLine.setText("Waiting for the network…");
            addressLine.setText("");
        } else {
            String address = url.replaceAll("^http://|/#.*$", "");
            statusLine.setText("Ready  ·  " + address);
            addressLine.setText("also " + RemoteService.localName(this) + address.replaceAll("^[^:]*", ""));
        }
        statusDot.setBackground(round(!on ? RED : url == null ? 0xFFFFC857 : GREEN, dp(4), 0));
        enable.setVisibility(on ? View.GONE : View.VISIBLE);
        cornerValue.setText(CORNERS[RemoteService.cardCorner(this)] + "  ›");
        nameValue.setText(RemoteService.deviceName(this) + "  ›");
        boolean allowed = RemoteService.pairingAllowed(this);
        pairingValue.setText(allowed ? "On" : "Off");
        pairingValue.setTextColor(allowed ? GREEN : RED);
        String kind = RemoteService.kind(this);
        kindValue.setText((kind.equals("TV") ? "TV" : "Projector") + "  ›");

        View focused = getCurrentFocus();
        Object focusedTag = focused == null ? null : focused.getTag();
        phoneList.removeAllViews();
        List<Pairing.Phone> phones = Pairing.get(this).phones();
        int limit = Pairing.get(this).limit();
        // The pairing text says when a scan would be refused, and what to change here.
        if (!RemoteService.pairingAllowed(this)) {
            scan.setText("New phones are off");
            then.setText("Turn on Allow new phones (on the right)\nto pair another phone.");
        } else if (phones.size() >= limit) {
            scan.setText("Full: " + phones.size() + " of " + limit + (limit == 1 ? " phone" : " phones"));
            then.setText("Remove a phone or raise Phone limit\n(on the right) to pair another.");
        } else {
            scan.setText("Scan with your phone camera");
            then.setText("then type the PIN that appears here.\nThe phone must be on the same Wi-Fi.");
        }
        phoneCount.setText(phones.size() + " of " + limit);
        boolean guests = Pairing.get(this).guestsByDefault();
        guestsValue.setText(guests ? "Guests  ›" : "Family  ›");
        guestsValue.setTextColor(guests ? 0xFFFFC857 : DIM);
        onOff(announceValue, RemoteService.announceConnections(this));
        onOff(mediaValue, NowPlaying.allowed(this));
        onOff(previewValue, RemoteService.previewAllowed(this) && android.os.Build.VERSION.SDK_INT >= 30);
        if (updatesValue != null) onOff(updatesValue, Updates.enabled(this));
        String update = Updates.available(this);
        aboutUpdate.setVisibility(update == null ? View.GONE : View.VISIBLE);
        if (update != null) aboutUpdate.setText("Version " + update + " is available: " + Updates.page(this).replaceFirst("^https?://", ""));
        limitValue.setText(limit + (limit == 1 ? " phone" : " phones") + "  ›");
        limitValue.setTextColor(phones.size() >= limit ? 0xFFFFC857 : ((View) limitValue.getParent()).isFocused() ? TEXT : DIM);
        phoneCount.setVisibility(View.VISIBLE);
        View restore = null;
        if (phones.isEmpty()) {
            LinearLayout empty = new LinearLayout(this);
            empty.setOrientation(LinearLayout.VERTICAL);
            empty.setPadding(dp(20), dp(16), dp(20), dp(16));
            empty.addView(text("No phones yet", 17, TEXT, true));
            TextView how = text("Scan the code on the left to pair one. Pair as many as you like.", 13, DIM, false);
            how.setPadding(0, dp(4), 0, 0);
            empty.addView(how);
            phoneList.addView(empty);
        } else {
            for (Pairing.Phone p : phones) {
                if (phoneList.getChildCount() > 0) divider(phoneList);
                View row = phoneRow(p);
                phoneList.addView(row);
                if (p.id.equals(focusedTag)) restore = row;
            }
            divider(phoneList);
            if (removeAll.getParent() != null) ((ViewGroup) removeAll.getParent()).removeView(removeAll);
            phoneList.addView(removeAll);
            if ("*all".equals(focusedTag)) restore = removeAll;
        }
        removeAll.setVisibility(phones.isEmpty() ? View.GONE : View.VISIBLE);
        boolean allArmed = "*all".equals(armedId);
        removeAllTitle.setText(allArmed ? "Press OK again to remove every phone" : "Remove all phones");

        if (restore != null) {
            restore.requestFocus();
        } else if (getCurrentFocus() == null || getCurrentFocus().getParent() == null) {
            View first = !on ? enable : phones.isEmpty() ? findViewWithTagCompat("corner") : phoneList.getChildAt(0);
            if (first != null) first.requestFocus();
        }
    }

    private View findViewWithTagCompat(String tag) {
        return getWindow().getDecorView().findViewWithTag(tag);
    }

    // ---- rows ------------------------------------------------------------------------------------------------------

    private View phoneRow(Pairing.Phone p) {
        boolean armed = p.id.equals(armedId);
        boolean connected = p.connected();
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(12), dp(20), dp(12));
        row.setBackground(states(round(0x00000000, dp(16), 0), focusGlass(dp(16))));

        // Avatar: the name's first letter on a soft disc, with the connected dot on its corner.
        FrameLayout avatar = new FrameLayout(this);
        TextView letter = text(p.name.isEmpty() ? "?" : p.name.substring(0, 1).toUpperCase(), 18, TEXT, true);
        letter.setGravity(Gravity.CENTER);
        letter.setBackground(round(0x2EFFFFFF, dp(22), 0));
        avatar.addView(letter, new FrameLayout.LayoutParams(dp(44), dp(44)));
        View dot = new View(this);
        dot.setBackground(round(connected ? GREEN : 0xFF6B6E78, dp(6), 0x00000000));
        GradientDrawable ring = round(connected ? GREEN : 0xFF6B6E78, dp(7), 0);
        ring.setStroke(dp(2), 0xFF15161C);
        dot.setBackground(ring);
        FrameLayout.LayoutParams dotLp = new FrameLayout.LayoutParams(dp(14), dp(14), Gravity.BOTTOM | Gravity.END);
        avatar.addView(dot, dotLp);
        row.addView(avatar, new LinearLayout.LayoutParams(dp(46), dp(46)));

        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setPadding(dp(16), 0, dp(12), 0);
        TextView name = text(p.name, 18, TEXT, true);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(name);
        long now = System.currentTimeMillis();
        String when = connected ? "Connected now"
                : "Last used " + DateUtils.getRelativeTimeSpanString(p.lastSeen, now, DateUtils.MINUTE_IN_MILLIS);
        if (p.guest()) {
            long hours = Math.max(1, (p.guestUntil - now + 3_599_999L) / 3_600_000L);
            when = "Guest, " + hours + " h left  ·  " + when;
        }
        TextView sub = text(p.guest() ? when : when + "  ·  paired "
                + DateUtils.getRelativeTimeSpanString(p.pairedAt, now, DateUtils.DAY_IN_MILLIS).toString().toLowerCase(),
                13, connected ? GREEN : DIM, false);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(sub);
        row.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Shown while selected: what OK does here.
        TextView hint = text("OK for options", 13, FAINT, false);
        hint.setVisibility(armed ? View.VISIBLE : View.INVISIBLE);
        row.addView(hint);
        row.setOnFocusChangeListener((v, has) -> {
            hint.setVisibility(has || armed ? View.VISIBLE : View.INVISIBLE);
        });

        focusable(row, p.id, () -> phoneMenu(p));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 0;
        row.setLayoutParams(lp);
        return row;
    }

    /** A settings row inside a card: title and explanation on the left, the current value on the right. */
    private TextView settingRow(LinearLayout card, String tag, String title, String subtitle, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), dp(14), dp(20), dp(14));
        row.setBackground(states(round(0x00000000, dp(16), 0), focusGlass(dp(16))));
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.addView(text(title, 16, TEXT, true));
        TextView sub = text(subtitle, 12, DIM, false);
        sub.setPadding(0, dp(2), 0, 0);
        words.addView(sub);
        row.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView value = text("", 15, DIM, false);
        row.addView(value);
        row.setOnFocusChangeListener((v, has) -> {
            int now = value.getCurrentTextColor();   // a status colour (green, red, amber) stays as it is
            if (now == TEXT || now == DIM) value.setTextColor(has ? TEXT : DIM);
        });
        focusable(row, tag, action);
        card.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return value;
    }

    private View removeAllRow() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), dp(14), dp(20), dp(14));
        row.setBackground(states(round(0x00000000, dp(16), 0), focusGlass(dp(16))));
        removeAllTitle = text("Remove all phones", 16, RED, true);
        row.addView(removeAllTitle);
        focusable(row, "*all", () -> {
            if ("*all".equals(armedId)) {
                Pairing.get(this).removeAll();
                armedId = null;
                if (RemoteService.instance != null) RemoteService.instance.refreshQrCard();
            } else {
                armedId = "*all";
            }
            render();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 0;
        row.setLayoutParams(lp);
        return row;
    }

    private View section(String title) {
        TextView t = text(title, 12, DIM, true);
        t.setLetterSpacing(0.12f);
        t.setPadding(dp(4), 0, 0, dp(2));
        t.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(4), dp(4), dp(4), dp(4));
        c.setBackground(glass(dp(20)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        c.setLayoutParams(lp);
        return c;
    }

    private void divider(LinearLayout card) {
        View line = new View(this);
        line.setBackgroundColor(0x14FFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.leftMargin = dp(20);
        lp.rightMargin = dp(20);
        card.addView(line, lp);
    }

    private void onOff(TextView value, boolean on) {
        value.setText(on ? "On" : "Off");
        value.setTextColor(on ? GREEN : DIM);
    }

    /** One phone: make it a guest or family, or remove it. */
    private void phoneMenu(Pairing.Phone p) {
        String[] items = {p.guest() ? "Make family (no time limit, all rights)" : "Make guest (24 hours, fewer rights)",
                "Remove " + p.name, "Cancel"};
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(p.name)
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        Pairing.get(this).setGuest(p.id, !p.guest());
                    } else if (which == 1) {
                        Pairing.get(this).remove(p.id);
                    } else {
                        return;
                    }
                    if (RemoteService.instance != null) RemoteService.instance.refreshQrCard();
                    render();
                })
                .show();
    }

    /**
     * Now playing needs Android's notification access for this app. Most TVs have a screen for it (Settings → Apps →
     * Special app access → Notification access); some projectors hide it, then one ADB command grants it.
     */
    private void toggleMedia() {
        boolean on = NowPlaying.allowed(this);
        String how = on ? "To turn it off, switch off " + getString(R.string.app_name) + " under Notification access."
                : "Allow " + getString(R.string.app_name) + " under Notification access. Only which app is playing and"
                + " its title are read, for the phone's Now playing card. Notifications are not read or sent.";
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(on ? "Now playing is on" : "Show what is playing on phones")
                .setMessage(how + "\n\nIf this device has no such screen, from a computer:\n" + NowPlaying.adbGrant(this)
                        .replace("allow_listener", on ? "disallow_listener" : "allow_listener"))
                .setPositiveButton("Open Notification access", (d, w) -> openListenerSettings())
                .setNegativeButton("Close", null)
                .show();
    }

    private void openListenerSettings() {
        for (Intent i : new Intent[] {new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"),
                new Intent().setClassName("com.android.tv.settings",
                        "com.android.tv.settings.device.apps.specialaccess.NotificationAccessActivity")}) {
            try {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (RuntimeException next) {
                // next
            }
        }
        android.widget.Toast.makeText(this, "This device hides Notification access: use the ADB command.",
                android.widget.Toast.LENGTH_LONG).show();
    }

    private void togglePreview() {
        if (android.os.Build.VERSION.SDK_INT < 30) return;
        if (RemoteService.previewAllowed(this)) {
            RemoteService.setPreviewAllowed(this, false);
            render();
            return;
        }
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Show this screen on phones?")
                .setMessage("Paired family phones see a small picture of this screen, about once a second, while their "
                        + RemoteService.kind(this) + " tab is open. Guests never do. The picture goes only to the phone"
                        + " on your Wi-Fi and is not kept. Protected video (most paid apps) shows black.")
                .setPositiveButton("Turn on", (d, w) -> {
                    RemoteService.setPreviewAllowed(this, true);
                    render();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showDiagnostics() {
        String report = Diagnostics.report(this);
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Diagnostics")
                .setMessage(report + "\nA paired phone can copy this: Settings → Copy diagnostics.")
                .setPositiveButton("Close", null)
                .show();
    }

    /** Branding and credit: the app, its version, who made it, where the code is, and what it is built with. */
    private View about() {
        LinearLayout c = card();
        c.setPadding(dp(20), dp(18), dp(20), dp(18));
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_launcher);
        top.addView(icon, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setPadding(dp(14), 0, 0, 0);
        words.addView(text(getString(R.string.app_name) + "  " + version(), 17, TEXT, true));
        TextView by = text("Made by " + getString(R.string.author), 13, DIM, false);
        by.setPadding(0, dp(3), 0, 0);
        words.addView(by);
        top.addView(words);
        c.addView(top);
        String url = getString(R.string.project_url).trim();
        TextView lines = text("Free and open source (MIT)." + (url.isEmpty() ? "" : "  " + url.replaceFirst("^https?://", ""))
                + "\nWorks only on your own network: no account, no cloud, no ads, no tracking."
                + "\nBuilt with ZXing (Apache 2.0) and the Outfit typeface (SIL OFL).", 12, FAINT, false);
        lines.setLineSpacing(0, 1.3f);
        lines.setPadding(0, dp(12), 0, 0);
        c.addView(lines);
        aboutUpdate = text("", 13, GREEN, true);
        aboutUpdate.setPadding(0, dp(10), 0, 0);
        aboutUpdate.setVisibility(View.GONE);
        c.addView(aboutUpdate);
        return c;
    }

    /** A text box (the D-pad keyboard, or type it from a paired phone's Type on screen) for the device's name. */
    private void editName() {
        android.widget.EditText field = new android.widget.EditText(this);
        field.setSingleLine(true);
        field.setText(RemoteService.deviceName(this));
        field.setSelection(field.getText().length());
        field.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(40)});
        field.setHint("e.g. Living room");
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(22), dp(8), dp(22), 0);
        box.addView(field);
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this,
                android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Name this " + RemoteService.kind(this))
                .setMessage("Phones show this name. You can also type it from a paired phone.")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    if (!RemoteService.rename(this, field.getText().toString())) {
                        android.widget.Toast.makeText(this, "Use 1 to 40 letters or numbers.",
                                android.widget.Toast.LENGTH_SHORT).show();
                    }
                    render();
                })
                .setNegativeButton("Cancel", null)
                .create();
        field.setOnEditorActionListener((v, action, event) -> {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
            return true;
        });
        dialog.show();
    }

    // ---- actions ---------------------------------------------------------------------------------------------------

    /**
     * Before the system's Accessibility screen: what switching the remote on allows, in plain words (the "prominent
     * disclosure" Google Play asks of apps that use an accessibility service).
     */
    private void disclose() {
        String kind = RemoteService.kind(this);
        String what = getString(R.string.app_name) + " uses Android's accessibility service to act as a remote control"
                + " for phones you pair with a PIN.\n\n"
                + "It can: press Back, Home, the arrows and OK; change the volume; play and pause; type into the text box"
                + " you selected; open apps; put the " + kind + " to sleep.\n\n"
                + "To do that it looks at which app is on screen and where its buttons are. It does not record the"
                + " screen, read your messages or passwords, or send anything you see anywhere. Nothing leaves your"
                + " home network, and only paired phones can use it.\n\n"
                + "Next, Android's Accessibility settings open: choose " + getString(R.string.app_name)
                + " and switch it on. You can switch it off there at any time, or remove phones on this screen.";
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Use this " + kind + " with your phone")
                .setMessage(what)
                .setPositiveButton("Agree and continue", (d, w) -> openAccessibility())
                .setNegativeButton("Not now", null)
                .show();
    }

    private void openAccessibility() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (RuntimeException none) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (RuntimeException ignored) { }
        }
    }

    private void openDeveloperOptions() {
        Intent standard = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
        Intent tvSettings = new Intent().setClassName("com.android.tv.settings",
                "com.android.tv.settings.system.development.DevelopmentActivity");
        for (Intent i : new Intent[] {standard, tvSettings}) {
            try {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (RuntimeException notHere) {
                // next
            }
        }
        android.widget.Toast.makeText(this, "This device does not let apps open Developer options.",
                android.widget.Toast.LENGTH_LONG).show();
    }

    // ---- building blocks -------------------------------------------------------------------------------------------

    /**
     * Focusable, selectable at once (even before the first key press), and OK or Enter presses it. Some firmwares do
     * not turn the remote's OK (DPAD_CENTER) into a click for focusable-in-touch-mode views, so it is handled here.
     */
    private void focusable(View v, String tag, Runnable action) {
        v.setTag(tag);
        v.setFocusable(true);
        v.setFocusableInTouchMode(true);
        v.setOnClickListener(view -> action.run());
        v.setOnKeyListener((view, code, event) -> {
            boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                    || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
            if (!ok) return false;
            if (event.getAction() == KeyEvent.ACTION_UP) view.performClick();
            return true;
        });
    }

    /** The screen's ground: near black with a soft blue-violet glow, like the launcher at night. */
    private android.graphics.drawable.Drawable backdrop() {
        GradientDrawable base = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {0xFF0B0D16, 0xFF07080D, 0xFF0A0B12});
        GradientDrawable glow = new GradientDrawable();
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glow.setColors(new int[] {0x332B3B8F, 0x00000000});
        glow.setGradientCenter(0.22f, 0.3f);
        glow.setGradientRadius(dp(520));
        GradientDrawable glow2 = new GradientDrawable();
        glow2.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glow2.setColors(new int[] {0x225A2D7A, 0x00000000});
        glow2.setGradientCenter(0.85f, 0.9f);
        glow2.setGradientRadius(dp(480));
        return new LayerDrawable(new android.graphics.drawable.Drawable[] {base, glow, glow2});
    }

    /** The launcher's glass: a faint white fill with a thin light rim. */
    private GradientDrawable glass(int radius) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0x1CFFFFFF, 0x0DFFFFFF});
        g.setCornerRadius(radius);
        g.setStroke(Math.max(1, dp(1)), 0x24FFFFFF);
        return g;
    }

    /** Selected: brighter glass and a clear white outline, readable from the sofa. */
    private GradientDrawable focusGlass(int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x2EFFFFFF);
        g.setCornerRadius(radius);
        g.setStroke(dp(2), 0xE6FFFFFF);
        return g;
    }

    private static GradientDrawable round(int color, int radius, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private static StateListDrawable states(android.graphics.drawable.Drawable normal,
                                            android.graphics.drawable.Drawable focused) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[] {android.R.attr.state_focused}, focused);
        s.addState(new int[] {}, normal);
        return s;
    }

    private TextView text(String value, int sp, int color, boolean medium) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setPadding(0, 0, 0, 0);
        try {
            t.setTypeface(getResources().getFont(medium ? R.font.outfit_medium : R.font.outfit_regular));
        } catch (RuntimeException missing) {
            t.setTypeface(medium ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
        return t;
    }

    private String version() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception unknown) {
            return "";
        }
    }
}
