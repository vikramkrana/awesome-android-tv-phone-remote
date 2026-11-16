# Development

## How it works

The phone needs no app (Android phones can get an optional one, below). The device runs one small Android app with three jobs:

1. an **accessibility service** that can press keys and move the selection,
2. a tiny **web server** that serves one page (the remote) and a few POST endpoints,
3. two **overlays** on the big screen: the QR card on Home and the pairing PIN.

```
 phone browser ── HTTP, local network ──▶ WebServer ──▶ RemoteService (accessibility) ──▶ Android
   remote.html                             Pairing        keys, focus, volume, text, app launch
```

| File | Role |
|---|---|
| `RemoteService.java` | The accessibility service. Keys (see below), volume and media through `AudioManager`, text through `ACTION_SET_TEXT`, app search, the Home QR card and the PIN overlay, which app is in front. |
| `WebServer.java` | HTTP on port 8080: the page, the icon, the web manifest, the API. Host and Origin checks, signed requests. |
| `Pairing.java` | PINs, paired phones, tokens (hashed), single-use handoff codes, limits. |
| `Apps.java` | Launchable apps, icons as PNG, launching; what is left out. |
| `InfoActivity.java` | The app's own screen on the device: QR, status, paired phones (guest/family/remove), settings. |
| `NowPlaying.java`, `MediaListener.java` | Media sessions for the Now playing card (needs notification access). |
| `Peers.java` | DNS-SD `_phoneremote._tcp`: announces this device and lists others for the phone's switcher. |
| `Updates.java` | Optional daily check of the GitHub releases of `project_url`. |
| `Diagnostics.java` | The plain-text report. |
| `Focus.java` | Lens motor and auto focus through Joyar's `DispatcherService` (`motorCtrl` 1/2/0, `action=focus`). |
| `DeviceTools.java` | Picture & input and power off where the firmware has them: Joyar camera correction, Siviton system menu (Projection page, power menu), MediaTek Source panel. |
| `Photos.java` | The slideshow activity; pictures in the cache, deleted when it closes. |
| `SelfEnable.java` | Boot receiver: re-enables the service on firmware that drops it, if the owner granted `WRITE_SECURE_SETTINGS`. `MediaListener` runs it too after boot, for firmware that blocks the boot broadcast. |
| `MdnsResponder.java` | Answers mDNS `A` queries for the device's own name, e.g. `living-room-3f2a.local`. |
| `NetUtil.java`, `Qr.java` | The device's IPv4 address; QR bitmaps. |
| `res/raw/remote.html` | The whole phone app: one file, no framework, no external requests. Colours are CSS variables at the top. |
| `phone/` | The optional Android phone app (about 400 KB, mostly the Play services QR scanner): a full-screen WebView on the remote page. Finds devices over DNS-SD (TXT `id`), keeps the page's storage under that id (`window.PhoneRemoteApp`, `Bridge.java`) so a new address keeps the pairing, speech through `RecognizerIntent` (`appHeard`), back button through `appBack`, Share → link, QR scan (Google code scanner: no camera permission). Built with the TV app and carried in its assets. |
| `res/values/config.xml` | Everything device-specific: port, mDNS name, projector/TV, extra home or hidden packages, mouse-mode property, YouTube packages. |

### Arrows and OK

- **Android 13+:** `performGlobalAction(GLOBAL_ACTION_DPAD_*)`, real key presses.
- **Android 8 to 12, or while a projector's mouse mode is on:** `RemoteService.dpad()` finds the nearest focusable
  node in that direction (`nearest()`), gives it focus, and scrolls the enclosing list when there is none. OK is
  `ACTION_CLICK` on the focused node.

Nothing needs root, a system signature or ADB at run time.

## HTTP API

All `POST`. Bodies are plain text unless noted. An action that is not possible right now answers `409`.

**Signing.** Protected endpoints take three headers instead of the token. With `T` the token from pairing:

```
K      = hex(sha256(T))                      # the key; the device stores only this
X-Key  = hex(sha256("pub:" + K))[0:32]       # which key
X-Ctr  = a number larger than the last one   # the page uses microseconds since 1970
X-Sig  = hex(hmac_sha256(K, epoch + "\n" + X-Ctr + "\n" + path?query + "\n" + body))
```

`epoch` comes from `POST /api/hello` and changes at every start of the remote. A request with a wrong signature,
an old epoch or a used counter gets `401` with `X-Epoch` (the current epoch) and `X-Ctr` (the next counter this
key may use); the page retries once with those. Counters are accepted once each, within a two-minute window
below the highest seen.

| Endpoint | Auth | Body | Answer |
|---|---|---|---|
| `GET /` | no | | the page |
| `GET /icon.png`, `GET /manifest.json` | no | | Home Screen icon, web app manifest |
| `GET /android.apk` | no | | the phone app (from `phone/`) |
| `/api/hello` | no | | the current epoch |
| `/api/pair/start` | no | form: `device` | shows a PIN on the device; `429` if asked within 10 s; `423` while **Allow new phones** is off; `409` (body: the limit) when the phone limit is reached |
| `/api/pair` | no | form: `secret` (PIN), `name`, `device` (32 hex) | the token, and the restore cookie; `403` on a wrong or expired PIN; `409`, `423` as above |
| `/api/resume` | cookie (`Path=/api/resume`) | | the token again (page storage was cleared) |
| `/api/handoff` | no | a handoff code | a new token for the same phone; `403` if used or expired |
| `/api/handoff/new` | yes | | a single-use handoff code |
| `/api/state` | yes | | `{"name","phone","host","pointer","youtube","current":{"pkg","label"},"vol","volMax","volCap","muted","canSleep","sleepIn","preview","mediaAllowed","media":{"title","artist","app","pkg","dur","pos","playing","canSeek","art"},"others":[{"name","url"}],"guest","guestLeft","update","updateUrl"}` (absent keys: not available) |
| `/api/apps` | yes | | `[{"pkg","label"}]` |
| `/api/icon?pkg=` | yes | | PNG, 96 px |
| `/api/key` | yes | `up` `down` `left` `right` `ok` `longok` `pageup` `pagedown` `back` `home` `volup` `voldown` `mute` `playpause` `forward` `rewind` `sleep` | `sleep`: Android 9+ |
| `/api/volume` | yes | 0 to `volMax` | sets the media volume |
| `/api/link` | yes | an http(s) link | opens it (YouTube links in the YouTube app) |
| `/api/sleeptimer` | yes, not guests | minutes, `0` cancels | |
| `/api/media` | yes | `toggle` `play` `pause` `next` `previous` `fwd` `back` | needs notification access |
| `/api/seek` | yes | seconds | |
| `/api/art` | yes | | JPEG of the artwork, `404` without |
| `/api/screen` | yes, not guests | | JPEG, at most 640 px wide; `409` when Screen preview is off |
| `/api/diag` | yes, not guests | | the diagnostics report, plain text |
| `/api/text` | yes | the text | `409` when no text box is selected |
| `/api/enter` | yes | | keyboard Enter on the text box (Android 11+) |
| `/api/search?app=youtube` | yes | the query | opens the app on its results (`play` for the Play Store) |
| `/api/launch` | yes | a package name | |
| `/api/projector` | yes | | opens the system settings screen |
| `/api/rename` | yes | the new name | |
| `/api/unpair` | yes | | removes this phone, clears the cookie |
| `/api/key` focus | yes | `focusin` `focusout` `autofocus` | where `state.focus` |
| `/api/tool` | yes, not guests/kids | `autocorrect` `keystone` `zoom` `inputs` | where listed in `state.tools` |
| `/api/poweroff` | yes, not guests/kids | | where `state.powerOff` |
| `/api/bedtime` | yes, not guests/kids | minutes after midnight, `-1` off | `state.bedtime` |
| `/api/kidapps` | yes, family | `pkg` adds, `-pkg` removes | `state.kidApps` |
| `/api/photo?i=&n=` | yes | base64 JPEG, up to 3 MB | picture `i` of `n`; `i=0` starts a new slideshow |
| `/api/photos` | yes | `next` `prev` `pause` `play` `stop` | `state.photos` while one is open |

Example in Python, with a token copied from a paired browser's storage (`localStorage.token`):

```python
import hashlib, hmac, time, urllib.request
T, URL = "<token>", "http://<device-ip>:8080"
K = hashlib.sha256(T.encode()).hexdigest()
kid = hashlib.sha256(("pub:" + K).encode()).hexdigest()[:32]
epoch = urllib.request.urlopen(urllib.request.Request(URL + "/api/hello", b"")).read().decode()
def call(path, body=""):
    ctr = str(int(time.time() * 1e6))
    sig = hmac.new(K.encode(), f"{epoch}\n{ctr}\n{path}\n{body}".encode(), hashlib.sha256).hexdigest()
    req = urllib.request.Request(URL + path, body.encode(), {"X-Key": kid, "X-Ctr": ctr, "X-Sig": sig})
    return urllib.request.urlopen(req).read().decode()
print(call("/api/key", "down"))
```

## Working on the page without a device

`tools/demo_server.py` serves `remote.html` with a fake projector behind it (Python 3, no packages):

```sh
python3 tools/demo_server.py            # http://localhost:8099/
python3 tools/demo_server.py --kind tv  # the wording a TV gets
```

`?demo=<scene>` starts in a given screen: `pair`, `pin`, `done`, `remote`, `touchpad`, `mouse`, `apps`, `edit`,
`projector`, `settings`, `search`, `type`, `lost`. `/frame?demo=…` shows it in a phone-sized frame.

`tools/screenshots.sh` re-takes every phone picture in `docs/screens` with headless Chrome. The three `tv-*.png`
pictures come from a real device: `adb exec-out screencap -p > docs/screens/tv-home.png`.

The page contains `{{kind}}`, `{{Kind}}` and `{{app}}`: the server fills in "projector" or "TV" and the app's
name when it serves the page.

## Settings for another device

`app/src/main/res/values/config.xml`:

| Setting | Default | For |
|---|---|---|
| `port` | 8080 | the page; the next nine ports are tried when it is taken |
| `mdns_name` | empty | the Bonjour name; empty makes one from the device's name and a random part, e.g. `living-room-3f2a.local` |
| `default_phone_limit` | 5 | how many phones may pair until the owner changes it (1, 2, 3, 4, 5, 10 or 20) |
| `default_kind` | `projector` | wording on the phone, `projector` or `tv`; can be switched on the device |
| `extra_home_packages` | none | a launcher that does not declare itself as HOME |
| `hidden_packages` | none | more apps to leave out of the Apps tab |
| `mouse_mode_property` | `sys.KeyMouse.mKeyMouseState` | system property that reads `on` while the remote's mouse mode is on; empty to skip |
| `youtube_packages` | YouTube TV, YouTube, SmartTube | apps that accept a YouTube search link (also list them under `<queries>` in the manifest) |

The app's name is `app_name` in `res/values/strings.xml`, next to `author` and `project_url` (the credit shown on
the device's About card and at the bottom of the phone's Settings; an empty `project_url` hides the link); the icon is `res/drawable/ic_mark.xml`, used for the
launcher icon, the TV banner and the phone's Home Screen icon alike.

## Build and install

```sh
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
adb logcat -s ProjRemote
```

On networks where Gradle's downloads time out over IPv6, add `-Djava.net.preferIPv4Stack=true`.

Installing over an existing copy keeps paired phones and the accessibility switch. `adb shell pm clear` loses both.

## Contributing

- Device reports are the most useful thing: model, Android version, what worked. Add a row to the table in
  [TROUBLESHOOTING.md](TROUBLESHOOTING.md#tested-devices).
- Keep the phone page one self-contained file with no external requests.
- Keep device-specific values in `config.xml`, behind detection where possible.
- Anything that widens what a paired phone can do belongs in [SECURITY.md](SECURITY.md) too.

## Not done yet

- Translations: the page and the device screen are English only.
- Android 8 to 12 focus navigation has not been run on a real device.
- No automated tests.
- HTTP only: see [SECURITY.md](SECURITY.md) for what that means on a shared network.
