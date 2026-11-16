package com.local.projremote;

import android.util.Log;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A deliberately small HTTP server for the phone remote: one page and a handful of POST endpoints, plain text
 * bodies, 4 KB limit, 5 s timeouts. Every endpoint except the page and pairing needs a paired phone's token.
 *
 * <p>Requests are signed with the phone's key (see Pairing): X-Key, X-Ctr and X-Sig headers. The token itself
 * crosses the network only when it is handed out (pairing, a Home Screen handoff) or handed back by /api/resume,
 * which reads it from an HttpOnly, SameSite=Strict cookie limited to that one path. That cookie keeps a phone
 * paired when the browser drops page storage (Safari does after 7 days without a visit).
 */
final class WebServer {
    private static final String TAG = "ProjRemote";
    private static final int MAX_BODY = 4096;
    private static final long PIN_REQUEST_GAP_MS = 10_000L;
    private static final String COOKIE = "pr";

    private final RemoteService service;
    private final Pairing pairing;
    private final int firstPort;
    private volatile int port;   // the port actually bound; 0 until then
    // A few workers and a short queue: a stuck or hostile client on the network cannot pile up threads.
    private final ExecutorService workers = new java.util.concurrent.ThreadPoolExecutor(4, 12, 30,
            java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.ArrayBlockingQueue<>(24));
    private volatile ServerSocket socket;
    private volatile boolean running;
    private long lastPinRequest;
    private byte[] page;
    private byte[] iconPng;

    WebServer(RemoteService service, Pairing pairing, int port) {
        this.service = service;
        this.pairing = pairing;
        this.firstPort = port;
    }

    /** The port the page is served on, 0 while none could be bound yet. */
    int port() {
        return port;
    }

    /** The page is cached with the device's kind word filled in: drop it when that changes. */
    synchronized void forgetPage() {
        page = null;
    }

    void start() {
        running = true;
        Thread accept = new Thread(this::acceptLoop, "remote-http");
        accept.setDaemon(true);
        accept.start();
    }

    void stop() {
        running = false;
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) { }
        workers.shutdownNow();
    }

    private void acceptLoop() {
        while (running) {
            // The configured port, or the next free one after it when another app holds it.
            ServerSocket bound = null;
            for (int p = firstPort; p < firstPort + 10 && bound == null; p++) {
                try {
                    bound = new ServerSocket(p);
                } catch (IOException taken) {
                    Log.w(TAG, "port " + p + ": " + taken);
                }
            }
            if (bound == null) {
                try { Thread.sleep(5000); } catch (InterruptedException e) { return; }
                continue;
            }
            try (ServerSocket s = bound) {
                socket = s;
                port = s.getLocalPort();
                service.onServerReady();
                while (running) {
                    Socket client = s.accept();
                    try {
                        workers.execute(() -> handle(client));
                    } catch (java.util.concurrent.RejectedExecutionException busy) {
                        try { client.close(); } catch (IOException ignored) { }
                    }
                }
            } catch (IOException failure) {
                if (!running) return;
                Log.w(TAG, "server: " + failure + ", retrying");
                try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
            } finally {
                port = 0;
            }
        }
    }

    private void handle(Socket client) {
        try (Socket c = client) {
            c.setSoTimeout(5000);
            InputStream in = new BufferedInputStream(c.getInputStream());
            String requestLine = readLine(in);
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0];
            String target = parts[1];
            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
                if (headers.size() > 40) return;
            }
            int length = 0;
            try { length = Integer.parseInt(headers.getOrDefault("content-length", "0")); } catch (NumberFormatException ignored) { }
            if (length < 0 || length > MAX_BODY) {
                respond(c.getOutputStream(), 413, "text/plain", "too large");
                return;
            }
            byte[] bodyBytes = new byte[length];
            int read = 0;
            while (read < length) {
                int n = in.read(bodyBytes, read, length - read);
                if (n < 0) break;
                read += n;
            }
            String body = new String(bodyBytes, 0, read, StandardCharsets.UTF_8);
            route(c.getOutputStream(), method, target, headers, body);
        } catch (SocketException ignored) {
            // phone went away
        } catch (Exception failure) {
            Log.w(TAG, "request: " + failure);
        }
    }

    private void route(OutputStream out, String method, String target, Map<String, String> headers, String body) throws IOException {
        String path = target;
        String query = "";
        int q = target.indexOf('?');
        if (q >= 0) {
            path = target.substring(0, q);
            query = target.substring(q + 1);
        }
        // Only by address or a .local name: a web page elsewhere that points its own domain at this device (DNS
        // rebinding) is refused. And a POST must come from this page itself, not from another site open on the phone.
        String host = headers.getOrDefault("host", "").toLowerCase(Locale.ROOT);
        if (!allowedHost(host)) {
            respond(out, 403, "text/plain", "bad host");
            return;
        }
        String origin = headers.get("origin");
        if (method.equals("POST") && origin != null && !sameOrigin(origin, host)) {
            respond(out, 403, "text/plain", "cross-site");
            return;
        }
        if (method.equals("GET")) {
            switch (path) {
                case "/":
                case "/index.html":
                    respond(out, 200, "text/html; charset=utf-8", page());
                    return;
                case "/icon.png":
                    respond(out, 200, "image/png", icon(), "Cache-Control: max-age=86400\r\n");
                    return;
                case "/android.apk":
                    // The phone app (built from phone/ and carried in this APK's assets).
                    byte[] apk = phoneApk();
                    if (apk == null) respond(out, 404, "text/plain", "not in this build");
                    else respond(out, 200, "application/vnd.android.package-archive", apk,
                            "Content-Disposition: attachment; filename=\"phone-remote.apk\"\r\n");
                    return;
                case "/manifest.json":
                    respond(out, 200, "application/manifest+json", manifest());
                    return;
                default:
                    break;
            }
        }
        if (!method.equals("POST")) {
            respond(out, 404, "text/plain", "not found");
            return;
        }
        switch (path) {
            case "/api/pair/start": {
                if (!RemoteService.pairingAllowed(service)) {
                    respond(out, 423, "text/plain", "pairing is off");
                    return;
                }
                if (pairing.full(decode(param(body, "device")))) {
                    respond(out, 409, "text/plain", String.valueOf(pairing.limit()));
                    return;
                }
                synchronized (this) {
                    long now = System.currentTimeMillis();
                    if (now - lastPinRequest < PIN_REQUEST_GAP_MS) {
                        respond(out, 429, "text/plain", "wait");
                        return;
                    }
                    lastPinRequest = now;
                }
                service.showPin(pairing.newPin(), Pairing.pinMillis());
                respond(out, 200, "text/plain", "ok");
                return;
            }
            case "/api/pair": {
                // Body: secret=<QR code or PIN>&name=<phone name>, form-encoded.
                String name = decode(param(body, "name"));
                if (!RemoteService.pairingAllowed(service)) {
                    respond(out, 423, "text/plain", "pairing is off");
                    return;
                }
                if (pairing.full(decode(param(body, "device")))) {
                    respond(out, 409, "text/plain", String.valueOf(pairing.limit()));
                    return;
                }
                String token = pairing.pair(decode(param(body, "secret")), name, decode(param(body, "device")));
                if (token == null) {
                    respond(out, 403, "text/plain", "wrong or expired code");
                } else {
                    service.onPaired(name);
                    respond(out, 200, "text/plain", token, cookie(token));
                }
                return;
            }
            case "/api/handoff": {
                // A Home Screen icon's first launch: its single-use code becomes a token of the same phone.
                String token = pairing.redeem(body.trim());
                if (token == null) respond(out, 403, "text/plain", "used or expired");
                else respond(out, 200, "text/plain", token, cookie(token));
                return;
            }
            case "/api/hello":
                // The run's epoch, for signing. Public: it only makes old signatures useless.
                respond(out, 200, "text/plain", pairing.epoch);
                return;
            case "/api/resume": {
                // Page storage gone but the cookie is still here: hand the page its token back.
                String token = cookieToken(headers);
                if (token == null || pairing.check(token) == null) respond(out, 401, "text/plain", "not paired");
                else respond(out, 200, "text/plain", token, cookie(token)
                        // The first 1.1 builds kept the cookie on every path, so it went out with every request.
                        + "Set-Cookie: " + COOKIE + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict\r\n");
                return;
            }
            default:
                break;
        }
        String keyId = headers.get("x-key");
        Pairing.Phone phone = pairing.verify(keyId, headers.get("x-ctr"), headers.get("x-sig"), target, body);
        if (phone == null) {
            // The page retries once with this run's epoch and a counter past the last one seen; a key that is not
            // known at all means the phone was removed.
            respond(out, 401, "text/plain", "not paired", "X-Epoch: " + pairing.epoch + "\r\n"
                    + "X-Ctr: " + pairing.nextCounter(keyId) + "\r\n");
            return;
        }
        service.onPhoneSeen(phone);
        // Guests (owner's choice, see Pairing) steer and watch, but do not change the device or look at its screen.
        if (phone.guest() && (GUEST_DENIED.contains(path) || (path.equals("/api/key") && body.trim().equals("sleep")))) {
            respond(out, 403, "text/plain", "not for guests");
            return;
        }
        switch (path) {
            case "/api/state":
                respond(out, 200, "application/json", service.stateJson(phone));
                return;
            case "/api/unpair":
                // "Unpair this phone": forget it here too, and drop the cookie so it cannot resume.
                pairing.remove(phone.id);
                service.onPhonesChanged();
                respond(out, 200, "text/plain", "ok",
                        "Set-Cookie: " + COOKIE + "=; Path=/api/resume; Max-Age=0; HttpOnly; SameSite=Strict\r\n");
                return;
            case "/api/handoff/new":
                respond(out, 200, "text/plain", pairing.newHandoff(phone));
                return;
            case "/api/apps":
                respond(out, 200, "application/json", service.apps().listJson());
                return;
            case "/api/art": {
                byte[] jpeg = NowPlaying.art(service);
                if (jpeg == null) respond(out, 404, "text/plain", "no artwork");
                else respond(out, 200, "image/jpeg", jpeg);
                return;
            }
            case "/api/screen": {
                int w = parseInt(param(query, "w"));
                byte[] jpeg = service.screenshot(w > 0 ? w : 640);
                if (jpeg == null) respond(out, 409, "text/plain", "no preview");
                else respond(out, 200, "image/jpeg", jpeg);
                return;
            }
            case "/api/diag":
                respond(out, 200, "text/plain; charset=utf-8", Diagnostics.report(service));
                return;
            case "/api/icon": {
                byte[] png = service.apps().icon(param(query, "pkg"));
                if (png == null) respond(out, 404, "text/plain", "no icon");
                else respond(out, 200, "image/png", png);
                return;
            }
            default:
                break;
        }
        boolean ok;
        switch (path) {
            case "/api/launch": ok = service.apps().launch(body.trim()); break;
            case "/api/projector": ok = service.apps().openProjectorSettings(); break;
            case "/api/rename": ok = service.rename(body); break;
            case "/api/ping": ok = true; break;
            case "/api/key": {
                String k = body.trim();
                // A guest's volume stops at the cap.
                ok = phone.guest() && k.equals("volup") && service.atGuestVolumeCap() || service.key(k);
                break;
            }
            case "/api/link": ok = service.openLink(body); break;
            case "/api/sleeptimer": ok = service.setSleepTimer(parseInt(body)); break;
            case "/api/volume": ok = service.setVolume(parseInt(body), phone.guest()); break;
            case "/api/media": ok = NowPlaying.command(service, body.trim()); break;
            case "/api/seek": ok = NowPlaying.seek(service, parseInt(body) * 1000L); break;
            case "/api/text": ok = service.text(body); break;
            case "/api/enter": ok = service.enter(); break;
            case "/api/search": ok = service.search(param(query, "app"), body.trim()); break;
            default:
                respond(out, 404, "text/plain", "not found");
                return;
        }
        respond(out, ok ? 200 : 409, "text/plain", ok ? "ok" : "not possible here");
    }

    private static String cookie(String token) {
        return "Set-Cookie: " + COOKIE + "=" + token + "; Path=/api/resume; Max-Age=34560000; HttpOnly; SameSite=Strict\r\n";
    }

    private static String cookieToken(Map<String, String> headers) {
        String all = headers.get("cookie");
        if (all == null) return null;
        for (String part : all.split(";")) {
            String c = part.trim();
            if (c.startsWith(COOKIE + "=")) return c.substring(COOKIE.length() + 1);
        }
        return null;
    }

    private static boolean allowedHost(String host) {
        String h = host;
        int colon = h.lastIndexOf(':');
        if (colon > 0 && h.indexOf(']') < colon) h = h.substring(0, colon);
        return h.matches("\\d{1,3}(\\.\\d{1,3}){3}") || h.endsWith(".local") || h.equals("localhost");
    }

    private static boolean sameOrigin(String origin, String host) {
        String o = origin.toLowerCase(Locale.ROOT);
        int scheme = o.indexOf("://");
        if (scheme < 0) return false;   // "null" and other opaque origins
        String oh = o.substring(scheme + 3);
        // Default ports are left out of Origin; Host carries the port whenever it is not 80.
        return oh.equals(host) || (oh + ":80").equals(host) || oh.equals(host + ":80");
    }

    private String manifest() {
        try {
            String name = service.deviceName();
            return new org.json.JSONObject().put("name", name + " remote").put("short_name", name)
                    .put("start_url", "/").put("display", "standalone")
                    .put("background_color", "#141416").put("theme_color", "#141416")
                    .put("icons", new org.json.JSONArray().put(new org.json.JSONObject()
                            .put("src", "/icon.png").put("sizes", "192x192").put("type", "image/png")))
                    // Android, once the remote is on the Home screen: Share → this remote opens the link on the device.
                    .put("share_target", new org.json.JSONObject().put("action", "/").put("method", "GET")
                            .put("params", new org.json.JSONObject().put("title", "title").put("text", "text").put("url", "url")))
                    .toString();
        } catch (Exception impossible) {
            return "{}";
        }
    }

    /** The phone's Home Screen icon: the app's mark (res/drawable/ic_mark.xml) on its dark ground, drawn once. */
    private synchronized byte[] icon() {
        if (iconPng != null) return iconPng;
        int n = 192;
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(n, n, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(b);
        android.graphics.Paint ground = new android.graphics.Paint();
        ground.setShader(new android.graphics.LinearGradient(0, 0, 0, n, 0xFF26272B, 0xFF101012,
                android.graphics.Shader.TileMode.CLAMP));
        c.drawRect(0, 0, n, n, ground);
        android.graphics.drawable.Drawable mark = service.getDrawable(R.drawable.ic_mark);
        if (mark != null) {
            mark.setBounds(0, 0, n, n);
            mark.draw(c);
        }
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, png);
        b.recycle();
        iconPng = png.toByteArray();
        return iconPng;
    }

    private static String param(String query, String name) {
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) return pair.substring(eq + 1);
        }
        return "";
    }

    private static String decode(String value) {
        try {
            return java.net.URLDecoder.decode(value, "UTF-8");
        } catch (Exception bad) {
            return "";
        }
    }

    private byte[] phoneApkBytes;

    private synchronized byte[] phoneApk() {
        if (phoneApkBytes != null) return phoneApkBytes;
        try (InputStream in = service.getAssets().open("phone-remote.apk")) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[16384];
            int n;
            while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
            phoneApkBytes = buf.toByteArray();
        } catch (IOException missing) {
            return null;
        }
        return phoneApkBytes;
    }

    private synchronized byte[] page() throws IOException {
        if (page == null) {
            try (InputStream in = service.getResources().openRawResource(R.raw.remote)) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int n;
                while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
                // The page says "projector" or "TV", whichever this device is.
                String kind = service.kind();
                String html = new String(buf.toByteArray(), StandardCharsets.UTF_8)
                        .replace("{{app}}", service.getString(R.string.app_name))
                        .replace("{{version}}", versionName())
                        .replace("{{author}}", escapeHtml(service.getString(R.string.author)))
                        .replace("{{project}}", projectLink(service.getString(R.string.project_url)))
                        .replace("{{id}}", RemoteService.deviceId(service))
                        .replace("{{kind}}", kind)
                        .replace("{{Kind}}", Character.toUpperCase(kind.charAt(0)) + kind.substring(1));
                page = html.getBytes(StandardCharsets.UTF_8);
            }
        }
        return page;
    }

    private static void respond(OutputStream out, int status, String type, String body) throws IOException {
        respond(out, status, type, body.getBytes(StandardCharsets.UTF_8), "");
    }

    private static void respond(OutputStream out, int status, String type, String body, String extra) throws IOException {
        respond(out, status, type, body.getBytes(StandardCharsets.UTF_8), extra);
    }

    private static void respond(OutputStream out, int status, String type, byte[] body) throws IOException {
        respond(out, status, type, body, "");
    }

    private static void respond(OutputStream out, int status, String type, byte[] body, String extra) throws IOException {
        String head = "HTTP/1.1 " + status + " " + (status == 200 ? "OK" : "Error") + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + (extra.contains("Cache-Control") ? "" : "Cache-Control: no-store\r\n")
                + "X-Content-Type-Options: nosniff\r\n"
                + "Referrer-Policy: no-referrer\r\n"
                + extra
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder s = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') s.append((char) c);
            if (s.length() > 2048) return null;
        }
        return c < 0 && s.length() == 0 ? null : s.toString();
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** The project's page as a link, only for an http(s) address. */
    private static String projectLink(String url) {
        String u = url == null ? "" : url.trim();
        if (!u.matches("https?://[^\\s\"<>]+")) return "";
        String shown = u.replaceFirst("^https?://", "");
        return "<a href=\"" + escapeHtml(u) + "\" target=\"_blank\" rel=\"noopener\">" + escapeHtml(shown) + "</a>";
    }

    private String versionName() {
        try {
            return service.getPackageManager().getPackageInfo(service.getPackageName(), 0).versionName;
        } catch (Exception unknown) {
            return "";
        }
    }

    /** What a guest phone may not do: the device's settings, its name, sleep, the screen picture, the report. */
    private static final java.util.Set<String> GUEST_DENIED = new java.util.HashSet<>(java.util.Arrays.asList(
            "/api/projector", "/api/rename", "/api/sleeptimer", "/api/screen", "/api/diag"));

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (RuntimeException bad) {
            return -1;
        }
    }
}
