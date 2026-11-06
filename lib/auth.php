<?php
/**
 * Parent authentication: PIN hashing and token-based sessions.
 *
 * Kids "log in" only by typing their name (not secure by design — it just
 * picks which dashboard to show). Everything that changes the household
 * (adding/removing kids, editing tasks, writing reports, BHIS exports) is
 * gated behind the parent PIN via require_parent().
 */

if (!defined('CHORE_APP')) {
    http_response_code(403);
    exit('Forbidden');
}

/* -------------------------------------------------------------------------
 * Families / accounts.
 *
 * A family is identified internally by a UUID and publicly by a short
 * family_code; the PIN is its secret. A PIN is only ever verified within a
 * known family, so PINs need NOT be unique across families. The family_code is
 * the durable handle for adding a second device, signing in on the web in a
 * fresh browser, or recovering — because a PIN alone is neither unique nor
 * recoverable.
 * ---------------------------------------------------------------------- */

/** Code alphabet with ambiguous characters removed (no 0/O/1/I). */
const FAMILY_CODE_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';

/** Generate a unique 8-char family code (retries on the tiny chance of a clash). */
function generate_family_code(): string
{
    $alpha = FAMILY_CODE_ALPHABET;
    $len = strlen($alpha);
    for ($attempt = 0; $attempt < 25; $attempt++) {
        $code = '';
        for ($i = 0; $i < 8; $i++) {
            $code .= $alpha[random_int(0, $len - 1)];
        }
        $stmt = db()->prepare('SELECT 1 FROM families WHERE family_code = ?');
        $stmt->execute([$code]);
        if (!$stmt->fetchColumn()) {
            return $code;
        }
    }
    return substr(strtoupper(bin2hex(random_bytes(8))), 0, 12); // astronomically unlikely fallback
}

/** Normalize a user-entered family code (strip spaces/dashes, uppercase). */
function normalize_family_code(string $code): string
{
    return strtoupper(preg_replace('/[^A-Za-z0-9]/', '', $code));
}

/** Create a family and return [id, family_code, name]. */
function create_family(string $pin, ?string $name, ?string $email): array
{
    $id   = new_id();
    $code = generate_family_code();
    db()->prepare(
        'INSERT INTO families (id, family_code, pin_hash, name, email, created_at) VALUES (?, ?, ?, ?, ?, ?)'
    )->execute([$id, $code, password_hash($pin, PASSWORD_DEFAULT), $name ?: null, $email ?: null, now_ts()]);
    return ['id' => $id, 'family_code' => $code, 'name' => $name ?: null];
}

/** Look up a family by id (or null). */
function family_by_id(string $id): ?array
{
    $stmt = db()->prepare('SELECT * FROM families WHERE id = ?');
    $stmt->execute([$id]);
    return $stmt->fetch() ?: null;
}

/** Look up a family by its (normalized) code (or null). */
function family_by_code(string $code): ?array
{
    $stmt = db()->prepare('SELECT * FROM families WHERE family_code = ?');
    $stmt->execute([normalize_family_code($code)]);
    return $stmt->fetch() ?: null;
}

/** Store a new PIN for a family (signup or PIN change). */
function set_pin(string $familyId, string $pin): void
{
    db()->prepare('UPDATE families SET pin_hash = ? WHERE id = ?')
        ->execute([password_hash($pin, PASSWORD_DEFAULT), $familyId]);
}

/** Verify a PIN against a specific family's stored hash. */
function check_family_pin(string $familyId, string $pin): bool
{
    $stmt = db()->prepare('SELECT pin_hash FROM families WHERE id = ?');
    $stmt->execute([$familyId]);
    $hash = $stmt->fetchColumn();
    return $hash ? password_verify($pin, $hash) : false;
}

/** Create a session for a family and return its token. */
function start_session(string $familyId): string
{
    $token = bin2hex(random_bytes(24)); // 48 hex chars
    $days = (int) (config()['session_days'] ?? 30);
    $tz = new DateTimeZone(config()['timezone'] ?? 'UTC');
    $created = new DateTime('now', $tz);
    $expires = (clone $created)->modify("+{$days} days");
    db()->prepare('INSERT INTO sessions (token, family_id, created_at, expires_at) VALUES (?, ?, ?, ?)')
        ->execute([$token, $familyId, $created->format('Y-m-d H:i:s'), $expires->format('Y-m-d H:i:s')]);
    return $token;
}

/** Pull the auth token from request headers. */
function request_token(): ?string
{
    $auth = $_SERVER['HTTP_AUTHORIZATION'] ?? $_SERVER['REDIRECT_HTTP_AUTHORIZATION'] ?? '';
    if ($auth && stripos($auth, 'Bearer ') === 0) {
        return trim(substr($auth, 7));
    }
    if (!empty($_SERVER['HTTP_X_AUTH_TOKEN'])) {
        return trim($_SERVER['HTTP_X_AUTH_TOKEN']);
    }
    return null;
}

/** The family_id for the current request's valid token, or null. */
function current_family_id(): ?string
{
    $token = request_token();
    if (!$token) {
        return null;
    }
    $stmt = db()->prepare('SELECT family_id FROM sessions WHERE token = ? AND expires_at > ?');
    $stmt->execute([$token, now_ts()]);
    $fid = $stmt->fetchColumn();
    return $fid ?: null;
}

/** Is the current request from a signed-in parent? */
function is_parent(): bool
{
    return current_family_id() !== null;
}

/** End the current session (logout). */
function end_session(): void
{
    $token = request_token();
    if ($token) {
        db()->prepare('DELETE FROM sessions WHERE token = ?')->execute([$token]);
    }
}

/** Stop with 401 unless a valid parent session is present; returns its family id. */
function require_parent(): string
{
    $fid = current_family_id();
    if ($fid === null) {
        json_out(['error' => 'unauthorized', 'message' => 'Parent sign-in required.'], 401);
    }
    return $fid;
}

/** Occasionally purge expired sessions and stale lockouts so the tables stay small. */
function gc_sessions(): void
{
    db()->prepare('DELETE FROM sessions WHERE expires_at < ?')->execute([now_ts()]);
    db()->prepare(
        'DELETE FROM login_attempts WHERE updated_at < ? AND (locked_until IS NULL OR locked_until < ?)'
    )->execute([ts_at(-LOGIN_WINDOW_SECONDS), now_ts()]);
}

/*
 * Brute-force protection for the parent PIN.
 *
 * The PIN is the only credential and the API becomes internet-facing once the
 * apps connect, so we cap wrong guesses per client IP. After LOGIN_MAX_ATTEMPTS
 * wrong tries within LOGIN_WINDOW_SECONDS, that IP is locked out for
 * LOGIN_LOCK_SECONDS. A correct PIN clears the counter immediately.
 */
const LOGIN_MAX_ATTEMPTS  = 6;
const LOGIN_WINDOW_SECONDS = 900; // 15 min of quiet resets the counter
const LOGIN_LOCK_SECONDS   = 900; // a lockout lasts 15 min

/** Best-effort client IP (REMOTE_ADDR only — proxy headers are easily spoofed). */
function client_ip(): string
{
    return substr($_SERVER['REMOTE_ADDR'] ?? '0.0.0.0', 0, 45);
}

/** Stop with 429 if this IP is currently locked out. Call BEFORE checking the PIN. */
function login_throttle_check(): void
{
    $stmt = db()->prepare('SELECT locked_until FROM login_attempts WHERE ip = ?');
    $stmt->execute([client_ip()]);
    $until = $stmt->fetchColumn();
    if ($until && $until > now_ts()) {
        header('Retry-After: ' . LOGIN_LOCK_SECONDS);
        json_out([
            'error'   => 'too_many_attempts',
            'message' => 'Too many incorrect PIN attempts. Please wait about 15 minutes and try again.',
        ], 429);
    }
}

/** Record a wrong-PIN attempt; lock the IP out once it reaches the limit. */
function login_register_failure(): void
{
    $ip  = client_ip();
    $now = now_ts();
    $stmt = db()->prepare('SELECT attempts, updated_at FROM login_attempts WHERE ip = ?');
    $stmt->execute([$ip]);
    $row = $stmt->fetch();

    // Keep counting within the active window; otherwise start fresh at 1.
    $attempts = ($row && $row['updated_at'] >= ts_at(-LOGIN_WINDOW_SECONDS))
        ? (int) $row['attempts'] + 1
        : 1;
    $lockedUntil = $attempts >= LOGIN_MAX_ATTEMPTS ? ts_at(LOGIN_LOCK_SECONDS) : null;

    db()->prepare(
        'INSERT INTO login_attempts (ip, attempts, locked_until, updated_at) VALUES (?, ?, ?, ?)
         ON DUPLICATE KEY UPDATE attempts = VALUES(attempts), locked_until = VALUES(locked_until), updated_at = VALUES(updated_at)'
    )->execute([$ip, $attempts, $lockedUntil, $now]);
}

/** Clear the failure counter after a correct PIN. */
function login_register_success(): void
{
    db()->prepare('DELETE FROM login_attempts WHERE ip = ?')->execute([client_ip()]);
}
