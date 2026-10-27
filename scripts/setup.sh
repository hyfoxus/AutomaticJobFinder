#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: scripts/setup.sh [--build]

Creates apps/*/.env from each .env.sample when missing, then starts the stack
with docker compose using apps/ingestion-service/.env for configuration (scoring, digest and the web UI read their own .env;
their ports are set with SCORING_HTTP_PORT, DIGEST_HTTP_PORT and UI_HTTP_PORT in your shell or the ingestion .env). Add --build to force image rebuilds.
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

# The digest service mounts these read-only (optional secret files such as secrets/digest/smtp_password).
mkdir -p secrets/digest digest-out

# Recipe learner: the review folder (ingestion writes proposed recipes here) and the secret shared by ingestion and
# scoring. The token is created once; delete the file to rotate it. Without the file Docker would make a folder.
mkdir -p recipes-proposed secrets/learner
if [ -d secrets/learner/token ]; then
  echo "secrets/learner/token is a directory (Docker made it because the file was missing at 'docker compose up')." >&2
  echo "Stop the stack, remove it with: rmdir secrets/learner/token   and run this script again." >&2
  exit 1
fi
if [ ! -s secrets/learner/token ]; then
  (umask 077; head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n' > secrets/learner/token)
  echo "Created secrets/learner/token"
fi

# --env-file makes the values in apps/ingestion-service/.env reach compose interpolation.
docker compose --env-file apps/ingestion-service/.env up -d $BUILD_FLAG
echo "Ingestion health: http://127.0.0.1:${INGESTION_HTTP_PORT:-8081}/actuator/health"
echo "Scoring health:   http://127.0.0.1:${SCORING_HTTP_PORT:-8082}/actuator/health"
echo "Digest health:    http://127.0.0.1:${DIGEST_HTTP_PORT:-8083}/actuator/health"
echo "Web UI health:    http://127.0.0.1:${UI_HTTP_PORT:-8084}/actuator/health"
echo "Web UI:           http://127.0.0.1:${UI_HTTP_PORT:-8084}/ (off until UI_ENABLED=true in apps/web-ui/.env)"
