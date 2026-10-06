#!/bin/sh
# Accepts the Worker credential as FDM_WORKER_TOKEN, as a token file (FDM_WORKER_TOKEN_FILE), or as a credential id plus a secret
# file (FDM_WORKER_CREDENTIAL_ID + FDM_WORKER_SECRET_FILE, e.g. a Docker secret) so the secret never has to appear in an environment
# listing. The value is never printed.
set -eu

if [ -n "${FDM_WORKER_TOKEN_FILE:-}" ]; then
  if [ ! -r "$FDM_WORKER_TOKEN_FILE" ]; then
    echo "FDM_WORKER_TOKEN_FILE is set but cannot be read" >&2
    exit 64
  fi
  FDM_WORKER_TOKEN="$(tr -d '\r\n' < "$FDM_WORKER_TOKEN_FILE")"
  export FDM_WORKER_TOKEN
fi

if [ -z "${FDM_WORKER_TOKEN:-}" ] && [ -n "${FDM_WORKER_CREDENTIAL_ID:-}" ] && [ -n "${FDM_WORKER_SECRET_FILE:-}" ]; then
  if [ ! -r "$FDM_WORKER_SECRET_FILE" ]; then
    echo "FDM_WORKER_SECRET_FILE is set but cannot be read" >&2
    exit 64
  fi
  FDM_WORKER_TOKEN="${FDM_WORKER_CREDENTIAL_ID}.$(tr -d '\r\n' < "$FDM_WORKER_SECRET_FILE")"
  export FDM_WORKER_TOKEN
fi

if [ -z "${FDM_WORKER_TOKEN:-}" ]; then
  echo "A Worker credential is required: FDM_WORKER_TOKEN, FDM_WORKER_TOKEN_FILE, or FDM_WORKER_CREDENTIAL_ID with FDM_WORKER_SECRET_FILE" >&2
  exit 64
fi
if [ -z "${FDM_API_BASE_URL:-}" ]; then
  echo "FDM_API_BASE_URL is required" >&2
  exit 64
fi

exec java -XX:MaxRAMPercentage=60 -jar /app/worker-agent.jar
