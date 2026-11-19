# Netflix on an uncertified projector

What it took to get Netflix working on the projector this app was built on, so you don't have to work it out again.
It applies to many budget Android projectors and TV boxes that are not certified by Google or Netflix.

## The device

| | |
|---|---|
| Sold as | **Pixpaq Gold Ray-Mastered** projector |
| Chip | MediaTek MT5873 |
| Android | 13 (reports itself as Google's "ADT-3" developer box) |
| Firmware | Joyar / Siviton launcher and settings |
| App store | Zeasn ("whale") store, plus Google Play with the TV version of Play services |

If your projector shows the same names in **Settings → About**, or `adb shell getprop ro.product.model` prints
`ADT-3`, everything below applies to you.

## Problem 1: the Netflix TV app says "not compatible" (error -13)

Netflix for Android TV (`com.netflix.ninja`, the one with the TV interface) refuses to start: *"This version is not
compatible with this device"*, error **-13**. Older versions (8.3.11) and newer ones (13.2.3) both do it.

**Why:** Netflix only runs its TV app on devices it has certified. This projector is not: it has no Netflix partner
ID (the `ro.nrdp.modelgroup` property is missing) and only the lower Widevine security level (L3), while the TV app
needs L1. Installing a different version doesn't change this, and without root nothing can.

**What to do instead:** use the Netflix **phone** app, below.

## Problem 2: the Netflix phone app says "Something went wrong" at sign-in

The phone app (`com.netflix.mediaclient`) installs and opens, but signing in fails with *"Something went wrong"*,
whatever you type. Turning off private DNS or ad blocking doesn't help.

**Why:** from version **9.60** on, the sign-in screen uses Google reCAPTCHA, which needs a part of Google Play
services that the TV version of Play services doesn't have. The check can never pass on this kind of device.

**Fix: install version 9.36.0** (build 7 63488, October 2025). It signs in normally (signing in with a phone number works).

1. Download `com.netflix.mediaclient_9.36.0_build_7_63488-…apkm` from APKMirror ("Netflix 9.36.0 build 7 63488").
   An `.apkm` file is a zip of split APKs.
2. Unzip it into an empty folder.
3. Remove the newer Netflix first (a newer version can't be replaced by an older one; this signs you out):

   ```sh
   adb uninstall com.netflix.mediaclient
   ```

4. Install the parts this projector needs (32-bit ARM, xhdpi screen). Leaving out the `partnermodule` or `twilio`
   parts gives `INSTALL_FAILED_MISSING_SPLIT`:

   ```sh
   adb install-multiple base.apk split_config.armeabi_v7a.apk split_config.xhdpi.apk \
     split_partnermodule.apk split_partnermodule.config.*.apk \
     split_twilio.apk split_twilio.config.*.apk
   ```

5. Open Netflix and sign in.

To check that a downloaded file really is from Netflix: `apksigner verify --print-certs base.apk` shows
`O="Netflix, Inc."` and the SHA-256 digest `363863596ea99241eb71b1a985553aa604de3ea3c5f0c546742390e682164e6b`.

## Problem 3: Netflix updates itself again and sign-in breaks

The Zeasn store (`zeasn.open.technical.tv.store`) runs a background service that silently installs and updates a
list of apps, Netflix among them. After it updates Netflix past 9.60, problem 2 is back.

**Fix:** stop the store from running in the background. It still works when you open it yourself; just don't update
Netflix from it.

```sh
adb shell cmd appops set zeasn.open.technical.tv.store RUN_ANY_IN_BACKGROUND ignore
adb shell cmd appops set zeasn.open.technical.tv.store RUN_IN_BACKGROUND ignore
```

Undo with the same commands and `allow` instead of `ignore`. Disabling the whole store
(`pm disable-user`) also works, but then you can't install anything from it.

## Problem 4: the remote doesn't work inside the Netflix phone app

The phone app is made for touch screens: it ignores arrow keys, so neither the projector's remote nor this phone
remote can move around in it. Only the projector's mouse mode works, which is slow.

**Fix:** the [`netflix-remote`](https://github.com/vikramkrana/awesome-android-tv-phone-remote/tree/netflix-remote)
branch of this project adds a remote mode for touch-only apps. While Netflix's phone app is in front, a white ring
shows where you are and both remotes drive it:

- **Home, search, My Netflix:** up/down go row by row, left/right poster by poster; rows and the page scroll at the
  edge. OK opens a title.
- **Details:** Play, Download, My List, the tabs, the **season list** (OK opens it, up/down picks a season), and
  every episode.
- **Pop-ups** like Switch Profiles and the cast list: the ring stays on the pop-up.
- **Player:** with the controls hidden, left/right skip 10 seconds. OK shows the controls with the ring on Pause; OK
  pauses and plays; arrows reach Back 10, Forward 10, Speed and Audio & Subtitles.
- When the on-screen keyboard is up, the keys go to the keyboard.

Not yet working: choosing a profile picture in Switch Profiles (Netflix doesn't expose them as buttons) and the
New & Hot page (it reshuffles itself while previews play).

**Download [phone-remote-tv-netflix.apk](https://github.com/vikramkrana/awesome-android-tv-phone-remote/releases/latest/download/phone-remote-tv-netflix.apk)**
(in the Downloader app: **`is.gd/tvphoneremotenf`**) and install it on the projector instead of `phone-remote-tv.apk` (over it is fine: pairing and settings stay). It
is the same app with this mode added, and still experimental. Or build the branch yourself
(`git checkout netflix-remote`, then see [Build](../README.md#build)). Other phone-only apps can be added to `touch_apps` in `app/src/main/res/values/config.xml`.

## Summary

| Symptom | Cause | Fix |
|---|---|---|
| TV app: "not compatible", error -13 | Device not certified by Netflix | Use the phone app |
| Phone app: "Something went wrong" at sign-in | reCAPTCHA in 9.60+, missing on TV Play services | Install 9.36.0 build 7 63488 |
| Netflix updates itself and breaks again | Zeasn store's background installer | `appops … RUN_ANY_IN_BACKGROUND ignore` |
| Remote does nothing inside Netflix | Phone app ignores arrow keys | `phone-remote-tv-netflix.apk` from the latest release |
