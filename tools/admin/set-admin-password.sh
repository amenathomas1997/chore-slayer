#!/bin/bash
# Sets (or changes) the password for the Chore Slayer admin board (admin.html).
# The password is typed hidden, sent over SSH on stdin, and stored only as a
# bcrypt hash. Signs out every open admin session.
#
#   SSH_TARGET=user@example.com SSH_KEY=~/.ssh/id_ed25519 \
#   APP_DIR=public_html/chores.example.com bash set-admin-password.sh
set -e
: "${SSH_TARGET:?Set SSH_TARGET to your cPanel login, e.g. user@example.com}"
: "${APP_DIR:?Set APP_DIR to the app folder on the server, e.g. public_html/chores.example.com}"
SSH_KEY="${SSH_KEY:-$HOME/.ssh/id_ed25519}"

read -r -s -p "New admin password (12+ characters): " PW; echo
read -r -s -p "Type it again: " PW2; echo
if [[ "$PW" != "$PW2" ]]; then echo "The passwords don't match. Nothing changed."; exit 1; fi
if (( ${#PW} < 12 )); then echo "Use at least 12 characters. Nothing changed."; exit 1; fi

printf '%s' "$PW" | ssh -i "$SSH_KEY" -o IdentitiesOnly=yes "$SSH_TARGET" \
  "cd \"$APP_DIR\" && php -r '
    define(\"CHORE_APP\", true);
    require \"lib/helpers.php\"; require \"lib/db.php\"; require \"lib/admin.php\";
    \$pw = stream_get_contents(STDIN);
    if (strlen(\$pw) < 12) { exit(\"Too short. Nothing changed.\n\"); }
    admin_install_schema();
    db()->prepare(\"REPLACE INTO admin_auth (id, pass_hash, updated_at) VALUES (1, ?, ?)\")
        ->execute([password_hash(\$pw, PASSWORD_DEFAULT), now_ts()]);
    db()->exec(\"DELETE FROM admin_sessions\");
    echo \"Admin password set. Sign in at admin.html.\n\";
  '"
