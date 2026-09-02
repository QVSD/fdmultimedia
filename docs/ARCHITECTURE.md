# Architecture

## Phase 1 scope

Phase 1 establishes the project foundation only: a working Angular +
Spring Boot + PostgreSQL + RabbitMQ stack behind Nginx, running as a modular
monolith. No product features (workers, jobs, publishing, analytics, AI,
etc.) are implemented yet — see [ROADMAP.md](ROADMAP.md).

## High-level architecture

```
Angular Dashboard
        |
        v
Spring Boot Control Plane
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

- **Angular Dashboard** — the browser-facing UI. It never talks to the
  backend directly; it goes through Nginx.
- **Spring Boot Control Plane** — a single deployable modular monolith. It
  owns the database schema and is the only thing that talks to Postgres and
  RabbitMQ.
- **Postgres** — system of record for the control plane.
- **RabbitMQ** — the future transport for distributing jobs to workers. In
  Phase 1 the backend is only wired up to connect to it; no queues,
  exchanges, or consumers are defined yet.
- **Workers** — interchangeable compute resources (a laptop, a cloud VM,
  anything that can run the worker process) that will eventually pull jobs
  from RabbitMQ and report back to the control plane. Not implemented yet.

## Request flow (Phase 1)

```
Browser
   |
   v
Nginx
   |
   +---- /api/* ----> Spring Boot (api:8080)
   |
   +---- /* --------> Angular  (web:80)
```

The browser only ever knows about the Nginx host/port. It never sees the
`api` or `web` container hostnames — those exist purely on the internal
Docker network. This is what lets the backend, frontend, and edge proxy be
deployed, scaled, or replaced independently later without changing anything
the browser does.

## Modular monolith

The backend (`apps/api-spring`) is a single Spring Boot application,
deliberately not split into microservices yet. It is organized into
top-level packages that map to future bounded contexts:

```
com.fdmultimedia.api
├── auth          — authentication and authorization (not implemented)
├── users         — user accounts and profiles
├── workspaces    — workspaces / tenants
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

Each package is a placeholder today (a `package-info.java` and nothing
else, except `shared`). The intent is that as each capability is built, its
code lands in the matching package with a clear boundary — so that if/when
part of the monolith needs to be extracted into its own service later, the
seams are already there.

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
