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
upload_config="${1:?One-time S3 upload configuration required}"
[[ -f "$upload_config" && ! -L "$upload_config" ]]
database_bytes="$(bash "$deploy_dir/compose.sh" exec -T postgres sh -ec \
  'psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB" -qAt -c "SELECT pg_database_size(current_database())"')"
python3 - "$backup_dir" "$database_bytes" <<'PY'
import shutil, sys
required = 2 * 1024 ** 3 + 2 * int(sys.argv[2])
if shutil.disk_usage(sys.argv[1]).free < required:
    sys.exit("Insufficient space for a backup plus the 2 GiB operating reserve")
PY
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
python3 "$deploy_dir/upload_backup.py" "$backup_file" "$upload_config"
echo 'Off-host backup verified; credentials and encryption keys must be backed up separately.'
