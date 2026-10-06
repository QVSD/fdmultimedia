#!/bin/sh
# Generates .env.pilot and the secrets/ directory for the pilot stack from .env.pilot.example with strong random values.
# Usage: scripts/pilot-init.sh <public-host> [loopback]
#   <public-host>  DNS name users will open, e.g. fdm.example.org (use "localhost" with the "loopback" mode)
#   loopback       evaluate on one machine over plain http://localhost (no TLS certificate needed)
# It refuses to overwrite an existing .env.pilot or secrets/ files. Nothing it generates is ever printed except the owner e-mail
# prompt text; read the passwords from the files it creates.
set -eu
cd "$(dirname "$0")/.."

host="${1:?usage: scripts/pilot-init.sh <public-host> [loopback]}"
mode="${2:-tls}"
[ ! -e .env.pilot ] || { echo ".env.pilot already exists; refusing to overwrite" >&2; exit 1; }
[ ! -e secrets/owner_password ] || { echo "secrets/ already populated; refusing to overwrite" >&2; exit 1; }
command -v openssl >/dev/null || { echo "openssl is required" >&2; exit 1; }

rand() { openssl rand -base64 48 | tr -d '/+=\n' | cut -c1-"$1"; }
umask 077
mkdir -p secrets/tls
rand 32 > secrets/owner_password
rand 40 > secrets/worker_secret
h="$(openssl rand -hex 16)"
uuid="$(echo "$h" | cut -c1-8)-$(echo "$h" | cut -c9-12)-4$(echo "$h" | cut -c14-16)-8$(echo "$h" | cut -c18-20)-$(echo "$h" | cut -c21-32)"

if [ "$mode" = "loopback" ]; then
  origin="http://localhost:8081"; storage="http://storage.localhost:9100"; http=8081; minio=9100; nginx_mode=loopback
else
  origin="https://$host"; storage="https://$host:9443"; http=80; minio=9000; nginx_mode=tls
fi

sed -e "s#^APP_PUBLIC_ORIGIN=.*#APP_PUBLIC_ORIGIN=$origin#" \
    -e "s#^PILOT_STORAGE_ORIGIN=.*#PILOT_STORAGE_ORIGIN=$storage#" \
    -e "s#^PILOT_STORAGE_HOST=.*#PILOT_STORAGE_HOST=$(echo "$storage" | sed -e 's#^[a-z]*://##' -e 's#:.*##')#" \
    -e "s#^NGINX_MODE=.*#NGINX_MODE=$nginx_mode#" \
    -e "s#^PILOT_HTTP_PORT=.*#PILOT_HTTP_PORT=$http#" \
    -e "s#^PILOT_MINIO_PORT=.*#PILOT_MINIO_PORT=$minio#" \
    -e "s#^POSTGRES_PASSWORD=.*#POSTGRES_PASSWORD=$(rand 32)#" \
    -e "s#^RABBITMQ_PASSWORD=.*#RABBITMQ_PASSWORD=$(rand 32)#" \
    -e "s#^MINIO_ROOT_PASSWORD=.*#MINIO_ROOT_PASSWORD=$(rand 32)#" \
    -e "s#^STORAGE_SECRET_KEY=.*#STORAGE_SECRET_KEY=$(rand 32)#" \
    -e "s#^FDM_WORKER_CREDENTIAL_ID=.*#FDM_WORKER_CREDENTIAL_ID=$uuid#" \
    -e "s#^FDM_BUILD_COMMIT=.*#FDM_BUILD_COMMIT=$(git rev-parse HEAD 2>/dev/null || echo unknown)#" \
    -e "s#^FDM_BUILD_TIME=.*#FDM_BUILD_TIME=$(date -u +%Y-%m-%dT%H:%M:%SZ)#" \
    .env.pilot.example > .env.pilot

echo "Created .env.pilot and secrets/ (owner_password, worker_secret)."
echo "Edit FDM_OWNER_EMAIL / FDM_OWNER_NAME / FDM_WORKSPACE_* in .env.pilot, then:"
if [ "$mode" != "loopback" ]; then
  echo "Place your certificate in secrets/tls/fullchain.pem and secrets/tls/privkey.pem (for a trial only: scripts/pilot-init.sh does not create one)."
fi
echo "docker compose --env-file .env.pilot -f docker-compose.pilot.yml up -d --build"
