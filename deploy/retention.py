"""Bound local deployment caches; never delete unexported dumps or mounted releases."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess

from release_env import IMAGE_PATTERN, read_generated
from release_state import RELEASE_ID


def runtime_releases(root):
    ids = subprocess.check_output(
        ["docker", "ps", "-aq", "--filter", "label=com.docker.compose.project=hanmaum-prod"], text=True).split()
    if not ids:
        return set()
    containers = json.loads(subprocess.check_output(["docker", "inspect", *ids], text=True))
    protected = set()
    for container in containers:
        configs = container["Config"]["Labels"].get("com.docker.compose.project.config_files", "")
        if not configs:
            raise ValueError("Cannot identify a container's release; refusing cleanup")
        for config in configs.split(","):
            path = Path(config).resolve().parent.parent
            if path.parent != root / "releases" or not re.fullmatch(RELEASE_ID, path.name):
                raise ValueError("Unmanaged Compose container; refusing cleanup")
            protected.add(path)
    return protected


def prune(root, mounted, remove_image, keep_releases=3, keep_backups=3):
    root = Path(root).resolve()
    protected = set(mounted)
    for name in ("current", "previous", "last-successful"):
        link = root / name
        if link.is_symlink():
            target = link.resolve()
            if target.parent != root / "releases":
                raise ValueError("Release pointer escapes the managed directory")
            protected.add(target)
    releases = sorted((path for path in (root / "releases").iterdir()
                       if path.is_dir() and not path.is_symlink()
                       and re.fullmatch(RELEASE_ID, path.name)),
                      key=lambda path: path.stat().st_mtime_ns, reverse=True)
    images, incomplete = {}, []
    for path in releases:
        try:
            images[path] = read_generated(path / ".env.prod")["BACKEND_IMAGE"]
        except (FileNotFoundError, ValueError):
            # Copying a candidate can be interrupted before its dotenv is complete.
            # A referenced release might contain live configuration: never discard it.
            if path in protected:
                raise ValueError(f"Protected release has an incomplete environment: {path.name}; "
                                 "reconcile it before cleanup") from None
            incomplete.append(path)
    # Validate every protected release before deleting any interrupted candidate.
    for path in incomplete:
        shutil.rmtree(path)
    releases = [path for path in releases if path in images]
    retained = set(protected)
    for path in releases:
        if len(retained) < keep_releases:
            retained.add(path)
    kept_images = {image for path, image in images.items() if path in retained}
    removed_images = set()
    for path in releases:
        if path not in retained:
            # Only direct, non-symlink children matching our release IDs are eligible.
            shutil.rmtree(path)
            removed_images.add(images[path])
    for image in removed_images - kept_images:
        # No --force: Docker also protects images used by any other container.
        remove_image(image)
    dumps = sorted((root / "backups").glob("hanmaum-*.dump"),
                   key=lambda path: path.stat().st_mtime_ns, reverse=True)
    for path in dumps[keep_backups:]:
        receipt_path = path.with_suffix(".dump.uploaded.json")
        if path.is_symlink() or receipt_path.is_symlink() or not receipt_path.is_file():
            continue
        receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
        with path.open("rb") as stream:
            checksum = hashlib.file_digest(stream, "sha256").hexdigest()
        if (receipt.get("sha256") != checksum or receipt.get("size") != path.stat().st_size
                or not receipt.get("version_id")):
            continue
        path.unlink()
        receipt_path.unlink()


def prune_cached_images(root):
    # Retry removal of orphaned pulls from failed attempts, including a prior image-rm failure.
    kept = {read_generated(path / ".env.prod")["BACKEND_IMAGE"]
            for path in (root / "releases").iterdir()
            if path.is_dir() and not path.is_symlink() and re.fullmatch(RELEASE_ID, path.name)}
    cached = subprocess.check_output(
        ["docker", "image", "ls", "--digests", "--format", "{{.Repository}}@{{.Digest}}",
         "ghcr.io/team-hanmaum/hanmaum-backend"], text=True).splitlines()
    for image in set(cached) - kept:
        if re.fullmatch(IMAGE_PATTERN, image):
            subprocess.run(["docker", "image", "rm", image], stdout=subprocess.DEVNULL,
                           stderr=subprocess.DEVNULL, check=False)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check-space", action="store_true")
    args = parser.parse_args()
    root = Path("/opt/hanmaum")
    try:
        prune(root, runtime_releases(root), lambda image: subprocess.run(
            ["docker", "image", "rm", image], stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL, check=False))
        prune_cached_images(root)
        if args.check_space and shutil.disk_usage(root).free < 2 * 1024 ** 3:
            raise ValueError("Less than 2 GiB free; recover disk space before deploying")
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        parser.exit(1, f"Local retention stopped: {error}\n")
