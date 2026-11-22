#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 || ! -f $1 ]]; then
  echo "Usage: bash scripts/create-transfer.sh <file>" >&2
  exit 1
fi

if [[ -z ${FILESERVER_USER:-} || -z ${FILESERVER_ACCESS_TOKEN:-} ]]; then
  echo "Set FILESERVER_USER and FILESERVER_ACCESS_TOKEN from SecretService." >&2
  exit 1
fi
if [[ ! $FILESERVER_USER =~ ^[a-zA-Z0-9][a-zA-Z0-9_.-]{2,63}$ || ! $FILESERVER_ACCESS_TOKEN =~ ^[a-zA-Z0-9_-]{43}$ ]]; then
  echo "Invalid FILESERVER_USER or FILESERVER_ACCESS_TOKEN." >&2
  exit 1
fi
auth_headers=$(mktemp)
chmod 600 "$auth_headers"
printf 'X-User: %s\nAuthorization: Bearer %s\n' "$FILESERVER_USER" "$FILESERVER_ACCESS_TOKEN" > "$auth_headers"
request_file=
cleanup() {
  rm -f -- "$auth_headers"
  if [[ -n $request_file ]]; then rm -f -- "$request_file"; fi
}
trap cleanup EXIT

request_file=$(mktemp)
if python3 -c 'import sys; sys.exit(sys.version_info < (3, 8))' 2>/dev/null; then
  python=python3
elif python -c 'import sys; sys.exit(sys.version_info < (3, 8))' 2>/dev/null; then
  python=python
else
  echo "Python 3.8 or later is required." >&2
  exit 1
fi

"$python" - "$1" > "$request_file" <<'PY'
import hashlib
import json
import os
import sys

path = sys.argv[1]
digest = hashlib.sha256()
size = 0
with open(path, "rb") as source:
    while chunk := source.read(1024 * 1024):
        size += len(chunk)
        digest.update(chunk)
json.dump({"filename": os.path.basename(path), "size": size, "sha256": digest.hexdigest(),
           "description": "Test upload", "fileType": "FILE", "tags": ["test"]}, sys.stdout)
PY

curl -fsS -i -H "@$auth_headers" \
  -H "Content-Type: application/json" \
  --data-binary "@$request_file" \
  "${FILESERVER_URL:-https://localhost:8443}/files/transfers"
