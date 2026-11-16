# Security

Phone Remote puts a small web server on your projector or TV. This page says exactly what that server lets a phone do,
who can reach it, and what it does not protect against.

## What a paired phone can do

- Press Back, Home, the arrows and OK, a long press of OK, a page up or down; change or set the volume; play/pause
- Put text into the text box that is selected on the screen
- See the list of launchable apps and their icons, and open one
- Open YouTube or the Play Store on a search, open an http(s) link in the app that handles it, open the system
  settings screen
- Set a sleep timer, put the device to sleep
- See what is playing (title, artist, app, artwork, position) and control it, if the owner switched on Now playing
- See a small picture of the screen, about once a second, if the owner switched on Screen preview (off by default)
- Copy a diagnostics report (device model, Android version, setup; no names, keys or screen content)
- Rename the device (the name shown on phones) and unpair itself
- See the names and addresses of other devices running this remote on the same network

**Guest phones** (the owner's choice, per phone or for all new phones) cannot open the system settings, rename,
sleep, set the timer or bedtime, power off, use Picture & input, see the screen or copy diagnostics; their volume
stops at 70%, and they are removed after 24 hours. **Kids phones** have the same limits without the time limit,
and in addition open only the apps family phones ticked, cannot send links, and search YouTube only when a YouTube
app is ticked.

**Voice keys** (Siri shortcuts): a family or kids phone can make one random 144-bit key for itself, used in
`/api/do?k=…&say=…` instead of a signature. The device stores only its hash. It carries that phone's limits,
runs only the spoken commands (Voice.java), at most a few per second, and stops working when turned off or
replaced in Settings. Unlike the signed requests, the key itself crosses the home network with every command, so
anyone on the same Wi-Fi who sees it can use it until it is turned off. Guests cannot make one.

**Photos** a phone sends (at most 3 MB each, signed like every request) are kept in the app's private cache only
while the slideshow is open, and deleted when it closes or when the remote starts.

**Firmware shortcuts:** focus, picture and power use services the projector's own firmware exposes to every app
(Joyar `DispatcherService`, Siviton `SystemUIService`, MediaTek's input panel), started with the same commands as
its own remote and menus. Nothing else is called, and only where they exist.

It cannot install or remove apps, read files, change system settings, or run commands. It cannot read what is on the
screen unless the owner switches on Screen preview, and then only family phones get the picture.
The accessibility service looks at the screen only for: which app is in front, where focusable elements are (to
move the selection on Android 8 to 12 and to keep the QR card off the launcher's buttons), and which text box is
selected. None of that is sent to the phone except the app's name and package.

A phone that is not paired can load the page and ask for a PIN to be shown. Nothing else.

## Pairing

- **A 6-digit PIN on the big screen** is the only way in. It proves the person is in the room. The QR code holds
  only the address.
- A PIN lasts 2 minutes and 5 wrong tries. At most 20 failed tries per hour in total. A new PIN can be requested
  every 10 seconds at most.
- A paired phone gets a random 192-bit token. The device stores only its SHA-256 hash, with the phone's name, a
  random per-browser id, and when it was paired and last used. At most as many phones as the **Phone limit** on
  the device (5 by default, up to 20). When it is full, new phones are refused; a paired phone is never pushed
  out to make room.
- **The token does not travel with requests.** Each request is signed instead: HMAC-SHA256 with a key derived
  from the token, over the path, the body, a counter and a random value the device makes at every start (the
  "epoch"). Someone who records a request can neither reuse it (each counter is accepted once, and old epochs
  die at a restart) nor forge a new one.
- The token crosses the network only when it is handed out (pairing, the Home Screen handoff) and when a page
  whose storage was cleared asks for it back. For that, it is also kept in an `HttpOnly; SameSite=Strict` cookie
  that the browser sends to that one address only (`/api/resume`).
- **iPhone Home Screen icon:** while "You're connected" is showing, the address carries a single-use handoff code
  (valid 30 days, dead after one use). The icon trades it for a token of its own. Until it is used, that code is
  as good as the phone's token: it sits in the address bar and may be saved in the browser's history.
- Removing a phone on the device, or **Unpair this phone** on the phone, revokes all of that phone's tokens.
- **Allow new phones** (on the device's Phone Remote screen) switched off: no PIN is shown and no new phone can
  pair, so nobody else on the network can even try. Phones already paired keep working. Worth doing once your
  phones are paired, on any network that is not only yours.

## Permissions

| Permission | Why |
|---|---|
| Accessibility service | Pressing keys, moving the selection, typing, the two overlays. Switched on by you. |
| Internet, network state, Wi-Fi multicast | Serving the page on the local network, answering its Bonjour name (e.g. `projector-3f2a.local`), and announcing itself (`_phoneremote._tcp`) so phones can switch between devices. The one outgoing connection is **Check for updates**, off by default: once a day to `api.github.com`, carrying nothing about the device or phones. |
| Notification access | Optional, for Now playing: Android lets an app see media sessions only through a notification listener. Notifications themselves are not read. |
| Accessibility screenshot | Only for Screen preview, off by default; family phones only; nothing is stored. |
| Run at startup | Only for the next row. |
| `WRITE_SECURE_SETTINGS` | **Never granted by installing.** Only `adb shell pm grant` gives it, and only firmware that switches the remote off at every restart needs it. With it the app does one thing: add itself back to the enabled accessibility services (`SelfEnable.java`). It is a strong permission (it can change system settings), so grant it only if you need it. |

## The web server

- Plain HTTP on port 8080 (or the next free port), on every network interface of the device.
- Requests are accepted only when addressed by IP address or a `.local` name, which blocks DNS rebinding.
- A POST whose `Origin` is another site is refused, so a web page open on your phone cannot press buttons or pop
  up PINs behind your back.
- 4 KB request limit, 5 s timeouts, a small fixed pool of workers.
- The page loads nothing from the internet.

## What it does not protect against

- **Someone on your Wi-Fi who can read traffic at the moment a phone pairs** (or restores its pairing) can copy
  the token then, because the traffic is not encrypted. Everyday button presses do not reveal it. They can also
  see what you do (which buttons, what you type into a search box). A home network with a password keeps
  outsiders off; put guests and untrusted gadgets on a guest network.
- **Anyone on the network can make the PIN pop up** on the screen (once every 10 seconds). They still cannot pair
  without seeing it.
- **Someone holding an unlocked paired phone** has the remote.
- The release APK in this repository is signed with the Android debug key.

## ADB

Installing with ADB needs USB debugging. On many low-cost projectors, network ADB has no authentication and runs
as root: anyone on the Wi-Fi can take over the device. **Switch USB debugging off when you are done**, and check
from a computer that port 5555 is closed:

```sh
nc -vz <device-ip> 5555     # should say "refused"
```

## Reporting a problem

Open an issue. For something that should not be public yet, use GitHub's private vulnerability report on the
repository's Security tab.
