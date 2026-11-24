<?php
/**
 * A child's day: which tasks are scheduled, their effective time slot and
 * time, and what is done. Shared by api.php and the push reminder cron.
 */

if (!defined('CHORE_APP')) {
    http_response_code(403);
    exit('Forbidden');
}

// Generalized time-of-day slots, in the order they occur through the day.
const SLOTS = ['morning', 'midday', 'afternoon', 'evening', 'anytime'];

function emoji_for(string $type): string
{
    // Not ✅: next to a task's checkbox a green tick looks like it is already done.
    return $type === 'activity' ? '⭐' : '🧹';
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
