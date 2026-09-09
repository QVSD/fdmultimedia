# Architecture

## Phase 2 scope

Phase 2 establishes authentication, users, workspaces, and membership on top
of the Phase 1 foundation. Workers, jobs, publishing, analytics, AI, and
social integrations remain out of scope — see [ROADMAP.md](ROADMAP.md).

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
        Future Workers
       /              \
Local Laptop       Cloud Worker
```

- **Angular Login + Dashboard** — the browser-facing UI. It never talks to
  the backend directly; it goes through Nginx.
- **Spring Boot Control Plane** — a single deployable modular monolith. It
  owns authentication, workspace context, the database schema, and is the only
  thing that talks to Postgres and RabbitMQ.
- **Postgres** — system of record for the control plane.
- **RabbitMQ** — the future transport for distributing jobs to workers. In
  Phase 1 the backend is only wired up to connect to it; no queues,
  exchanges, or consumers are defined yet.
- **Workers** — interchangeable compute resources (a laptop, a cloud VM,
  anything that can run the worker process) that will eventually pull jobs
  from RabbitMQ and report back to the control plane. Not implemented yet.

## Request flow (Phase 2)

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

Packages outside `auth`, `users`, `workspaces`, and `shared` are still
placeholders today. The intent is that as each capability is built, its code
lands in the matching package with a clear boundary.

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

This rule has no code to enforce it yet (workers and jobs aren't
implemented), but it must shape the schema and APIs when they are designed.
