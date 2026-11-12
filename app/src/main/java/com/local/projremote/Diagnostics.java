package com.local.projremote;

import android.content.Context;
import android.os.Build;

/**
 * A plain-text report for bug reports: the device, Android, the network address, what the remote can do here and
 * how it is set up. Nothing about the phones beyond how many there are, no tokens, no names, nothing on screen.
 */
final class Diagnostics {
    private Diagnostics() { }

    static String report(Context c) {
        RemoteService s = RemoteService.instance;
        StringBuilder r = new StringBuilder();
        line(r, "App", c.getString(R.string.app_name) + " " + Updates.version(c) + " (" + c.getPackageName() + ")");
        line(r, "Device", Build.MANUFACTURER + " " + Build.MODEL + " (" + Build.DEVICE + ", " + Build.HARDWARE + ")");
        line(r, "Android", Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")");
        java.net.Inet4Address ip = NetUtil.ipv4(c);
        line(r, "Address", ip == null ? "none" : ip.getHostAddress());
        line(r, "Remote", s == null ? "off" : "on, port " + (s.serverPort() > 0 ? s.serverPort() : "not bound"));
        line(r, ".local name", RemoteService.localName(c));
        line(r, "Arrows", Build.VERSION.SDK_INT >= 33 ? "key presses (Android 13+)" : "focus moves (Android 8 to 12)");
        line(r, "Mouse mode now", s == null ? "unknown" : String.valueOf(s.pointerMode()));
        line(r, "Self switch-on", SelfEnable.allowed(c) ? "granted" : "not granted");
        line(r, "Now playing", NowPlaying.allowed(c) ? "allowed" : "not allowed");
        line(r, "Screen preview", RemoteService.previewAllowed(c) ? (Build.VERSION.SDK_INT >= 30 ? "on" : "on, but needs Android 11+") : "off");
        line(r, "Home screens", s == null ? "unknown" : String.valueOf(s.homes()));
        line(r, "Kind", RemoteService.kind(c));
        Pairing p = Pairing.get(c);
        line(r, "Phones", p.phones().size() + " of " + p.limit() + (RemoteService.pairingAllowed(c) ? "" : ", new phones off")
                + (p.guestsByDefault() ? ", new phones join as guests" : ""));
        line(r, "Other devices", s == null ? "unknown" : String.valueOf(s.peerCount()));
        line(r, "Update check", Updates.enabled(c) ? "on" : "off");
        return r.toString();
    }

    private static void line(StringBuilder r, String label, String value) {
        r.append(label).append(": ").append(value).append('\n');
    }
}
