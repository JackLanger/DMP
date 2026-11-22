#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "Usage: bash $0 <new-output-directory> [DNS:hostname|IP:address ...]" >&2
  exit 2
fi

output=$1
shift
if ! command -v openssl >/dev/null; then
  echo "openssl is required." >&2
  exit 1
fi

san="DNS:secretservice,DNS:localhost,IP:127.0.0.1,IP:::1"
for name in "$@"; do
  case $name in
    DNS:*)
      value=${name#DNS:}
      if [[ ! $value =~ ^[a-zA-Z0-9][a-zA-Z0-9.-]*$ || $value == *..* || $value == *- || $value == *. ]]; then
        echo "Invalid DNS name: $value" >&2
        exit 2
      fi
      ;;
    IP:*)
      value=${name#IP:}
      if [[ ! $value =~ ^[0-9a-fA-F:.]+$ ]]; then
        echo "Invalid IP address: $value" >&2
        exit 2
      fi
      ;;
    *)
      echo "Use DNS:hostname or IP:address for each additional name: $name" >&2
      exit 2
      ;;
  esac
  san+=",$name"
done

umask 077
if ! mkdir -- "$output"; then
  echo "Cannot create output directory; it must not already exist: $output" >&2
  exit 1
fi
output=$(cd -- "$output" && pwd -P)
extensions="$output/server-extensions.cnf"
request="$output/server.csr"
trap 'rm -f -- "$extensions" "$request"' EXIT

printf '%s\n' \
  'basicConstraints=critical,CA:FALSE' \
  'keyUsage=critical,digitalSignature' \
  'extendedKeyUsage=serverAuth' \
  "subjectAltName=$san" > "$extensions"

openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$output/ca.key"
openssl req -new -x509 -sha256 -days 3650 -key "$output/ca.key" \
  -subj '/CN=SecretService local CA' \
  -addext 'basicConstraints=critical,CA:TRUE' \
  -addext 'keyUsage=critical,keyCertSign,cRLSign' \
  -out "$output/ca.crt"
openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$output/server.key"
openssl req -new -sha256 -key "$output/server.key" \
  -subj '/CN=secretservice' -out "$request"
serial=$(openssl rand -hex 16)
openssl x509 -req -sha256 -days 365 -in "$request" \
  -CA "$output/ca.crt" -CAkey "$output/ca.key" -set_serial "0x$serial" \
  -extfile "$extensions" -out "$output/server.crt"
openssl verify -CAfile "$output/ca.crt" -verify_hostname secretservice "$output/server.crt"

printf '\nCertificate files created in %s\n' "$output"
printf 'SECRET_TLS_CERT=%s/server.crt\nSECRET_TLS_KEY=%s/server.key\nSECRET_SERVICE_CA=%s/ca.crt\n' \
  "$output" "$output" "$output"
echo "Keep both private keys private; distribute only ca.crt to trusted clients."
