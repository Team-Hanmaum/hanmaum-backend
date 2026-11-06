#!/usr/bin/env bash
set -euo pipefail
umask 077
# The SSH uploader must be able to remove its directory after this sudo process exits.
export PYTHONDONTWRITEBYTECODE=1

[[ "$EUID" == 0 ]] || { echo 'Run this deployment as root.' >&2; exit 1; }
incoming="${1:?Private upload directory required}"
[[ "$incoming" =~ ^/tmp/hanmaum-cd\.[a-zA-Z0-9]{10}$ && -d "$incoming" && ! -L "$incoming" ]]
[[ -z "$(find "$incoming" -type l -print -quit)" ]]
[[ "$(uname -m)" == x86_64 ]] || { echo 'This release requires an amd64 server.' >&2; exit 1; }
exec 9>/run/lock/hanmaum-deploy.lock
flock -n 9 || { echo 'Another deployment is active.' >&2; exit 1; }
exec 8>/run/lock/hanmaum-certbot.lock
flock -n 8 || { echo 'Certificate maintenance is active; retry deployment later.' >&2; exit 1; }
export HANMAUM_ACTIVATING=1

root=/opt/hanmaum
install -d -m 700 "$root/releases" "$root/backups"
if [[ -e "$root/current" && ! -L "$root/current" ]]; then
  echo 'current must be a managed release symlink.' >&2
  exit 1
fi
mapfile -t metadata < <(python3 - "$incoming/release.json" <<'PY'
import json, re, sys
m = json.load(open(sys.argv[1], encoding="utf-8"))
assert re.fullmatch(r"[0-9a-f]{40}-[0-9]+-[0-9]+", m["id"])
assert m["sha"] == m["id"][:40]
assert re.fullmatch(r"[A-Za-z0-9-]+(?:\[bot\])?", m["registry_user"])
assert isinstance(m["bootstrap"], bool)
assert not any(c in m["email"] for c in "\r\n\x00")
for key in ("id", "sha", "registry_user"):
    print(m[key])
print("true" if m["bootstrap"] else "false")
print(m["email"])
PY
)
[[ "${#metadata[@]}" == 5 ]]
python3 "$incoming/deploy/release_state.py" check "${metadata[1]}" "$incoming/registry-token"
python3 "$incoming/deploy/check_ai_host.py"
python3 "$incoming/deploy/retention.py" --check-space
release="$root/releases/${metadata[0]}"
[[ ! -e "$release" ]]
install -d -m 700 "$release"
cp -R -- "$incoming/deploy" "$release/deploy"
chown -R root:root "$release"
# Container users must read their bind-mounted healthcheck/init SQL. Secrets stay outside deploy/.
find "$release/deploy" -type d -exec chmod 755 {} +
find "$release/deploy" -type f -exec chmod 644 {} +
install -o root -g root -m 600 "$incoming/.env.prod" "$release/.env.prod"
install -o root -g root -m 600 "$incoming/release.json" "$release/release.json"
image="$(python3 - "$release" <<'PY'
import sys
sys.path.insert(0, sys.argv[1] + "/deploy")
from release_env import read_generated
print(read_generated(sys.argv[1] + "/.env.prod")["BACKEND_IMAGE"])
PY
)"
compose=(bash "$release/deploy/compose.sh")
"${compose[@]}" config --quiet

if [[ -f "$root/credential-baseline.env" ]]; then
  python3 "$release/deploy/release_env.py" compare "$root/credential-baseline.env" "$release/.env.prod"
fi

registry_dir="$(mktemp -d /tmp/hanmaum-registry.XXXXXXXXXX)"
applying=false
cleanup() {
  local result=$?
  trap - EXIT
  rm -rf -- "$registry_dir"
  rm -f -- "$incoming/registry-token"
  if [[ "$result" != 0 ]]; then
    if [[ "$applying" == true ]]; then
      python3 "$release/deploy/release_state.py" state "$root" "$release" failed || true
    fi
    echo "Deployment failed. Candidate: $release. Inspect deployment-state.json and container config labels before recovery; no automatic schema rollback." >&2
  fi
  exit "$result"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM HUP
export DOCKER_CONFIG="$registry_dir"
docker login ghcr.io -u "${metadata[2]}" --password-stdin < "$incoming/registry-token"
docker pull "$image"
[[ "$(docker image inspect "$image" --format '{{.Architecture}}')" == amd64 ]]
[[ "$(docker image inspect "$image" --format '{{index .Config.Labels "org.opencontainers.image.revision"}}')" == "${metadata[1]}" ]]
"${compose[@]}" --profile tools pull postgres nginx certbot

# A volume can exist even when initdb never ran. Do not mistake existence for initialization.
if [[ ! -f "$root/credential-baseline.env" ]] && \
  docker volume inspect hanmaum-prod_postgres-data > /dev/null 2>&1 && \
  docker run --rm --network none --entrypoint sh \
    --mount type=volume,source=hanmaum-prod_postgres-data,target=/pgdata,readonly \
    postgres:17.11 -c 'test -s /pgdata/PG_VERSION'; then
  [[ -f "$root/database-bootstrap.env" ]] || {
    echo 'Existing initialized database without deployment provenance; reconcile credentials first.' >&2
    exit 1
  }
fi

if ! "${compose[@]}" run --rm --no-deps --entrypoint sh certbot -c \
  'test -s /etc/letsencrypt/live/api.hanmaum.site/fullchain.pem && test -s /etc/letsencrypt/live/api.hanmaum.site/privkey.pem'; then
  if [[ "${metadata[3]}" != true || -e "$root/current" ]]; then
    echo 'A TLS certificate is required. Use the documented first-deployment bootstrap.' >&2
    exit 1
  fi
  bootstrap=(docker compose -p hanmaum-prod -f "$release/deploy/compose.bootstrap.yml")
  "${bootstrap[@]}" up -d nginx
  "${bootstrap[@]}" run --rm --no-deps certbot certonly --non-interactive \
    --webroot --webroot-path /var/www/certbot --agree-tos --email "${metadata[4]}" \
    --cert-name api.hanmaum.site -d api.hanmaum.site --keep-until-expiring
fi
"${compose[@]}" run --rm --no-deps --entrypoint nginx nginx -t -q

# Required host setup must succeed before changing services or the current target.
install -m 644 "$release/deploy/systemd/hanmaum-cert-renew.service" /etc/systemd/system/
install -m 644 "$release/deploy/systemd/hanmaum-cert-renew.timer" /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now hanmaum-cert-renew.timer
# dev may have advanced while images/TLS were prepared. Old reruns fail closed here too.
python3 "$release/deploy/release_state.py" check "${metadata[1]}" "$incoming/registry-token"
rm -f -- "$incoming/registry-token"
python3 "$release/deploy/release_state.py" state "$root" "$release" applying
applying=true
if [[ ! -f "$root/credential-baseline.env" ]]; then
  install -o root -g root -m 600 "$release/.env.prod" "$root/database-bootstrap.env"
fi
"${compose[@]}" up -d --wait --wait-timeout 120 postgres
# pg_isready alone does not verify authentication or completion of the application-role init SQL.
"${compose[@]}" exec -T postgres sh -ec \
  'PGPASSWORD="$POSTGRES_PASSWORD" psql -X -h 127.0.0.1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -qAt -c "SELECT 1" >/dev/null
   PGPASSWORD="$HANMAUM_APP_PASSWORD" psql -X -h 127.0.0.1 -U hanmaum_app -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -qAt -c "SELECT 1" >/dev/null'
if [[ ! -f "$root/credential-baseline.env" ]]; then
  install -o root -g root -m 600 "$release/.env.prod" "$root/credential-baseline.env.next"
  mv -Tf "$root/credential-baseline.env.next" "$root/credential-baseline.env"
fi
rm -f -- "$root/database-bootstrap.env"
bash "$release/deploy/backup-db.sh" "$incoming/backup-upload.json"
"${compose[@]}" up -d --no-deps --wait --wait-timeout 240 backend
"${compose[@]}" up -d --no-deps --force-recreate --wait --wait-timeout 90 nginx

curl_options=(--fail --silent --show-error --retry 3 --max-time 20
  --resolve api.hanmaum.site:443:127.0.0.1)
curl "${curl_options[@]}" https://api.hanmaum.site/actuator/health | \
  python3 -c 'import json,sys; assert json.load(sys.stdin)["status"] == "UP"'
curl "${curl_options[@]}" --output /dev/null https://api.hanmaum.site/swagger-ui/index.html
curl "${curl_options[@]}" https://api.hanmaum.site/v3/api-docs | \
  python3 -c 'import json,sys; assert "/api/users/me" in json.load(sys.stdin)["paths"]'
[[ "$(curl -sS --max-time 15 --resolve api.hanmaum.site:443:127.0.0.1 \
  -o /dev/null -w '%{http_code}' https://api.hanmaum.site/api/users/me)" == 401 ]]

# Cleanup is best effort and never converts a healthy, promoted release into a failed deploy.
python3 "$release/deploy/retention.py" || echo 'Local retention needs operator attention.' >&2
python3 "$release/deploy/release_state.py" state "$root" "$release" succeeded
applying=false
echo "Deployment completed: ${metadata[0]}; AI v2 startup readiness passed, analysis worker disabled."
