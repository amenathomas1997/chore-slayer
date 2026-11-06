#!/bin/bash
# One-time setup, run from your Mac:
#   1. uploads sync-wildcard-ssl.php to ~/bin on your cPanel account
#   2. runs it once now (fixes every subdomain with an old certificate)
#   3. adds a daily cron job at 3:15 am so future renewals reach every subdomain
#
# Set these for your own server first, e.g.
#   SSH_TARGET=user@example.com SSH_KEY=~/.ssh/id_ed25519 bash setup-ssl-sync.sh
set -e
cd "$(dirname "$0")"
: "${SSH_TARGET:?Set SSH_TARGET to your cPanel login, e.g. user@example.com}"
SSH_KEY="${SSH_KEY:-$HOME/.ssh/id_ed25519}"
SSH=(ssh -i "$SSH_KEY" -o IdentitiesOnly=yes "$SSH_TARGET")

scp -i "$SSH_KEY" -o IdentitiesOnly=yes sync-wildcard-ssl.php "$SSH_TARGET":bin/sync-wildcard-ssl.php
"${SSH[@]}" 'chmod 700 ~/bin/sync-wildcard-ssl.php && mkdir -p ~/logs'

echo "=== Installing the newest certificate where needed ==="
"${SSH[@]}" 'php ~/bin/sync-wildcard-ssl.php' || true

echo "=== Adding the daily cron job ==="
"${SSH[@]}" 'LINE="15 3 * * * php $HOME/bin/sync-wildcard-ssl.php > /dev/null 2>&1"
  if crontab -l 2>/dev/null | grep -qF sync-wildcard-ssl.php; then
    echo "Cron job already there."
  else
    (crontab -l 2>/dev/null; echo "$LINE") | crontab - && echo "Cron job added."
  fi
  crontab -l'
