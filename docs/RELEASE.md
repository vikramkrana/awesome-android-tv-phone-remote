# Releasing

## A GitHub release

1. Bump `versionCode` and `versionName` in `app/build.gradle`.
2. `./gradlew assembleRelease`, copy `app/build/outputs/apk/release/app-release.apk` to
   `dist/phone-remote-<version>.apk`, update the link in the README.
3. Tag `v<version>` and attach the APKs to a GitHub release under fixed names, so the README's download buttons
   (`releases/latest/download/...`) keep working: `phone-remote-tv.apk` (the TV app) and
   `phone-remote-android.apk` (the phone app, `phone/build/outputs/apk/release/phone-release.apk`), and
   `phone-remote-tv-netflix.apk` (the TV app built from the `netflix-remote` branch).
   The short links in the README (`is.gd/tvphoneremote`, `is.gd/tvphoneremotenf`) point to these
   `releases/latest/download/` addresses, so they follow every release by themselves.
   **Check for updates** reads the latest release's tag, so tags must look like `v1.2`.
4. Set `project_url` in `res/values/strings.xml` to the repository (`https://github.com/<you>/<repo>`); it shows in
   About and turns on the update check option.

## Google Play

Play accepts apps that use an accessibility service for something other than helping people with disabilities,
but reviews them closely. What it asks for, and where this project stands:

| Requirement | State |
|---|---|
| Own package name | **To do.** `com.local.projremote` is a placeholder; pick e.g. `io.github.<you>.phoneremote` (`applicationId` and `namespace` in `app/build.gradle`). Changing it makes a new app: installed copies do not upgrade, phones pair again. |
| Final app name | **To do** (`app_name`). Check that it is not taken on Play. |
| Upload key | Ready: `keystore.properties.example`. Keep the key safe; Play App Signing holds the release key. |
| App bundle | `./gradlew bundleRelease` → `app/build/outputs/bundle/release/app-release.aab`. |
| Target API level | **To do.** Play requires a recent `targetSdk` (35 or newer for new apps at the time of writing). Install that platform (`sdkmanager "platforms;android-35"`), raise `compileSdk`/`targetSdk`, and test: Android 15 makes activities edge-to-edge. |
| Privacy policy | [PRIVACY.md](../PRIVACY.md); Play needs it at a public URL (the GitHub page works). |
| Prominent disclosure | Done: the dialog before Accessibility settings (`InfoActivity.disclose()`). |
| Accessibility declaration | In Play Console, App content → Accessibility API: "remote control of this device from the owner's paired phones", with a short video: pairing, pressing keys from the phone. Mention `isAccessibilityTool` is not set. |
| Data safety form | No data collected or shared with the developer or third parties. Data moves only between the device and the owner's paired phones on the local network, over plain HTTP: answer "not encrypted in transit". |
| Notification listener | Used only for media sessions; describe it in the store listing. |
| `WRITE_SECURE_SETTINGS` | Not grantable by Play installs; harmless to declare, but Play may ask. It is only for owners who grant it over ADB. |
| TV listing | Leanback banner present (`res/drawable/banner.xml`); Play wants TV screenshots (1920×1080) and passes a TV quality review. `docs/screens/tv-*.png` are a start. |
| Testing | New personal developer accounts must run a closed test with testers for 14 days before going to production. |

The phone side needs nothing from any store: it is a web page the device serves.
