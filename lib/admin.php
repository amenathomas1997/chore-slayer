<?php
/**
 * Site administration: one admin account that can see and manage every family.
 *
 * The admin password is separate from any family PIN. It is never set through
 * the web: run tools/admin/set-admin-password.sh over SSH, which stores only a
 * bcrypt hash in the admin_auth table. Admin sessions are short (1 hour, or 20
 * minutes idle) and are sent in the X-Admin-Token header, so they can never be
 * confused with a parent's Bearer token.
 */

if (!defined('CHORE_APP')) {
    http_response_code(403);
    exit('Forbidden');
}

const ADMIN_SESSION_SECONDS = 3600;
const ADMIN_IDLE_SECONDS    = 1200;

function admin_install_schema(): void
{
    $pdo = db();
    $pdo->exec("CREATE TABLE IF NOT EXISTS admin_auth (
        id TINYINT UNSIGNED NOT NULL PRIMARY KEY,
        pass_hash VARCHAR(255) NOT NULL,
        updated_at DATETIME NOT NULL
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    $pdo->exec("CREATE TABLE IF NOT EXISTS admin_sessions (
        token_hash CHAR(64) NOT NULL PRIMARY KEY,
        created_at DATETIME NOT NULL,
        expires_at DATETIME NOT NULL,
        last_seen DATETIME NOT NULL
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
}

function admin_pass_hash(): ?string
{
    $h = db()->query('SELECT pass_hash FROM admin_auth WHERE id = 1')->fetchColumn();
    return $h ?: null;
}

function admin_token(): ?string
{
    $t = trim($_SERVER['HTTP_X_ADMIN_TOKEN'] ?? '');
    return preg_match('/^[a-f0-9]{64}$/', $t) ? $t : null;
}

/** Start an admin session. Only a SHA-256 of the token is stored. */
function admin_start_session(): string
{
    $token = bin2hex(random_bytes(32));
    $pdo = db();
    $pdo->prepare('DELETE FROM admin_sessions WHERE expires_at < ? OR last_seen < ?')
        ->execute([now_ts(), ts_at(-ADMIN_IDLE_SECONDS)]);
    $pdo->prepare('INSERT INTO admin_sessions (token_hash, created_at, expires_at, last_seen) VALUES (?, ?, ?, ?)')
        ->execute([hash('sha256', $token), now_ts(), ts_at(ADMIN_SESSION_SECONDS), now_ts()]);
    return $token;
}

/** Stop with 401 unless the request carries a live admin session. */
function require_admin(): void
{
    $t = admin_token();
    if ($t) {
        $h = hash('sha256', $t);
        $s = db()->prepare('SELECT 1 FROM admin_sessions WHERE token_hash = ? AND expires_at > ? AND last_seen > ?');
        $s->execute([$h, now_ts(), ts_at(-ADMIN_IDLE_SECONDS)]);
        if ($s->fetchColumn()) {
            db()->prepare('UPDATE admin_sessions SET last_seen = ? WHERE token_hash = ?')->execute([now_ts(), $h]);
            return;
        }
    }
    json_out(['error' => 'admin_required', 'message' => 'Admin sign-in required.'], 401);
}

function admin_end_session(): void
{
    if ($t = admin_token()) {
        db()->prepare('DELETE FROM admin_sessions WHERE token_hash = ?')->execute([hash('sha256', $t)]);
    }
}

/** Every family with counts the admin board shows. */
function admin_list_families(): array
{
    $sql = "SELECT f.id, f.family_code AS code, f.name, f.created_at AS createdAt,
                (SELECT COUNT(*) FROM children c WHERE c.family_id = f.id AND c.active = 1) AS children,
                (SELECT COUNT(*) FROM tasks t JOIN children c ON c.id = t.child_id
                    WHERE c.family_id = f.id AND t.active = 1 AND t.parent_id IS NULL) AS tasks,
                (SELECT MAX(cp.day) FROM completions cp JOIN children c ON c.id = cp.child_id
                    WHERE c.family_id = f.id) AS lastActive,
                (SELECT COUNT(*) FROM sessions s WHERE s.family_id = f.id AND s.expires_at > ?) AS signedIn
            FROM families f ORDER BY f.created_at DESC";
    $s = db()->prepare($sql);
    $s->execute([now_ts()]);
    $rows = $s->fetchAll();
    foreach ($rows as &$r) {
        foreach (['children', 'tasks', 'signedIn'] as $k) {
            $r[$k] = (int) $r[$k];
        }
    }
    return $rows;
}

function admin_family_or_404(string $id): array
{
    $fam = family_by_id($id);
    if (!$fam) {
        json_out(['error' => 'not_found', 'message' => 'That family no longer exists.'], 404);
    }
    return $fam;
}
