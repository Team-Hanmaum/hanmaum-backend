"""Server only: upload one dump using expiring URLs and record a verified receipt."""

import argparse
import hashlib
import json
from pathlib import Path
import urllib.parse
import urllib.request

from release_state import atomic_json


def upload(path, config, opener=urllib.request.urlopen):
    path = Path(path)
    data = json.loads(Path(config).read_text(encoding="utf-8"))
    expected_host = data["bucket"] + ".s3.ap-southeast-2.amazonaws.com"
    for key in ("put", "head"):
        url = urllib.parse.urlsplit(data[key])
        if (url.scheme != "https" or url.netloc != expected_host
                or urllib.parse.unquote(url.path) != "/" + data["key"]):
            raise ValueError("Unexpected backup endpoint")
    size = path.stat().st_size
    if not 0 < size < 5 * 1024 ** 3:
        raise ValueError("Dump must fit a single S3 PUT (under 5 GiB)")
    with path.open("rb") as stream:
        sha256 = hashlib.file_digest(stream, "sha256").hexdigest()
    with path.open("rb") as stream:
        request = urllib.request.Request(data["put"], data=stream, method="PUT",
                                         headers={**data["headers"], "Content-Length": str(size)})
        with opener(request, timeout=300) as response:
            if response.status != 200:
                raise ValueError("Backup upload was not acknowledged")
    with opener(urllib.request.Request(data["head"], method="HEAD"), timeout=30) as response:
        if (int(response.headers["Content-Length"]) != size
                or response.headers.get("x-amz-server-side-encryption") != "AES256"
                or not response.headers.get("x-amz-version-id")
                or response.headers.get("x-amz-version-id") == "null"):
            raise ValueError("Backup verification failed")
        receipt = {"bucket": data["bucket"], "key": data["key"], "size": size,
                   "sha256": sha256, "version_id": response.headers["x-amz-version-id"]}
    atomic_json(path.with_suffix(".dump.uploaded.json"), receipt)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dump")
    parser.add_argument("upload_config")
    args = parser.parse_args()
    try:
        upload(args.dump, args.upload_config)
    except Exception:
        parser.exit(1, "Off-host backup failed; local dump retained, application activation stopped.\n")
