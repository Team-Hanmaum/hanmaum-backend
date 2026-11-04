#!/usr/bin/env bash
set -euo pipefail

deploy_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
compose=(bash "$deploy_dir/compose.sh")
if [[ "$EUID" -ne 0 ]]; then
  echo "Run this script with sudo." >&2
  exit 1
fi

exec 9>/run/lock/hanmaum-certbot.lock
flock -n 9 || exit 0
"${compose[@]}" run --rm --no-deps certbot renew \
  --webroot --webroot-path /var/www/certbot --non-interactive "$@"
# A graceful reload also covers a prior renewal whose reload failed.
"${compose[@]}" exec -T nginx nginx -t
"${compose[@]}" exec -T nginx nginx -s reload
