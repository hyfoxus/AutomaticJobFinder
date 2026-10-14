#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: scripts/setup.sh [--build]

Creates apps/*/.env from each .env.sample when missing, then starts the stack
with docker compose. Add --build to force image rebuilds.
USAGE
}

BUILD_FLAG=""
for arg in "$@"; do
  case "$arg" in
    --build) BUILD_FLAG="--build" ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $arg" >&2; usage; exit 1 ;;
  esac
done

cd "$(dirname "$0")/.."

for sample in apps/*/.env.sample; do
  [ -e "$sample" ] || continue
  env_file="${sample%.sample}"
  if [ ! -f "$env_file" ]; then
    cp "$sample" "$env_file"
    echo "Created $env_file from sample"
  fi
done

docker compose up -d $BUILD_FLAG
echo "Ingestion health: http://localhost:${INGESTION_HTTP_PORT:-8081}/actuator/health"
