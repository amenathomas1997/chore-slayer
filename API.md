# Chore Slayer — Mobile API Reference

This is the JSON API the iPhone and Android apps talk to. It's a single PHP
endpoint, `api.php`, that already powers the web app. This document is the
contract for building the native apps against it.

- **Base URL:** wherever you deploy, e.g. `https://yourdomain.com/chores/api.php`
- **Everything is** `GET` or `POST` to `api.php?action=<name>`
- **Responses are JSON.** `Content-Type: application/json; charset=utf-8`
- **Current `apiVersion`:** `1.0` (read it from the `status` action)

---

## Conventions

### Request format
- The **action** is always a query-string parameter: `api.php?action=status`.
- **Read** actions take their other parameters in the **query string**
  (`?childId=...&day=2026-06-05`).
- **Write** actions are `POST` with a **JSON body** and
  `Content-Type: application/json`. Example body: `{"firstName":"Ava","lastName":"Lee"}`.
- Dates are `YYYY-MM-DD`. Times are 24-hour `HH:MM`. "Today" is computed in the
  server's configured timezone — trust `status.today` rather than the device clock.

### Authentication
There are two "roles", handled very differently:

- **Parent** — holds a **bearer token**. Obtain it from `setup_pin` (first run) or
  `parent_login`, then send it on every parent action:
  ```
  Authorization: Bearer <token>
  ```
  (A fallback header `X-Auth-Token: <token>` also works if a host mangles
  `Authorization`.) Tokens last `session_days` (default 30) and are revoked by
  `parent_logout`. A `401 unauthorized` means the token is missing/expired — drop
  it and send the user back to the PIN screen.

- **Child** — there is **no token and no password**, by design. A child "logs in"
  with `child_login` (a name lookup that returns the child's `id`). Child-facing
  actions (`tasks`, `day_summary`, `toggle_completion`) are authorized **only by a
  valid `childId`**. Store the returned `child` object on the device and use its
  `id`. See [Security notes](#security-notes).

### Errors
Any non-2xx response is JSON shaped like:
```json
{ "error": "bad_pin", "message": "Incorrect PIN." }
```
Show `message` to the user; branch on `error`/HTTP status in code.

| Status | Meaning |
|---|---|
| `401 unauthorized` | Missing/expired parent token — re-authenticate. |
| `403 bad_pin` / forbidden | Wrong PIN. |
| `404` | Unknown action, child, or task not found. |
| `405 method_not_allowed` | Use `POST` for this action. |
| `409` | `pin_exists` (already set up) or `no_pin` (not set up yet). |
| `422` | Validation: `missing_fields` (see `fields`), bad PIN format, no child. |
| `429 too_many_attempts` | Too many wrong PINs from this IP. Respect `Retry-After`. |
| `500` | `db_connection` or `server_error`. |

### CORS
The API sends permissive CORS headers (`Access-Control-Allow-Origin: *`) and
answers `OPTIONS` preflights, so browser-based/hybrid/PWA builds and local dev
servers work out of the box. Native iOS/Android HTTP clients ignore CORS entirely.
Lock origins down with `cors_origins` in `config.php` if you want.

---

## Quick start (auth flow)

```
1. GET  api.php?action=status
        → { installed, apiVersion, hasPin, isParent, today }
        If hasPin is false, this server has never been set up.

2a. First run only:
    POST api.php?action=setup_pin   body { "pin": "1234" }   → { ok, token }

2b. Normal parent sign-in:
    POST api.php?action=parent_login  body { "pin": "1234" } → { ok, token }

3.  Save token. Send "Authorization: Bearer <token>" on all parent calls.

Child side (no token):
    POST api.php?action=child_login  body { "firstName":"Ava", "lastName":"Lee" }
        → { ok, child:{ id, firstName, lastName, color, emoji, active } }
    Use child.id as childId for tasks / day_summary / toggle_completion.
```

---

## Endpoints

Legend: **Auth** = 🔓 none · 👪 parent token · 🧒 valid childId.

### Status & setup
| Action | Method | Auth | Params / Body | Returns |
|---|---|---|---|---|
| `status` | GET | 🔓 | — | `{ installed, apiVersion, hasPin, isParent, today }` |
| `setup_pin` | POST | 🔓 (first run) | `{ pin }` 4–8 digits | `{ ok, token }` · `409 pin_exists` if already set |
| `change_pin` | POST | 👪 | `{ current, pin }` | `{ ok }` · `403 bad_pin` |

### Parent auth
| Action | Method | Auth | Params / Body | Returns |
|---|---|---|---|---|
| `parent_login` | POST | 🔓 | `{ pin }` | `{ ok, token }` · `403 bad_pin` · `429 too_many_attempts` |
| `parent_logout` | POST | 👪 | — | `{ ok }` |

### Children
| Action | Method | Auth | Params / Body | Returns |
|---|---|---|---|---|
| `child_login` | POST | 🔓 | `{ firstName, lastName }` | `{ ok, child }` · `404 not_found` |
| `children` | GET | 👪 | `?all=1` to include removed | `{ children: [child, …] }` |
| `add_child` | POST | 👪 | `{ firstName, lastName, color?, emoji? }` | `{ ok, id, reactivated? }` |
| `update_child` | POST | 👪 | `{ id, firstName, lastName, color?, emoji? }` | `{ ok }` |
| `remove_child` | POST | 👪 | `{ id }` | `{ ok }` — soft delete, keeps history |
| `delete_child_forever` | POST | 👪 | `{ id }` | `{ ok }` — erases the child + all data |

### Tasks (chores & activities)
| Action | Method | Auth | Params / Body | Returns |
|---|---|---|---|---|
| `tasks` | GET | 🧒 | `?childId=` | `{ tasks: [task, …] }` each with nested `subtasks` |
| `add_task` | POST | 👪 | `{ childId, title, type?, emoji?, daysOfWeek?, timeSlot?, timeOfDay? }` | `{ ok, id }` |
| `update_task` | POST | 👪 | `{ id, title, type?, emoji?, daysOfWeek?, timeSlot?, timeOfDay? }` | `{ ok }` |
| `remove_task` | POST | 👪 | `{ id }` | `{ ok }` — also removes its follow-ups |

- `type`: `"chore"` or `"activity"` (default `chore`).
- `daysOfWeek`: 7-char mask of `0/1`, **index 0 = Sunday … 6 = Saturday**, e.g.
  `"0111110"` = weekdays. Default `"1111111"`.
- `timeSlot`: `morning` · `midday` · `afternoon` · `evening` · `anytime`.
- `timeOfDay`: `"HH:MM"` or omit/empty for none.

### Follow-up checks (sub-steps under a task)
| Action | Method | Auth | Params / Body | Returns |
|---|---|---|---|---|
| `add_subtask` | POST | 👪 | `{ parentId, title, emoji? }` | `{ ok, id }` · `404 bad_parent` |
| `update_subtask` | POST | 👪 | `{ id, title }` | `{ ok }` |

A task is **fully complete** only when its own box *and* all of its follow-ups are
checked on that day.

### Daily check-offs (the kid action)
| Action | Method | Auth | Params / Body | Returns |
|---|---|---|---|---|
| `toggle_completion` | POST | 🧒 | `{ childId, taskId, day? }` | `{ ok, completed, day }` |

Toggles the check for `day` (default today). Works for both main tasks and
follow-up checks (pass the sub-step's id as `taskId`). `completed` is the new state.

### Day views
| Action | Method | Auth | Params / Body | Returns |
|---|---|---|---|---|
| `day_summary` | GET | 🧒 | `?childId=&day?` | day summary (below) — the kid's board + progress |
| `day_schedule` | GET | 👪 | `?childId=&day?` | `{ day, tasks: [...] }` incl. skipped, for editing |
| `set_override` | POST | 👪 | `{ taskId, day?, timeSlot?, timeOfDay?, skipped? }` | `{ ok }` / `{ ok, cleared }` |

`set_override` tweaks one task for one day only (move its time/slot, or
`skipped: "1"` to skip it) without changing the recurring schedule. Sending an
empty override clears it (reverts to the recurring default).

### Reports
| Action | Method | Auth | Params / Body | Returns |
|---|---|---|---|---|
| `report` | GET | 👪 | `?childId=&day?` | `{ report, summary }` |
| `save_report` | POST | 👪 | `{ childId, day?, mood?, behaviorRating?, highlights?, challenges?, notes?, goals? }` | `{ ok, day }` |
| `reports` | GET | 👪 | `?childId=&from?&to?` | `{ reports: [report, …] }` |
| `bhis` | GET | 👪 | `?childId=&from?&to?` | aggregated BHIS report (below) |

`behaviorRating` is 1–5. A range defaults to the last 7 days when `from`/`to` are omitted.

---

## Data shapes

**child**
```json
{ "id": "uuid", "firstName": "Ava", "lastName": "Lee",
  "color": "#5b8def", "emoji": "🙂", "active": true }
```

**task**
```json
{ "id": "uuid", "childId": "uuid", "title": "Brush teeth", "type": "chore",
  "emoji": "✅", "sortOrder": 1, "parentId": null,
  "daysOfWeek": "1111111", "timeSlot": "morning", "timeOfDay": "07:30",
  "subtasks": [ { "id": "uuid", "title": "Top and bottom", "emoji": "☑️" } ] }
```

**day_summary**
```json
{ "childId": "uuid", "day": "2026-06-05",
  "tasks": [
    { "id":"uuid","title":"Brush teeth","type":"chore","emoji":"✅",
      "slot":"morning","time":"07:30","done":true,"complete":true,
      "completedAt":"2026-06-05 07:32:10",
      "subtasks":[ {"id":"uuid","title":"Top and bottom","emoji":"☑️","done":true} ] }
  ],
  "counts": { "total":5,"done":3,"percent":72,
    "chores":{"total":3,"done":2},"activities":{"total":2,"done":1},
    "steps":{"total":4,"done":3} } }
```
`done` = main box checked; `complete` = box **and** all follow-ups checked.
`percent` counts every checkbox (mains + follow-ups) for a smooth progress bar.

**report**
```json
{ "id":"uuid","childId":"uuid","day":"2026-06-05","mood":"happy",
  "behaviorRating":4,"highlights":"…","challenges":"…","notes":"…","goals":"…",
  "updatedAt":"2026-06-05 18:00:00" }
```

**bhis** (range aggregate)
```json
{ "child": { "id":"uuid","name":"Ava Lee" },
  "range": { "from":"2026-06-01","to":"2026-06-07","days":7 },
  "stats": { "totalCompletions":24,"daysWithActivity":6,"activeTaskCount":5,
             "avgCompletionPct":80,"avgBehaviorRating":4.2,"moodCounts":{"happy":4} },
  "daily":    [ { "day":"2026-06-01","completed":4,"scheduled":5,"percent":80 } ],
  "taskFreq": [ { "title":"Brush teeth","type":"chore","count":7 } ],
  "reports":  [ /* report objects */ ] }
```

---

## Security notes

- **Always serve over HTTPS.** Tokens and kids' names travel in the request.
- **A `childId` is a capability.** Anyone with a child's `id` (a random UUID) can
  read that child's tasks and check them off — there is no per-child password.
  This is intentional (kids don't sign in), but it means: don't expose `childId`s
  publicly, and treat `children`/`add_child` (which mint and list ids) as
  parent-only (they already require the parent token).
- **PIN brute-force is throttled** server-side: 6 wrong tries from one IP triggers
  a ~15-minute lockout (`429`, with `Retry-After`). Handle `429` gracefully in the
  login screen — show the wait, don't hammer retries.
- **Tokens are bearer tokens** — store them in the platform secure store
  (iOS Keychain / Android EncryptedSharedPreferences or Keystore), not plain prefs.

---

## Versioning

Read `status.apiVersion` on launch. Today it's `"1.0"`. It will bump when the JSON
contract changes in a way an app must react to; additive fields won't bump it, so
parse leniently and ignore unknown fields.
