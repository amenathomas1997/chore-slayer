<?php
/**
 * Chore Tracker JSON API.
 *
 * Single entry point. Call as:  api.php?action=<name>
 * Parent-only actions require an "Authorization: Bearer <token>" header.
 */

define('CHORE_APP', true);

require __DIR__ . '/lib/helpers.php';
require __DIR__ . '/lib/db.php';
require __DIR__ . '/lib/auth.php';

// Generalized time-of-day slots, in the order they occur through the day.
const SLOTS = ['morning', 'midday', 'afternoon', 'evening', 'anytime'];

// Bumped when the JSON contract changes in a way the apps must react to.
// Apps can read it from the `status` action to check compatibility.
const API_VERSION = '2.0';

// Let the iOS / Android apps (and any cross-origin client) reach this API, and
// answer the browser CORS preflight before touching the database.
api_cors();
if (($_SERVER['REQUEST_METHOD'] ?? 'GET') === 'OPTIONS') {
    http_response_code(204);
    exit;
}

// Convert PHP errors into JSON so the client never receives a broken HTML page.
set_exception_handler(function (Throwable $e) {
    $debug = !empty(config()['debug']);
    json_out([
        'error'   => 'server_error',
        'message' => $debug ? $e->getMessage() : 'Something went wrong on the server.',
    ], 500);
});

install_schema();
if (random_int(1, 25) === 1) {
    gc_sessions();
}

$action = $_GET['action'] ?? '';
$method = $_SERVER['REQUEST_METHOD'] ?? 'GET';

switch ($action) {

    // ---- Status / setup -------------------------------------------------
    case 'status':
        json_out([
            'installed'  => true,
            'apiVersion' => API_VERSION,
            'isParent'   => is_parent(),
            'today'      => today(),
        ]);
        break;

    case 'signup': // create a new family / account
        need_post($method);
        $pin = field('pin');
        validate_pin($pin);
        $fam = create_family($pin, field('name', '') ?: null, field('email', '') ?: null);
        json_out([
            'ok'     => true,
            'family' => ['id' => $fam['id'], 'code' => $fam['family_code'], 'name' => $fam['name']],
            'token'  => start_session($fam['id']),
        ]);
        break;

    case 'change_pin':
        need_post($method);
        $familyId = require_parent();
        $current = field('current');
        if (!check_family_pin($familyId, $current)) {
            json_out(['error' => 'bad_pin', 'message' => 'Current PIN is incorrect.'], 403);
        }
        $pin = field('pin');
        validate_pin($pin);
        set_pin($familyId, $pin);
        json_out(['ok' => true]);
        break;

    // ---- Parent auth ----------------------------------------------------
    case 'parent_login': // PIN unlock, scoped to a family (by stored familyId, or familyCode on a new device/web)
        need_post($method);
        $fam = null;
        if (($fid = field('familyId', '')) !== '') {
            $fam = family_by_id($fid);
        } elseif (($fc = field('familyCode', '')) !== '') {
            $fam = family_by_code($fc);
        } else {
            json_out(['error' => 'missing_family', 'message' => 'A family is required to sign in.'], 422);
        }
        login_throttle_check();
        // Same generic error whether the family or the PIN is wrong (no enumeration).
        if (!$fam || !check_family_pin($fam['id'], field('pin'))) {
            login_register_failure();
            json_out(['error' => 'bad_pin', 'message' => 'Incorrect family code or PIN.'], 403);
        }
        login_register_success();
        json_out([
            'ok'     => true,
            'token'  => start_session($fam['id']),
            'family' => ['id' => $fam['id'], 'code' => $fam['family_code'], 'name' => $fam['name']],
        ]);
        break;

    case 'parent_logout':
        end_session();
        json_out(['ok' => true]);
        break;

    // ---- Kid login ------------------------------------------------------
    case 'child_login':
        need_post($method);
        $f = require_fields(['firstName', 'lastName', 'familyId']);
        $stmt = db()->prepare(
            'SELECT id, first_name, last_name, color, emoji FROM children
             WHERE active = 1 AND family_id = ? AND LOWER(first_name) = LOWER(?) AND LOWER(last_name) = LOWER(?)'
        );
        $stmt->execute([$f['familyId'], $f['firstName'], $f['lastName']]);
        $child = $stmt->fetch();
        if (!$child) {
            json_out(['error' => 'not_found',
                'message' => "We couldn't find that name. Ask a parent to add you, and check the spelling."], 404);
        }
        json_out(['ok' => true, 'child' => shape_child($child)]);
        break;

    // ---- Children (parent-managed) -------------------------------------
    case 'children':
        $familyId = require_parent();
        $all = ($_GET['all'] ?? '') === '1';
        $sql = 'SELECT id, first_name, last_name, color, emoji, active FROM children
                WHERE family_id = ?' . ($all ? '' : ' AND active = 1')
             . ' ORDER BY active DESC, first_name, last_name';
        $stmt = db()->prepare($sql);
        $stmt->execute([$familyId]);
        json_out(['children' => array_map('shape_child', $stmt->fetchAll())]);
        break;

    case 'add_child':
        need_post($method);
        $familyId = require_parent();
        $f = require_fields(['firstName', 'lastName']);
        $exists = db()->prepare('SELECT id FROM children WHERE family_id=? AND LOWER(first_name)=LOWER(?) AND LOWER(last_name)=LOWER(?)');
        $exists->execute([$familyId, $f['firstName'], $f['lastName']]);
        if ($row = $exists->fetch()) {
            // Reactivate a previously removed child rather than erroring.
            db()->prepare('UPDATE children SET active = 1 WHERE id = ?')->execute([$row['id']]);
            json_out(['ok' => true, 'id' => $row['id'], 'reactivated' => true]);
        }
        $id = new_id();
        db()->prepare(
            'INSERT INTO children (id, family_id, first_name, last_name, color, emoji, active, created_at)
             VALUES (?, ?, ?, ?, ?, ?, 1, ?)'
        )->execute([
            $id, $familyId, $f['firstName'], $f['lastName'],
            field('color', '#5b8def'), field('emoji', '🙂'), now_ts(),
        ]);
        json_out(['ok' => true, 'id' => $id]);
        break;

    case 'update_child':
        need_post($method);
        $familyId = require_parent();
        $f = require_fields(['id', 'firstName', 'lastName']);
        assert_child_in_family($f['id'], $familyId);
        db()->prepare('UPDATE children SET first_name=?, last_name=?, color=?, emoji=? WHERE id=?')
            ->execute([$f['firstName'], $f['lastName'], field('color', '#5b8def'), field('emoji', '🙂'), $f['id']]);
        json_out(['ok' => true]);
        break;

    case 'remove_child': // soft delete — keeps report history for BHIS records
        need_post($method);
        $familyId = require_parent();
        $id = field('id');
        assert_child_in_family($id, $familyId);
        db()->prepare('UPDATE children SET active = 0 WHERE id = ?')->execute([$id]);
        json_out(['ok' => true]);
        break;

    case 'delete_child_forever': // hard delete + all related data
        need_post($method);
        $familyId = require_parent();
        $id = field('id');
        assert_child_in_family($id, $familyId);
        $pdo = db();
        $pdo->prepare('DELETE FROM completions WHERE child_id = ?')->execute([$id]);
        $pdo->prepare('DELETE FROM tasks WHERE child_id = ?')->execute([$id]);
        $pdo->prepare('DELETE FROM reports WHERE child_id = ?')->execute([$id]);
        $pdo->prepare('DELETE FROM children WHERE id = ?')->execute([$id]);
        json_out(['ok' => true]);
        break;

    // ---- Tasks ----------------------------------------------------------
    case 'tasks': // list a child's main tasks, each with its follow-up checks nested
        $childId = $_GET['childId'] ?? '';
        require_child($childId);
        $stmt = db()->prepare(
            'SELECT id, child_id, parent_id, title, type, emoji, sort_order, active,
                    days_of_week, time_slot, time_of_day FROM tasks
             WHERE child_id = ? AND active = 1
             ORDER BY FIELD(time_slot, \'morning\',\'midday\',\'afternoon\',\'evening\',\'anytime\'), time_of_day IS NULL, time_of_day, type, sort_order, title'
        );
        $stmt->execute([$childId]);
        $rows = $stmt->fetchAll();
        $subs = [];
        foreach ($rows as $r) {
            if (!empty($r['parent_id'])) {
                $subs[$r['parent_id']][] = ['id' => $r['id'], 'title' => $r['title'], 'emoji' => $r['emoji'] ?? '☑️'];
            }
        }
        $mains = [];
        foreach ($rows as $r) {
            if (empty($r['parent_id'])) {
                $t = shape_task($r);
                $t['subtasks'] = $subs[$r['id']] ?? [];
                $mains[] = $t;
            }
        }
        json_out(['tasks' => $mains]);
        break;

    case 'add_task':
        need_post($method);
        $familyId = require_parent();
        $f = require_fields(['childId', 'title']);
        assert_child_in_family($f['childId'], $familyId);
        $type = field('type', 'chore');
        if (!in_array($type, ['chore', 'activity'], true)) {
            $type = 'chore';
        }
        $max = db()->prepare('SELECT COALESCE(MAX(sort_order), 0) FROM tasks WHERE child_id = ? AND parent_id IS NULL');
        $max->execute([$f['childId']]);
        $order = (int) $max->fetchColumn() + 1;
        $id = new_id();
        db()->prepare(
            'INSERT INTO tasks (id, child_id, title, type, emoji, sort_order, active, days_of_week, time_slot, time_of_day, created_at)
             VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?)'
        )->execute([
            $id, $f['childId'], $f['title'], $type, field('emoji', emoji_for($type)), $order,
            clean_dow(field('daysOfWeek', '1111111')), clean_slot(field('timeSlot', 'anytime')),
            clean_time(field('timeOfDay', '')), now_ts(),
        ]);
        json_out(['ok' => true, 'id' => $id]);
        break;

    case 'update_task':
        need_post($method);
        $familyId = require_parent();
        $f = require_fields(['id', 'title']);
        assert_task_in_family($f['id'], $familyId);
        $type = field('type', 'chore');
        if (!in_array($type, ['chore', 'activity'], true)) {
            $type = 'chore';
        }
        db()->prepare('UPDATE tasks SET title=?, type=?, emoji=?, days_of_week=?, time_slot=?, time_of_day=? WHERE id=?')
            ->execute([
                $f['title'], $type, field('emoji', emoji_for($type)),
                clean_dow(field('daysOfWeek', '1111111')), clean_slot(field('timeSlot', 'anytime')),
                clean_time(field('timeOfDay', '')), $f['id'],
            ]);
        json_out(['ok' => true]);
        break;

    case 'remove_task': // soft delete — also removes its follow-up checks; history kept
        need_post($method);
        $familyId = require_parent();
        $tid = field('id');
        assert_task_in_family($tid, $familyId);
        db()->prepare('UPDATE tasks SET active = 0 WHERE id = ? OR parent_id = ?')->execute([$tid, $tid]);
        json_out(['ok' => true]);
        break;

    // ---- Follow-up checks (sub-steps that appear after a task is checked) ----
    case 'add_subtask':
        need_post($method);
        $familyId = require_parent();
        $f = require_fields(['parentId', 'title']);
        assert_task_in_family($f['parentId'], $familyId);
        $p = db()->prepare('SELECT child_id, type FROM tasks WHERE id = ? AND active = 1 AND parent_id IS NULL');
        $p->execute([$f['parentId']]);
        $prow = $p->fetch();
        if (!$prow) {
            json_out(['error' => 'bad_parent', 'message' => 'Parent task not found.'], 404);
        }
        $max = db()->prepare('SELECT COALESCE(MAX(sort_order), 0) FROM tasks WHERE parent_id = ?');
        $max->execute([$f['parentId']]);
        $order = (int) $max->fetchColumn() + 1;
        $id = new_id();
        db()->prepare(
            'INSERT INTO tasks (id, child_id, parent_id, title, type, emoji, sort_order, active, days_of_week, time_slot, time_of_day, created_at)
             VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?)'
        )->execute([
            $id, $prow['child_id'], $f['parentId'], $f['title'], $prow['type'], field('emoji', '☑️'),
            $order, '1111111', 'anytime', null, now_ts(),
        ]);
        json_out(['ok' => true, 'id' => $id]);
        break;

    case 'update_subtask':
        need_post($method);
        $familyId = require_parent();
        $f = require_fields(['id', 'title']);
        assert_task_in_family($f['id'], $familyId);
        db()->prepare('UPDATE tasks SET title = ? WHERE id = ? AND parent_id IS NOT NULL')->execute([$f['title'], $f['id']]);
        json_out(['ok' => true]);
        break;

    // ---- Daily check-offs (kid action) ----------------------------------
    case 'toggle_completion':
        need_post($method);
        $f = require_fields(['childId', 'taskId']);
        require_child($f['childId']);
        $day = clean_date(field('day', ''), today());
        // Confirm task belongs to child and is active.
        $t = db()->prepare('SELECT id FROM tasks WHERE id = ? AND child_id = ? AND active = 1');
        $t->execute([$f['taskId'], $f['childId']]);
        if (!$t->fetchColumn()) {
            json_out(['error' => 'bad_task', 'message' => 'That task is not available.'], 404);
        }
        $existing = db()->prepare('SELECT id FROM completions WHERE task_id = ? AND day = ?');
        $existing->execute([$f['taskId'], $day]);
        if ($existing->fetchColumn()) {
            db()->prepare('DELETE FROM completions WHERE task_id = ? AND day = ?')->execute([$f['taskId'], $day]);
            json_out(['ok' => true, 'completed' => false, 'day' => $day]);
        }
        db()->prepare(
            'INSERT INTO completions (id, child_id, task_id, day, completed_at) VALUES (?, ?, ?, ?, ?)'
        )->execute([new_id(), $f['childId'], $f['taskId'], $day, now_ts()]);
        json_out(['ok' => true, 'completed' => true, 'day' => $day]);
        break;

    // ---- A single day's picture (kid "My Day" + parent report helper) ---
    case 'day_summary':
        $childId = $_GET['childId'] ?? '';
        require_child($childId);
        $day = clean_date($_GET['day'] ?? '', today());
        json_out(day_summary($childId, $day));
        break;

    // ---- The day's scheduled tasks for parent editing (includes skipped) -
    case 'day_schedule':
        $familyId = require_parent();
        $childId = $_GET['childId'] ?? '';
        assert_child_in_family($childId, $familyId);
        $day = clean_date($_GET['day'] ?? '', today());
        json_out(['day' => $day, 'tasks' => day_schedule($childId, $day)]);
        break;

    // ---- Per-day tweak: change a task's time/slot for one day, or skip it -
    case 'set_override':
        need_post($method);
        $familyId = require_parent();
        $f = require_fields(['taskId']);
        assert_task_in_family($f['taskId'], $familyId);
        $day = clean_date(field('day', ''), today());
        $t = db()->prepare('SELECT id FROM tasks WHERE id = ? AND active = 1');
        $t->execute([$f['taskId']]);
        if (!$t->fetchColumn()) {
            json_out(['error' => 'bad_task', 'message' => 'Task not found.'], 404);
        }
        $rawSlot = field('timeSlot', '');
        $slot = ($rawSlot === '') ? null : clean_slot($rawSlot);
        $time = clean_time(field('timeOfDay', ''));
        $skipped = field('skipped', '0') === '1' ? 1 : 0;
        // Nothing customized → remove the override (revert to the recurring default).
        if ($slot === null && $time === null && $skipped === 0) {
            db()->prepare('DELETE FROM task_overrides WHERE task_id = ? AND day = ?')->execute([$f['taskId'], $day]);
            json_out(['ok' => true, 'cleared' => true]);
        }
        $ex = db()->prepare('SELECT id FROM task_overrides WHERE task_id = ? AND day = ?');
        $ex->execute([$f['taskId'], $day]);
        $oid = $ex->fetchColumn();
        if ($oid) {
            db()->prepare('UPDATE task_overrides SET time_slot=?, time_of_day=?, skipped=? WHERE id=?')
                ->execute([$slot, $time, $skipped, $oid]);
        } else {
            db()->prepare(
                'INSERT INTO task_overrides (id, task_id, day, time_slot, time_of_day, skipped, created_at)
                 VALUES (?, ?, ?, ?, ?, ?, ?)'
            )->execute([new_id(), $f['taskId'], $day, $slot, $time, $skipped, now_ts()]);
        }
        json_out(['ok' => true]);
        break;

    // ---- Parent daily reports ------------------------------------------
    case 'report': // fetch one child's report for a day (with the day summary)
        $familyId = require_parent();
        $childId = $_GET['childId'] ?? '';
        assert_child_in_family($childId, $familyId);
        $day = clean_date($_GET['day'] ?? '', today());
        $stmt = db()->prepare('SELECT * FROM reports WHERE child_id = ? AND day = ?');
        $stmt->execute([$childId, $day]);
        json_out([
            'report'  => shape_report($stmt->fetch() ?: null),
            'summary' => day_summary($childId, $day),
        ]);
        break;

    case 'save_report':
        need_post($method);
        $familyId = require_parent();
        $f = require_fields(['childId']);
        assert_child_in_family($f['childId'], $familyId);
        $day = clean_date(field('day', ''), today());
        $rating = field('behaviorRating', '');
        $rating = ($rating === '') ? null : max(1, min(5, (int) $rating));
        $existing = db()->prepare('SELECT id FROM reports WHERE child_id = ? AND day = ?');
        $existing->execute([$f['childId'], $day]);
        $rid = $existing->fetchColumn();
        if ($rid) {
            db()->prepare(
                'UPDATE reports SET mood=?, behavior_rating=?, highlights=?, challenges=?, notes=?, goals=?, updated_at=?
                 WHERE id = ?'
            )->execute([
                field('mood', ''), $rating, field('highlights', ''), field('challenges', ''),
                field('notes', ''), field('goals', ''), now_ts(), $rid,
            ]);
        } else {
            db()->prepare(
                'INSERT INTO reports (id, child_id, day, mood, behavior_rating, highlights, challenges, notes, goals, created_at, updated_at)
                 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)'
            )->execute([
                new_id(), $f['childId'], $day, field('mood', ''), $rating,
                field('highlights', ''), field('challenges', ''), field('notes', ''),
                field('goals', ''), now_ts(), now_ts(),
            ]);
        }
        json_out(['ok' => true, 'day' => $day]);
        break;

    case 'reports': // list of reports in a range for one child
        $familyId = require_parent();
        $childId = $_GET['childId'] ?? '';
        assert_child_in_family($childId, $familyId);
        [$from, $to] = range_params();
        $stmt = db()->prepare('SELECT * FROM reports WHERE child_id = ? AND day BETWEEN ? AND ? ORDER BY day DESC');
        $stmt->execute([$childId, $from, $to]);
        json_out(['reports' => array_map('shape_report', $stmt->fetchAll())]);
        break;

    // ---- BHIS aggregated report ----------------------------------------
    case 'bhis':
        $familyId = require_parent();
        $childId = $_GET['childId'] ?? '';
        assert_child_in_family($childId, $familyId);
        [$from, $to] = range_params();
        json_out(bhis_report($childId, $from, $to));
        break;

    default:
        json_out(['error' => 'unknown_action', 'message' => "Unknown action: '$action'"], 404);
}

// ===================== helper functions =====================

function need_post(string $method): void
{
    if ($method !== 'POST') {
        json_out(['error' => 'method_not_allowed', 'message' => 'Use POST for this action.'], 405);
    }
}

/**
 * Send CORS headers so the iOS / Android apps and any cross-origin client can
 * call this API. Auth is by Authorization header (not cookies), so a wildcard
 * origin is safe — we never enable Access-Control-Allow-Credentials. To lock it
 * down, set 'cors_origins' => ['https://app.example.com', ...] in config.php.
 */
function api_cors(): void
{
    $origins = '*';
    if (is_file(__DIR__ . '/config.php')) {
        $cfg = config();
        if (!empty($cfg['cors_origins'])) {
            $origins = $cfg['cors_origins'];
        }
    }
    $origin = $_SERVER['HTTP_ORIGIN'] ?? '';
    if ($origins === '*' || $origins === ['*']) {
        header('Access-Control-Allow-Origin: *');
    } elseif ($origin !== '' && in_array($origin, (array) $origins, true)) {
        header('Access-Control-Allow-Origin: ' . $origin);
        header('Vary: Origin');
    }
    header('Access-Control-Allow-Methods: GET, POST, OPTIONS');
    header('Access-Control-Allow-Headers: Authorization, Content-Type, X-Auth-Token');
    header('Access-Control-Max-Age: 86400');
}

function validate_pin(string $pin): void
{
    if (!preg_match('/^\d{4,8}$/', $pin)) {
        json_out(['error' => 'bad_pin', 'message' => 'PIN must be 4 to 8 digits.'], 422);
    }
}

/** Ensure a child id exists & is active; 404 otherwise. */
function require_child(string $childId): void
{
    if ($childId === '') {
        json_out(['error' => 'missing_child', 'message' => 'A child must be selected.'], 422);
    }
    $stmt = db()->prepare('SELECT id FROM children WHERE id = ? AND active = 1');
    $stmt->execute([$childId]);
    if (!$stmt->fetchColumn()) {
        json_out(['error' => 'child_not_found', 'message' => 'That child was not found.'], 404);
    }
}

/** Parent-side: ensure a child belongs to this family (any active state); 404 otherwise. */
function assert_child_in_family(string $childId, string $familyId): void
{
    if ($childId === '') {
        json_out(['error' => 'missing_child', 'message' => 'A child must be selected.'], 422);
    }
    $stmt = db()->prepare('SELECT 1 FROM children WHERE id = ? AND family_id = ?');
    $stmt->execute([$childId, $familyId]);
    if (!$stmt->fetchColumn()) {
        json_out(['error' => 'child_not_found', 'message' => 'That child was not found.'], 404);
    }
}

/** Parent-side: ensure a task's child belongs to this family; 404 otherwise. */
function assert_task_in_family(string $taskId, string $familyId): void
{
    if ($taskId === '') {
        json_out(['error' => 'missing_task', 'message' => 'A task is required.'], 422);
    }
    $stmt = db()->prepare(
        'SELECT 1 FROM tasks t JOIN children c ON c.id = t.child_id WHERE t.id = ? AND c.family_id = ?'
    );
    $stmt->execute([$taskId, $familyId]);
    if (!$stmt->fetchColumn()) {
        json_out(['error' => 'task_not_found', 'message' => 'That task was not found.'], 404);
    }
}

function emoji_for(string $type): string
{
    return $type === 'activity' ? '⭐' : '✅';
}

function shape_child(array $r): array
{
    return [
        'id'        => $r['id'],
        'firstName' => $r['first_name'],
        'lastName'  => $r['last_name'],
        'color'     => $r['color'] ?? '#5b8def',
        'emoji'     => $r['emoji'] ?? '🙂',
        'active'    => isset($r['active']) ? (int) $r['active'] === 1 : true,
    ];
}

function shape_task(array $r): array
{
    return [
        'id'         => $r['id'],
        'childId'    => $r['child_id'],
        'title'      => $r['title'],
        'type'       => $r['type'],
        'emoji'      => $r['emoji'] ?? emoji_for($r['type']),
        'sortOrder'  => (int) ($r['sort_order'] ?? 0),
        'parentId'   => $r['parent_id'] ?? null,
        'daysOfWeek' => $r['days_of_week'] ?? '1111111',
        'timeSlot'   => $r['time_slot'] ?? 'anytime',
        'timeOfDay'  => !empty($r['time_of_day']) ? substr($r['time_of_day'], 0, 5) : null,
    ];
}

/** Validate a 7-char weekday mask (index 0=Sunday … 6=Saturday). */
function clean_dow(string $v): string
{
    return preg_match('/^[01]{7}$/', $v) ? $v : '1111111';
}

/** Validate a time-of-day slot. */
function clean_slot(string $v): string
{
    return in_array($v, SLOTS, true) ? $v : 'anytime';
}

/** Validate "HH:MM" (24h) → "HH:MM:00" for a TIME column, or null. */
function clean_time(string $v): ?string
{
    if ($v === '') {
        return null;
    }
    return preg_match('/^([01]\d|2[0-3]):[0-5]\d$/', $v) ? $v . ':00' : null;
}

/** Sort rank for a slot (earlier in the day = smaller). */
function slot_rank(string $slot): int
{
    $i = array_search($slot, SLOTS, true);
    return $i === false ? 99 : $i;
}

/** Day-of-week index (0=Sun … 6=Sat) for a YYYY-MM-DD date. */
function dow_index(string $day): int
{
    return (int) (new DateTime($day, new DateTimeZone(config()['timezone'] ?? 'UTC')))->format('w');
}

/** Sort a list of task arrays by slot, then time (nulls last), then title. */
function sort_by_slot_time(array &$tasks): void
{
    usort($tasks, function ($a, $b) {
        $sa = slot_rank($a['slot'] ?? $a['effectiveSlot'] ?? 'anytime');
        $sb = slot_rank($b['slot'] ?? $b['effectiveSlot'] ?? 'anytime');
        if ($sa !== $sb) {
            return $sa - $sb;
        }
        $ta = $a['time'] ?? $a['effectiveTime'] ?? null;
        $tb = $b['time'] ?? $b['effectiveTime'] ?? null;
        if ($ta === null && $tb === null) {
            return strcmp($a['title'], $b['title']);
        }
        if ($ta === null) {
            return 1;
        }
        if ($tb === null) {
            return -1;
        }
        return strcmp($ta, $tb);
    });
}

function shape_report(?array $r): ?array
{
    if (!$r) {
        return null;
    }
    return [
        'id'             => $r['id'],
        'childId'        => $r['child_id'],
        'day'            => $r['day'],
        'mood'           => $r['mood'],
        'behaviorRating' => isset($r['behavior_rating']) ? (int) $r['behavior_rating'] : null,
        'highlights'     => $r['highlights'],
        'challenges'     => $r['challenges'],
        'notes'          => $r['notes'],
        'goals'          => $r['goals'],
        'updatedAt'      => $r['updated_at'] ?? null,
    ];
}

/** from/to date range from query params; defaults to the last 7 days. */
function range_params(): array
{
    $to   = clean_date($_GET['to'] ?? '', today());
    $tz   = new DateTimeZone(config()['timezone'] ?? 'UTC');
    $def  = (new DateTime($to, $tz))->modify('-6 days')->format('Y-m-d');
    $from = clean_date($_GET['from'] ?? '', $def);
    if ($from > $to) {
        [$from, $to] = [$to, $from];
    }
    return [$from, $to];
}

/**
 * Tasks + completion state for one child on one day.
 * Only tasks scheduled for that weekday are included; per-day overrides adjust
 * the effective slot/time, and skipped tasks are left out (kid-facing view).
 */
function day_summary(string $childId, string $day): array
{
    $pos = dow_index($day) + 1; // SUBSTRING is 1-based
    $stmt = db()->prepare(
        'SELECT t.id, t.title, t.type, t.emoji, t.sort_order, t.time_slot, t.time_of_day,
                o.time_slot AS o_slot, o.time_of_day AS o_time, o.skipped AS o_skipped,
                (c.id IS NOT NULL) AS done, c.completed_at
         FROM tasks t
         LEFT JOIN task_overrides o ON o.task_id = t.id AND o.day = ?
         LEFT JOIN completions c ON c.task_id = t.id AND c.day = ?
         WHERE t.child_id = ? AND t.active = 1 AND t.parent_id IS NULL
           AND SUBSTRING(t.days_of_week, ?, 1) = 1
           AND (o.skipped IS NULL OR o.skipped = 0)'
    );
    $stmt->execute([$day, $day, $childId, $pos]);
    $rows = $stmt->fetchAll();

    // Follow-up checks (sub-steps) for the scheduled tasks, with their done state.
    $mainIds = array_column($rows, 'id');
    $subsByParent = [];
    $stepsTotal = 0;
    $stepsDone = 0;
    if ($mainIds) {
        $in = implode(',', array_fill(0, count($mainIds), '?'));
        $sq = db()->prepare(
            "SELECT s.id, s.parent_id, s.title, s.emoji, (c.id IS NOT NULL) AS done
             FROM tasks s LEFT JOIN completions c ON c.task_id = s.id AND c.day = ?
             WHERE s.parent_id IN ($in) AND s.active = 1
             ORDER BY s.sort_order, s.title"
        );
        $sq->execute(array_merge([$day], $mainIds));
        foreach ($sq->fetchAll() as $s) {
            $sDone = (int) $s['done'] === 1;
            $subsByParent[$s['parent_id']][] = [
                'id'    => $s['id'],
                'title' => $s['title'],
                'emoji' => $s['emoji'] ?? '☑️',
                'done'  => $sDone,
            ];
            $stepsTotal++;
            if ($sDone) {
                $stepsDone++;
            }
        }
    }

    $tasks = [];
    $done = 0;            // fully-complete tasks (main + all follow-ups)
    $totalUnits = 0;      // every checkbox (main + each follow-up) for granular %
    $doneUnits = 0;
    $chores = ['total' => 0, 'done' => 0];
    $acts   = ['total' => 0, 'done' => 0];
    foreach ($rows as $r) {
        $isDone = (int) $r['done'] === 1;
        $slot = !empty($r['o_slot']) ? $r['o_slot'] : $r['time_slot'];
        if (!in_array($slot, SLOTS, true)) {
            $slot = 'anytime';
        }
        $time = !empty($r['o_time']) ? $r['o_time'] : $r['time_of_day'];
        $mySubs = $subsByParent[$r['id']] ?? [];
        $subCount = count($mySubs);
        $subDone = 0;
        foreach ($mySubs as $s) {
            if (!empty($s['done'])) {
                $subDone++;
            }
        }
        $complete = $isDone && $subDone === $subCount;
        $totalUnits += 1 + $subCount;
        $doneUnits += ($isDone ? 1 : 0) + $subDone;
        $tasks[] = [
            'id'          => $r['id'],
            'title'       => $r['title'],
            'type'        => $r['type'],
            'emoji'       => $r['emoji'] ?? emoji_for($r['type']),
            'slot'        => $slot,
            'time'        => !empty($time) ? substr($time, 0, 5) : null,
            'done'        => $isDone,
            'complete'    => $complete,
            'completedAt' => $r['completed_at'],
            'subtasks'    => $mySubs,
        ];
        if ($complete) {
            $done++;
        }
        $bucket = $r['type'] === 'activity' ? 'acts' : 'chores';
        ${$bucket}['total']++;
        if ($complete) {
            ${$bucket}['done']++;
        }
    }
    sort_by_slot_time($tasks);
    $total = count($tasks);
    return [
        'childId' => $childId,
        'day'     => $day,
        'tasks'   => $tasks,
        'counts'  => [
            'total'    => $total,
            'done'     => $done,
            'percent'  => $totalUnits ? (int) round($doneUnits * 100 / $totalUnits) : 0,
            'chores'   => $chores,
            'activities' => $acts,
            'steps'    => ['total' => $stepsTotal, 'done' => $stepsDone],
        ],
    ];
}

/**
 * The day's scheduled tasks for the PARENT editor — includes skipped tasks and
 * exposes both the recurring defaults and any per-day override, so a parent can
 * adjust or restore them.
 */
function day_schedule(string $childId, string $day): array
{
    $pos = dow_index($day) + 1;
    $stmt = db()->prepare(
        'SELECT t.id, t.title, t.type, t.emoji, t.sort_order, t.time_slot, t.time_of_day,
                o.time_slot AS o_slot, o.time_of_day AS o_time, o.skipped AS o_skipped
         FROM tasks t
         LEFT JOIN task_overrides o ON o.task_id = t.id AND o.day = ?
         WHERE t.child_id = ? AND t.active = 1 AND t.parent_id IS NULL
           AND SUBSTRING(t.days_of_week, ?, 1) = 1'
    );
    $stmt->execute([$day, $childId, $pos]);

    $out = [];
    foreach ($stmt->fetchAll() as $r) {
        $effSlot = !empty($r['o_slot']) ? $r['o_slot'] : $r['time_slot'];
        if (!in_array($effSlot, SLOTS, true)) {
            $effSlot = 'anytime';
        }
        $effTime = !empty($r['o_time']) ? $r['o_time'] : $r['time_of_day'];
        $out[] = [
            'id'            => $r['id'],
            'title'         => $r['title'],
            'type'          => $r['type'],
            'emoji'         => $r['emoji'] ?? emoji_for($r['type']),
            'defaultSlot'   => $r['time_slot'],
            'defaultTime'   => !empty($r['time_of_day']) ? substr($r['time_of_day'], 0, 5) : null,
            'overrideSlot'  => !empty($r['o_slot']) ? $r['o_slot'] : null,
            'overrideTime'  => !empty($r['o_time']) ? substr($r['o_time'], 0, 5) : null,
            'skipped'       => (int) $r['o_skipped'] === 1,
            'effectiveSlot' => $effSlot,
            'effectiveTime' => !empty($effTime) ? substr($effTime, 0, 5) : null,
        ];
    }
    sort_by_slot_time($out);
    return $out;
}

/** Aggregate completions + reports across a date range for a BHIS summary. */
function bhis_report(string $childId, string $from, string $to): array
{
    $child = db()->prepare('SELECT id, first_name, last_name FROM children WHERE id = ?');
    $child->execute([$childId]);
    $childRow = $child->fetch();

    // All days in range.
    $tz = new DateTimeZone(config()['timezone'] ?? 'UTC');
    $cursor = new DateTime($from, $tz);
    $end = new DateTime($to, $tz);
    $days = [];
    while ($cursor <= $end) {
        $days[] = $cursor->format('Y-m-d');
        $cursor->modify('+1 day');
    }

    // Active main-task count, and how many fall on each weekday (for per-day %).
    $tc = db()->prepare('SELECT days_of_week FROM tasks WHERE child_id = ? AND active = 1 AND parent_id IS NULL');
    $tc->execute([$childId]);
    $schedByWeekday = array_fill(0, 7, 0); // index 0=Sun … 6=Sat
    $activeTotal = 0;
    foreach ($tc->fetchAll(PDO::FETCH_COLUMN) as $dow) {
        $activeTotal++;
        for ($i = 0; $i < 7; $i++) {
            if (($dow[$i] ?? '0') === '1') {
                $schedByWeekday[$i]++;
            }
        }
    }

    // Tasks skipped via per-day override (so they don't inflate the denominator).
    $sk = db()->prepare(
        'SELECT o.day, COUNT(*) n FROM task_overrides o
         JOIN tasks t ON t.id = o.task_id
         WHERE t.child_id = ? AND o.skipped = 1 AND o.day BETWEEN ? AND ?
         GROUP BY o.day'
    );
    $sk->execute([$childId, $from, $to]);
    $skipByDay = [];
    foreach ($sk->fetchAll() as $row) {
        $skipByDay[$row['day']] = (int) $row['n'];
    }

    // How many follow-up checks each main task has (a task is "complete" only when
    // its main box AND all of these are checked on the same day).
    $scN = db()->prepare(
        'SELECT parent_id, COUNT(*) n FROM tasks
         WHERE child_id = ? AND active = 1 AND parent_id IS NOT NULL GROUP BY parent_id'
    );
    $scN->execute([$childId]);
    $subCount = [];
    foreach ($scN->fetchAll() as $row) {
        $subCount[$row['parent_id']] = (int) $row['n'];
    }

    // Main-task check-offs per (day, task).
    $mc = db()->prepare(
        'SELECT c.day, c.task_id FROM completions c
         JOIN tasks t ON t.id = c.task_id
         WHERE c.child_id = ? AND t.parent_id IS NULL AND c.day BETWEEN ? AND ?'
    );
    $mc->execute([$childId, $from, $to]);
    $mainDone = [];
    foreach ($mc->fetchAll() as $row) {
        $mainDone[$row['day']][$row['task_id']] = true;
    }

    // Follow-up check-offs per (day, parent task).
    $scD = db()->prepare(
        'SELECT c.day, t.parent_id, COUNT(*) n FROM completions c
         JOIN tasks t ON t.id = c.task_id
         WHERE c.child_id = ? AND t.parent_id IS NOT NULL AND c.day BETWEEN ? AND ?
         GROUP BY c.day, t.parent_id'
    );
    $scD->execute([$childId, $from, $to]);
    $subDone = [];
    foreach ($scD->fetchAll() as $row) {
        $subDone[$row['day']][$row['parent_id']] = (int) $row['n'];
    }

    // Per day: count fully-complete tasks, and whether any task was started.
    $compByDay = [];
    $startedByDay = [];
    foreach ($mainDone as $d => $mains) {
        $startedByDay[$d] = count($mains);
        $full = 0;
        foreach ($mains as $mid => $_x) {
            if (($subDone[$d][$mid] ?? 0) >= ($subCount[$mid] ?? 0)) {
                $full++;
            }
        }
        $compByDay[$d] = $full;
    }

    // Completion frequency per task (which tasks done most/least).
    $tf = db()->prepare(
        'SELECT t.title, t.type, COUNT(c.id) n
         FROM tasks t LEFT JOIN completions c
           ON c.task_id = t.id AND c.day BETWEEN ? AND ?
         WHERE t.child_id = ? AND t.parent_id IS NULL
         GROUP BY t.id, t.title, t.type
         ORDER BY n DESC, t.title'
    );
    $tf->execute([$from, $to, $childId]);
    $taskFreq = array_map(function ($r) {
        return ['title' => $r['title'], 'type' => $r['type'], 'count' => (int) $r['n']];
    }, $tf->fetchAll());

    // Reports in range.
    $rp = db()->prepare('SELECT * FROM reports WHERE child_id = ? AND day BETWEEN ? AND ? ORDER BY day');
    $rp->execute([$childId, $from, $to]);
    $reports = array_map('shape_report', $rp->fetchAll());

    // Daily breakdown + aggregate stats.
    $dailyRows = [];
    $totalCompletions = 0;
    $daysWithActivity = 0;
    $percentSum = 0;
    $percentDays = 0;
    foreach ($days as $d) {
        $n = $compByDay[$d] ?? 0; // fully-complete tasks that day
        $totalCompletions += $n;
        if (($startedByDay[$d] ?? 0) > 0) {
            $daysWithActivity++;
        }
        // How many tasks were scheduled that weekday, minus any skipped that day.
        $w = dow_index($d);
        $scheduled = max(0, $schedByWeekday[$w] - ($skipByDay[$d] ?? 0));
        $pct = $scheduled ? min(100, (int) round($n * 100 / $scheduled)) : 0;
        if ($scheduled) {
            $percentSum += $pct;
            $percentDays++;
        }
        $dailyRows[] = ['day' => $d, 'completed' => $n, 'scheduled' => $scheduled, 'percent' => $pct];
    }

    // Mood + behavior aggregates from reports.
    $moodCounts = [];
    $ratingSum = 0;
    $ratingN = 0;
    foreach ($reports as $rep) {
        if (!empty($rep['mood'])) {
            $moodCounts[$rep['mood']] = ($moodCounts[$rep['mood']] ?? 0) + 1;
        }
        if ($rep['behaviorRating'] !== null) {
            $ratingSum += $rep['behaviorRating'];
            $ratingN++;
        }
    }

    return [
        'child' => $childRow ? [
            'id'   => $childRow['id'],
            'name' => $childRow['first_name'] . ' ' . $childRow['last_name'],
        ] : null,
        'range' => ['from' => $from, 'to' => $to, 'days' => count($days)],
        'stats' => [
            'totalCompletions'  => $totalCompletions,
            'daysWithActivity'  => $daysWithActivity,
            'activeTaskCount'   => $activeTotal,
            'avgCompletionPct'  => $percentDays ? (int) round($percentSum / $percentDays) : 0,
            'avgBehaviorRating' => $ratingN ? round($ratingSum / $ratingN, 1) : null,
            'moodCounts'        => $moodCounts,
        ],
        'daily'    => $dailyRows,
        'taskFreq' => $taskFreq,
        'reports'  => $reports,
    ];
}
