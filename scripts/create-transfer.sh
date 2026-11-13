#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 || ! -f $1 ]]; then
  echo "Usage: bash scripts/create-transfer.sh <file>" >&2
  exit 1
fi

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
cookies=$(mktemp)
request_file=
cleanup() {
  rm -f -- "$cookies"
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
           "description": "Test upload", "tags": ["test"]}, sys.stdout)
PY

csrf=$(curl -fsS -c "$cookies" http://localhost:8080/login \
  | sed -n 's/.*name="_csrf".*value="\([^"]*\)".*/\1/p')
if [[ -z $csrf ]]; then
  echo "CSRF token not found in the login page." >&2
  exit 1
fi

curl -fsS -i --netrc-file "$script_dir/fileserver.netrc" -b "$cookies" \
  -H "X-CSRF-TOKEN: $csrf" \
  -H "Content-Type: application/json" \
  --data-binary "@$request_file" \
  http://localhost:8080/files/transfers
