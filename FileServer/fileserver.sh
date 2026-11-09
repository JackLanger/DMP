#!/bin/bash

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"

function build() {
  local version
  version="$(<"$SCRIPT_DIR/VERSION")"

  "$PROJECT_ROOT/gradlew" -p "$PROJECT_ROOT" :FileServer:bootJar
  docker build \
    --build-arg "VERSION=$version" \
    -t fileserver:latest \
    -t "fileserver:$version" \
    -f "$SCRIPT_DIR/FileServer.Containerfile" \
    "$PROJECT_ROOT"
}

function run() {
  # Compose resolves .env and relative paths from the working directory
  cd "$SCRIPT_DIR" || return 1

  if command -v docker-compose >/dev/null 2>&1; then
    docker-compose -f compose.yaml up -d
  elif command -v docker >/dev/null 2>&1; then
    docker compose -f compose.yaml up -d
  elif command -v podman-compose >/dev/null 2>&1; then
    podman-compose -f compose.yaml up -d
  else
    echo "No Docker Compose tool found" >&2
    return 1
  fi
}

## Main script execution
case "$1" in
  b|build)
    build
    ;;
  r|run)
    run
    ;;
  *)
    echo "Usage: $0 {b|build|r|run}"
    exit 1
    ;;
esac