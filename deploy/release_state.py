"""Track attempted and successful releases separately; never roll a migrated DB back."""

import argparse
import json
import os
from pathlib import Path
import re
import urllib.request


RELEASE_ID = r"[0-9a-f]{40}-[0-9]+-[0-9]+"


def atomic_json(path, value):
    temporary = path.with_name(path.name + ".next")
    temporary.write_text(json.dumps(value) + "\n", encoding="utf-8")
    temporary.chmod(0o600)
    os.replace(temporary, path)


def point(root, name, release):
    temporary = root / (name + ".next")
    temporary.unlink(missing_ok=True)
    temporary.symlink_to(release)
    os.replace(temporary, root / name)


def current_dev_sha(token):
    request = urllib.request.Request(
        "https://api.github.com/repos/Team-Hanmaum/hanmaum-backend/git/ref/heads/dev",
        headers={"Accept": "application/vnd.github+json", "User-Agent": "hanmaum-deploy",
                 "Authorization": "Bearer " + token.strip(),
                 "Cache-Control": "no-cache"},
    )
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            head = json.load(response)["object"]["sha"]
        if not isinstance(head, str) or not re.fullmatch(r"[0-9a-f]{40}", head):
            raise ValueError("Invalid dev SHA")
    except Exception:
        raise ValueError("Cannot verify current dev head; deployment stopped") from None
    return head


def require_latest(sha, token_file):
    if not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise ValueError("Invalid release SHA")
    head = current_dev_sha(Path(token_file).read_text(encoding="utf-8"))
    if sha != head:
        raise ValueError("Stale release rejected: start a NEW workflow on current dev; "
                         "restore old behavior with a reviewed revert, not an old run rerun")


def transition(root, release, phase):
    root, release = Path(root).resolve(), Path(release).resolve()
    if release.parent != root / "releases" or not re.fullmatch(RELEASE_ID, release.name):
        raise ValueError("Release is outside the managed releases directory")
    state_file = root / "deployment-state.json"
    if phase == "applying":
        # Compatibility with a deployment made before the explicit state file existed.
        if not (root / "last-successful").exists() and (root / "current").exists() and not state_file.exists():
            point(root, "last-successful", (root / "current").resolve())
        atomic_json(state_file, {"release": str(release), "phase": phase})
        # This is the configuration target, not a claim that every container is healthy.
        point(root, "current", release)
    else:
        state = json.loads(state_file.read_text(encoding="utf-8"))
        if state["release"] != str(release):
            raise ValueError("Deployment state belongs to another release")
        if phase == "succeeded":
            successful = root / "last-successful"
            if successful.exists() and successful.resolve() != release:
                point(root, "previous", successful.resolve())
            point(root, "last-successful", release)
        atomic_json(state_file, {"release": str(release), "phase": phase})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    latest = sub.add_parser("check")
    latest.add_argument("sha")
    latest.add_argument("token_file")
    state = sub.add_parser("state")
    state.add_argument("root")
    state.add_argument("release")
    state.add_argument("phase", choices=("applying", "failed", "succeeded"))
    args = parser.parse_args()
    try:
        if args.command == "check":
            require_latest(args.sha, args.token_file)
        else:
            transition(args.root, args.release, args.phase)
    except (ValueError, OSError) as error:
        parser.exit(1, f"Release state validation failed: {error}\n")
