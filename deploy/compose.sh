#!/usr/bin/env bash
set -euo pipefail

deploy_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
env_file="$deploy_dir/../.env.prod"

if [[ "$EUID" -ne 0 ]]; then
  echo "Run with sudo bash $deploy_dir/compose.sh ..." >&2
  exit 1
fi
if [[ ! -f "$env_file" || -L "$env_file" ]]; then
  echo "Create a regular .env.prod file from deploy/.env.prod.example first." >&2
  exit 1
fi
if [[ "$(stat -c '%u' "$env_file")" != 0 ]]; then
  echo ".env.prod must be owned by root." >&2
  exit 1
fi
case "$(stat -c '%a' "$env_file")" in
  600|400) ;;
  *) echo ".env.prod must have mode 600 or 400." >&2; exit 1 ;;
esac

# A failed/interrupted apply may have migrated the DB. Block accidental manual restart/rollback.
if [[ "${HANMAUM_ACTIVATING:-}" != 1 && "${HANMAUM_RECOVERY:-}" != 1 && \
  -f /opt/hanmaum/deployment-state.json ]]; then
  phase="$(python3 -c 'import json; print(json.load(open("/opt/hanmaum/deployment-state.json"))["phase"])')"
  if [[ "$phase" != succeeded ]]; then
    case "${1:-}" in
      ps|logs|config) ;;
      *) echo 'Deployment is incomplete. Inspect state and DB compatibility; use HANMAUM_RECOVERY=1 only for deliberate recovery.' >&2; exit 1 ;;
    esac
  fi
fi

exec docker compose --project-name hanmaum-prod --env-file "$env_file" \
  -f "$deploy_dir/compose.prod.yml" "$@"
