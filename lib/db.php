<?php
/**
 * Database connection + schema installation (idempotent).
 */

if (!defined('CHORE_APP')) {
    http_response_code(403);
    exit('Forbidden');
}

/** Get the shared PDO connection (connects on first call). */
function db(): PDO
{
    static $pdo = null;
    if ($pdo instanceof PDO) {
        return $pdo;
    }
    $cfg = config();
    $dsn = sprintf(
        'mysql:host=%s;dbname=%s;charset=%s',
        $cfg['db_host'] ?? 'localhost',
        $cfg['db_name'] ?? '',
        $cfg['db_charset'] ?? 'utf8mb4'
    );
    try {
        $pdo = new PDO($dsn, $cfg['db_user'] ?? '', $cfg['db_pass'] ?? '', [
            PDO::ATTR_ERRMODE            => PDO::ERRMODE_EXCEPTION,
            PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
            PDO::ATTR_EMULATE_PREPARES   => false,
        ]);
    } catch (Throwable $e) {
        $msg = !empty($cfg['debug'])
            ? 'Database connection failed: ' . $e->getMessage()
            : 'Could not connect to the database. Double-check the db_* values in config.php.';
        json_out(['error' => 'db_connection', 'message' => $msg], 500);
    }
    return $pdo;
}

/** Create tables if they do not exist. Safe to run on every request. */
function install_schema(): void
{
    $pdo = db();
    $statements = [
        "CREATE TABLE IF NOT EXISTS settings (
            id TINYINT UNSIGNED NOT NULL PRIMARY KEY,
            parent_pin_hash VARCHAR(255) NULL,
            household_name VARCHAR(120) NULL,
            created_at DATETIME NOT NULL
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",

        /* A family / account. Each family signs in with its own PIN. The
           family_code is a durable, shareable handle (used to add a 2nd device,
           sign in on the web in a fresh browser, or recover) because a PIN
           alone is neither unique across families nor recoverable. email is
           reserved for an optional future 'email me my code' — unused for now. */
        "CREATE TABLE IF NOT EXISTS families (
            id CHAR(36) NOT NULL PRIMARY KEY,
            family_code VARCHAR(16) NOT NULL,
            pin_hash VARCHAR(255) NOT NULL,
            name VARCHAR(120) NULL,
            email VARCHAR(190) NULL,
            created_at DATETIME NOT NULL,
            UNIQUE KEY uniq_family_code (family_code)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",

        "CREATE TABLE IF NOT EXISTS children (
            id CHAR(36) NOT NULL PRIMARY KEY,
            first_name VARCHAR(80) NOT NULL,
            last_name VARCHAR(80) NOT NULL,
            color VARCHAR(20) NULL,
            emoji VARCHAR(16) NULL,
            active TINYINT(1) NOT NULL DEFAULT 1,
            created_at DATETIME NOT NULL,
            UNIQUE KEY uniq_name (first_name, last_name)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",

        "CREATE TABLE IF NOT EXISTS tasks (
            id CHAR(36) NOT NULL PRIMARY KEY,
            child_id CHAR(36) NOT NULL,
            parent_id CHAR(36) NULL,
            title VARCHAR(160) NOT NULL,
            type ENUM('chore','activity') NOT NULL DEFAULT 'chore',
            emoji VARCHAR(16) NULL,
            sort_order INT NOT NULL DEFAULT 0,
            active TINYINT(1) NOT NULL DEFAULT 1,
            days_of_week CHAR(7) NOT NULL DEFAULT '1111111',
            time_slot VARCHAR(12) NOT NULL DEFAULT 'anytime',
            time_of_day TIME NULL,
            created_at DATETIME NOT NULL,
            INDEX idx_task_child (child_id),
            INDEX idx_task_parent (parent_id)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",

        /* Per-day tweaks to a recurring task: change its time/slot for one day,
           or skip it that day, without altering the recurring schedule. */
        "CREATE TABLE IF NOT EXISTS task_overrides (
            id CHAR(36) NOT NULL PRIMARY KEY,
            task_id CHAR(36) NOT NULL,
            day DATE NOT NULL,
            time_slot VARCHAR(12) NULL,
            time_of_day TIME NULL,
            skipped TINYINT(1) NOT NULL DEFAULT 0,
            created_at DATETIME NOT NULL,
            UNIQUE KEY uniq_task_day_ovr (task_id, day),
            INDEX idx_ovr_day (day)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",

        "CREATE TABLE IF NOT EXISTS completions (
            id CHAR(36) NOT NULL PRIMARY KEY,
            child_id CHAR(36) NOT NULL,
            task_id CHAR(36) NOT NULL,
            day DATE NOT NULL,
            completed_at DATETIME NOT NULL,
            UNIQUE KEY uniq_task_day (task_id, day),
            INDEX idx_comp_child_day (child_id, day)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",

        "CREATE TABLE IF NOT EXISTS reports (
            id CHAR(36) NOT NULL PRIMARY KEY,
            child_id CHAR(36) NOT NULL,
            day DATE NOT NULL,
            mood VARCHAR(20) NULL,
            behavior_rating TINYINT NULL,
            highlights TEXT NULL,
            challenges TEXT NULL,
            notes TEXT NULL,
            goals TEXT NULL,
            created_at DATETIME NOT NULL,
            updated_at DATETIME NOT NULL,
            UNIQUE KEY uniq_child_day (child_id, day),
            INDEX idx_report_child_day (child_id, day)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",

        "CREATE TABLE IF NOT EXISTS sessions (
            token CHAR(48) NOT NULL PRIMARY KEY,
            created_at DATETIME NOT NULL,
            expires_at DATETIME NOT NULL
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",

        /* Throttles wrong-PIN attempts per client IP. The PIN is the only
           credential and the API is internet-facing once the apps connect,
           so repeated guesses get locked out for a while. */
        "CREATE TABLE IF NOT EXISTS login_attempts (
            ip VARCHAR(45) NOT NULL PRIMARY KEY,
            attempts INT UNSIGNED NOT NULL DEFAULT 0,
            locked_until DATETIME NULL,
            updated_at DATETIME NOT NULL
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
    ];
    foreach ($statements as $sql) {
        $pdo->exec($sql);
    }

    // Upgrades for installs created before recurrence/time was added.
    ensure_column('tasks', 'days_of_week', "ALTER TABLE tasks ADD COLUMN days_of_week CHAR(7) NOT NULL DEFAULT '1111111'");
    ensure_column('tasks', 'time_slot',    "ALTER TABLE tasks ADD COLUMN time_slot VARCHAR(12) NOT NULL DEFAULT 'anytime'");
    ensure_column('tasks', 'time_of_day',  "ALTER TABLE tasks ADD COLUMN time_of_day TIME NULL");
    ensure_column('tasks', 'parent_id',    "ALTER TABLE tasks ADD COLUMN parent_id CHAR(36) NULL");

    // Ensure the legacy settings row exists (kept only so old installs migrate).
    $pdo->prepare('INSERT IGNORE INTO settings (id, created_at) VALUES (1, ?)')
        ->execute([now_ts()]);

    // ---- Multi-family (multi-tenant) upgrade -------------------------------
    // Scope children + sessions to a family, wrap any pre-upgrade data into one
    // family, then make child names unique per-family instead of globally.
    ensure_column('children', 'family_id', 'ALTER TABLE children ADD COLUMN family_id CHAR(36) NULL');
    ensure_column('sessions', 'family_id', 'ALTER TABLE sessions ADD COLUMN family_id CHAR(36) NULL');
    ensure_index('children', 'idx_child_family', 'ALTER TABLE children ADD INDEX idx_child_family (family_id)');
    migrate_legacy_to_family();
    if (index_exists('children', 'uniq_name')) {
        $pdo->exec('ALTER TABLE children DROP INDEX uniq_name');
    }
    ensure_index('children', 'uniq_family_name',
        'ALTER TABLE children ADD UNIQUE KEY uniq_family_name (family_id, first_name, last_name)');
}

/** Add a column only if it does not already exist (portable across MySQL/MariaDB). */
function ensure_column(string $table, string $column, string $alterSql): void
{
    $stmt = db()->prepare(
        'SELECT COUNT(*) FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?'
    );
    $stmt->execute([$table, $column]);
    if ((int) $stmt->fetchColumn() === 0) {
        db()->exec($alterSql);
    }
}

/** True if a named index exists on a table. */
function index_exists(string $table, string $index): bool
{
    $stmt = db()->prepare(
        'SELECT COUNT(*) FROM information_schema.STATISTICS
         WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?'
    );
    $stmt->execute([$table, $index]);
    return (int) $stmt->fetchColumn() > 0;
}

/** Add an index only if it does not already exist. */
function ensure_index(string $table, string $index, string $alterSql): void
{
    if (!index_exists($table, $index)) {
        db()->exec($alterSql);
    }
}

/**
 * One-time wrap of pre-multi-tenant data. If any children predate the family
 * column and a legacy single PIN exists, create one family from it and attach
 * all orphaned children + sessions to it. Idempotent: a no-op once everything
 * has a family_id. (generate_family_code() lives in auth.php, which api.php
 * loads before install_schema() runs.)
 */
function migrate_legacy_to_family(): void
{
    $pdo = db();
    $orphans = (int) $pdo->query('SELECT COUNT(*) FROM children WHERE family_id IS NULL')->fetchColumn();
    if ($orphans === 0) {
        return;
    }
    $s = $pdo->query('SELECT parent_pin_hash, household_name, created_at FROM settings WHERE id = 1')->fetch();
    if (empty($s['parent_pin_hash'])) {
        return; // no legacy PIN to migrate from
    }
    $famId = new_id();
    $pdo->prepare(
        'INSERT INTO families (id, family_code, pin_hash, name, email, created_at) VALUES (?, ?, ?, ?, NULL, ?)'
    )->execute([$famId, generate_family_code(), $s['parent_pin_hash'], $s['household_name'] ?? null, $s['created_at'] ?? now_ts()]);
    $pdo->prepare('UPDATE children SET family_id = ? WHERE family_id IS NULL')->execute([$famId]);
    $pdo->prepare('UPDATE sessions SET family_id = ? WHERE family_id IS NULL')->execute([$famId]);
}

/** Fetch the settings row. */
function get_settings(): array
{
    $row = db()->query('SELECT * FROM settings WHERE id = 1')->fetch();
    return $row ?: ['id' => 1, 'parent_pin_hash' => null, 'household_name' => null];
}
