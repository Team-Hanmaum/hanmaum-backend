#!/usr/bin/env bash
set -euo pipefail
umask 077

: "${RUNNER_TEMP:?}" "${DEPLOY_HOST:?}" "${DEPLOY_USER:?}" "${GH_TOKEN:?}"
: "${DEPLOY_SSH_PRIVATE_KEY:?}" "${DEPLOY_SSH_KNOWN_HOSTS:?}"
[[ "$GITHUB_SHA" =~ ^[0-9a-f]{40}$ ]]
[[ "$GITHUB_RUN_ID" =~ ^[0-9]+$ && "$GITHUB_RUN_ATTEMPT" =~ ^[0-9]+$ ]]
[[ "$DEPLOY_USER" == ubuntu ]]
python3 -c 'import ipaddress,os; assert ipaddress.ip_address(os.environ["DEPLOY_HOST"]).version == 4'

work_dir="$(mktemp -d "$RUNNER_TEMP/hanmaum-upload.XXXXXXXXXX")"
remote_dir=""
ssh_options=(-4 -i "$work_dir/key" -o BatchMode=yes -o IdentitiesOnly=yes
  -o StrictHostKeyChecking=yes -o "UserKnownHostsFile=$work_dir/known_hosts"
  -o ConnectTimeout=10 -o ServerAliveInterval=15 -o ServerAliveCountMax=3)
target="$DEPLOY_USER@$DEPLOY_HOST"
cleanup() {
  local result=$?
  trap - EXIT
  if [[ "$remote_dir" =~ ^/tmp/hanmaum-cd\.[a-zA-Z0-9]{10}$ ]]; then
    if ! ssh "${ssh_options[@]}" "$target" "rm -rf -- '$remote_dir'"; then
      echo "::warning::Remove the private upload directory $remote_dir on the server."
    fi
  fi
  rm -rf -- "$work_dir"
  exit "$result"
}
trap cleanup EXIT
printf '%s\n' "$DEPLOY_SSH_PRIVATE_KEY" > "$work_dir/key"
printf '%s\n' "$DEPLOY_SSH_KNOWN_HOSTS" > "$work_dir/known_hosts"
ssh-keygen -y -P '' -f "$work_dir/key" > /dev/null
ssh-keygen -F "$DEPLOY_HOST" -f "$work_dir/known_hosts" > /dev/null
mkdir "$work_dir/payload"
# Transfer only committed deployment files, never the working tree or a developer .env.
git archive "$GITHUB_SHA" deploy | tar -x -C "$work_dir/payload"
python3 deploy/release_env.py write "$work_dir/payload/.env.prod"
"${BACKUP_PYTHON:?Backup SDK interpreter required}" deploy/prepare_backup.py "$work_dir/payload/backup-upload.json"
printf '%s' "$GH_TOKEN" > "$work_dir/payload/registry-token"
python3 - "$work_dir/payload/release.json" <<'PY'
import json, os, re, sys
bootstrap = os.environ["BOOTSTRAP_HTTPS"] == "true"
email = os.environ.get("ACME_EMAIL", "")
if bootstrap and not re.fullmatch(r"[^\s@]+@[^\s@]+\.[^\s@]+", email):
    sys.exit("ACME_EMAIL is required for the first certificate")
with open(sys.argv[1], "x", encoding="utf-8") as stream:
    json.dump({"id": f'{os.environ["GITHUB_SHA"]}-{os.environ["GITHUB_RUN_ID"]}-{os.environ["GITHUB_RUN_ATTEMPT"]}',
               "sha": os.environ["GITHUB_SHA"], "registry_user": os.environ["GITHUB_ACTOR"],
               "bootstrap": bootstrap, "email": email}, stream)
PY

for attempt in {1..12}; do
  if remote_dir="$(ssh "${ssh_options[@]}" "$target" 'umask 077; mktemp -d /tmp/hanmaum-cd.XXXXXXXXXX')"; then
    break
  fi
  sleep 5
done
[[ "$remote_dir" =~ ^/tmp/hanmaum-cd\.[a-zA-Z0-9]{10}$ ]]
tar -C "$work_dir/payload" -czf "$work_dir/release.tgz" .
scp "${ssh_options[@]}" "$work_dir/release.tgz" "$target:$remote_dir/release.tgz"
ssh "${ssh_options[@]}" "$target" \
  "umask 077; tar -xzf '$remote_dir/release.tgz' -C '$remote_dir'; sudo -n bash '$remote_dir/deploy/activate-release.sh' '$remote_dir'"
