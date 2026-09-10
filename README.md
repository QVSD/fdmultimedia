# fd multimedia

A distributed media automation platform: eventually this will manage
social-media content workflows, video processing workers running across
multiple laptops/cloud machines, scheduling, AI-assisted content creation,
publishing, analytics, and revenue tracking.

## Phase 4 scope

This repository is currently at **Phase 4: jobs and distributed execution**. That
means:

- A clean monorepo layout (`apps/`, `workers/`, `infra/`, `docs/`).
- A Spring Boot 21 modular monolith (`apps/api-spring`) with the package
  structure for future modules, a health endpoint, PostgreSQL + Flyway,
  RabbitMQ connectivity, and secure session-based authentication.
- Users, workspaces, and workspace memberships with OWNER/ADMIN/MEMBER
  roles. Future business resources can be scoped to `workspace_id`.
- Worker credentials, worker registration, heartbeat tracking, and a
  workspace-scoped Compute page. Worker status is derived from heartbeat age.
- Centrally-created `SYSTEM_TEST` jobs with PostgreSQL-backed durable state,
  atomic worker claiming, leases, bounded retry, and result/error tracking.
- A standalone Java 21 worker agent in `workers/java-agent` that persists a
  random installation identifier locally, reports basic machine metadata,
  heartbeats, polls for work, and executes only the safe `SYSTEM_TEST` job.
- An Angular application (`apps/web-angular`) with a login page, protected
  dashboard routes, a sidebar shell, a Compute page, a Jobs page, and
  placeholder pages for later product sections.
- Nginx as the single entry point, routing `/api/*` to the backend and
  everything else to the frontend.
- Docker Compose to run the whole stack locally.

No media processing, publishing, AI, social integrations, analytics, or
billing are implemented yet — see [docs/ROADMAP.md](docs/ROADMAP.md) for what
comes next and [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for how the pieces
fit together.

## Prerequisites

- [Docker](https://www.docker.com/) with Docker Compose v2 (`docker compose`,
  not the old `docker-compose`).
- That's it for running the stack. For working on the apps directly
  outside Docker, see [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)
  (Java 21 + Maven for the backend, Node.js 22+ for the frontend).

## Configure

```bash
cp .env.example .env
```

On Windows PowerShell:

```powershell
Copy-Item .env.example .env
```

Edit `.env` if you want different local ports or credentials — the
defaults are safe, development-only values. Never commit a real `.env`
file.

## Start the stack

```bash
docker compose up --build
```

This builds and starts, in dependency order:

1. `postgres` — PostgreSQL, with a named volume for data.
2. `rabbitmq` — RabbitMQ, with a named volume for data.
3. `api` — the Spring Boot backend (waits for Postgres and RabbitMQ to be
   healthy, then runs Flyway migrations on startup).
4. `web` — the Angular frontend, built and served as static files.
5. `nginx` — the reverse proxy in front of `api` and `web`.

Once everything is healthy, open:

**http://localhost:8080** (or whatever `NGINX_PORT` you set in `.env`)

You should be redirected to `/login`. Sign in with the bootstrap credentials
from your local `.env`, then you should see the dashboard with **Backend:
Online**, your user, workspace, and role.

Run it in the background instead with `docker compose up --build -d`, and
follow logs with `docker compose logs -f`.

## Stop the stack

```bash
docker compose down
```

## Reset local data (Postgres / RabbitMQ volumes)

```bash
docker compose down -v
```

This deletes the named volumes (`postgres_data`, `rabbitmq_data`), so the
next `docker compose up` starts from a clean database and message broker.

## Backend health check

- Application health endpoint: `GET http://localhost:8080/api/health`
  → `{"status":"UP","service":"media-platform-api"}`
- Spring Boot Actuator health endpoint:
  `GET http://localhost:8080/api/actuator/health`

(Both are reached through Nginx at the port above; the backend itself is
not exposed directly to the host.) Actuator dependency details are hidden by
default and in `prod`; the `dev` profile exposes them for local diagnostics.

## Authentication

- `POST /api/auth/login` starts a secure server-side session.
- `GET /api/auth/me` returns the current safe user/workspace context.
- `POST /api/auth/logout` invalidates the session and requires CSRF.
- `GET /api/workspaces` and `GET /api/workspaces/{workspaceId}` require an
  authenticated workspace member.

The development profile can bootstrap one OWNER and one optional ADMIN in the
same workspace from `.env`. Restarting the stack is idempotent and does not
create duplicates.

## Worker registration

The development profile can also bootstrap one worker credential from `.env`.
The API stores only a BCrypt hash of the worker secret. A worker agent uses a
machine token in this format:

```text
FDM_WORKER_TOKEN=<BOOTSTRAP_WORKER_CREDENTIAL_ID>.<BOOTSTRAP_WORKER_CREDENTIAL_SECRET>
```

Build and run the local Java worker agent:

```bash
cd workers/java-agent
../../apps/api-spring/mvnw -f pom.xml package
FDM_API_BASE_URL=http://localhost:8080/api \
FDM_WORKER_TOKEN=11111111-1111-4111-8111-111111111111.dev_worker_secret_change_me \
java -jar target/worker-agent-0.1.0-SNAPSHOT.jar
```

On Windows PowerShell, run Maven through `apps\api-spring\mvnw.cmd` and set
the same environment variables with `$env:FDM_API_BASE_URL` and
`$env:FDM_WORKER_TOKEN`.

## Jobs and distributed execution

Phase 4 uses PostgreSQL as the durable job queue. Workers poll the control
plane and claim jobs with a transactional `FOR UPDATE SKIP LOCKED` query, so
one queued job is assigned to exactly one worker. RabbitMQ remains available
in the stack but is reserved for a later event-driven dispatch optimization.

The first executable job type is `SYSTEM_TEST`. It accepts:

```json
{
  "message": "hello worker",
  "durationMs": 2000
}
```

The worker validates this payload, optionally waits for `durationMs`, and
returns a JSON result with the message, worker name, and execution duration.
It never executes shell commands or arbitrary code.

Job states are:

```text
QUEUED -> ASSIGNED -> RUNNING -> SUCCEEDED
QUEUED -> CANCELLED
ASSIGNED/RUNNING -> QUEUED     (retry after failure or expired lease)
ASSIGNED/RUNNING -> FAILED     (max attempts exhausted)
```

`SUCCEEDED`, `FAILED`, and `CANCELLED` are terminal. Active jobs have a
lease; if a worker disappears, the next claim operation recovers expired
leases and either requeues the job or marks it `FAILED` when attempts are
exhausted.

## RabbitMQ management UI

Exposed for local development at **http://localhost:15672** (or whatever
`RABBITMQ_MANAGEMENT_PORT` you set), using the `RABBITMQ_USER` /
`RABBITMQ_PASSWORD` from your `.env`.

## Troubleshooting

- **`docker compose up` fails immediately / "Cannot connect to the Docker
  daemon"** — make sure Docker Desktop (or your Docker engine) is running.
- **`api` never becomes healthy** — check `docker compose logs api`. The
  backend fails fast and loudly if it can't reach Postgres or RabbitMQ, or
  if required environment variables are missing — the log will say exactly
  what's wrong. Confirm `.env` exists (`cp .env.example .env`) and that
  `postgres`/`rabbitmq` are already healthy (`docker compose ps`).
- **Flyway migration errors** — these show up in `docker compose logs api`
  at startup. Since this is local/dev data, `docker compose down -v` and
  starting again is usually the fastest fix.
- **Port already in use** — change `NGINX_PORT` (and/or
  `RABBITMQ_MANAGEMENT_PORT`) in `.env` to a free port.
- **Frontend shows "Backend: Offline"** — the browser reached Nginx but
  the `/api/*` proxy to the backend failed; check `docker compose ps` and
  `docker compose logs api nginx`.
- **Stale frontend after code changes** — Docker cached an old build;
  run `docker compose up --build web` (or `--build` on everything) to
  rebuild the image.

## Repository layout

```
apps/
├── web-angular/   Angular frontend
└── api-spring/    Spring Boot backend (modular monolith)
workers/           Standalone worker agent implementation
infra/
├── nginx/         Reverse proxy configuration
└── docker/        Shared Docker resources
docs/              Architecture, development, and roadmap docs
```
