# Pilot Checklist (v0.1.0-rc1)

Work through this top to bottom on the machine that will host the pilot. Each box is a statement you can check; do not tick one you have not verified. Configuration details are in `docs/CONFIGURATION.md`, procedures in `docs/OPERATIONS.md`.

## Supported pilot scope

Single host running Docker Compose; one workspace and one team sharing the owner account; one to two API replicas, one to two Workers each running one media Job at a time; a few hundred media assets. These limits come from what was tested (see the release notes), not from measured ceilings. Not covered: high availability of PostgreSQL or object storage, multi-region, hostile traffic, self-service sign-up, hosted multi-tenant use.

## 1. Host and network

- [ ] Docker Engine with Compose v2, at least 8 GB RAM for the Docker VM or host, and disk for the volumes (see "Disk growth" in `docs/OPERATIONS.md`).
- [ ] A DNS name for the application, for example `fdm.example.org`, resolving to the host. The storage origin uses the same name on port 9443.
- [ ] Ports 80, 443 and 9443 reachable (or the ports you set in `.env.pilot`); firewall allows nothing else inbound. PostgreSQL, RabbitMQ, the API and the web container are never published.
- [ ] A valid TLS certificate for the name (chain in `secrets/tls/fullchain.pem`, key in `secrets/tls/privkey.pem`, readable by uid 101). The platform terminates TLS in its own nginx; HTTP redirects to HTTPS. For a one-machine evaluation without a certificate use `NGINX_MODE=loopback` and open `http://localhost:<port>` only.

## 2. Secrets and configuration

- [ ] `scripts/pilot-init.sh <host>` (or copy `.env.pilot.example` by hand) created `.env.pilot` and `secrets/` with generated passwords; nothing from `.env.pilot.example` is still a placeholder.
- [ ] `.env.pilot` and `secrets/` are not in git and are backed up somewhere safe and separate from the data backups (losing `SOCIAL_CREDENTIAL_ENCRYPTION_KEY` makes stored provider credentials unrecoverable).
- [ ] `FDM_OWNER_EMAIL`, `FDM_OWNER_NAME`, `FDM_WORKSPACE_NAME`, `FDM_WORKSPACE_SLUG` set; `secrets/owner_password` is at least 12 characters.
- [ ] `FDM_BUILD_COMMIT=$(git rev-parse HEAD)` exported (or set in `.env.pilot`) before building.
- [ ] `docker compose --env-file .env.pilot -f docker-compose.pilot.yml config -q` succeeds.

## 3. Build and start

- [ ] `docker compose --env-file .env.pilot -f docker-compose.pilot.yml build` (first build downloads PyTorch and the Whisper model and compiles MinIO; allow 15 to 30 minutes).
- [ ] `docker compose --env-file .env.pilot -f docker-compose.pilot.yml up -d`; `ps` shows postgres, rabbitmq, minio, api, web, worker and nginx healthy and `minio-init` exited 0.
- [ ] API log shows `Initial provisioning created workspace ...` once, and no `Invalid production configuration`.

## 4. Database and storage

- [ ] PostgreSQL role is dedicated to the application and not a superuser (the compose file creates one). Flyway reached V45 (`SELECT max(version::int) FROM flyway_schema_history`).
- [ ] The bucket exists, is private, and the application key can only touch that bucket (`minio-init` log). An unsigned object GET returns 403 and a presigned GET with `Range` returns 206.

## 5. Worker, FFmpeg, Whisper, Ollama

- [ ] Worker log shows FFmpeg, FFprobe and `Transcription provider available: LOCAL_WHISPER_CLI model base`.
- [ ] Operations page shows the Worker `ONLINE` with the same release version as the API.
- [ ] Ollama (optional): running on the host, model pulled, `CONTENT_AI_PROVIDER=OLLAMA` and `CONTENT_AI_RUNTIME=OLLAMA` set, Worker log shows the Ollama provider available. If you skip it, accept that AI copy requests fail as "provider unavailable" while deterministic features work.
- [ ] Meta/TikTok (optional): app credentials, an https redirect URI registered with the provider, `SOCIAL_CREDENTIAL_ENCRYPTION_KEY`, `META_PUBLIC_BASE_URL` publicly reachable. Skip for a TEST-only pilot.

## 6. First login and setup

- [ ] Sign in at `APP_PUBLIC_ORIGIN` with the owner e-mail and password. The browser shows no certificate warning and no console errors.
- [ ] Settings: add a TEST social account (and a real account only if you deliberately intend real publishing).
- [ ] Personas: create a Persona. Robots: create a Robot; leave cadence manual and adaptive features at their defaults (nothing adaptive is enabled by default; new Robots are human controlled).
- [ ] Content: import a video you have the rights to from a public https URL, wait for inspection, transcribe, analyse highlights, create a clip or vertical and a draft.
- [ ] Publishing mode is unmistakable in the UI: TEST items say "(non-real)", real providers say "(LIVE)". Schedule or publish a TEST publication and see it published.

## 7. Operations, backup, restore

- [ ] Operations page: overall `Healthy`, no incidents, release versions match.
- [ ] Incident drill: stop MinIO, see the critical incident and `Action required`, acknowledge it, start MinIO, see it resolve.
- [ ] Worker drill: stop the Worker, see it go stale then offline and the no-Worker incident, start it, see recovery.
- [ ] Backup: `scripts/backup-postgres.ps1` produced a dump; the bucket has its own backup policy; both are recorded with the date.
- [ ] Restore rehearsal: restore the dump into a second, empty environment, start it, and confirm sign-in works (sessions are intentionally not restored) and Operations is healthy.
- [ ] Log retention: Docker log rotation (`max-size`/`max-file`) or log shipping is configured.

## 8. Go / no-go

- [ ] Every box above is ticked, the known limitations in the release notes are accepted by the pilot owner, and a rollback owner knows the procedure in `docs/OPERATIONS.md` ("Release upgrade, failure and rollback").
