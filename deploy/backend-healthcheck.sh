#!/usr/bin/env bash
set -euo pipefail

# The existing JRE image includes Bash, so no curl package or application change is needed.
exec 3<>/dev/tcp/127.0.0.1/8080
printf 'GET /actuator/health HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n' >&3
IFS= read -r status <&3
case "$status" in
  HTTP/1.[01]" 200 "*) exit 0 ;;
  *) exit 1 ;;
esac
