#!/usr/bin/env bash
set -euo pipefail
umask 077

[[ "$EUID" == 0 ]] || { echo 'Run this deployment as root.' >&2; exit 1; }
incoming="${1:?Private upload directory required}"
[[ "$incoming" =~ ^/tmp/hanmaum-cd\.[a-zA-Z0-9]{10}$ && -d "$incoming" && ! -L "$incoming" ]]
[[ -z "$(find "$incoming" -type l -print -quit)" ]]
[[ "$(uname -m)" == x86_64 ]] || { echo 'This release requires an amd64 server.' >&2; exit 1; }
exec 9>/run/lock/hanmaum-deploy.lock
flock -n 9 || { echo 'Another deployment is active.' >&2; exit 1; }
exec 8>/run/lock/hanmaum-certbot.lock
flock -n 8 || { echo 'Certificate maintenance is active; retry deployment later.' >&2; exit 1; }

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
release="$root/releases/${metadata[0]}"
[[ ! -e "$release" ]]
install -d -m 700 "$release"
cp -R -- "$incoming/deploy" "$release/deploy"
chown -R root:root "$release"
# Container users must read their bind-mounted healthcheck/init SQL. Secrets stay outside deploy/.
find "$release/deploy" -type d -exec chmod 755 {} +
find "$release/deploy" -type f -exec chmod 644 {} +
install -o root -g root -m 600 "$incoming/.env.prod" "$release/.env.prod"
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
elif docker volume inspect hanmaum-prod_postgres-data > /dev/null 2>&1; then
  echo 'Existing database without a credential baseline; reconcile credentials before deployment.' >&2
  exit 1
fi

registry_dir="$(mktemp -d /tmp/hanmaum-registry.XXXXXXXXXX)"
cleanup() {
  local result=$?
  trap - EXIT
  rm -rf -- "$registry_dir"
  rm -f -- "$incoming/registry-token"
  if [[ "$result" != 0 ]]; then
    echo "Deployment failed. Candidate: $release. No database reset or automatic schema rollback was performed." >&2
  fi
  exit "$result"
}
trap cleanup EXIT
export DOCKER_CONFIG="$registry_dir"
docker login ghcr.io -u "${metadata[2]}" --password-stdin < "$incoming/registry-token"
rm -f -- "$incoming/registry-token"
docker pull "$image"
[[ "$(docker image inspect "$image" --format '{{.Architecture}}')" == amd64 ]]
[[ "$(docker image inspect "$image" --format '{{index .Config.Labels "org.opencontainers.image.revision"}}')" == "${metadata[1]}" ]]
"${compose[@]}" --profile tools pull postgres nginx certbot

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

existing_db=false
if docker volume inspect hanmaum-prod_postgres-data > /dev/null 2>&1; then existing_db=true; fi
if [[ ! -f "$root/credential-baseline.env" ]]; then
  install -o root -g root -m 600 "$release/.env.prod" "$root/credential-baseline.env"
fi
"${compose[@]}" up -d --wait --wait-timeout 120 postgres
if [[ "$existing_db" == true ]]; then bash "$release/deploy/backup-db.sh"; fi
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

if [[ -L "$root/current" ]]; then
  ln -s "$(readlink "$root/current")" "$root/previous.${metadata[0]}.next"
  mv -Tf "$root/previous.${metadata[0]}.next" "$root/previous"
fi
ln -s "$release" "$root/current.${metadata[0]}.next"
mv -Tf "$root/current.${metadata[0]}.next" "$root/current"
install -m 644 "$release/deploy/systemd/hanmaum-cert-renew.service" /etc/systemd/system/
install -m 644 "$release/deploy/systemd/hanmaum-cert-renew.timer" /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now hanmaum-cert-renew.timer
echo "Deployment completed: ${metadata[0]}"
