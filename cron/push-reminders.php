<?php
/**
 * Push notification cron. Run it every minute from cPanel:
 *
 *   * * * * * /usr/local/bin/php /home/ACCOUNT/public_html/chores.example.com/cron/push-reminders.php
 *
 * It sends three kinds of notification, all in the app's configured timezone:
 *
 *   1. Kid reminders: to the device(s) a kid is signed in on, when a chore's
 *      time arrives. Chores without an exact time use their slot's default time
 *      (config 'push' => 'slot_times'); "anytime" chores get no reminder.
 *   2. Parents, day finished: once per kid per day, when every task is done.
 *   3. Parents, chores left: once per family per evening at 'nudge_time',
 *      listing each kid who still has unfinished tasks.
 *
 * Anything more than an hour late is marked handled without sending, so the
 * first run after a deploy or an outage never sends a pile of stale alerts.
 *
 * Options: --dry  print what would be sent, send nothing, record nothing.
 */

if (PHP_SAPI !== 'cli') {
    http_response_code(403);
    exit('Forbidden');
}

define('CHORE_APP', true);
require __DIR__ . '/../lib/helpers.php';
require __DIR__ . '/../lib/db.php';
require __DIR__ . '/../lib/auth.php';
require __DIR__ . '/../lib/schedule.php';
require __DIR__ . '/../lib/push.php';

const GRACE_MINUTES = 60;
const DEFAULT_SLOT_TIMES = ['morning' => '07:30', 'midday' => '12:00', 'afternoon' => '15:30', 'evening' => '18:30'];

$dry = in_array('--dry', $argv ?? [], true);
$log = function (string $line) { echo '[' . now_ts() . '] ' . $line, "\n"; };

install_schema();

$tz = new DateTimeZone(config()['timezone'] ?? 'UTC');
$now = new DateTime('now', $tz);
$day = $now->format('Y-m-d');
$slotTimes = array_merge(DEFAULT_SLOT_TIMES, (array) push_cfg('slot_times', []));

/** Minutes since a HH:MM time today (negative = still to come). */
$minutesSince = function (string $hm) use ($now, $tz, $day): int {
    $at = DateTime::createFromFormat('Y-m-d H:i', $day . ' ' . substr($hm, 0, 5), $tz);
    return $at ? intdiv($now->getTimestamp() - $at->getTimestamp(), 60) : -1;
};

/** Claim a key and send, or (dry run) just describe. */
$deliver = function (string $key, array $devices, array $msg) use ($dry, $log): void {
    if ($dry) {
        $log("would send [$key] to " . count($devices) . " device(s): {$msg['title']} | {$msg['body']}");
        return;
    }
    if (!push_claim($key)) {
        return;
    }
    $n = push_send_devices($devices, $msg, $log);
    $log("sent [$key] to $n of " . count($devices) . " device(s): {$msg['title']}");
};

$skip = function (string $key) use ($dry): void {
    if (!$dry) {
        push_claim($key);
    }
};

$summaries = []; // childId => today's day_summary, computed once per run
$summary = function (string $childId) use (&$summaries, $day): array {
    return $summaries[$childId] ??= day_summary($childId, $day);
};

// ---- 1. Kid reminders --------------------------------------------------
$rows = db()->query(
    'SELECT d.*, c.first_name FROM push_devices d
     JOIN children c ON c.id = d.child_id AND c.active = 1 AND c.family_id = d.family_id
     WHERE d.child_id IS NOT NULL'
)->fetchAll();
$kidDevices = [];
$kidNames = [];
foreach ($rows as $r) {
    $kidDevices[$r['child_id']][] = $r;
    $kidNames[$r['child_id']] = $r['first_name'];
}
foreach ($kidDevices as $childId => $devices) {
    foreach ($summary($childId)['tasks'] as $t) {
        if ($t['done']) {
            continue;
        }
        $at = $t['time'] ?? ($slotTimes[$t['slot']] ?? null);
        if (!$at) {
            continue; // "anytime"
        }
        $late = $minutesSince($at);
        if ($late < 0) {
            continue;
        }
        $key = "r:{$t['id']}:$day";
        if ($late > GRACE_MINUTES) {
            $skip($key);
            continue;
        }
        $deliver($key, $devices, [
            'type'     => 'reminder',
            'childId'  => $childId,
            'collapse' => 'task-' . $t['id'],
            'title'    => trim(($t['emoji'] ?? '') . ' ' . $t['title']),
            'body'     => "It's time, {$kidNames[$childId]}! Check it off when you're done.",
        ]);
    }
}

// ---- 2 + 3. Parent alerts ----------------------------------------------
$parentRows = db()->query('SELECT * FROM push_devices WHERE parent_alerts = 1')->fetchAll();
$parentDevices = [];
foreach ($parentRows as $r) {
    $parentDevices[$r['family_id']][] = $r;
}
$nudgeAt = (string) push_cfg('nudge_time', '19:30');
$nudgeLate = $minutesSince($nudgeAt);

foreach ($parentDevices as $familyId => $devices) {
    $k = db()->prepare('SELECT id, first_name FROM children WHERE family_id = ? AND active = 1 ORDER BY first_name');
    $k->execute([$familyId]);
    $left = [];
    foreach ($k->fetchAll() as $child) {
        $counts = $summary($child['id'])['counts'];
        if ($counts['total'] === 0) {
            continue;
        }
        if ($counts['done'] === $counts['total']) {
            $deliver("f:{$child['id']}:$day", $devices, [
                'type'     => 'finished',
                'childId'  => $child['id'],
                'collapse' => 'finished-' . $child['id'],
                'title'    => "🎉 {$child['first_name']} finished today's list!",
                'body'     => $counts['total'] === 1 ? 'Their one task is done.' : "All {$counts['total']} tasks are done.",
            ]);
        } else {
            $n = $counts['total'] - $counts['done'];
            $left[] = $child['first_name'] . ': ' . $n . ' left';
        }
    }

    if ($nudgeLate >= 0) {
        $key = "n:$familyId:$day";
        if ($nudgeLate > GRACE_MINUTES || !$left) {
            $skip($key);
        } else {
            $deliver($key, $devices, [
                'type'     => 'nudge',
                'collapse' => 'nudge',
                'title'    => 'Chores still to do today',
                'body'     => implode(' · ', $left),
            ]);
        }
    }
}

// Old log entries are no longer needed once their day is over.
if (!$dry && (int) $now->format('i') === 0) {
    db()->prepare('DELETE FROM push_log WHERE sent_at < ?')->execute([ts_at(-3 * 86400)]);
}
