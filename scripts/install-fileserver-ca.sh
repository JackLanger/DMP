#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "Usage: bash $0 <root-certificate.pem> <expected-sha256-fingerprint>" >&2
  exit 2
fi

certificate=$1
expected=${2//:/}
expected=$(printf '%s' "$expected" | tr '[:lower:]' '[:upper:]')
if [[ ! $expected =~ ^[0-9A-F]{64}$ ]]; then
  echo "Expected fingerprint must be 64 hexadecimal characters, optionally separated by colons." >&2
  exit 2
fi

if [[ ! -f $certificate || ! -r $certificate ]]; then
  echo "Cannot read certificate: $certificate" >&2
  exit 1
fi
if ! command -v openssl >/dev/null; then
  echo "openssl is required to verify the certificate." >&2
  exit 1
fi

verified_certificate=$(mktemp "${TMPDIR:-/tmp}/fileserver-ca.XXXXXXXX")
trap 'rm -f -- "$verified_certificate"' EXIT
if ! openssl x509 -in "$certificate" -out "$verified_certificate"; then
  echo "Could not read the PEM certificate." >&2
  exit 1
fi
fingerprint=$(openssl x509 -in "$verified_certificate" -noout -fingerprint -sha256)
fingerprint=${fingerprint#*=}
fingerprint=${fingerprint//:/}
fingerprint=$(printf '%s' "$fingerprint" | tr '[:lower:]' '[:upper:]')
if [[ $fingerprint != "$expected" ]]; then
  echo "Certificate fingerprint does not match; nothing was installed." >&2
  echo "Actual SHA-256 fingerprint: $fingerprint" >&2
  exit 1
fi

case $(uname -s) in
  Darwin)
    sudo security add-trusted-cert -d -r trustRoot \
      -k /Library/Keychains/System.keychain "$verified_certificate"
    ;;
  Linux)
    if [[ ! -r /etc/os-release ]]; then
      echo "Cannot identify Linux distribution: /etc/os-release is missing." >&2
      exit 1
    fi
    # shellcheck source=/dev/null
    . /etc/os-release
    distribution=" ${ID:-} ${ID_LIKE:-} "
    if [[ $distribution == *" arch "* ]]; then
      destination=/etc/ca-certificates/trust-source/anchors
      update=update-ca-trust
    elif [[ $distribution == *" fedora "* || $distribution == *" rhel "* || $distribution == *" centos "* ]]; then
      destination=/etc/pki/ca-trust/source/anchors
      update=update-ca-trust
    elif [[ $distribution == *" opensuse "* || $distribution == *" suse "* ]]; then
      destination=/etc/pki/trust/anchors
      update=update-ca-certificates
    elif [[ $distribution == *" debian "* || $distribution == *" ubuntu "* ]]; then
      destination=/usr/local/share/ca-certificates
      update=update-ca-certificates
    else
      echo "Unsupported Linux distribution: ${ID:-unknown}. Install the certificate using your distribution's trust store." >&2
      exit 1
    fi
    if [[ ! -d $destination ]] || ! command -v "$update" >/dev/null; then
      echo "The system CA trust directory or $update command is missing." >&2
      exit 1
    fi
    sudo install -m 0644 -- "$verified_certificate" "$destination/fileserver-ca-$fingerprint.crt"
    sudo "$update"
    ;;
  *)
    echo "Unsupported operating system: $(uname -s)" >&2
    exit 1
    ;;
esac

echo "FileServer CA installed. Restart your browser if necessary."
