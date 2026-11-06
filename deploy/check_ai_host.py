"""Read-only prerequisites for the existing AI deployment; never provision/reset AI."""

import json
from pathlib import Path
import subprocess
import sys


def check(root=Path("/opt/hanmaum-ai")):
    if not (root / "initialized").is_file() or not (root / "data/receipts.sqlite3").is_file():
        raise ValueError("AI initialization and receipt storage are required")
    if json.loads((root / "deployment-state.json").read_text())["phase"] != "succeeded":
        raise ValueError("Resolve the incomplete AI deployment before deploying BE")
    if not (root / "tls/ca.crt").is_file() or not (root / "tls/ca.crt").stat().st_size:
        raise ValueError("AI public CA certificate is required")
    result = subprocess.run(
        ["docker", "network", "inspect", "hanmaum-ai-link"],
        check=True, capture_output=True, text=True,
    )
    networks = json.loads(result.stdout)
    if len(networks) != 1 or networks[0]["Driver"] != "bridge" or not networks[0]["Internal"]:
        raise ValueError("AI link must be an internal bridge")


if __name__ == "__main__":
    try:
        check()
    except Exception:
        sys.exit("AI host prerequisites failed; check the AI deployment, storage, CA and internal network")
