<?php
/**
 * Shared helpers: config loading, JSON I/O, request parsing, dates.
 */

if (!defined('CHORE_APP')) {
    http_response_code(403);
    exit('Forbidden');
}

/** Load and cache config.php (falls back to a clear error if missing). */
function config(): array
{
    static $cfg = null;
    if ($cfg !== null) {
        return $cfg;
    }
    $path = dirname(__DIR__) . '/config.php';
    if (!file_exists($path)) {
        json_out([
            'error' => 'not_configured',
            'message' => 'config.php is missing. Copy config.sample.php to config.php and add your MySQL details.',
        ], 500);
    }
    $cfg = require $path;
    if (!is_array($cfg)) {
        json_out(['error' => 'bad_config', 'message' => 'config.php must return an array.'], 500);
    }
    return $cfg;
}

/** Send a JSON response and stop. */
function json_out($data, int $code = 200): void
{
    if (!headers_sent()) {
        http_response_code($code);
        header('Content-Type: application/json; charset=utf-8');
        header('Cache-Control: no-store');
    }
    echo json_encode($data, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    exit;
}

/** Decode the JSON request body into an associative array. */
function body(): array
{
    static $parsed = null;
    if ($parsed !== null) {
        return $parsed;
    }
    $raw = file_get_contents('php://input');
    if ($raw === '' || $raw === false) {
        $parsed = [];
        return $parsed;
    }
    $data = json_decode($raw, true);
    $parsed = is_array($data) ? $data : [];
    return $parsed;
}

/** Trimmed string field from the JSON body (or $default). */
function field(string $key, $default = ''): string
{
    $b = body();
    if (!array_key_exists($key, $b) || $b[$key] === null) {
        return $default;
    }
    return trim((string) $b[$key]);
}

/** Require listed fields to be present & non-empty; returns them as an array. */
function require_fields(array $keys): array
{
    $out = [];
    $missing = [];
    foreach ($keys as $k) {
        $v = field($k, '');
        if ($v === '') {
            $missing[] = $k;
        }
        $out[$k] = $v;
    }
    if ($missing) {
        json_out(['error' => 'missing_fields', 'fields' => $missing,
            'message' => 'Please fill in: ' . implode(', ', $missing)], 422);
    }
    return $out;
}

/** A GUID for primary keys. */
function new_id(): string
{
    $d = random_bytes(16);
    $d[6] = chr((ord($d[6]) & 0x0f) | 0x40);
    $d[8] = chr((ord($d[8]) & 0x3f) | 0x80);
    return vsprintf('%s%s-%s-%s-%s-%s%s%s', str_split(bin2hex($d), 4));
}

/** Today's date (YYYY-MM-DD) in the configured timezone. */
function today(): string
{
    $tz = new DateTimeZone(config()['timezone'] ?? 'UTC');
    return (new DateTime('now', $tz))->format('Y-m-d');
}

/** Current timestamp (YYYY-MM-DD HH:MM:SS) in the configured timezone. */
function now_ts(): string
{
    $tz = new DateTimeZone(config()['timezone'] ?? 'UTC');
    return (new DateTime('now', $tz))->format('Y-m-d H:i:s');
}

/** A timestamp offset from now (in seconds; negative = in the past), configured tz. */
function ts_at(int $offsetSeconds): string
{
    $tz = new DateTimeZone(config()['timezone'] ?? 'UTC');
    $dt = new DateTime('now', $tz);
    $dt->modify(($offsetSeconds < 0 ? '-' : '+') . abs($offsetSeconds) . ' seconds');
    return $dt->format('Y-m-d H:i:s');
}

/** Validate a YYYY-MM-DD string; returns it, or $fallback if invalid. */
function clean_date(string $value, ?string $fallback = null): ?string
{
    $d = DateTime::createFromFormat('Y-m-d', $value);
    if ($d && $d->format('Y-m-d') === $value) {
        return $value;
    }
    return $fallback;
}
