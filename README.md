# Chore Slayer

A simple, accessible web app for tracking kids' daily chores and activities,
with parent management, daily reports, and a BHIS progress report you can
print, copy, or export to CSV.

Built with **plain PHP + MySQL** — no frameworks, no build step, no Composer.
It runs on essentially any cPanel host.

---

## What it does

**Kids**
- Log in with their first and last name (no password — it just opens their board).
- Tasks are grouped by time of day (Morning / Midday / Afternoon / Evening /
  Anytime), and show a specific time when one is set.
- Tap big, colorful tiles to check off chores and activities through the day.
- **Follow-up checks:** when a task has sub-steps, checking the main task reveals
  them — e.g. checking "Wipe table" asks "Did you wipe it twice?" and "Did you
  wipe the chairs?". Each is its own checkbox. A task isn't **fully done** until
  its follow-ups are all checked too; until then it shows as *partly done* (amber).
- A live progress bar that counts every checkbox (main tasks + follow-ups), so it
  inches forward as the follow-ups get done. "X of Y done" counts fully-finished
  tasks.
- Tap **"See my day"** for a visual, screen-reader-friendly summary (progress
  ring, stars, and a done / not-yet list, including follow-up checks) they can
  also print.

**Parents** (protected by a PIN)
- Add and remove children (remove keeps their history; "delete forever" wipes it).
- Add/edit/remove chores and activities on each child's dashboard. For each one set:
  - **which days it repeats** (Mon–Sun checkboxes, with Every day / Weekdays /
    Weekends shortcuts),
  - a **time of day** slot and an optional **specific time**,
  - optional **follow-up checks** (the sub-steps described above).
- **Adjust a single day:** in Daily Report, nudge a task's time/slot just for that
  day, or skip it for that day — without changing the recurring schedule.
- Write a daily report per child (mood, behavior rating 1–5, highlights,
  challenges, notes, goals). The day's check-off totals are shown automatically.
- Generate a **BHIS report** over any date range: completion stats (vs. how many
  tasks were scheduled each day), a daily table, how often each task was done, and
  all the daily notes — with **Print / Save PDF**, **Copy as text**, and
  **Download CSV** buttons.

---

## Files

```
index.php            ← the app (open this in a browser)
api.php              ← the JSON API (web app + iPhone/Android apps call this)
API.md               ← API reference for building the mobile apps
config.sample.php    ← copy to config.php and add your DB details
config.php           ← YOU create this (holds DB password; never shared)
install.php          ← one-time web setup wizard (delete after install)
.htaccess            ← security + passes the Authorization header through to PHP
lib/                 ← server code (db, auth, helpers) — blocked from the web
assets/              ← styles.css and app.js
```

---

## Mobile apps (iPhone & Android)

The same `api.php` that powers the web app is the backend for the native apps —
no separate service to run. Once this is deployed over HTTPS, point the apps at
`https://yourdomain.com/chores/api.php`.

- **The full contract is in [API.md](API.md)** — auth flow, every endpoint,
  request/response shapes, and error codes.
- Auth is a **bearer token** for parents (from the PIN) and a **name lookup** for
  kids; send `Authorization: Bearer <token>` on parent calls.
- **CORS** is enabled, so hybrid/PWA/web builds and local dev work too; native
  iOS/Android clients don't need it.
- Wrong-PIN attempts are **rate-limited** server-side, since the API is now
  reachable from the internet.

The included `.htaccess` ensures the `Authorization` header reaches PHP on cPanel
(otherwise parent login works but every parent action returns *unauthorized*) —
make sure it uploads.

---

## Quick install (recommended): the setup wizard

1. **Create the database** in cPanel → **MySQL® Databases** (see step 1 below for
   details), and note the database name, username, password, and host.
2. **Upload** all of these files to `public_html` (or a subfolder like
   `public_html/chores`), keeping the `lib/` and `assets/` folders intact.
3. In your browser, go to **`https://yourdomain.com/chores/install.php`**.
4. The wizard checks your server, asks for the database details (and tests them),
   writes `config.php`, creates the tables, and lets you set your **parent PIN**
   and add your first child.
5. On the last screen, click **Delete installer** (or manually delete
   `install.php` and `installed.lock`). Done — open the app and you're live.

If you'd rather set it up by hand instead, follow the manual steps below.

---

## Manual deploy to cPanel (step by step)

### 1. Create the database
1. Log in to cPanel → **MySQL® Databases**.
2. Under **Create New Database**, type a name (e.g. `chores`) → **Create Database**.
   cPanel will prefix it, giving something like `youracct_chores`.
3. Under **MySQL Users → Add New User**, create a user and a strong password.
   You'll get something like `youracct_choreapp`.
4. Under **Add User To Database**, pick the user + database → **Add**, then on
   the next screen check **ALL PRIVILEGES** → **Make Changes**.
5. Write down the four values: **database name**, **username**, **password**,
   and host (almost always `localhost`).

### 2. Upload the app
1. In cPanel → **File Manager**, go to `public_html`.
   - To put it at your domain root, upload the files directly into `public_html`.
   - To put it in a subfolder (recommended), create `public_html/chores` and
     upload into there. The app will live at `https://yourdomain.com/chores/`.
2. Upload **all** the files and folders from this project (keep the folder
   structure: `lib/` and `assets/` must stay as subfolders).
   - Easiest: zip this folder, upload the zip, then **Extract** in File Manager.

### 3. Add your database details
1. In File Manager, copy `config.sample.php` and rename the copy to `config.php`.
2. Edit `config.php` (right-click → **Edit**) and fill in:
   ```php
   'db_host' => 'localhost',
   'db_name' => 'youracct_chores',
   'db_user' => 'youracct_choreapp',
   'db_pass' => 'the-password-you-set',
   ```
3. Also set your **timezone** (e.g. `'America/Chicago'`) so "today" is correct.
4. Save.

### 4. Open it
Visit your URL (e.g. `https://yourdomain.com/chores/`).
- The database tables are created automatically on first visit.
- Go to the **Parent / Admin** tab and **set your parent PIN** (4–8 digits).
- Add your children, give each some chores and activities, and you're live.

> **PHP version:** any modern cPanel PHP (7.4 or newer) works. If you see a
> blank page, set PHP to 8.0+ in cPanel → **Select PHP Version**.

---

## First-time checklist

- [ ] Database + user created, ALL PRIVILEGES granted
- [ ] Files uploaded with `lib/` and `assets/` intact
- [ ] `config.php` created with correct DB details + timezone
- [ ] Opened the site and set the parent PIN
- [ ] Added at least one child and a few chores/activities
- [ ] Tested a kid login (first + last name) and checked off a task

---

## Using it day to day

- **Kids:** open the site, "I'm a Kid" tab, type name, tap tiles. "See my day"
  for the celebration screen.
- **Parents:** "Parent / Admin" tab, enter PIN. At the end of the day open
  **Daily Report**, pick the child, and jot a few notes — completion totals are
  filled in for you.
- **For the BHIS worker:** open **BHIS Report**, choose the child and date range,
  click **Generate**, then **Print / Save PDF** or **Copy as text** to paste into
  an email, or **Download CSV** for a spreadsheet.

---

## Privacy & safety notes

- Kid login is **by name only** — by design, so kids don't need passwords.
  Everything that changes data is behind the parent PIN.
- `config.php` and the `lib/` folder are blocked from the web by `.htaccess`,
  and the PIN is stored only as a secure hash.
- Always run the site over **HTTPS** (free with cPanel's AutoSSL / Let's Encrypt)
  so names and notes are encrypted in transit.
- Removing a child hides them but **keeps their reports** for your records.
  "Delete forever" (in Edit) permanently erases all of their data.

---

## Troubleshooting

| Symptom | Fix |
|---|---|
| "Could not connect to the database" | Re-check the four `db_*` values in `config.php`. Confirm the user is added to the database with ALL PRIVILEGES. |
| Blank white page | cPanel → **Select PHP Version**, choose 8.0+. Set `'debug' => true` in `config.php` temporarily to see the error, then set it back to `false`. |
| Parent login "works" but actions say *unauthorized* | Your host may strip the Authorization header. The included `.htaccess` fixes this on most hosts; make sure it uploaded. |
| Wrong "today" date | Set the correct `'timezone'` in `config.php`. |

---

## Want it changed?

Common tweaks (ask and they're quick): shared chores assigned to all kids,
recurring/weekly chores, reward points or streaks, photo attachments on tasks,
or multiple parent accounts.

---

## Repository notes

- **Secrets are not in this repo.** Copy `config.sample.php` to `config.php`
  (or run `install.php`) to set your database details. `config.php`, Android
  signing files and build outputs are all ignored by `.gitignore`.
- **Android source:** only `apps/android/app/build.gradle.kts` and
  `proguard-rules.pro` survived; the Kotlin source was lost and has to be
  rebuilt. The iOS (SwiftUI) source in `apps/ios` is complete.
- **`tools/ssl-sync/`** is the daily cron job that copies a renewed wildcard
  certificate onto every subdomain of a cPanel account. It is written for
  `*.a-t-365.com`; change the constants at the top for another domain.
