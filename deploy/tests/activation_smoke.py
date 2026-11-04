"""Run ONLY inside the disposable container documented below; commands are simulated.

docker run --rm --network none --mount type=bind,src=<repo>,dst=/src,readonly \
  python:3.13-slim-bookworm python /src/deploy/tests/activation_smoke.py
"""

import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

if not Path("/.dockerenv").exists() or not Path("/src/deploy").exists():
    sys.exit("This simulation must run in its disposable Linux container")

sys.path.insert(0, "/src/deploy")
from release_env import render
from validate import REQUIRED

FAKE = '''#!/usr/local/bin/python
import json, os, sys
from pathlib import Path
name, a = Path(sys.argv[0]).name, sys.argv[1:]
with open('/events', 'a') as stream:
    stream.write(json.dumps([name, *a]) + '\\n')
if name == 'systemctl':
    sys.exit(1 if Path('/fail-systemd').exists() else 0)
if name == 'curl':
    if Path('/fail-health').exists(): sys.exit(22)
    url = a[-1]
    if url.endswith('/actuator/health'): print('{"status":"UP"}')
    elif url.endswith('/v3/api-docs'): print('{"paths":{"/api/users/me":{}}}')
    elif url.endswith('/api/users/me'): print('401', end='')
    sys.exit(0)
services_file = Path('/services.json')
services = json.loads(services_file.read_text()) if services_file.exists() else {}
if a[0] == 'ps':
    print('\\n'.join(services)); sys.exit(0)
if a[0] == 'inspect':
    print(json.dumps([{'Config': {'Labels': {'com.docker.compose.project.config_files': services[key]}}}
                      for key in a[1:]])); sys.exit(0)
if a[:2] == ['volume', 'inspect']:
    sys.exit(0 if Path('/volume-exists').exists() else 1)
if a[0] == 'run' and 'PG_VERSION' in a[-1]:
    sys.exit(0 if Path('/database-initialized').exists() else 1)
if a[:2] == ['image', 'inspect']:
    print('amd64' if '.Architecture' in a[-1] else os.environ['RELEASE_SHA'])
if a[0] == 'login':
    assert sys.stdin.read() == 'synthetic-short-lived-token'
if a[0] == 'compose':
    if 'run' in a and 'certbot' in a and '-c' in a:
        sys.exit(0 if Path('/certificate-exists').exists() else 1)
    if 'certonly' in a: Path('/certificate-exists').touch()
    if 'up' in a:
        services[a[-1]] = a[a.index('-f') + 1]
        services_file.write_text(json.dumps(services))
    if 'up' in a and a[-1] == 'postgres':
        Path('/volume-exists').touch()
        if Path('/fail-postgres').exists(): sys.exit(1)
        Path('/database-initialized').touch()
    if 'up' in a and a[-1] == 'backend' and Path('/fail-backend').exists(): sys.exit(1)
    if 'exec' in a and 'pg_dump' in a[-1]: print('synthetic-dump')
    if 'exec' in a and 'pg_database_size' in a[-1]: print('1024')
    if 'exec' in a and 'SELECT 1' in a[-1] and Path('/fail-auth').exists(): sys.exit(1)
'''
HTTP = '''
import io, json, urllib.request
from pathlib import Path
class Reply(io.BytesIO):
    status = 200
    headers = {}
def fake_open(request, timeout=None):
    url = request.full_url
    if url.startswith('https://api.github.com/'):
        return Reply(json.dumps({'object': {'sha': Path('/dev-head').read_text()}}).encode())
    assert url.startswith('https://test-backups.s3.ap-southeast-2.amazonaws.com/')
    if Path('/fail-upload').exists(): raise OSError('simulated upload failure')
    if request.get_method() == 'PUT':
        Path('/uploaded-dump').write_bytes(request.data.read())
        return Reply()
    reply = Reply()
    reply.headers = {'Content-Length': str(Path('/uploaded-dump').stat().st_size),
                     'x-amz-server-side-encryption': 'AES256', 'x-amz-version-id': 'version-1'}
    return reply
urllib.request.urlopen = fake_open
'''
fakebin = Path("/fakebin")
fakebin.mkdir()
for name in ("docker", "curl", "systemctl"):
    executable = fakebin / name
    executable.write_text(FAKE)
    executable.chmod(0o755)
Path("/run/lock").mkdir(parents=True, exist_ok=True)
Path('/mocklib').mkdir()
Path('/mocklib/sitecustomize.py').write_text(HTTP)
Path('/dev-head').write_text('a' * 40)
environment = {**os.environ, "PATH": f"{fakebin}:{os.environ['PATH']}", "PYTHONPATH": "/mocklib"}
values = {**REQUIRED, "BACKEND_IMAGE": "ghcr.io/team-hanmaum/hanmaum-backend@sha256:" + "b" * 64}


def activate(attempt, changed=None, sha='a' * 40):
    incoming = Path(f"/tmp/hanmaum-cd.{attempt:010d}")
    incoming.mkdir(mode=0o700)
    shutil.copytree("/src/deploy", incoming / "deploy", ignore=shutil.ignore_patterns("__pycache__"))
    (incoming / ".env.prod").write_text(render({**values, **(changed or {})}))
    (incoming / "registry-token").write_text("synthetic-short-lived-token")
    release_id = f"{sha}-1-{attempt}"
    (incoming / "release.json").write_text(json.dumps({
        "id": release_id, "sha": sha, "registry_user": "test-actor",
        "bootstrap": True, "email": "test@example.invalid",
    }))
    endpoint = 'https://test-backups.s3.ap-southeast-2.amazonaws.com/hanmaum/postgres/test.dump'
    (incoming / 'backup-upload.json').write_text(json.dumps({
        'bucket': 'test-backups', 'key': 'hanmaum/postgres/test.dump',
        'put': endpoint, 'head': endpoint, 'headers': {},
    }))
    result = subprocess.run(["bash", str(incoming / "deploy/activate-release.sh"), str(incoming)],
                            env={**environment, 'RELEASE_SHA': sha}, capture_output=True, text=True)
    # send-release.sh's caller cleanup is separate from the server activation.
    shutil.rmtree(incoming)
    return result, Path("/opt/hanmaum/releases") / release_id


root = Path("/opt/hanmaum")


def phase():
    return json.loads((root / 'deployment-state.json').read_text())['phase']


Path('/fail-systemd').touch()
failure, _ = activate(1)
assert failure.returncode != 0 and not (root / 'current').exists()
assert not (root / 'credential-baseline.env').exists()
Path('/fail-systemd').unlink()
Path('/fail-postgres').touch()
failure, failed_db = activate(2)
assert failure.returncode != 0 and phase() == 'failed'
assert (root / 'current').resolve() == failed_db
assert not (root / 'credential-baseline.env').exists()
Path('/fail-postgres').unlink()
values['DB_PASSWORD'] = 'corrected-before-database-initialization'
Path('/fail-auth').touch()
failure, _ = activate(20)
assert failure.returncode != 0 and not (root / 'credential-baseline.env').exists()
Path('/fail-auth').unlink()
first, release3 = activate(3)
assert first.returncode == 0, first.stderr
assert phase() == 'succeeded'
assert (root / 'last-successful').resolve() == release3
assert (root / 'credential-baseline.env').read_text() == render(values)
assert list((root / 'backups').glob('*.uploaded.json'))

rotation, _ = activate(4, {"DB_PASSWORD": "accidental-password-change"})
assert rotation.returncode != 0 and "DB_PASSWORD" in rotation.stderr
assert (root / "current").resolve() == release3

Path('/fail-upload').touch()
failure, _ = activate(5)
assert failure.returncode != 0 and phase() == 'failed'
assert json.loads(Path('/services.json').read_text())['backend'].startswith(str(release3))
assert len(list((root / 'backups').glob('*.dump'))) > len(list((root / 'backups').glob('*.uploaded.json')))
Path('/fail-upload').unlink()

Path("/fail-backend").touch()
failure, failed_app = activate(6)
assert failure.returncode != 0 and phase() == 'failed'
assert (root / "current").resolve() == failed_app
assert (root / "last-successful").resolve() == release3
manual = subprocess.run(['bash', str(root / 'current/deploy/compose.sh'), 'up', '-d'],
                         env=environment, capture_output=True, text=True)
assert manual.returncode != 0 and 'HANMAUM_RECOVERY' in manual.stderr
Path("/fail-backend").unlink()

Path('/fail-systemd').touch()
failure, _ = activate(7)
assert failure.returncode != 0 and (root / 'current').resolve() == failed_app
assert (root / 'last-successful').resolve() == release3
Path('/fail-systemd').unlink()
Path('/dev-head').write_text('c' * 40)
stale, _ = activate(8)
assert stale.returncode != 0 and 'Stale release' in stale.stderr
second, release9 = activate(9, sha='c' * 40)
assert second.returncode == 0, second.stderr
assert (root / "current").resolve() == release9
assert (root / "previous").resolve() == release3
assert (root / 'last-successful').resolve() == release9
assert phase() == 'succeeded'
assert (release9 / '.env.prod').stat().st_mode & 0o777 == 0o600
assert (release9 / 'deploy/backend-healthcheck.sh').stat().st_mode & 0o777 == 0o644
Path('/fail-health').touch()
failure, failed_health = activate(10, sha='c' * 40)
assert failure.returncode != 0 and phase() == 'failed'
assert (root / 'current').resolve() == failed_health
assert (root / 'last-successful').resolve() == release9
for service in ('backend', 'nginx'):
    assert json.loads(Path('/services.json').read_text())[service].startswith(str(failed_health))
Path('/fail-health').unlink()
assert not list(Path('/tmp').glob('hanmaum-registry.*'))
for line in Path("/events").read_text().splitlines():
    command = json.loads(line)
    assert "down" not in command and "prune" not in command
print("Activation failures: stale SHA, timer, empty DB retry, secret rotation, off-host backup, partial apply and recovery passed.")
