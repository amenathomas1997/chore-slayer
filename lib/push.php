<?php
/**
 * Push notifications: device registry plus senders for Apple (APNs), Android
 * (Firebase Cloud Messaging HTTP v1) and browsers (Web Push with VAPID).
 *
 * Every platform is optional. Fill in the 'push' block in config.php; a
 * platform with missing settings is skipped. Web push needs no setup: its
 * VAPID key pair is generated on first use and kept in the app_kv table.
 *
 *   'push' => [
 *       'apns_key_path'  => '/home/ACCOUNT/keys/AuthKey_XXXXXXXXXX.p8',
 *       'apns_key_id'    => 'XXXXXXXXXX',
 *       'apns_team_id'   => '9G2D7CDZ5D',
 *       'apns_bundle_id' => 'com.at365.choreslayer',
 *       'fcm_service_account_path' => '/home/ACCOUNT/keys/firebase-service-account.json',
 *       'contact'    => 'https://chores.example.com/support.html', // VAPID subject
 *       'nudge_time' => '19:30',   // parents' "chores still to do" nudge
 *       'slot_times' => ['morning' => '07:30', 'midday' => '12:00',
 *                        'afternoon' => '15:30', 'evening' => '18:30'],
 *   ],
 *
 * A device row is one phone, tablet or browser. It belongs to one family and
 * can carry kid reminders (child_id = the kid signed in there) and/or parent
 * alerts (parent_alerts = 1, switched on by a signed-in parent).
 */

if (!defined('CHORE_APP')) {
    http_response_code(403);
    exit('Forbidden');
}

const PUSH_PLATFORMS = ['ios', 'android', 'web'];

function push_install_schema(): void
{
    $pdo = db();
    $pdo->exec("CREATE TABLE IF NOT EXISTS push_devices (
        id CHAR(36) NOT NULL PRIMARY KEY,
        family_id CHAR(36) NOT NULL,
        platform VARCHAR(10) NOT NULL,
        token VARCHAR(1024) NOT NULL,
        token_hash CHAR(64) NOT NULL,
        p256dh VARCHAR(200) NULL,
        auth_secret VARCHAR(64) NULL,
        sandbox TINYINT(1) NOT NULL DEFAULT 0,
        child_id CHAR(36) NULL,
        parent_alerts TINYINT(1) NOT NULL DEFAULT 0,
        created_at DATETIME NOT NULL,
        updated_at DATETIME NOT NULL,
        UNIQUE KEY uniq_push_token (token_hash),
        INDEX idx_push_family (family_id),
        INDEX idx_push_child (child_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    // One row per notification already handled, so the cron never repeats one.
    $pdo->exec("CREATE TABLE IF NOT EXISTS push_log (
        k VARCHAR(120) NOT NULL PRIMARY KEY,
        sent_at DATETIME NOT NULL
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    $pdo->exec("CREATE TABLE IF NOT EXISTS app_kv (
        k VARCHAR(64) NOT NULL PRIMARY KEY,
        v TEXT NOT NULL
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
}

function push_cfg(string $key, $default = null)
{
    $p = config()['push'] ?? [];
    return (isset($p[$key]) && $p[$key] !== '') ? $p[$key] : $default;
}

function kv_get(string $k): ?string
{
    $s = db()->prepare('SELECT v FROM app_kv WHERE k = ?');
    $s->execute([$k]);
    $v = $s->fetchColumn();
    return $v === false ? null : $v;
}

function kv_set(string $k, string $v): void
{
    db()->prepare('REPLACE INTO app_kv (k, v) VALUES (?, ?)')->execute([$k, $v]);
}

// ---------------------------------------------------------------- devices

/** Find a device by its push token (or web endpoint). */
function push_device_by_token(string $token): ?array
{
    $s = db()->prepare('SELECT * FROM push_devices WHERE token_hash = ?');
    $s->execute([hash('sha256', $token)]);
    return $s->fetch() ?: null;
}

/** Shape a device row for the API. */
function push_device_out(?array $d): array
{
    return [
        'registered'   => $d !== null,
        'childId'      => $d['child_id'] ?? null,
        'parentAlerts' => $d ? (int) $d['parent_alerts'] === 1 : false,
    ];
}

/** Remove every device of a family (used when the family is deleted). */
function push_forget_family(string $familyId): void
{
    db()->prepare('DELETE FROM push_devices WHERE family_id = ?')->execute([$familyId]);
}

/** Stop kid reminders for a child on every device (child removed). */
function push_forget_child(string $childId): void
{
    db()->prepare('UPDATE push_devices SET child_id = NULL, updated_at = ? WHERE child_id = ?')
        ->execute([now_ts(), $childId]);
}

// ---------------------------------------------------------------- helpers

function b64url(string $bin): string
{
    return rtrim(strtr(base64_encode($bin), '+/', '-_'), '=');
}

/** HTTPS POST; returns the body, or null on a transport error ($err set). */
function push_http(string $url, string $body, array $headers, ?string &$err = null, ?int &$code = null, bool $http2 = false): ?string
{
    if (!function_exists('curl_init')) {
        $err = 'php-curl is not available';
        return null;
    }
    $ch = curl_init($url);
    $opts = [
        CURLOPT_POST           => true,
        CURLOPT_POSTFIELDS     => $body,
        CURLOPT_HTTPHEADER     => $headers,
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_TIMEOUT        => 20,
    ];
    if ($http2 && defined('CURL_HTTP_VERSION_2TLS')) {
        $opts[CURLOPT_HTTP_VERSION] = CURL_HTTP_VERSION_2TLS;
    }
    curl_setopt_array($ch, $opts);
    $resp = curl_exec($ch);
    $code = (int) curl_getinfo($ch, CURLINFO_HTTP_CODE);
    if ($resp === false) {
        $err = 'curl: ' . curl_error($ch);
        curl_close($ch);
        return null;
    }
    curl_close($ch);
    return (string) $resp;
}

/** openssl_sign() gives a DER ECDSA signature; JWTs need raw R||S. */
function ecdsa_der_to_raw(string $der, int $partLen = 32): ?string
{
    $pos = 2;
    if ((ord($der[1]) & 0x80) !== 0) {
        $pos += (ord($der[1]) & 0x7f);
    }
    $out = '';
    for ($i = 0; $i < 2; $i++) {
        if (ord($der[$pos]) !== 0x02) {
            return null;
        }
        $len = ord($der[$pos + 1]);
        $int = ltrim(substr($der, $pos + 2, $len), "\x00");
        $pos += 2 + $len;
        if (strlen($int) > $partLen) {
            return null;
        }
        $out .= str_pad($int, $partLen, "\x00", STR_PAD_LEFT);
    }
    return $out;
}

function push_configured(string $platform): bool
{
    if ($platform === 'ios') {
        $p = push_cfg('apns_key_path');
        return $p && is_readable($p) && push_cfg('apns_key_id') && push_cfg('apns_team_id');
    }
    if ($platform === 'android') {
        $p = push_cfg('fcm_service_account_path');
        return $p && is_readable($p);
    }
    if ($platform === 'web') {
        return function_exists('openssl_pkey_derive');
    }
    return false;
}

// ---------------------------------------------------------------- APNs

function apns_jwt(?string &$err = null): ?string
{
    static $jwt = null, $iat = 0;
    if ($jwt !== null && (time() - $iat) < 2700) {
        return $jwt;
    }
    $pem = @file_get_contents((string) push_cfg('apns_key_path'));
    $key = $pem ? openssl_pkey_get_private($pem) : false;
    if (!$key) {
        $err = 'APNs key is missing or not a valid private key';
        return null;
    }
    $iat = time();
    $input = b64url(json_encode(['alg' => 'ES256', 'kid' => push_cfg('apns_key_id')]))
        . '.' . b64url(json_encode(['iss' => push_cfg('apns_team_id'), 'iat' => $iat]));
    if (!openssl_sign($input, $der, $key, OPENSSL_ALGO_SHA256) || !($raw = ecdsa_der_to_raw($der))) {
        $err = 'APNs JWT signing failed';
        return null;
    }
    return $jwt = $input . '.' . b64url($raw);
}

/** @return true|false|'invalid' */
function apns_send(array $dev, array $msg, ?string &$err = null)
{
    $jwt = apns_jwt($err);
    if (!$jwt) {
        return false;
    }
    $host = (int) $dev['sandbox'] === 1 ? 'api.sandbox.push.apple.com' : 'api.push.apple.com';
    $payload = json_encode([
        'aps'     => ['alert' => ['title' => $msg['title'], 'body' => $msg['body']], 'sound' => 'default'],
        'type'    => $msg['type'],
        'childId' => $msg['childId'] ?? '',
    ], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    $headers = [
        'authorization: bearer ' . $jwt,
        'apns-topic: ' . push_cfg('apns_bundle_id', 'com.at365.choreslayer'),
        'apns-push-type: alert',
        'apns-priority: 10',
        'content-type: application/json',
    ];
    if (!empty($msg['collapse'])) {
        $headers[] = 'apns-collapse-id: ' . substr($msg['collapse'], 0, 64);
    }
    $resp = push_http('https://' . $host . '/3/device/' . rawurlencode($dev['token']), $payload, $headers, $err, $code, true);
    if ($resp === null) {
        return false;
    }
    if ($code === 200) {
        return true;
    }
    $reason = json_decode($resp, true)['reason'] ?? ('HTTP ' . $code);
    if ($code === 410 || in_array($reason, ['BadDeviceToken', 'Unregistered', 'DeviceTokenNotForTopic'], true)) {
        return 'invalid';
    }
    $err = 'APNs ' . $code . ': ' . $reason;
    return false;
}

// ---------------------------------------------------------------- FCM

function fcm_account(?string &$err = null): ?array
{
    static $sa = null;
    if ($sa !== null) {
        return $sa;
    }
    $raw = @file_get_contents((string) push_cfg('fcm_service_account_path'));
    $j = $raw ? json_decode($raw, true) : null;
    if (!$j || empty($j['client_email']) || empty($j['private_key']) || empty($j['project_id'])) {
        $err = 'FCM service-account JSON missing or invalid';
        return null;
    }
    return $sa = $j;
}

function fcm_access_token(?string &$err = null): ?string
{
    static $tok = null, $exp = 0;
    if ($tok !== null && time() < $exp - 120) {
        return $tok;
    }
    $sa = fcm_account($err);
    if (!$sa) {
        return null;
    }
    $now = time();
    $input = b64url(json_encode(['alg' => 'RS256', 'typ' => 'JWT'])) . '.' . b64url(json_encode([
        'iss'   => $sa['client_email'],
        'scope' => 'https://www.googleapis.com/auth/firebase.messaging',
        'aud'   => $sa['token_uri'],
        'iat'   => $now,
        'exp'   => $now + 3600,
    ]));
    $key = openssl_pkey_get_private($sa['private_key']);
    if (!$key || !openssl_sign($input, $sig, $key, OPENSSL_ALGO_SHA256)) {
        $err = 'FCM JWT signing failed';
        return null;
    }
    $resp = push_http($sa['token_uri'], http_build_query([
        'grant_type' => 'urn:ietf:params:oauth:grant-type:jwt-bearer',
        'assertion'  => $input . '.' . b64url($sig),
    ]), ['Content-Type: application/x-www-form-urlencoded'], $err, $code);
    $data = $resp !== null ? json_decode($resp, true) : null;
    if (empty($data['access_token'])) {
        $err = $err ?: ('FCM token request failed: HTTP ' . $code);
        return null;
    }
    $exp = time() + (int) ($data['expires_in'] ?? 3600);
    return $tok = $data['access_token'];
}

/** Data message; the Android app builds the notification itself. @return true|false|'invalid' */
function fcm_send(array $dev, array $msg, ?string &$err = null)
{
    $at = fcm_access_token($err);
    $sa = fcm_account($err);
    if (!$at || !$sa) {
        return false;
    }
    $payload = json_encode(['message' => [
        'token'   => $dev['token'],
        'data'    => [
            'type'    => $msg['type'],
            'childId' => (string) ($msg['childId'] ?? ''),
            'title'   => $msg['title'],
            'body'    => $msg['body'],
        ],
        'android' => ['priority' => 'HIGH'],
    ]], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    $url = 'https://fcm.googleapis.com/v1/projects/' . rawurlencode($sa['project_id']) . '/messages:send';
    $resp = push_http($url, $payload, ['Content-Type: application/json', 'Authorization: Bearer ' . $at], $err, $code);
    if ($resp === null) {
        return false;
    }
    if ($code === 200) {
        return true;
    }
    if ($code === 404 || strpos($resp, 'UNREGISTERED') !== false) {
        return 'invalid';
    }
    $err = 'FCM ' . $code . ': ' . substr($resp, 0, 200);
    return false;
}

// ---------------------------------------------------------------- Web Push

/** ['public' => base64url point, 'pem' => private key], created on first use. */
function vapid_keys(): ?array
{
    static $cached = null;
    if ($cached !== null) {
        return $cached;
    }
    $pub = kv_get('vapid_public');
    $pem = kv_get('vapid_private');
    if ($pub && $pem) {
        return $cached = ['public' => $pub, 'pem' => $pem];
    }
    $key = openssl_pkey_new(['curve_name' => 'prime256v1', 'private_key_type' => OPENSSL_KEYTYPE_EC]);
    if (!$key) {
        return null;
    }
    openssl_pkey_export($key, $pemOut);
    $det = openssl_pkey_get_details($key);
    $pub = b64url("\x04" . str_pad($det['ec']['x'], 32, "\0", STR_PAD_LEFT) . str_pad($det['ec']['y'], 32, "\0", STR_PAD_LEFT));
    kv_set('vapid_public', $pub);
    kv_set('vapid_private', $pemOut);
    return $cached = ['public' => $pub, 'pem' => $pemOut];
}

function vapid_jwt(string $audience, ?string &$err = null): ?string
{
    $k = vapid_keys();
    if (!$k) {
        $err = 'No VAPID keys';
        return null;
    }
    $input = b64url(json_encode(['typ' => 'JWT', 'alg' => 'ES256'])) . '.' . b64url(json_encode([
        'aud' => $audience,
        'exp' => time() + 43200,
        'sub' => push_cfg('contact', 'https://chores.a-t-365.com/support.html'),
    ]));
    if (!openssl_sign($input, $der, $k['pem'], OPENSSL_ALGO_SHA256) || !($raw = ecdsa_der_to_raw($der))) {
        $err = 'VAPID signing failed';
        return null;
    }
    return $input . '.' . b64url($raw);
}

/** RFC 8291 aes128gcm encryption of one payload for one browser subscription. */
function webpush_encrypt(string $plaintext, string $p256dh, string $auth, ?string &$err = null): ?string
{
    $uaPublic   = base64_decode(strtr($p256dh, '-_', '+/'));
    $authSecret = base64_decode(strtr($auth, '-_', '+/'));
    if (strlen($uaPublic) !== 65 || strlen($authSecret) < 16) {
        $err = 'Bad subscription keys';
        return null;
    }
    $as = openssl_pkey_new(['curve_name' => 'prime256v1', 'private_key_type' => OPENSSL_KEYTYPE_EC]);
    if (!$as) {
        $err = 'keygen failed';
        return null;
    }
    $d = openssl_pkey_get_details($as);
    $asPublic = "\x04" . str_pad($d['ec']['x'], 32, "\0", STR_PAD_LEFT) . str_pad($d['ec']['y'], 32, "\0", STR_PAD_LEFT);
    $uaPem = "-----BEGIN PUBLIC KEY-----\n"
        . chunk_split(base64_encode(hex2bin('3059301306072a8648ce3d020106082a8648ce3d030107034200') . $uaPublic), 64, "\n")
        . "-----END PUBLIC KEY-----\n";
    $shared = openssl_pkey_derive(openssl_pkey_get_public($uaPem), $as);
    if ($shared === false) {
        $err = 'ECDH failed';
        return null;
    }
    $prk   = hash_hkdf('sha256', $shared, 32, "WebPush: info\0" . $uaPublic . $asPublic, $authSecret);
    $salt  = random_bytes(16);
    $cek   = hash_hkdf('sha256', $prk, 16, "Content-Encoding: aes128gcm\0", $salt);
    $nonce = hash_hkdf('sha256', $prk, 12, "Content-Encoding: nonce\0", $salt);
    $cipher = openssl_encrypt($plaintext . "\x02", 'aes-128-gcm', $cek, OPENSSL_RAW_DATA, $nonce, $tag);
    if ($cipher === false) {
        $err = 'encrypt failed';
        return null;
    }
    return $salt . pack('N', 4096) . chr(65) . $asPublic . $cipher . $tag;
}

/** @return true|false|'invalid' */
function webpush_send(array $dev, array $msg, ?string &$err = null)
{
    $parts = parse_url($dev['token']);
    if (!$parts || ($parts['scheme'] ?? '') !== 'https' || empty($parts['host'])) {
        return 'invalid';
    }
    $jwt = vapid_jwt('https://' . $parts['host'], $err);
    if (!$jwt) {
        return false;
    }
    $body = webpush_encrypt(json_encode([
        'title'   => $msg['title'],
        'body'    => $msg['body'],
        'type'    => $msg['type'],
        'childId' => $msg['childId'] ?? '',
        'tag'     => $msg['collapse'] ?? '',
    ], JSON_UNESCAPED_UNICODE), (string) $dev['p256dh'], (string) $dev['auth_secret'], $err);
    if ($body === null) {
        return false;
    }
    $resp = push_http($dev['token'], $body, [
        'TTL: 3600',
        'Urgency: high',
        'Content-Encoding: aes128gcm',
        'Content-Type: application/octet-stream',
        'Authorization: vapid t=' . $jwt . ', k=' . vapid_keys()['public'],
    ], $err, $code);
    if ($resp === null) {
        return false;
    }
    if ($code === 404 || $code === 410) {
        return 'invalid';
    }
    if ($code < 200 || $code >= 300) {
        $err = 'Web push HTTP ' . $code . ': ' . substr($resp, 0, 200);
        return false;
    }
    return true;
}

// ---------------------------------------------------------------- sending

/**
 * Send one message to a list of device rows. Dead tokens are deleted.
 * $msg: title, body, type, childId?, collapse?. Returns how many were accepted.
 */
function push_send_devices(array $devices, array $msg, ?callable $log = null): int
{
    $sent = 0;
    foreach ($devices as $dev) {
        if (!push_configured($dev['platform'])) {
            continue;
        }
        $err = null;
        if ($dev['platform'] === 'ios') {
            $r = apns_send($dev, $msg, $err);
        } elseif ($dev['platform'] === 'android') {
            $r = fcm_send($dev, $msg, $err);
        } else {
            $r = webpush_send($dev, $msg, $err);
        }
        if ($r === true) {
            $sent++;
        } elseif ($r === 'invalid') {
            db()->prepare('DELETE FROM push_devices WHERE id = ?')->execute([$dev['id']]);
            if ($log) {
                $log("  removed dead {$dev['platform']} device");
            }
        } elseif ($log) {
            $log("  {$dev['platform']} send failed: " . ($err ?: 'unknown'));
        }
    }
    return $sent;
}

/** True the first time a key is claimed; false if it was handled already. */
function push_claim(string $key): bool
{
    $s = db()->prepare('INSERT IGNORE INTO push_log (k, sent_at) VALUES (?, ?)');
    $s->execute([$key, now_ts()]);
    return $s->rowCount() === 1;
}
