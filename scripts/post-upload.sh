#!/bin/bash
set -euo pipefail
: "${FILESERVER_USER:?Set FILESERVER_USER}"
: "${FILESERVER_ACCESS_TOKEN:?Set FILESERVER_ACCESS_TOKEN}"
if [[ ! $FILESERVER_USER =~ ^[a-zA-Z0-9][a-zA-Z0-9_.-]{2,63}$ || ! $FILESERVER_ACCESS_TOKEN =~ ^[a-zA-Z0-9_-]{43}$ ]]; then
  echo "Invalid FILESERVER_USER or FILESERVER_ACCESS_TOKEN." >&2
  exit 1
fi
auth_headers=$(mktemp)
trap 'rm -f -- "$auth_headers"' EXIT
chmod 600 "$auth_headers"
printf 'X-User: %s\nAuthorization: Bearer %s\n' "$FILESERVER_USER" "$FILESERVER_ACCESS_TOKEN" > "$auth_headers"
curl --fail-with-body -i -H "@$auth_headers" \
  -F "file=@$1" \
  -F 'metadata={"description":"Test upload","tags":["test"]};type=application/json' \
  "${FILESERVER_URL:-https://localhost:8443}/files"