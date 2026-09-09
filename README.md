# fd multimedia

A distributed media automation platform: eventually this will manage
social-media content workflows, video processing workers running across
multiple laptops/cloud machines, scheduling, AI-assisted content creation,
publishing, analytics, and revenue tracking.

## Phase 2 scope

This repository is currently at **Phase 2: authentication, users,
workspaces, and membership**. That
means:

- A clean monorepo layout (`apps/`, `workers/`, `infra/`, `docs/`).
- A Spring Boot 21 modular monolith (`apps/api-spring`) with the package
  structure for future modules, a health endpoint, PostgreSQL + Flyway,
  RabbitMQ connectivity, and secure session-based authentication.
- Users, workspaces, and workspace memberships with OWNER/ADMIN/MEMBER
  roles. Future business resources can be scoped to `workspace_id`.
- An Angular application (`apps/web-angular`) with a login page, protected
  dashboard routes, a sidebar shell, and placeholder pages for every planned
  section.
- Nginx as the single entry point, routing `/api/*` to the backend and
  everything else to the frontend.
- Docker Compose to run the whole stack locally.

No workers, jobs, publishing, AI, social integrations, analytics, or billing
are implemented yet — see [docs/ROADMAP.md](docs/ROADMAP.md) for what comes
next and [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for how the pieces fit
together.

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
- Spring Boot Actuator health endpoint (more detail on Postgres/RabbitMQ
  connectivity): `GET http://localhost:8080/api/actuator/health`

(Both are reached through Nginx at the port above; the backend itself is
not exposed directly to the host.)

## Authentication

- `POST /api/auth/login` starts a secure server-side session.
- `GET /api/auth/me` returns the current safe user/workspace context.
- `POST /api/auth/logout` invalidates the session and requires CSRF.
- `GET /api/workspaces` and `GET /api/workspaces/{workspaceId}` require an
  authenticated workspace member.

The development profile can bootstrap one OWNER and one optional ADMIN in the
same workspace from `.env`. Restarting the stack is idempotent and does not
create duplicates.

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
workers/           Future distributed worker implementation (not yet built)
infra/
├── nginx/         Reverse proxy configuration
└── docker/        Shared Docker resources
docs/              Architecture, development, and roadmap docs
```
