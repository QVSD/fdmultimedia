# Architecture

## Phase 4 scope

Phase 4 adds the first real distributed execution pipeline on top of worker
registration. Authenticated users can create safe `SYSTEM_TEST` jobs, worker
agents can claim exactly one job at a time through the control plane, and job
state is tracked durably in PostgreSQL. Video processing, FFmpeg, AI,
publishing, smart scheduling, and social integrations remain out of scope; see
[ROADMAP.md](ROADMAP.md).

## High-level architecture

```
Angular Login + Dashboard
        |
        v
Spring Boot Control Plane + Session Auth
        |
   +----+----+
   |         |
Postgres   RabbitMQ
              |
              v
         Workers
       /              \
Local Laptop       Cloud Worker
```

- **Angular Login + Dashboard** — the browser-facing UI. It never talks to
  the backend directly; it goes through Nginx.
- **Spring Boot Control Plane** — a single deployable modular monolith. It
  owns authentication, workspace context, the database schema, and is the only
  thing that talks to Postgres and RabbitMQ.
- **Postgres** — system of record for the control plane and the Phase 4
  durable job queue.
- **RabbitMQ** — available infrastructure reserved for a later event-driven
  dispatch optimization. Phase 4 intentionally uses PostgreSQL row locking
  because the database must remain the source of truth for job state anyway.
- **Workers** — interchangeable compute resources (a laptop, a cloud VM,
  anything that can run the worker process). They register, heartbeat, poll
  for jobs, execute only `SYSTEM_TEST`, and report results.

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

Human job APIs (`/api/jobs`) use the same session and CSRF path. The workspace
for create/list/detail/cancel is derived from the authenticated membership;
clients cannot choose an arbitrary `workspaceId`.

Worker job APIs (`/api/worker-agent/jobs/**`) use `WorkerToken` machine
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

Phase 4 persists jobs in PostgreSQL with JSONB `payload` and `result` fields.
The initial and only executable type is `SYSTEM_TEST`, which accepts a bounded
message and duration. It never executes shell commands or arbitrary code.

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

Claims set `lease_expires_at` and increment `attempt_count`. Starting a job
refreshes the lease. If a laptop disappears after claim/start, the next claim
for that workspace lazily recovers expired active jobs: retryable jobs return
to `QUEUED`, while jobs that exhausted `max_attempts` become `FAILED`. This is
deliberately simple and avoids a distributed scheduler in Phase 4.

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
