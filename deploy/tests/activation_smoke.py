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
    sys.exit(0)
if name == 'curl':
    url = a[-1]
    if url.endswith('/actuator/health'): print('{"status":"UP"}')
    elif url.endswith('/v3/api-docs'): print('{"paths":{"/api/users/me":{}}}')
    elif url.endswith('/api/users/me'): print('401', end='')
    sys.exit(0)
if a[:2] == ['volume', 'inspect']:
    sys.exit(0 if Path('/database-exists').exists() else 1)
if a[:2] == ['image', 'inspect']:
    print('amd64' if '.Architecture' in a[-1] else 'a' * 40)
if a[0] == 'login':
    assert sys.stdin.read() == 'synthetic-short-lived-token'
if a[0] == 'compose':
    if 'run' in a and 'certbot' in a and '-c' in a:
        sys.exit(0 if Path('/certificate-exists').exists() else 1)
    if 'certonly' in a: Path('/certificate-exists').touch()
    if 'up' in a and a[-1] == 'postgres': Path('/database-exists').touch()
    if 'up' in a and a[-1] == 'backend' and Path('/fail-backend').exists(): sys.exit(1)
    if 'exec' in a and 'pg_dump' in a[-1]: print('synthetic-dump')
'''
fakebin = Path("/fakebin")
fakebin.mkdir()
for name in ("docker", "curl", "systemctl"):
    executable = fakebin / name
    executable.write_text(FAKE)
    executable.chmod(0o755)
Path("/run/lock").mkdir(parents=True, exist_ok=True)
environment = {**os.environ, "PATH": f"{fakebin}:{os.environ['PATH']}"}
values = {**REQUIRED, "BACKEND_IMAGE": "ghcr.io/team-hanmaum/hanmaum-backend@sha256:" + "b" * 64}


def activate(attempt, changed=None):
    incoming = Path(f"/tmp/hanmaum-cd.{attempt:010d}")
    incoming.mkdir(mode=0o700)
    shutil.copytree("/src/deploy", incoming / "deploy", ignore=shutil.ignore_patterns("__pycache__"))
    (incoming / ".env.prod").write_text(render({**values, **(changed or {})}))
    (incoming / "registry-token").write_text("synthetic-short-lived-token")
    release_id = f"{'a' * 40}-1-{attempt}"
    (incoming / "release.json").write_text(json.dumps({
        "id": release_id, "sha": "a" * 40, "registry_user": "test-actor",
        "bootstrap": attempt == 1, "email": "test@example.invalid",
    }))
    result = subprocess.run(["bash", str(incoming / "deploy/activate-release.sh"), str(incoming)],
                            env=environment, capture_output=True, text=True)
    # send-release.sh's caller cleanup is separate from the server activation.
    shutil.rmtree(incoming)
    return result, Path("/opt/hanmaum/releases") / release_id


first, release1 = activate(1)
assert first.returncode == 0, first.stderr
root = Path("/opt/hanmaum")
assert (root / "current").resolve() == release1
assert (release1 / ".env.prod").stat().st_mode & 0o777 == 0o600
assert (release1 / "deploy/backend-healthcheck.sh").stat().st_mode & 0o777 == 0o644
assert not list(Path("/tmp").glob("hanmaum-registry.*"))

rotation, _ = activate(2, {"DB_PASSWORD": "accidental-password-change"})
assert rotation.returncode != 0 and "DB_PASSWORD" in rotation.stderr
assert (root / "current").resolve() == release1

Path("/fail-backend").touch()
failure, _ = activate(3)
assert failure.returncode != 0
assert (root / "current").resolve() == release1
assert list((root / "backups").glob("*.dump"))
assert not list(Path("/tmp").glob("hanmaum-registry.*"))
Path("/fail-backend").unlink()

second, release4 = activate(4)
assert second.returncode == 0, second.stderr
assert (root / "current").resolve() == release4
assert (root / "previous").resolve() == release1
assert (root / "credential-baseline.env").read_text() == render(values)
for line in Path("/events").read_text().splitlines():
    command = json.loads(line)
    assert "down" not in command and "prune" not in command
print("Simulated activation: first TLS, secret modes, key guard, backup, failed release and promotion passed.")
