# Chore Slayer — Mobile Apps

Native **iPhone (Swift/SwiftUI)** and **Android (Kotlin/Jetpack Compose)** apps
that connect to the Chore Slayer API.

- **Backend:** the same `api.php` you deployed, live at
  `https://chores.a-t-365.com/api.php`. Full contract: [`../API.md`](../API.md).
- Release builds always use the live server. Debug builds can point at a test
  server: `CHORE_API_BASE` env var on iOS, `-e apiBase <url>` on Android
  (see below). The default lives in `Session.swift` (iOS) and
  `app/build.gradle.kts` → `API_BASE` (Android).

> ⚠️ **Don't upload `apps/` to the web server.** It's app source code, not part
> of the website. When you update the backend in cPanel, upload only `api.php`,
> `lib/`, `assets/`, `index.php`, and `.htaccess` — not this folder.

---

## Status

Both apps match the web app: link a device to a family (or create one), kid
sign-in by name, the kid board with follow-up checks and "See my day", parent
PIN sign-in with a 10-minute idle lock, children, tasks and schedules, daily
and BHIS reports, "Adjust a day", family code, change PIN, delete family, and
push notifications (kid chore reminders, parent alerts).

| | Android | iOS |
|---|---|---|
| All app flows | ✅ (rebuilt Nov 2026) | ✅ |
| Push notifications | ✅ code; needs Firebase setup (below) | ✅ code; needs the APNs key on the server |

---

## Android — run it

**You need:** [Android Studio](https://developer.android.com/studio). It bundles
the JDK and Android SDK.

1. **Open** Android Studio → **Open** → pick the `apps/android` folder, and let
   it Gradle sync.
2. Pick an emulator or a USB device and press **Run ▶**. Release and debug
   builds talk to `https://chores.a-t-365.com/api.php`.

Command line (uses Android Studio's JDK):

```bash
cd apps/android
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug
```

**Test server (debug builds only):** start the app with a different API, e.g. a
local PHP server reached from the emulator at `10.0.2.2`:

```bash
adb shell am start -n com.at365.choreslayer/com.at365.choretracker.MainActivity -e apiBase http://10.0.2.2:8766/api.php
```

Debug builds allow plain HTTP for this; release builds don't.

Notes:
- Min Android 8.0 (API 26), target/compile SDK 36, AGP 8.6.1, Kotlin 2.0.21,
  Gradle 8.9 (wrapper included).
- No third-party networking libraries: platform `HttpURLConnection` + `org.json`.
- The parent sign-in token is kept only in memory, so closing the app signs the
  parent out (same as iOS and the web).
- `local.properties`, `keystore.properties`, keystores and `google-services.json`
  are gitignored. Never commit them.

### Push notifications on Android (one-time setup)

The app builds and runs without Firebase; push simply stays off and the
Notifications screen says it isn't available. To turn it on:

1. In the [Firebase console](https://console.firebase.google.com), open (or
   create) a project and **add an Android app** with package name
   `com.at365.choreslayer`.
2. Download its **`google-services.json`** and put it at
   `apps/android/app/google-services.json`. The build applies the Google
   Services plugin automatically once that file exists.
3. In the same Firebase project: Project settings → **Service accounts** →
   generate a private key (JSON). Upload it to the server outside the web
   folder (e.g. `/home/at365/keys/`), then point the server config at it:
   `config.php` → `'push' => ['fcm_service_account_path' => '/home/at365/keys/<file>.json', …]`.
4. Rebuild the app. Kids then see "Remind me when it's time" on their board,
   and parents get the Notifications switch in the ⋮ menu.

### Android project layout
```
apps/android/
  app/src/main/
    AndroidManifest.xml
    java/com/at365/choretracker/
      MainActivity.kt     ← entry point, top bar, screen switch, idle lock
      AppModel.kt         ← state + actions (mirrors the iOS AppModel)
      Api.kt              ← Session (server, family, parent token) + API client
      Models.kt           ← JSON models
      Push.kt             ← FCM token, notification channel, PushService
      Ui.kt               ← theme + shared components
      FamilyScreens.kt, KidScreens.kt, ParentScreens.kt, ReportScreens.kt
    res/                  ← icons, theme, notification icon
  app/src/debug/AndroidManifest.xml   ← debug-only: allow HTTP test servers
```

---

## iOS

`apps/ios` (SwiftUI). Generate the Xcode project with XcodeGen:
`cd apps/ios && xcodegen generate && open ChoreSlayer.xcodeproj`. Push needs the
Push Notifications capability (in `ChoreSlayer.entitlements`) and the APNs key
settings in the server's `config.php` (`push.apns_*`).
