# Chore Slayer — Mobile Apps

Native **iPhone (Swift/SwiftUI)** and **Android (Kotlin/Jetpack Compose)** apps
that connect to the Chore Slayer API.

- **Backend:** the same `api.php` you deployed, live at
  `https://chores.a-t-365.com/api.php`. Full contract: [`../API.md`](../API.md).
- The server URL is editable on the app's first screen, so you can point a build
  at a test server without recompiling. The default is baked into
  `Session.kt` (Android) / `Session.swift` (iOS).

> ⚠️ **Don't upload `apps/` to the web server.** It's app source code, not part
> of the website. When you update the backend in cPanel, upload only `api.php`,
> `lib/`, `assets/`, `index.php`, and `.htaccess` — not this folder.

---

## Status

| Flow | Android | iOS |
|---|---|---|
| Connect + version check | ✅ | ⏳ next |
| Kid login (by name) | ✅ | ⏳ |
| Kid board: tasks by time-of-day, tap to check off, progress, follow-up checks | ✅ | ⏳ |
| Parent PIN login (token stored) | ✅ | ⏳ |
| Parent: list children → open a child's board | ✅ | ⏳ |
| Parent: add/edit children, tasks (with schedule), follow-up checks, daily reports | ✅ | ⏳ |
| Parent: BHIS range reports (stats, daily table, task frequency, notes, share-as-text) | ✅ | ⏳ |
| Parent: "Adjust a day" — per-day overrides (nudge a task's slot/time or skip it for one day) | ✅ | ⏳ |

The first slice covers the **daily core** (the kid checking things off and the
parent reviewing). Heavier parent management screens are the next milestone;
they already work in the web app in the meantime.

---

## Android — run it

**You need:** [Android Studio](https://developer.android.com/studio) (current
stable — a 2025/2026 build). It bundles the JDK and Android SDK it needs.

1. **Open** Android Studio → **Open** → pick the `apps/android` folder.
2. Let it **Gradle sync** (first time it downloads Gradle 9.4.1 + dependencies —
   give it a few minutes).
3. Pick an emulator (or a USB device with USB debugging on) and press **Run ▶**.
4. On the first screen the server URL is prefilled
   (`https://chores.a-t-365.com/api.php`) — tap **Connect**.

**Try it:** Connect → *I'm a Kid* → type a child's first & last name → check off
tasks and watch the progress bar. Or Connect → *Parent* → enter your PIN → tap a
child to see their board.

Notes:
- Min Android 7.0 (API 24). No third-party networking libraries — it uses the
  platform `HttpURLConnection` + `org.json`.
- A Gradle **wrapper is included**, so `./gradlew :app:assembleDebug` works on the
  command line too (point `JAVA_HOME` at Android Studio's bundled JDK). Verified
  building on **AGP 9.2.1 / Kotlin 2.2.10 / Gradle 9.4.1 / compileSdk 37**.
- **Before release:** the parent token is stored in plain `SharedPreferences` for
  now (flagged in `Session.kt`). Switch to `EncryptedSharedPreferences`.

### Android project layout
```
apps/android/
  app/src/main/
    AndroidManifest.xml
    java/com/at365/choretracker/
      MainActivity.kt     ← entry point
      Api.kt              ← API client + data models
      Session.kt          ← stored base URL + parent token
      AppViewModel.kt     ← state + actions
      Ui.kt               ← all Compose screens
    res/values/           ← app name + theme
  build.gradle.kts, settings.gradle.kts, …
```

---

## iOS — coming next

The SwiftUI app (same flows, `URLSession` + `Codable`, token in the Keychain)
lands in `apps/ios/` in the next step. It'll open in Xcode and run in the
simulator with no extra tooling.
