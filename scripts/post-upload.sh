#!/bin/bash

cookies=$(mktemp)
csrf=$(curl -fsS -c "$cookies" http://localhost:8080/login \
  | sed -n 's/.*name="_csrf".*value="\([^"]*\)".*/\1/p')

curl --fail-with-body -i --netrc-file fileserver.netrc -b "$cookies" \
  -H "X-CSRF-TOKEN: $csrf" \
  -F "file=@$1" \
  -F 'metadata={"description":"Test upload","tags":["test"]};type=application/json' \
  http://localhost:8080/files
status=$?

rm "$cookies"
exit "$status"