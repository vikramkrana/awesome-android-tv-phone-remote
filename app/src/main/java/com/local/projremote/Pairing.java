package com.local.projremote;

import android.content.Context;
import android.content.SharedPreferences;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONObject;

/**
 * Phone pairing, for any number of phones. The only way in is a 6-digit PIN shown on the projector screen on
 * request (the Home QR just opens the page and asks for one): valid two minutes, five wrong guesses and it is
 * gone, at most twenty failed attempts an hour in total.
 *
 * <p>What makes a phone "one phone": a web page cannot read any hardware identity, so each browser keeps two
 * random values of its own: a device id (sent when pairing, so that browser pairing again replaces its old entry)
 * and the token it gets from pairing (sent with every request). The projector keeps only the token's SHA-256 hash,
 * the device id, the phone's name (made unique: "iPhone", "iPhone 2"), and when it was paired and last used.
 *
 * <p>One phone can hold more than one token: on iPhone a Home Screen icon has storage of its own, separate from
 * Safari. So a paired page can ask for a single-use handoff code, put it in its address just while the person adds
 * it to the Home Screen, and the icon trades that code for a token of its own on first launch, without a PIN.
 * Handoff codes last 30 days and die on use; removing the phone removes all of its tokens.
 *
 * <p>Requests are signed, so the token itself never crosses the network after pairing. From its token T the page
 * derives K = sha256(T) (what this class stores) and a public key id P = sha256("pub:" + K), first 32 hex. Each
 * request carries P, a counter and HMAC-SHA256(K, epoch + "\n" + counter + "\n" + path + "\n" + body). The epoch
 * is a random value made at every start of the remote, so a request recorded before a restart is useless after
 * it; within one epoch each counter is accepted once. Counters only need to grow on the phone's side: no clock
 * agreement between phone and device is needed.
 */
final class Pairing {
    private static final long PIN_MS = 120_000L;
    private static final int MAX_TRIES_PER_PIN = 5;
    private static final int MAX_FAILURES_PER_HOUR = 20;
    /** A phone whose page checked in within this time counts as connected now. */
    static final long CONNECTED_MS = 90_000L;
    private static final int MAX_PHONES = 20;
    /** The choices for the owner's phone limit, on the device's screen. */
    static final int[] LIMITS = {1, 2, 3, 4, 5, 10, MAX_PHONES};
    private final int defaultLimit;
    private static final long HANDOFF_MS = 30L * 24 * 3_600_000L;
    /** A guest phone is removed by itself this long after it paired. */
    static final long GUEST_MS = 24L * 3_600_000L;

    /** One paired phone. */
    static final class Phone {
        final String id;          // hash of its first token
        final List<String> keys = new ArrayList<>();   // hashes of all its tokens (Safari, Home Screen icon...)
        final String name;
        final String device;      // the browser's own random id; "" for phones paired before it existed
        final long pairedAt;
        long lastSeen;
        String handoff = "";      // hash of an unused handoff code
        long handoffUntil;
        /** A guest: fewer rights (see WebServer.GUEST_DENIED), and gone after GUEST_MS. 0: not a guest. */
        long guestUntil;
        /** A kids phone: a guest's limits without the time limit, and only the apps in kidApps(). */
        boolean kid;

        Phone(String id, String name, String device, long pairedAt, long lastSeen) {
            this.id = id;
            this.name = name;
            this.device = device;
            this.pairedAt = pairedAt;
            this.lastSeen = lastSeen;
            keys.add(id);
        }

        boolean connected() {
            return System.currentTimeMillis() - lastSeen < CONNECTED_MS;
        }

        boolean guest() {
            return guestUntil > 0;
        }

        /** Guest or kid: the owner's restrictions apply (WebServer.GUEST_DENIED, volume cap, no screen). */
        boolean limited() {
            return guestUntil > 0 || kid;
        }

        boolean expired() {
            return guestUntil > 0 && System.currentTimeMillis() > guestUntil;
        }
    }

    private final SharedPreferences prefs;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Phone> phones = new ConcurrentHashMap<>();   // by id
    private final Map<String, Phone> byKey = new ConcurrentHashMap<>();    // by token hash
    private final Map<String, String> pubToKey = new ConcurrentHashMap<>(); // public key id -> token hash
    /** Changes at every start: signatures made for an earlier run do not verify. */
    final String epoch;
    /** Per key: the highest counter seen and the counters seen within the window below it. */
    private final Map<String, java.util.TreeSet<Long>> seen = new ConcurrentHashMap<>();
    private static final long WINDOW = 120_000_000L;   // counters are microseconds on the phone: two minutes
    private String pin;
    private long pinUntil;
    private int pinTries;
    private long hourStart;
    private int hourFailures;
    private long lastSaved;

    private static Pairing shared;

    /** One instance for the service and the app's own screen, so both see the same phones and codes. */
    static synchronized Pairing get(Context context) {
        if (shared == null) shared = new Pairing(context.getApplicationContext());
        return shared;
    }

    private Pairing(Context context) {
        prefs = context.getSharedPreferences("pairing", Context.MODE_PRIVATE);
        defaultLimit = context.getResources().getInteger(R.integer.default_phone_limit);
        epoch = hex(randomBytes(12));
        load();
    }

    // ---- ways in ----------------------------------------------------------------------------------------------

    synchronized String newPin() {
        pin = String.format("%06d", random.nextInt(1_000_000));
        pinUntil = System.currentTimeMillis() + PIN_MS;
        pinTries = 0;
        return pin;
    }

    static long pinMillis() {
        return PIN_MS;
    }

    /**
     * typed is the PIN; name is what the phone calls itself; device is the browser's random id. Returns the new
     * phone's token, or null.
     */
    synchronized String pair(String typed, String name, String device) {
        long now = System.currentTimeMillis();
        if (now - hourStart > 3_600_000L) {
            hourStart = now;
            hourFailures = 0;
        }
        if (hourFailures >= MAX_FAILURES_PER_HOUR || typed == null) return null;
        typed = typed.trim();
        boolean ok = false;
        if (pin != null && now <= pinUntil) {
            ok = MessageDigest.isEqual(pin.getBytes(), typed.getBytes());
            if (ok) pin = null;
            else if (++pinTries >= MAX_TRIES_PER_PIN) pin = null;
        }
        if (!ok) {
            hourFailures++;
            return null;
        }
        String token = hex(randomBytes(24));
        String id = hash(token);
        String dev = device != null && device.matches("[0-9a-f]{32}") ? device : "";
        // The same browser pairing again (e.g. after the projector's address changed) replaces its old entry.
        if (!dev.isEmpty()) {
            for (Phone old : new ArrayList<>(phones.values())) if (old.device.equals(dev)) drop(old);
        }
        // Full: refused, never by pushing out a phone that is already paired.
        expireGuests();
        if (phones.size() >= limit()) return null;
        Phone phone = new Phone(id, uniqueName(cleanName(name)), dev, now, now);
        if (guestsByDefault()) phone.guestUntil = now + GUEST_MS;
        phones.put(id, phone);
        register(id, phone);
        save();
        return token;
    }

    /** The owner's choice on the device: new phones join as guests (24 hours, fewer rights) instead of family. */
    synchronized boolean guestsByDefault() {
        return prefs.getBoolean("guests", false);
    }

    synchronized void setGuestsByDefault(boolean guests) {
        prefs.edit().putBoolean("guests", guests).apply();
    }

    /** Makes a paired phone a guest (from now for GUEST_MS) or family. */
    synchronized void setGuest(String id, boolean guest) {
        Phone phone = phones.get(id);
        if (phone == null) return;
        phone.guestUntil = guest ? System.currentTimeMillis() + GUEST_MS : 0;
        save();
    }

    /** Makes a paired phone a kids phone (or back to family). Not a guest any more either way. */
    synchronized void setKid(String id, boolean kid) {
        Phone phone = phones.get(id);
        if (phone == null) return;
        phone.kid = kid;
        phone.guestUntil = 0;
        save();
    }

    /** The apps kids phones may open (package names), chosen by a family phone. */
    synchronized java.util.Set<String> kidApps() {
        return new java.util.TreeSet<>(prefs.getStringSet("kidApps", java.util.Collections.emptySet()));
    }

    synchronized void setKidApp(String pkg, boolean allowed) {
        java.util.Set<String> apps = kidApps();
        if (allowed) apps.add(pkg); else apps.remove(pkg);
        prefs.edit().putStringSet("kidApps", apps).apply();
    }

    /** Removes guests whose time is up. True when one was removed. */
    synchronized boolean expireGuests() {
        boolean any = false;
        for (Phone p : new ArrayList<>(phones.values())) {
            if (p.expired()) {
                drop(p);
                any = true;
            }
        }
        if (any) save();
        return any;
    }

    /** How many phones may be paired at once; the owner sets it on the device's screen. */
    synchronized int limit() {
        int n = prefs.getInt("limit", defaultLimit);
        return Math.max(1, Math.min(MAX_PHONES, n));
    }

    synchronized void setLimit(int n) {
        prefs.edit().putInt("limit", Math.max(1, Math.min(MAX_PHONES, n))).apply();
    }

    /**
     * True when no new phone can pair. A browser that is already paired (same device id) may always pair again: it
     * replaces its own entry.
     */
    synchronized boolean full(String device) {
        expireGuests();
        if (device != null && device.matches("[0-9a-f]{32}")) {
            for (Phone p : phones.values()) if (p.device.equals(device)) return false;
        }
        return phones.size() >= limit();
    }

    /** A new single-use code for this phone's Home Screen icon (replaces any unused one). */
    synchronized String newHandoff(Phone phone) {
        String code = hex(randomBytes(24));
        phone.handoff = hash(code);
        phone.handoffUntil = System.currentTimeMillis() + HANDOFF_MS;
        save();
        return code;
    }

    /** Trades a handoff code for a new token of the same phone; null if unknown, used or expired. */
    synchronized String redeem(String code) {
        long now = System.currentTimeMillis();
        if (now - hourStart > 3_600_000L) {
            hourStart = now;
            hourFailures = 0;
        }
        if (hourFailures >= MAX_FAILURES_PER_HOUR || code == null || code.length() != 48) return null;
        String h = hash(code);
        for (Phone phone : phones.values()) {
            if (!phone.handoff.isEmpty() && MessageDigest.isEqual(phone.handoff.getBytes(), h.getBytes())
                    && now <= phone.handoffUntil) {
                phone.handoff = "";
                phone.handoffUntil = 0;
                String token = hex(randomBytes(24));
                String key = hash(token);
                phone.keys.add(key);
                register(key, phone);
                phone.lastSeen = now;
                save();
                return token;
            }
        }
        hourFailures++;
        return null;
    }

    // ---- paired phones ------------------------------------------------------------------------------------------

    /** The phone this token belongs to, marked as seen now; null if not paired. */
    private void register(String key, Phone phone) {
        byKey.put(key, phone);
        pubToKey.put(publicId(key), key);
    }

    static String publicId(String key) {
        return hash("pub:" + key).substring(0, 32);
    }

    /**
     * The phone that signed this request, marked as seen now; null if the key id is unknown, the signature is
     * wrong, or the counter was already used (a replay) or is too far behind.
     */
    Phone verify(String keyId, String counter, String signature, String path, String body) {
        if (keyId == null || counter == null || signature == null) return null;
        String key = pubToKey.get(keyId);
        if (key == null) return null;
        long ctr;
        try {
            ctr = Long.parseLong(counter);
        } catch (NumberFormatException bad) {
            return null;
        }
        String expected = hmac(key, epoch + "\n" + counter + "\n" + path + "\n" + body);
        if (!MessageDigest.isEqual(expected.getBytes(), signature.toLowerCase(java.util.Locale.ROOT).getBytes())) return null;
        java.util.TreeSet<Long> used = seen.computeIfAbsent(key, k -> new java.util.TreeSet<>());
        synchronized (used) {
            long max = used.isEmpty() ? Long.MIN_VALUE : used.last();
            if (used.contains(ctr) || (max != Long.MIN_VALUE && ctr < max - WINDOW)) return null;
            used.add(ctr);
            long floor = used.last() - WINDOW;
            while (!used.isEmpty() && used.first() < floor) used.pollFirst();
        }
        Phone phone = byKey.get(key);
        if (phone == null) return null;
        if (phone.expired()) {
            expireGuests();
            return null;
        }
        long now = System.currentTimeMillis();
        phone.lastSeen = now;
        if (now - lastSaved > 60_000L) save();
        return phone;
    }

    /** The lowest counter this key may use next: the phone moves past it after its clock went backwards. */
    long nextCounter(String keyId) {
        String key = keyId == null ? null : pubToKey.get(keyId);
        java.util.TreeSet<Long> used = key == null ? null : seen.get(key);
        if (used == null) return 0;
        synchronized (used) {
            return used.isEmpty() ? 0 : used.last() + 1;
        }
    }

    private static String hmac(String key, String message) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(key.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            return hex(mac.doFinal(message.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Only for /api/resume, the one place the token itself is still accepted (from the restricted cookie). */
    Phone check(String token) {
        if (token == null || token.length() != 48) return null;
        Phone phone = byKey.get(hash(token));
        if (phone == null) return null;
        if (phone.expired()) {
            expireGuests();
            return null;
        }
        long now = System.currentTimeMillis();
        phone.lastSeen = now;
        if (now - lastSaved > 60_000L) save();   // "last used" survives a restart, without a write per key press
        return phone;
    }

    List<Phone> phones() {
        expireGuests();
        List<Phone> list = new ArrayList<>(phones.values());
        list.sort((a, b) -> Long.compare(b.lastSeen, a.lastSeen));
        return list;
    }

    synchronized void remove(String id) {
        Phone phone = phones.get(id);
        if (phone != null) drop(phone);
        save();
    }

    synchronized void removeAll() {
        phones.clear();
        byKey.clear();
        pubToKey.clear();
        seen.clear();
        save();
    }

    private void drop(Phone phone) {
        phones.remove(phone.id);
        for (String key : phone.keys) {
            byKey.remove(key);
            pubToKey.values().remove(key);
            seen.remove(key);
        }
    }

    /** "iPhone" stays "iPhone" unless another phone has it: then "iPhone 2", "iPhone 3"... */
    private String uniqueName(String base) {
        java.util.Set<String> taken = new java.util.HashSet<>();
        for (Phone p : phones.values()) taken.add(p.name.toLowerCase(java.util.Locale.ROOT));
        if (!taken.contains(base.toLowerCase(java.util.Locale.ROOT))) return base;
        for (int n = 2; ; n++) {
            String candidate = base + " " + n;
            if (!taken.contains(candidate.toLowerCase(java.util.Locale.ROOT))) return candidate;
        }
    }

    private static String cleanName(String name) {
        if (name == null) return "Phone";
        String n = name.replaceAll("[\\p{Cntrl}<>\"&]", "").trim();
        if (n.isEmpty()) return "Phone";
        return n.length() > 30 ? n.substring(0, 30) : n;
    }

    // ---- storage ------------------------------------------------------------------------------------------------

    private void load() {
        try {
            JSONObject all = new JSONObject(prefs.getString("phones", "{}"));
            for (Iterator<String> it = all.keys(); it.hasNext(); ) {
                String id = it.next();
                JSONObject p = all.getJSONObject(id);
                Phone phone = new Phone(id, p.optString("name", "Phone"), p.optString("device", ""),
                        p.optLong("paired"), p.optLong("seen"));
                org.json.JSONArray more = p.optJSONArray("keys");
                for (int i = 0; more != null && i < more.length(); i++) {
                    String key = more.optString(i);
                    if (!key.isEmpty() && !phone.keys.contains(key)) phone.keys.add(key);
                }
                phone.handoff = p.optString("handoff", "");
                phone.handoffUntil = p.optLong("handoffUntil");
                phone.guestUntil = p.optLong("guestUntil");
                phone.kid = p.optBoolean("kid");
                phones.put(id, phone);
                for (String key : phone.keys) register(key, phone);
            }
        } catch (Exception ignored) {
            // nothing paired yet, or unreadable: start empty
        }
    }

    private synchronized void save() {
        try {
            JSONObject all = new JSONObject();
            for (Phone p : phones.values()) {
                all.put(p.id, new JSONObject().put("name", p.name).put("device", p.device)
                        .put("paired", p.pairedAt).put("seen", p.lastSeen)
                        .put("keys", new org.json.JSONArray(p.keys))
                        .put("handoff", p.handoff).put("handoffUntil", p.handoffUntil)
                        .put("guestUntil", p.guestUntil).put("kid", p.kid));
            }
            prefs.edit().putString("phones", all.toString()).apply();
            lastSaved = System.currentTimeMillis();
        } catch (Exception ignored) { }
    }

    private byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        random.nextBytes(b);
        return b;
    }

    private static String hash(String token) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(token.getBytes()));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder s = new StringBuilder();
        for (byte b : bytes) s.append(String.format("%02x", b));
        return s.toString();
    }
}
