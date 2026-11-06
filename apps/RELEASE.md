# Chore Slayer — Release Runway

Both apps are feature-complete and build. This is the step-by-step to ship them.
Checkboxes track progress; ⚙️ = I can do it here, 👤 = needs you (account/console).

---

## 0. Before anything — a real decision: children's data ⚠️

The app stores **children's first/last names** and behavior/chore notes. Both
stores have strict rules when an app is "directed to children":

- **Google Play** — *Families / Designed for Families* policy + a **Target
  Audience** declaration.
- **Apple** — the **Kids Category** + COPPA/GDPR-K obligations.

You must decide the positioning and confirm it's acceptable for your situation
(ideally with a quick legal sanity check):

- **Option A (recommended): a parent/caregiver tool.** Target audience = adults;
  children only appear as records a parent enters. Avoids the strictest kids-
  program requirements, but you still need a privacy policy and honest data
  disclosures.
- **Option B: a children's app.** Triggers Designed for Families / Kids Category
  — extra review, no third-party ads/analytics SDKs, stricter consent.

Everything below assumes **Option A**. 👤 **Confirm this before submitting.**

---

## 1. Shared prerequisites

- [ ] ⚙️/👤 **Privacy policy** hosted at a public URL (e.g. `https://chores.a-t-365.com/privacy`). I can draft + generate the page; you upload it.
- [ ] ⚙️ **Store listing copy** — app name, subtitle/short description, full description, keywords.
- [ ] ⚙️ **Screenshots** — captured from the simulator/emulator at the required sizes.
- [ ] 👤 **Support contact** — a support URL and/or email for both listings.
- [ ] 👤 **Marketing decisions** — category (Productivity or Lifestyle), price (free).

---

## 2. Android — Google Play 🤖

- [ ] ⚙️ **Upload keystore + signing config** (this session). Uses **Play App
      Signing**: you upload with the *upload key*; Google holds the real signing
      key (the upload key is resettable if lost).
- [ ] ⚙️ **versionCode / versionName** (start at 1 / "1.0").
- [ ] ⚙️ **Build signed AAB** — `./gradlew :app:bundleRelease` → `app/build/outputs/bundle/release/app-release.aab`.
- [ ] ⚙️ Verify the **release APK** installs & runs (sanity check the signed/release variant).
- [ ] 👤 **Play Console → Create app** (name, default language, app/free).
- [ ] 👤 **Internal testing track** → upload the AAB → add testers → roll out.
- [ ] 👤 **Store listing** (copy + screenshots + feature graphic 1024×500 + icon 512×512).
- [ ] 👤 **App content**: privacy policy URL, **Data safety** form, **Content
      rating** questionnaire, **Target audience & content**, ads declaration (none),
      news (no).
- [ ] 👤 Promote internal → **closed** → **production**.

**Play needs:** 512×512 icon (⚙️ I'll export), 1024×500 feature graphic (⚙️),
phone screenshots (⚙️), and the forms above (👤).

---

## 3. iOS — App Store 

- [ ] 👤 **Register the bundle ID** `com.at365.choreslayer` (Apple Developer →
      Identifiers) — or let Xcode auto-create it on first archive.
- [ ] ⚙️/👤 **Signing**: in Xcode → ChoreSlayer target → Signing & Capabilities →
      check *Automatically manage signing* → select your **Team**. (I've set the
      project to automatic signing; it needs your Team ID, which only you can sign in for.)
- [ ] ⚙️ App icon (done), version `1.0`, build `1`.
- [ ] 👤 **Archive**: Xcode → *Product ▸ Archive* → *Distribute App ▸ App Store Connect ▸ Upload*. (Needs your signed-in Apple account; can't be done headlessly.)
- [ ] 👤 **App Store Connect → New App** (name "Chore Slayer", bundle ID, SKU).
- [ ] 👤 **TestFlight** — once the build processes, test on a device.
- [ ] 👤 **App Privacy** ("nutrition labels") — declare: names + usage data, not
      linked to identity beyond the household, not used for tracking.
- [ ] 👤 **Age rating**, **screenshots** (6.7" + 6.1" iPhone — ⚙️ I'll capture),
      description, keywords, support URL.
- [ ] 👤 **Submit for review**.

**App Store needs:** screenshots for required device sizes (⚙️), the 1024 icon
(done), and the metadata/privacy forms (👤).

---

## 4. After approval
- [ ] Tag the release (`v1.0`) once you set up git.
- [ ] Keep `apps/android/keystore/upload.jks` + `keystore.properties` backed up
      somewhere safe (NOT in git — they're gitignored).
- [ ] Plan the device-VoiceOver/TalkBack pass on the parent screens.

---

### What I'm doing now
Working top-down through the ⚙️ items: **Android signing + signed AAB** first,
then store assets (privacy policy, listing copy, screenshots), then the iOS
release config. The 👤 console steps I'll hand you with exact instructions.
