# Troubleshooting and compatibility

## Pairing and connecting

| What you see | Why, and what to do |
|---|---|
| The QR opens a page that never loads | The phone is not on the same network as the device, or the network keeps devices apart (hotels, offices, some "guest" networks). Join the same Wi-Fi, or connect the device to the phone's hotspot. Mobile data and VPNs on the phone can also get in the way. |
| No QR card on the Home screen | Open **Phone Remote** on the device. If it says the remote is off, switch it on ([guide, step 2](GUIDE.md#2-switch-the-remote-on)). If it is on, check **QR card on Home** is not set to *Off*. The card also hides while a button of the launcher would be under it: move it to another corner. |
| "Waiting for the network…" | The device has no address yet. Check its Wi-Fi or cable. |
| "Wrong or expired PIN" | PINs last 2 minutes and 5 tries. Tap **Show a new PIN**. |
| "Wait a few seconds and try again" | A new PIN can be requested every 10 seconds. |
| "This projector is full" | The **Phone limit** is reached (the Home card says *Full*). On the device, remove a phone you no longer use, or raise the limit, then tap **Try again**. Already paired phones are not affected. |
| "New phones are turned off" | **Allow new phones** is off on the device. Turn it on in Phone Remote there, tap **Try again**, pair, turn it off again. |
| The phone asks for a PIN again | The device got a new address (other network, or the router handed out another one): to the browser that is a new site. Pair once more, and give the device a fixed address in your router (DHCP reservation). Also happens if the phone was removed on the device, or in a private browsing tab. |
| Android: "This app cannot be installed", or Create shortcut does nothing | Chrome will not add a shortcut to a page served over plain HTTP from a home device. Use the Android app instead: **Download the app** on the "You're connected" screen, or Projector tab → **Get the Android app**. Open the file and allow installs from Chrome when asked. |
| iPhone Home Screen icon asks for a PIN | The icon was added when "You're connected" was not on screen. Pair it once, or delete the icon and add it again from **Settings → Add to Home Screen**. |
| "Can't reach …" | The device is off, asleep with Wi-Fi off, or on another network. The page retries by itself. |
| Android app says "not answering" | The device is off, Phone Remote is not running on it, or the phone is on another Wi-Fi. The app finds the device again by itself when its address changes. |
| Blank screen when opening the remote | The device is off (or on another network). The remote's page comes from the device itself, and a page from a home device (plain HTTP) cannot be kept offline on the phone. Switch the device on, wait until its Home screen shows, then open the remote again. |
| The same phone is listed twice | Two browsers on one phone (Safari and Chrome) are two phones to the device. Remove the one you do not use. |

## Buttons

| What you see | Why, and what to do |
|---|---|
| Arrows move a mouse pointer on the screen | The device's own remote has a **mouse button** and its mode is on. Press it again. Phone Remote notices the mode on boards that report it and keeps moving the selection meanwhile. |
| Arrows do nothing in one app (Android 8 to 12) | That app draws its own interface, which only real key presses can steer. Android 13+ does not have this limit. |
| Volume does nothing | The device has fixed volume (sound goes to a TV or receiver over HDMI/optical). Use that device's volume. |
| Rewind/forward do nothing | The app ignores media keys. Use left/right. |
| Typing: "Select a search box … first" | Nothing editable is selected on the screen. Move onto the search box and press OK first. Apps with their own on-screen keyboards and no real text box cannot be typed into. |
| Typed text arrives but the search does not start (Android 8 to 10) | Those versions cannot press the keyboard's Enter for you. Press OK on the search button. |
| No haptic tick on iPhone | Needs iOS 17.4+, **Settings → Sounds & Haptics → System Haptics** on, and Safari or the Home Screen icon. Other iPhone browsers may not tick. |
| Now playing only shows the app's name | Turn on **Now playing** on the device. Some apps publish no title or position; Kodi publishes them only while a video or song plays. |
| Screen preview is black | The app on screen protects its picture (most paid video). The preview works for menus and free video. |
| No other TVs listed | They need this remote too, on the same Wi-Fi, and the network must pass Bonjour/mDNS (guest networks often do not). |
| Empty band under the tab bar (iPhone icon) | Fixed in 1.1. Remove the icon's old page from the app switcher and open it again. |

## The service

| What you see | Why, and what to do |
|---|---|
| Accessibility switch greyed out, "Restricted setting" | Android 13+ does this for apps installed from a file. Settings → Apps → Phone Remote → ⋮ → **Allow restricted settings**. Or install with ADB. |
| Remote is off after an update | Android switches accessibility services off when their app is force-stopped or its data is cleared. Switch it on again. |
| Remote is off after **every** restart | Some projector firmwares rewrite the list of accessibility services at boot and keep only their own. Let the app switch itself back on, once, over ADB: `adb shell pm grant com.local.projremote android.permission.WRITE_SECURE_SETTINGS`. After that it adds itself back at each boot (and when you open its screen). Without ADB: open Phone Remote after a restart and use **Turn on**. |
| Another app uses port 8080 | Phone Remote takes the next free port (8081 …). The QR and the Phone Remote screen show the right address. |

## Compatibility notes

What the app assumes, and how it adapts. All of it is in `app/src/main/res/values/config.xml` if a device needs
something else.

- **Home screens** are found automatically: every app that can be the HOME app. The QR card shows only over them.
- **The network address** is taken from the network Android is using (Wi-Fi or Ethernet), not from an interface
  name.
- **Arrows and OK:** real key presses on Android 13+. On Android 8 to 12, and while a projector's mouse mode is
  on, the nearest focusable element in that direction gets focus, lists scroll at their edge, and OK clicks.
- **Mouse mode** is detected on MStar/Softwinner-style firmware through the `sys.KeyMouse.mKeyMouseState`
  property. Other firmwares simply never report it.
- **Search YouTube** uses YouTube for Android TV, the phone YouTube app, or SmartTube, whichever is installed.
  Without one, the search button is hidden.
- **Apps tab** leaves out home screens, system settings, and packages whose name looks like a factory or
  calibration tool.
- **Certified Android TV / Google TV:** Google's own remote apps work there, so you may not need this. Phone Remote
  runs on them too.

## Tested devices

| Device | Android | Result |
|---|---|---|
| 1080p projector, MediaTek board (sold under several names) | 13 | Everything above |

Tried it on something else? Please open an issue or a pull request with the model, Android version, and what did
or did not work.
