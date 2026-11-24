<?php
// Copy this file to config.php and fill in your own values, or run install.php,
// which writes config.php for you. config.php is never committed (see .gitignore).
return array (
  'db_host' => 'localhost',
  'db_name' => 'your_database_name',
  'db_user' => 'your_database_user',
  'db_pass' => 'your_database_password',
  'db_charset' => 'utf8mb4',
  'session_days' => 30,
  'timezone' => 'America/Chicago',
  'debug' => false,
  // Optional push notifications (see lib/push.php). Web push needs nothing here.
  // 'push' => array (
  //   'apns_key_path' => '/home/ACCOUNT/keys/AuthKey_XXXXXXXXXX.p8',
  //   'apns_key_id' => 'XXXXXXXXXX',
  //   'apns_team_id' => 'XXXXXXXXXX',
  //   'apns_bundle_id' => 'com.at365.choreslayer',
  //   'fcm_service_account_path' => '/home/ACCOUNT/keys/firebase-service-account.json',
  //   'nudge_time' => '19:30',
  // ),
);
