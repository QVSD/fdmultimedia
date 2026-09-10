# Architecture

## Phase 5 scope

Phase 5 adds media ingestion on top of the distributed job pipeline.
Authenticated users can submit direct HTTP/HTTPS media file URLs. The control
plane creates a `MediaAsset` plus an `IMPORT_MEDIA` job, and a worker safely
downloads, validates, checksums, uploads, and completes the import. FFmpeg
transformations, platform extraction, AI, publishing, smart scheduling, and
social integrations remain out of scope; see [ROADMAP.md](ROADMAP.md).

## High-level architecture

```
Angular Login + Dashboard
        |
        v
Spring Boot Control Plane + Session Auth
        |
   +----+----+--------+
   |         |        |
Postgres   RabbitMQ  MinIO / S3-compatible storage
   ^
   |
Workers ---- presigned PUT/GET mediated by API
       /              \
Local Laptop       Cloud Worker
```

- **Angular Login + Dashboard** — the browser-facing UI. It never talks to
  the backend directly; it goes through Nginx.
- **Spring Boot Control Plane** — a single deployable modular monolith. It
  owns authentication, workspace context, the database schema, and is the only
  thing that talks to Postgres and RabbitMQ.
- **Postgres** — system of record for the control plane, media asset metadata,
  and the durable job queue.
- **RabbitMQ** — available infrastructure reserved for a later event-driven
  dispatch optimization. Phase 5 intentionally continues to use PostgreSQL row locking
  because the database must remain the source of truth for job state anyway.
- **MinIO / S3-compatible storage** — private object storage for imported
  media binaries. The local stack uses MinIO; the storage abstraction can point
  at S3/R2-compatible storage later.
- **Workers** — interchangeable compute resources (a laptop, a cloud VM,
  anything that can run the worker process). They register, heartbeat, poll
  for jobs, execute `SYSTEM_TEST` and `IMPORT_MEDIA`, and report results.

## Request flow

```
Browser
   |
   v
Nginx
   |
    +---- /api/* ----> Spring Boot (api:8080, session + CSRF)
   |
   +---- /* --------> Angular  (web:80)
```

The browser only ever knows about the Nginx host/port. It never sees the
`api` or `web` container hostnames — those exist purely on the internal
Docker network. This is what lets the backend, frontend, and edge proxy be
deployed, scaled, or replaced independently later without changing anything
the browser does.

## Authentication and workspace context

Authentication uses Spring Security with email/password login, BCrypt password
hashes, and server-side HTTP sessions. The browser stores only cookies; it
does not store credentials, password hashes, or bearer tokens in localStorage.

Spring Security keeps CSRF enabled. The backend publishes an `XSRF-TOKEN`
cookie for the Angular SPA, and Angular sends the matching `X-XSRF-TOKEN`
header on protected mutating requests. The session cookie is `HttpOnly`;
production marks it `Secure`.

The backend derives the current user from the session principal. Workspace
access is then checked against `workspace_memberships`; client-supplied
workspace IDs are never trusted without validating membership. Phase 2 chooses
the first membership as the current workspace, leaving explicit workspace
switching for a later phase.

Worker agents use a separate machine-token path under `/api/worker-agent/**`.
Those endpoints do not use browser sessions or CSRF cookies, and CSRF remains
enabled for browser APIs. Worker credentials belong to one workspace, are
stored as BCrypt hashes, and authorize only worker registration/heartbeat.
Human APIs such as `GET /api/workers` remain session-protected and scoped to
the user's current workspace membership.

Human job APIs (`/api/jobs`) and asset APIs (`/api/assets`) use the same
session and CSRF path. The workspace for create/list/detail/cancel/import is
derived from the authenticated membership; clients cannot choose an arbitrary
`workspaceId`.

Worker job APIs (`/api/worker-agent/jobs/**`) and worker import APIs
(`/api/worker-agent/assets/imports/**`) use `WorkerToken` machine
authentication. The credential's workspace is authoritative, the worker must
already be registered and currently online, and a worker may only update jobs
assigned to itself.

## Modular monolith

The backend (`apps/api-spring`) is a single Spring Boot application,
deliberately not split into microservices yet. It is organized into
top-level packages that map to future bounded contexts:

```
com.fdmultimedia.api
├── auth          — session authentication, bootstrap, and auth DTOs
├── users         — user accounts and email normalization
├── workspaces    — workspaces / tenants and membership authorization
├── accounts      — connected external (social) accounts
├── robots        — logical content-automation entities
├── assets        — media assets
├── jobs          — distributed processing jobs
├── workers       — worker registration and management
├── publishing    — publishing to external platforms
├── analytics     — analytics and reporting
├── revenue       — revenue tracking and attribution
└── shared        — cross-cutting concerns (web, config, health)
```

Packages outside `auth`, `users`, `workspaces`, `jobs`, `workers`, and
`shared` are still placeholders today. The intent is that as each capability
is built, its code lands in the matching package with a clear boundary.

## Job lifecycle and worker protocol

Jobs are persisted in PostgreSQL with JSONB `payload` and `result` fields.
`SYSTEM_TEST` accepts a bounded message and duration. `IMPORT_MEDIA` accepts
only an `assetId` reference; source URL and storage state live on the
`MediaAsset`. Neither job type executes shell commands or arbitrary code.

Allowed state transitions:

- `QUEUED -> ASSIGNED` when one online worker claims the job.
- `ASSIGNED -> RUNNING` when that worker acknowledges start.
- `RUNNING -> SUCCEEDED` when that worker reports a result.
- `ASSIGNED|RUNNING -> QUEUED` when a retryable failure or expired lease still
  has attempts remaining.
- `ASSIGNED|RUNNING -> FAILED` when attempts are exhausted.
- `QUEUED|ASSIGNED|RUNNING -> CANCELLED` when a human cancels an active job.

`SUCCEEDED`, `FAILED`, and `CANCELLED` are terminal and never transition back
to active states.

Workers poll `POST /api/worker-agent/jobs/claim`. The claim transaction first
recovers expired leases for that workspace, then selects the oldest queued job
with `FOR UPDATE SKIP LOCKED` and assigns it to the worker. That prevents two
workers from claiming the same queued row concurrently without introducing a
separate broker-level dispatch protocol.

Claims set `lease_expires_at` and increment `attempt_count`. Starting and
renewing a job refreshes the lease. The worker renews leases while long-running
imports are active, so legitimate downloads do not look abandoned. If a laptop
disappears after claim/start and stops renewing, the next claim for that
workspace lazily recovers expired active jobs: retryable jobs return to
`QUEUED`, while jobs that exhausted `max_attempts` become `FAILED`. This is
deliberately simple and avoids a distributed scheduler in Phase 5.

## MediaAsset lifecycle and object storage

`MediaAsset` rows belong to a workspace and record the original direct URL,
status, created user, linked import job, storage bucket/key, checksum, size,
content type, and basic metadata.

Allowed asset transitions:

- `PENDING -> IMPORTING` when an assigned worker requests import authorization.
- `IMPORTING -> READY` when that worker completes the assigned import job.
- `IMPORTING -> PENDING` after a retryable worker failure while attempts remain.
- `PENDING|IMPORTING -> FAILED` for terminal validation failures or exhausted
  retries.

`READY` is terminal for Phase 5 and means a private original object exists in
object storage. Server-generated storage keys use
`workspaces/{workspaceId}/assets/{assetId}/original`; user filenames are stored
only as metadata and never influence object paths.

The API owns permanent object-storage credentials. Workers request a short-lived
presigned PUT URL, upload the downloaded file directly, and report the bucket
and key they were authorized to use. The server verifies worker authentication,
job ownership, workspace, asset/job matching, storage bucket/key, checksum
shape, and legal state before marking the asset `READY` and the job
`SUCCEEDED`. Browser users request short-lived presigned GET URLs for READY
assets; MinIO buckets remain private and signed URLs are not stored.

`IMPORT_MEDIA` retry behavior is intentionally bounded. Retryable failures
such as temporary source or storage failures requeue the job until
`maxAttempts`; terminal failures such as SSRF blocks, invalid schemes,
non-media content, and oversized files fail the asset and job immediately.

## URL and download security

The API validates direct import URLs before asset creation and the worker
revalidates before every download and redirect. Only `http` and `https` are
allowed. Embedded credentials, localhost, loopback, private IPv4 ranges,
private/link-local IPv6 ranges, multicast/reserved addresses, and cloud
metadata endpoints are rejected. The worker follows only a small bounded number
of redirects and revalidates each target to avoid blindly trusting the initial
host.

Downloads use connection/read timeouts and stream to a temporary file while
enforcing `MEDIA_MAX_DOWNLOAD_SIZE_BYTES`, even when `Content-Length` is
missing or wrong. Temporary files are deleted in success and failure paths.
The worker rejects clearly non-media response types such as HTML, JSON, XML,
and text. Phase 5 records size, SHA-256, content type, safe original filename,
and container-like information inferred from content type. Rich codec,
duration, and resolution extraction can be added later with read-only FFprobe
without changing the asset model.

## An important architectural rule: Robots are not workers

A **Robot** is a logical content-automation entity — think of it as "a
personality/pipeline that posts to a specific channel" or "an automated
workflow the user configured." A **Worker** is a physical or virtual compute
resource (a laptop, a cloud VM) that executes jobs.

**A Robot MUST NOT be coupled 1:1 to a physical worker.** Workers are
interchangeable, disposable compute — any worker with the right
capabilities should be able to pick up any job for any robot. A robot's
identity, configuration, and history must never depend on which specific
machine happened to run its jobs. This separation is what allows workers to
be added, removed, or replaced (a laptop goes offline, a cloud instance is
scaled up) without affecting the robots whose jobs they process.

Phase 4 lets workers execute generic platform jobs, but still does not tie
robots to workers. `SYSTEM_TEST` is a controlled pipeline proof, not media
execution or robot automation.
