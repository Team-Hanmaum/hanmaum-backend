#!/usr/bin/env bash
set -euo pipefail
umask 077

deploy_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
if [[ "$EUID" -ne 0 ]]; then
  echo "Run this script with sudo." >&2
  exit 1
fi
backup_dir=/opt/hanmaum/backups
install -d -m 700 "$backup_dir"
partial_file="$(mktemp "$backup_dir/hanmaum-$(date -u +%Y%m%dT%H%M%SZ)-XXXXXX.dump.partial")"
trap 'rm -f -- "$partial_file"' EXIT

if ! bash "$deploy_dir/compose.sh" exec -T postgres sh -ec \
  'exec pg_dump --username="$POSTGRES_USER" --dbname="$POSTGRES_DB" --format=custom --no-owner --no-acl' \
  > "$partial_file"; then
  echo "Backup failed; the incomplete file will be removed." >&2
  exit 1
fi
backup_file="${partial_file%.partial}"
if [[ -e "$backup_file" ]]; then
  echo "Refusing to overwrite an existing backup." >&2
  exit 1
fi
mv -- "$partial_file" "$backup_file"
trap - EXIT
echo "Database backup: $backup_file"
echo "Copy it to protected storage outside this server; keep .env.prod separately."
