#!/usr/bin/env python3
"""Serve the phone page with a fake projector behind it.

For working on app/src/main/res/raw/remote.html without a device, and for taking the screenshots in docs/screens.

    python3 tools/demo_server.py            # http://localhost:8099/
    python3 tools/demo_server.py --kind tv  # the page as a TV sees it

Open http://localhost:8099/?demo=<scene> to start in a given screen:

    pair, pin, done, remote, touchpad, mouse, apps, edit, projector, settings, search, type, lost

Nothing here talks to a real device. PIN for the fake pairing: any six digits.
"""
import argparse
import json
import re
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

PAGE = Path(__file__).resolve().parent.parent / "app/src/main/res/raw/remote.html"
TOKEN = "d" * 48

APPS = [
    ("com.google.android.youtube.tv", "YouTube", "#FF0033"),
    ("com.netflix.ninja", "Netflix", "#E50914"),
    ("com.amazon.amazonvideo.livingroom", "Prime Video", "#1A98FF"),
    ("com.disney.disneyplus", "Disney+", "#0E2A8A"),
    ("com.spotify.tv.android", "Spotify", "#1DB954"),
    ("org.videolan.vlc", "VLC", "#FF8800"),
    ("com.plexapp.android", "Plex", "#E5A00D"),
    ("org.xbmc.kodi", "Kodi", "#17B2E7"),
    ("com.android.vending", "Play Store", "#34A853"),
    ("com.example.files", "Files", "#5B6CFF"),
    ("com.example.cast", "Screen cast", "#00B8A9"),
    ("com.example.media", "USB Media", "#8E5BFF"),
]

# Runs before the page's own script: every request carries the scene, so the fake projector can play along.
BEFORE = """<script>
(() => {
  const scene = new URLSearchParams(location.search).get("demo") || "";
  window.__scene = scene; window.__cut = false;
  const real = window.fetch;
  window.fetch = (url, opt = {}) => {
    if (window.__cut) return Promise.reject(new Error("offline"));
    opt.headers = Object.assign({}, opt.headers, { "X-Demo": scene });
    return real(url, opt);
  };
})();
</script>"""

# Runs after it: walks to the screen the scene asks for.
AFTER = """<script>
(() => {
  const scene = window.__scene, $ = (id) => document.getElementById(id);
  const click = (sel) => { const el = document.querySelector(sel); if (el) el.click(); };
  const later = (ms, f) => setTimeout(f, ms);
  const steps = {
    pin: () => { $("pin").value = "482"; $("pin").dispatchEvent(new Event("input")); },
    done: () => { $("pin").value = "482913"; $("pin").dispatchEvent(new Event("input")); click("#pairBtn"); },
    touchpad: () => click("#modeBtn"),
    apps: () => click("nav [data-tab=apps]"),
    edit: () => { click("nav [data-tab=apps]"); later(300, () => click("#editQuick")); },
    projector: () => click("nav [data-tab=proj]"),
    settings: () => click("nav [data-tab=settings]"),
    guest: () => click("nav [data-tab=settings]"),
    screens: () => setTimeout(() => click("#devPill"), 300),
    search: () => click("[data-sheet=search]"),
    type: () => click("[data-sheet=type]"),
    lost: () => { window.__cut = true; document.dispatchEvent(new Event("visibilitychange")); },
  };
  if (steps[scene]) later(900, steps[scene]);
})();
</script>"""


class Handler(BaseHTTPRequestHandler):
    kind = "projector"

    def log_message(self, *args):
        pass

    def send(self, status, ctype, body):
        data = body if isinstance(body, bytes) else body.encode()
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        path = urlparse(self.path).path
        if path in ("/", "/index.html"):
            kind = "TV" if self.kind == "tv" else "projector"
            html = PAGE.read_text(encoding="utf-8")
            html = html.replace("{{app}}", "Phone Remote").replace("{{kind}}", kind).replace("{{Kind}}", kind[0].upper() + kind[1:])
            html = html.replace("{{id}}", "demo0000").replace("{{version}}", "1.2").replace("{{author}}", "Vikram Rana").replace("{{project}}", "")
            html = html.replace("<script>", BEFORE + "<script>", 1).replace("</body>", AFTER + "</body>")
            return self.send(200, "text/html; charset=utf-8", html)
        if path == "/frame":
            # A phone-sized window onto the page, for screenshots: desktop browsers will not go that narrow.
            scene = (parse_qs(urlparse(self.path).query).get("demo") or [""])[0]
            scene = re.sub(r"[^a-z-]", "", scene)
            return self.send(200, "text/html", '<body style="margin:0;background:#141416"><iframe src="/?demo=' + scene
                             + '" style="border:0;width:390px;height:844px;display:block"></iframe>')
        if path == "/manifest.json":
            return self.send(200, "application/manifest+json", "{}")
        self.send(404, "text/plain", "not found")

    def do_POST(self):
        url = urlparse(self.path)
        path, query = url.path, parse_qs(url.query)
        scene = self.headers.get("X-Demo", "")
        self.rfile.read(int(self.headers.get("Content-Length") or 0))
        fresh = scene in ("pair", "pin", "done")   # scenes that start as a phone that is not paired yet

        if path == "/api/resume":
            return self.send(401, "text/plain", "not paired") if fresh else self.send(200, "text/plain", TOKEN)
        if path in ("/api/pair", "/api/handoff", "/api/handoff/new"):
            return self.send(200, "text/plain", TOKEN)
        if path == "/api/state":
            name = "TV" if self.kind == "tv" else "Projector"
            state = {"name": name, "phone": "iPhone", "pointer": scene == "mouse", "youtube": True, "focus": True, "tools": ["autocorrect", "keystone", "zoom", "inputs"]}
            if scene not in ("projector-home",):
                state["current"] = {"pkg": APPS[0][0], "label": APPS[0][1]}
            state.update({"vol": 9, "volMax": 15, "volCap": 15, "muted": False, "canSleep": True,
                          "sleepIn": 2580 if scene == "projector" else 0, "mediaAllowed": True,
                          "others": [{"name": "Bedroom TV", "url": "http://192.168.1.23:8080/"}]})
            if scene == "guest":
                state.update({"guest": True, "guestLeft": 20 * 3600, "volCap": 10})
            state["media"] = {"title": "Big Buck Bunny", "artist": "Blender Foundation", "pkg": APPS[0][0],
                              "app": APPS[0][1], "dur": 634000, "pos": 201000, "playing": True, "canSeek": True,
                              "art": "demo"}
            return self.send(200, "application/json", json.dumps(state))
        if path == "/api/art":
            svg = ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 96 96"><defs><linearGradient id="g" x1="0" y1="0" '
                   'x2="1" y2="1"><stop offset="0" stop-color="#6EC6FF"/><stop offset="1" stop-color="#2E7D32"/>'
                   '</linearGradient></defs><rect width="96" height="96" fill="url(#g)"/><circle cx="62" cy="40" r="16" '
                   'fill="#FFF59D"/><path d="M0 70 Q30 50 60 70 T96 66 V96 H0z" fill="#1B5E20"/></svg>')
            return self.send(200, "image/svg+xml", svg)
            return self.send(200, "application/json", json.dumps(state))
        if path == "/api/apps":
            return self.send(200, "application/json", json.dumps([{"pkg": p, "label": l} for p, l, _ in APPS]))
        if path == "/api/icon":
            pkg = (query.get("pkg") or [""])[0]
            for p, label, color in APPS:
                if p == pkg:
                    letter = re.sub(r"[^A-Za-z+]", "", label)[:1]
                    svg = (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 96 96"><rect width="96" height="96" '
                           f'rx="22" fill="{color}"/><text x="48" y="64" font-family="-apple-system,Helvetica,Arial" '
                           f'font-size="46" font-weight="700" fill="#fff" text-anchor="middle">{letter}</text></svg>')
                    return self.send(200, "image/svg+xml", svg)
            return self.send(404, "text/plain", "no icon")
        self.send(200, "text/plain", "ok")


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--port", type=int, default=8099)
    ap.add_argument("--kind", choices=["projector", "tv"], default="projector")
    args = ap.parse_args()
    Handler.kind = args.kind
    print(f"Fake {args.kind} on http://localhost:{args.port}/  (add ?demo=apps, ?demo=pair ... see --help)")
    ThreadingHTTPServer(("127.0.0.1", args.port), Handler).serve_forever()
