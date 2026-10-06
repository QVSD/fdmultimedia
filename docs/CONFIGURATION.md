# Configuration Reference (v0.1.0-rc1)

This is the authoritative list of what a production or pilot deployment must configure. The development stack (`docker-compose.yml`, profile `dev`) keeps its convenience defaults and is described in `docs/DEVELOPMENT.md`; nothing below applies to it unless stated.

Two files drive a pilot:

- `.env.pilot` (copy of `.env.pilot.example`; created with generated secrets by `scripts/pilot-init.sh`) holds non-file values.
- `secrets/` holds secrets that are read from files and never appear in an environment listing: `owner_password`, `worker_secret`, and in TLS mode `tls/fullchain.pem` and `tls/privkey.pem`.

Neither is ever committed (`.gitignore` covers both). The Spring profile is always `prod` in the pilot Compose file.

## Fail-closed startup

With the `prod` profile the API refuses to start, before it touches the database, when critical configuration is missing or still a development placeholder. The error lists variable names and reasons, never values. It checks that:

- `DB_PASSWORD`, `STORAGE_ACCESS_KEY` and `STORAGE_SECRET_KEY` are set, at least 12 characters, and not a known development placeholder (`change_me`, `dev_*`, `password`, `example`).
- `APP_PUBLIC_ORIGIN` is an absolute origin (scheme, host, optional port, no path). Plain `http` is accepted only for `localhost`, `127.0.0.1`, `[::1]` and `*.localhost`.
- `STORAGE_PUBLIC_ENDPOINT` is set, and is `https` whenever `APP_PUBLIC_ORIGIN` is `https` (browsers block mixed content).
- Session cookies are `Secure` (the profile forces it) and Hibernate may only validate the schema.
- Instagram or TikTok publishing, when enabled, has every credential, an https callback and the encryption key.
- Development `BOOTSTRAP_*` variables are not set.

On a database with no users it additionally requires the first-owner variables below. Compose refuses to start with `required variable ... is missing` for the values it cannot default.

## Required

| Variable | Used by | Meaning |
| --- | --- | --- |
| `APP_PUBLIC_ORIGIN` | API, nginx | Canonical external origin, for example `https://fdm.example.org`. Also used for the HTTP to HTTPS redirect target and to reject state-changing browser requests whose `Origin` header differs. |
| `PILOT_STORAGE_ORIGIN` | API, nginx | Origin browsers and Workers use for presigned media URLs (`https://fdm.example.org:9443` in TLS mode). |
| `PILOT_STORAGE_HOST` | Worker | Host part of the storage origin; mapped to the Docker host inside the Worker container. |
| `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` | PostgreSQL, API | Database name, application user and password. |
| `RABBITMQ_USER`, `RABBITMQ_PASSWORD` | RabbitMQ, API | Optional transport credentials (the API requires them to start). |
| `MINIO_ROOT_USER`, `MINIO_ROOT_PASSWORD` | MinIO, `minio-init` only | Root credentials; never given to the API or Worker. |
| `STORAGE_ACCESS_KEY`, `STORAGE_SECRET_KEY` | API, `minio-init` | The application's own MinIO key, created by `minio-init` with a policy limited to the bucket. |
| `secrets/owner_password` | API | Password of the first owner (at least 12 characters). Used only while the database has no users. |
| `FDM_OWNER_EMAIL`, `FDM_OWNER_NAME`, `FDM_WORKSPACE_NAME`, `FDM_WORKSPACE_SLUG` | API | First owner and workspace (slug: lower-case letters, digits, hyphens). Used only while the database has no users. |
| `FDM_WORKER_CREDENTIAL_ID`, `secrets/worker_secret` | API, Worker | Worker credential: a UUID you generate and a random secret of at least 24 characters. The API stores only a BCrypt hash. |
| `NGINX_MODE`, `PILOT_HTTP_PORT`, `PILOT_HTTPS_PORT`, `PILOT_STORAGE_TLS_PORT`, `PILOT_MINIO_PORT` | Compose | Edge mode and published ports. |
| `secrets/tls/fullchain.pem`, `secrets/tls/privkey.pem` | nginx | Certificate chain and key (TLS mode only; readable by uid 101). |

## Optional

| Variable | Default | Meaning |
| --- | --- | --- |
| `FDM_VERSION`, `FDM_BUILD_COMMIT`, `FDM_BUILD_TIME` | `0.1.0-rc1`, `unknown` | Image tag and the release identity recorded in images and shown on the Operations page. Set the commit from `git rev-parse HEAD`. |
| `STORAGE_BUCKET`, `STORAGE_REGION` | `media-assets`, `us-east-1` | Private bucket and region. |
| `RABBITMQ_VHOST` | `/` | Virtual host. |
| `SESSION_TIMEOUT` | `30m` | Idle session timeout. |
| `DB_MAX_POOL_SIZE` | `12` | Connections per API replica. |
| `WORKER_MAX_ACTIVE_JOBS`, `FDM_WORKER_NAME`, `FDM_WORKER_JOB_POLL_SECONDS` | `1`, `pilot-worker`, `3` | Worker concurrency, display name and poll interval. |
| `TRANSCRIPTION_MODEL`, `TRANSCRIPTION_TIMEOUT_SECONDS` | `base`, `1800` | Whisper model name (the image bakes in `base`) and per-job limit. |
| `CONTENT_AI_PROVIDER`, `CONTENT_AI_MODEL` | `DETERMINISTIC_TEST` | API side of AI copy. Set `OLLAMA` to allow LLM copy. |
| `CONTENT_AI_RUNTIME`, `CONTENT_AI_ENDPOINT`, `WORKER_CONTENT_AI_MODEL`, `SEMANTIC_HIGHLIGHT_RUNTIME` | blank, `http://host.docker.internal:11434`, `llama3.2:3b` | Worker side of AI. Leave blank for deterministic-only operation. |
| `SOCIAL_CREDENTIAL_ENCRYPTION_KEY` | blank | Base64 AES-256 key (`openssl rand -base64 32`); required when real publishing is enabled. Losing it makes stored provider credentials unreadable. |
| `INSTAGRAM_ENABLED`, `META_APP_ID`, `META_APP_SECRET`, `META_OAUTH_REDIRECT_URI`, `META_PUBLIC_BASE_URL`, `META_GRAPH_API_VERSION` | `false` | Instagram publishing through the official Graph API. |
| `TIKTOK_ENABLED`, `TIKTOK_CLIENT_KEY`, `TIKTOK_CLIENT_SECRET`, `TIKTOK_REDIRECT_URI` | `false` | TikTok publishing through the official Content Posting API. |
| `WORKER_INSTAGRAM_PUBLISHING_ENABLED` | `false` | Opt a specific Worker in to driving real Instagram publishes (it never receives a token). |
| `PUBLISH_SCHEDULER_ENABLED`, `PUBLICATION_ANALYTICS_ENABLED`, `ROBOT_AUTOMATION_ENABLED` | `true` | Background schedulers; every tuning property of `application.yml` can also be overridden by its documented environment name in `docker-compose.pilot.yml`. |
| `app.operations.*` | see `docs/OPERATIONS.md` | Operations thresholds and probe bounds. |

Anything not listed here is not part of the pilot contract. Development-only variables (`BOOTSTRAP_*`, `SPRING_PROFILES_ACTIVE=dev`) are rejected or ignored in production.

## First account and Worker credential (the supported bootstrap)

There is exactly one supported way to create the first account, and it runs inside the API at startup, in any profile except `dev`:

1. If the database has **no users**, the API creates the workspace (`FDM_WORKSPACE_*`) and its OWNER (`FDM_OWNER_*`, password from `secrets/owner_password`). A weak, placeholder or e-mail-derived password, or a missing variable, stops startup with a message that names the variable and never the value. If the database already has users, these variables are ignored (logged without values).
2. If `FDM_WORKER_CREDENTIAL_ID` and the worker secret are set, the API registers that Worker credential once (idempotent by id).

There are no default accounts and no default tokens. Users cannot be created through the UI in this release; additional people are a documented limitation (see the release notes).

### Credential rotation

- **Worker credential:** generate a new UUID and secret, set `FDM_WORKER_CREDENTIAL_ID` and replace `secrets/worker_secret`, restart `api` (registers the new credential) and then `worker`. Disable the old one with `UPDATE worker_credentials SET enabled = false WHERE id = '<old id>';`.
- **Application MinIO key:** change `STORAGE_SECRET_KEY` and rerun `minio-init` (`docker compose ... run --rm minio-init`), then restart `api`.
- **Database password:** `ALTER USER ... PASSWORD` in PostgreSQL, update `POSTGRES_PASSWORD`, restart `api`.
- **Provider credentials** (Instagram/TikTok) are stored encrypted per account; reconnect the account from Settings. Rotating `SOCIAL_CREDENTIAL_ENCRYPTION_KEY` requires reconnecting every account.
- **Owner password:** there is no password-reset flow in this release (see limitations).

## PostgreSQL

The compose file creates the database and a dedicated application role. For an external database, use a non-superuser role that owns the database: Flyway runs `CREATE TABLE`, `CREATE INDEX` and `ALTER` as the application role at startup, and normal operation needs only DML on those tables. No extension or superuser privilege is required. Back up with `scripts/backup-postgres.ps1` (`docs/OPERATIONS.md`).

## Object storage

The bucket is private. Exactly one component creates it: the `minio-init` job (the API never needs `s3:CreateBucket`). The application key's policy allows `s3:ListBucket` and `s3:GetBucketLocation` on the bucket and `s3:GetObject`, `s3:PutObject`, `s3:DeleteObject` and multipart actions on its objects, nothing else. With an external S3-compatible store, create the bucket and an equivalent policy yourself. Unsigned object reads return 403; presigned reads support HTTP range requests.

## Edge, HTTPS and cookies

nginx is the only published entry point and the only component that terminates TLS and redirects HTTP to HTTPS. In `tls` mode it redirects to `APP_PUBLIC_ORIGIN` (not to a client-supplied host), adds HSTS (`max-age=31536000`, no `includeSubDomains` or `preload`), and serves a second TLS port for presigned storage URLs. The API and web containers are not published. nginx sets `X-Forwarded-Proto`, `X-Forwarded-Host` and `X-Forwarded-For` (the TCP peer, not an appended list), and the `prod` profile uses Spring's forwarded-header handling, which is safe only because the API port is never reachable directly. Session cookies are `Secure`, `HttpOnly`, `SameSite=Lax` (Lax is required for the provider OAuth redirects). The `loopback` mode serves plain HTTP on `localhost` only; browsers accept `Secure` cookies there. A load balancer or CDN in front of nginx must preserve the `Origin` and `Host` headers.

## Security headers and CSP

Every response carries `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: strict-origin-when-cross-origin`, a restrictive `Permissions-Policy` and a Content-Security-Policy: `default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src/media-src/connect-src 'self' plus the storage origin; object-src 'none'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'`. `style-src 'unsafe-inline'` is required because Angular injects component styles at runtime; scripts have no inline allowance (the production build has no inline script and does not inline critical CSS). nginx version disclosure is off. There is no CORS configuration: the application is same-origin, so no cross-origin access is granted. Production source maps are not built.

## Limits and timeouts

JSON requests are limited to 2 MB (login to 8 KB) at the edge. Media never passes through the API: imports are fetched by the Worker (limit `MEDIA_MAX_DOWNLOAD_SIZE_BYTES`, default 500 MiB, redirect and size bounded) and uploaded to storage through the storage origin, which accepts bodies up to 520 MB without buffering. Proxy timeouts are 60 s for the API (long work is asynchronous Jobs) and 600 s for storage transfers. Login is limited to 10 requests per minute per address with a burst of 5 (HTTP 429). There is no further rate limiting; the pilot is not designed for hostile traffic.

## Containers

All application containers run as non-root (`10001`, nginx `101`), with a read-only root filesystem, `no-new-privileges` and all capabilities dropped. Writable paths are `/tmp` (tmpfs; 4 GB for the Worker's media scratch space), nginx's `conf.d` (tmpfs, regenerated at start), the Worker installation id volume, and the PostgreSQL, RabbitMQ and MinIO data volumes. Images are tagged `fdm/{api,web,worker}:${FDM_VERSION}` and carry version, commit and build time as OCI labels. MinIO is built from the pinned upstream source tag (`infra/minio`) because upstream no longer publishes images to Docker Hub.

## Worker image, FFmpeg, Whisper and Ollama

The Worker image contains Ubuntu 22.04's FFmpeg 4.4.2 and FFprobe, the OpenAI Whisper CLI (CPU PyTorch) and the `base` model baked in under `/opt/cache`, so transcription never downloads a model at runtime. A host-run Worker (see `workers/README.md`) remains possible for development. Ollama is not part of the stack: to use LLM features run Ollama on the Docker host, pull the model, set `CONTENT_AI_PROVIDER=OLLAMA` (API) and `CONTENT_AI_RUNTIME=OLLAMA` (Worker), and ensure `CONTENT_AI_ENDPOINT` is reachable from the Worker container. When Ollama is absent the Worker does not advertise the AI capabilities and AI requests fail as "provider unavailable" instead of hanging; deterministic copy and highlights keep working.
