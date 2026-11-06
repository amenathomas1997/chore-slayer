<?php
/**
 * sync-wildcard-ssl.php — runs daily from cron on a cPanel account.
 *
 * The server renews the *.a-t-365.com certificate on its own about every
 * 60 days, but only installs it on a-t-365.com. This finds the newest stored
 * *.a-t-365.com certificate and installs it on every site whose current
 * certificate expires sooner. Sites with their own newer certificate
 * (for example training.a-t-365.com) are left alone.
 *
 * Usage:  php ~/bin/sync-wildcard-ssl.php            install where needed
 *         php ~/bin/sync-wildcard-ssl.php --dry-run  only report
 * Log:    ~/logs/ssl-sync.log
 */

const WILDCARD = '*.a-t-365.com';
const APEX     = 'a-t-365.com';

$dryRun = in_array('--dry-run', $argv, true);
$logFile = getenv('HOME') . '/logs/ssl-sync.log';

function say(string $msg): void
{
    global $logFile;
    $line = '[' . date('Y-m-d H:i:s') . '] ' . $msg . "\n";
    echo $line;
    @file_put_contents($logFile, $line, FILE_APPEND);
}

function uapi(string $func, array $args = []): array
{
    $cmd = 'uapi --output=json SSL ' . escapeshellarg($func);
    foreach ($args as $k => $v) {
        $cmd .= ' ' . escapeshellarg($k . '=' . rawurlencode((string) $v));
    }
    $out = json_decode((string) shell_exec($cmd . ' 2>/dev/null'), true);
    return $out['result'] ?? ['status' => 0, 'errors' => ['No answer from uapi']];
}

// 1. Newest stored wildcard certificate.
$certs = uapi('list_certs');
$best = null;
foreach ($certs['data'] ?? [] as $c) {
    if (in_array(WILDCARD, $c['domains'] ?? [], true)
        && (!$best || $c['not_after'] > $best['not_after'])) {
        $best = $c;
    }
}
if (!$best) {
    say('ERROR: no ' . WILDCARD . ' certificate is stored on the account.');
    exit(1);
}
if ($best['not_after'] < time()) {
    say('WARNING: newest ' . WILDCARD . ' certificate expired ' . date('Y-m-d', $best['not_after'])
        . '. Renewal on the server is not happening; check WHM / AutoSSL.');
    exit(1);
}

// 2. Sites that are behind.
$hosts = uapi('installed_hosts');
$targets = [];
foreach ($hosts['data'] ?? [] as $h) {
    $name = $h['servername'];
    $isOurs = $name === APEX || substr($name, -strlen('.' . APEX)) === '.' . APEX;
    // The wildcard covers one level only: sub.a-t-365.com, not a.b.a-t-365.com.
    $oneLevel = $name === APEX || substr_count($name, '.') === 2;
    if (!$isOurs || !$oneLevel) {
        continue;
    }
    $current = (int) ($h['certificate']['not_after'] ?? 0);
    if ($best['not_after'] > $current) {
        $targets[$name] = $current;
    }
}

if (!$targets) {
    say('All sites already have the newest certificate (expires ' . date('Y-m-d', $best['not_after']) . ').');
    exit(0);
}

// 3. Install.
$info = uapi('fetch_cert_info', ['id' => $best['id']]);
$d = $info['data'] ?? [];
if (empty($d['certificate']) || empty($d['key'])) {
    say('ERROR: could not read the certificate or its key for ' . $best['id']);
    exit(1);
}

$failed = 0;
foreach ($targets as $domain => $current) {
    $from = $current ? date('Y-m-d', $current) : 'none';
    $to = date('Y-m-d', $best['not_after']);
    if ($dryRun) {
        say("DRY RUN: would install on $domain (now expires $from, new expires $to)");
        continue;
    }
    $r = uapi('install_ssl', [
        'domain'   => $domain,
        'cert'     => $d['certificate'],
        'key'      => $d['key'],
        'cabundle' => $d['cabundle'] ?? '',
    ]);
    if (!empty($r['status'])) {
        say("OK: $domain now uses the certificate that expires $to (was $from)");
    } else {
        $failed++;
        say("FAILED: $domain — " . implode(' ', (array) ($r['errors'] ?? ['unknown error'])));
    }
}
exit($failed ? 1 : 0);
