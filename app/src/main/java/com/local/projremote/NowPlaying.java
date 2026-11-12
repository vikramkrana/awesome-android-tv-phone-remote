package com.local.projremote;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.provider.Settings;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.json.JSONObject;

/**
 * What is playing on the device, for the phone's Now playing card: title, artist, app, artwork, position, and
 * seeking. It reads the media sessions apps publish for their own media controls; that needs this app's
 * notification listener to be allowed (the owner switches it on, on the device's screen).
 */
final class NowPlaying {
    private NowPlaying() { }

    static ComponentName listener(Context context) {
        return new ComponentName(context, MediaListener.class);
    }

    /** Whether Android lets this app see media sessions (notification access granted). */
    static boolean allowed(Context context) {
        try {
            android.app.NotificationManager nm = context.getSystemService(android.app.NotificationManager.class);
            return nm != null && nm.isNotificationListenerAccessGranted(listener(context));
        } catch (RuntimeException unknown) {
            return false;
        }
    }

    /** The ADB command that grants it, for devices that hide Android's Notification access screen. */
    static String adbGrant(Context context) {
        return "adb shell cmd notification allow_listener " + listener(context).flattenToString();
    }

    /** The session that is playing, else the last one with something loaded; null when none or not allowed. */
    static MediaController controller(Context context) {
        if (!allowed(context)) return null;
        try {
            MediaSessionManager sessions = context.getSystemService(MediaSessionManager.class);
            List<MediaController> list = sessions.getActiveSessions(listener(context));
            MediaController loaded = null;
            for (MediaController c : list) {
                PlaybackState s = c.getPlaybackState();
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING) return c;
                if (loaded == null && c.getMetadata() != null) loaded = c;
            }
            return loaded;
        } catch (RuntimeException notAllowed) {
            return null;
        }
    }

    /** {"title","artist","pkg","app","dur","pos","playing","art"} in milliseconds, or null when nothing plays. */
    static JSONObject json(Context context, Apps apps) {
        MediaController c = controller(context);
        if (c == null) return null;
        try {
            MediaMetadata m = c.getMetadata();
            PlaybackState s = c.getPlaybackState();
            String title = m == null ? "" : first(m, MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            if (title.isEmpty() && s == null) return null;
            String artist = m == null ? "" : first(m, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
                    MediaMetadata.METADATA_KEY_ALBUM_ARTIST, MediaMetadata.METADATA_KEY_ALBUM);
            long duration = m == null ? 0 : Math.max(0, m.getLong(MediaMetadata.METADATA_KEY_DURATION));
            boolean playing = s != null && s.getState() == PlaybackState.STATE_PLAYING;
            long pos = position(s);
            if (duration > 0) pos = Math.min(pos, duration);
            boolean art = artwork(m) != null;
            return new JSONObject().put("title", title).put("artist", artist).put("pkg", c.getPackageName())
                    .put("app", apps.label(c.getPackageName())).put("dur", duration).put("pos", pos)
                    .put("playing", playing).put("canSeek", s != null && (s.getActions() & PlaybackState.ACTION_SEEK_TO) != 0)
                    .put("art", art ? Integer.toHexString((title + duration + c.getPackageName()).hashCode()) : "");
        } catch (Exception broken) {
            return null;
        }
    }

    private static long position(PlaybackState s) {
        if (s == null || s.getPosition() < 0) return 0;
        long pos = s.getPosition();
        if (s.getState() == PlaybackState.STATE_PLAYING && s.getLastPositionUpdateTime() > 0) {
            pos += (long) ((SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * s.getPlaybackSpeed());
        }
        return Math.max(0, pos);
    }

    private static String first(MediaMetadata m, String... keys) {
        for (String k : keys) {
            CharSequence v = m.getText(k);
            if (v != null && v.toString().trim().length() > 0) return v.toString().trim();
        }
        return "";
    }

    private static Bitmap artwork(MediaMetadata m) {
        if (m == null) return null;
        for (String k : new String[] {MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_ALBUM_ART,
                MediaMetadata.METADATA_KEY_DISPLAY_ICON}) {
            Bitmap b = m.getBitmap(k);
            if (b != null) return b;
        }
        return null;
    }

    /** The artwork as a JPEG of at most 360 px, or null. */
    static byte[] art(Context context) {
        MediaController c = controller(context);
        Bitmap b = c == null ? null : artwork(c.getMetadata());
        if (b == null) return null;
        try {
            float scale = Math.min(1f, 360f / Math.max(b.getWidth(), b.getHeight()));
            Bitmap small = scale < 1f ? Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * scale),
                    Math.round(b.getHeight() * scale), true) : b;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            small.compress(Bitmap.CompressFormat.JPEG, 82, out);
            return out.toByteArray();
        } catch (RuntimeException broken) {
            return null;
        }
    }

    /** play, pause, toggle, next, previous, fwd (+10 s), back (-10 s). */
    static boolean command(Context context, String what) {
        MediaController c = controller(context);
        if (c == null) return false;
        MediaController.TransportControls t = c.getTransportControls();
        PlaybackState s = c.getPlaybackState();
        switch (what) {
            case "play": t.play(); return true;
            case "pause": t.pause(); return true;
            case "toggle":
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING) t.pause(); else t.play();
                return true;
            case "next": t.skipToNext(); return true;
            case "previous": t.skipToPrevious(); return true;
            case "fwd": t.seekTo(position(s) + 10_000L); return true;
            case "back": t.seekTo(Math.max(0, position(s) - 10_000L)); return true;
            default: return false;
        }
    }

    static boolean seek(Context context, long ms) {
        MediaController c = controller(context);
        if (c == null || ms < 0) return false;
        c.getTransportControls().seekTo(ms);
        return true;
    }
}
