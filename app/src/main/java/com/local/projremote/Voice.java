package com.local.projremote;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spoken or typed commands ("open Netflix", "pause", "volume up", "search YouTube for lofi"), from Siri through an
 * iPhone shortcut (WebServer /api/do with a voice key) or from the phone page (/api/say). Answers with one short
 * sentence, which Siri reads out. The phone's own limits apply (guests, kids).
 */
final class Voice {
    private Voice() { }

    private static final Pattern SEARCH = Pattern.compile(
            "^(?:search|find|look up)(?: on)?(?: youtube)?(?: for)? (.+?)(?: on youtube)?$|^(?:play|put on) (.+?) on youtube$");
    private static final Pattern OPEN = Pattern.compile("^(?:open|launch|start|run|go to|show)(?: the)?(?: app)? (.+?)(?: app)?$");
    private static final Pattern VOLUME_TO = Pattern.compile("^(?:set )?(?:the )?volume(?: to)? (\\d{1,3})(?: percent| %|%)?$");

    static String run(RemoteService s, Pairing pairing, Pairing.Phone phone, String said) {
        String t = said == null ? "" : said.toLowerCase(Locale.ROOT).trim()
                .replaceAll("[.!?,]+$", "").replaceAll("^(please|hey|ok|okay)\\s+", "").replaceAll("\\s+please$", "")
                .replaceAll("\\s+", " ");
        if (t.isEmpty()) return "Say what the " + s.kind() + " should do, like open YouTube or pause.";
        String kind = s.kind();

        // Playback
        if (t.matches("(pause|stop|hold on|wait)( (it|the video|this|playback|music))?")) return media(s, "pause", "Paused.");
        if (t.matches("(play|resume|continue|unpause)( (it|the video|this|playback|music))?")) return media(s, "play", "Playing.");
        if (t.matches("(next|skip)( (one|video|song|episode|track))?")) return media(s, "next", "Next.");
        if (t.matches("(previous|go back one|last)( (one|video|song|episode|track))?")) return media(s, "previous", "Previous.");
        if (t.matches("(skip|fast)? ?forward( 10 seconds)?")) return media(s, "fwd", "Forward 10 seconds.");
        if (t.matches("(rewind|back 10 seconds|go back 10 seconds)")) return media(s, "back", "Back 10 seconds.");

        // Volume
        if (t.matches("(un ?mute|sound on)( it| the sound)?")) return s.key("mute") ? "Sound on." : "Couldn't unmute.";
        if (t.matches("(mute|silence|sound off|quiet)( it| the sound)?")) return s.key("mute") ? "Muted." : "Couldn't mute.";
        if (t.matches("(volume up|louder|turn (it )?up|increase (the )?volume)( a lot| more)?")) {
            int steps = t.endsWith("a lot") || t.endsWith("more") ? 4 : 2;
            for (int i = 0; i < steps; i++) if (phone.limited() && s.atGuestVolumeCap() || !s.key("volup")) break;
            return "Louder.";
        }
        if (t.matches("(volume down|quieter|softer|turn (it )?down|decrease (the )?volume|lower (the )?volume)( a lot| more)?")) {
            int steps = t.endsWith("a lot") || t.endsWith("more") ? 4 : 2;
            for (int i = 0; i < steps; i++) s.key("voldown");
            return "Quieter.";
        }
        Matcher v = VOLUME_TO.matcher(t);
        if (v.matches()) {
            int percent = Math.min(100, Integer.parseInt(v.group(1)));
            int max = s.volumeMax();
            return max > 0 && s.setVolume(Math.round(max * percent / 100f), phone.limited())
                    ? "Volume " + percent + " percent." : "Couldn't set the volume.";
        }

        // Navigation
        if (t.matches("(go )?home|home screen")) return s.key("home") ? "Home." : "Couldn't go home.";
        if (t.matches("(go )?back")) return s.key("back") ? "Back." : "Couldn't go back.";

        // Focus
        if (t.matches("(auto ?focus|focus|fix (the )?focus|sharpen( the picture)?)")) {
            if (!Focus.supported(s)) return "This " + kind + " has no focus control.";
            return s.key("autofocus") ? "Focusing." : "Couldn't focus.";
        }

        // Screen and power (not for guests and kids)
        if (t.matches("(turn |switch )?(the )?(screen|projector|tv|display) off|turn off (the )?(screen|display)|go to sleep|sleep")) {
            if (phone.limited()) return "Only family phones can do that.";
            return s.key("sleep") ? "Screen off. Good night." : "This " + kind + " can't sleep from the phone.";
        }
        if (t.matches("(power off|shut down|switch off|turn off)( the (projector|tv|device))?")) {
            if (phone.limited()) return "Only family phones can do that.";
            return DeviceTools.powerOff(s) ? "Switching off." : "Couldn't switch it off.";
        }

        // YouTube search
        Matcher m = SEARCH.matcher(t);
        if (m.matches()) {
            String q = m.group(1) != null ? m.group(1) : m.group(2);
            if (phone.kid && pairing.kidApps().stream().noneMatch(p -> p.contains("youtube"))) return "YouTube isn't one of the kids apps.";
            return s.search("youtube", q) ? "Searching YouTube for " + q + "." : "YouTube isn't on this " + kind + ".";
        }

        // Apps: "open Netflix", or just "Netflix"
        Matcher o = OPEN.matcher(t);
        String wanted = o.matches() ? o.group(1) : t;
        String pkg = findApp(s, wanted, phone.kid ? pairing.kidApps() : null);
        if (pkg != null) {
            return s.apps().launch(pkg) ? "Opening " + s.apps().label(pkg) + "." : "Couldn't open " + s.apps().label(pkg) + ".";
        }
        if (o.matches()) return "I can't find " + wanted + " on this " + kind + ".";
        return "Sorry, I didn't get that. Try open YouTube, pause, volume up, or search YouTube for something.";
    }

    private static String media(RemoteService s, String what, String done) {
        if (NowPlaying.command(s, what)) return done;
        // No media access: the play/pause key still works for most players.
        if (what.equals("pause") || what.equals("play")) return s.key("playpause") ? done : "Nothing is playing.";
        return "Nothing is playing.";
    }

    /** The installed app whose name best matches what was said; null when none does. */
    private static String findApp(RemoteService s, String said, java.util.Set<String> allowed) {
        String w = said.replace("hot star", "hotstar").replace("you tube", "youtube").replace("amazon prime", "prime video")
                .replace("prime", "prime video").replace("prime video video", "prime video").trim();
        if (w.isEmpty()) return null;
        String best = null;
        int bestScore = 0;
        try {
            JSONArray apps = new JSONArray(s.apps().listJson(allowed));
            for (int i = 0; i < apps.length(); i++) {
                JSONObject a = apps.getJSONObject(i);
                String label = a.optString("label").toLowerCase(Locale.ROOT);
                String compact = label.replace(" ", "");
                int score = label.equals(w) || compact.equals(w.replace(" ", "")) ? 100
                        : label.startsWith(w) ? 80
                        : compact.contains(w.replace(" ", "")) ? 60
                        : w.contains(label) && label.length() >= 4 ? 50 : 0;
                if (score > bestScore) { bestScore = score; best = a.optString("pkg"); }
            }
        } catch (Exception ignored) { }
        return best;
    }
}
