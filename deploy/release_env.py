"""Render single-line GitHub secrets into Compose dotenv without shell evaluation."""

import argparse
import base64
import json
import os
from pathlib import Path
import re


SECRET_NAMES = (
    "POSTGRES_ADMIN_PASSWORD", "DB_PASSWORD", "GOOGLE_CLIENT_ID", "GOOGLE_CLIENT_SECRET",
    "KAKAO_CLIENT_ID", "KAKAO_CLIENT_SECRET", "COMMAND_DIGEST_KEY",
    "INVITATION_ENCRYPTION_KEY", "AI_INTERNAL_API_KEY",
)
STABLE_NAMES = ("POSTGRES_ADMIN_PASSWORD", "DB_PASSWORD", "COMMAND_DIGEST_KEY",
                "INVITATION_ENCRYPTION_KEY", "AI_INTERNAL_API_KEY")
IMAGE_PATTERN = r"ghcr\.io/team-hanmaum/hanmaum-backend@sha256:[0-9a-f]{64}"


def checked_values(environment):
    values = {}
    for name in ("BACKEND_IMAGE", *SECRET_NAMES):
        value = environment.get(name, "")
        if not value or any(char in value for char in "\r\n\x00"):
            raise ValueError(f"{name}: a nonempty single-line value is required")
        values[name] = value
    if not re.fullmatch(IMAGE_PATTERN, values["BACKEND_IMAGE"]):
        raise ValueError("BACKEND_IMAGE: the expected GHCR repository and sha256 digest are required")
    try:
        key = base64.b64decode(values["INVITATION_ENCRYPTION_KEY"], validate=True)
    except ValueError:
        raise ValueError("INVITATION_ENCRYPTION_KEY: valid Base64 is required") from None
    if len(key) != 32:
        raise ValueError("INVITATION_ENCRYPTION_KEY: exactly 32 decoded bytes are required")
    for name in ("COMMAND_DIGEST_KEY", "AI_INTERNAL_API_KEY"):
        if len(values[name].encode("utf-8")) < 32:
            raise ValueError(f"{name}: at least 32 bytes are required")
    if values["POSTGRES_ADMIN_PASSWORD"] == values["DB_PASSWORD"]:
        raise ValueError("Database administrator and application passwords must differ")
    if len({values[name] for name in STABLE_NAMES}) != len(STABLE_NAMES):
        raise ValueError("Generate each database password and internal key independently")
    return values


def render(values):
    # Compose expands dollars inside double quotes; $$ preserves a literal dollar.
    # JSON quoting handles quotes/backslashes without turning values into shell code.
    return "".join(f"{name}={json.dumps(value, ensure_ascii=False).replace('$', '$$')}\n"
                   for name, value in values.items())


def read_generated(path):
    values = {}
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        name, separator, value = line.partition("=")
        if not separator or name in values:
            raise ValueError("Expected a deployment-generated dotenv file")
        try:
            values[name] = json.loads(value).replace("$$", "$")
        except (ValueError, AttributeError):
            raise ValueError("Expected a deployment-generated dotenv file") from None
    return checked_values(values)


def compare_stable(old, new):
    previous, candidate = read_generated(old), read_generated(new)
    changed = [name for name in STABLE_NAMES if previous[name] != candidate[name]]
    if changed:
        raise ValueError("Ordinary deployment cannot rotate persisted credentials/keys: "
                         + ", ".join(changed))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    write = sub.add_parser("write")
    write.add_argument("output", type=Path)
    compare = sub.add_parser("compare")
    compare.add_argument("old", type=Path)
    compare.add_argument("new", type=Path)
    args = parser.parse_args()
    try:
        if args.command == "write":
            content = render(checked_values(os.environ))
            descriptor = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as stream:
                stream.write(content)
        else:
            compare_stable(args.old, args.new)
    except (ValueError, OSError) as error:
        parser.exit(1, f"Environment validation failed: {error}\n")
