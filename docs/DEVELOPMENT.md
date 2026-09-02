# Development

## Prerequisites

- Docker + Docker Compose (v2) — for the full stack.
- Java 21 — for working on `apps/api-spring` outside Docker.
- Node.js 22+ and npm — for working on `apps/web-angular` outside Docker.

## Running everything

See the root [README.md](../README.md) for the `docker compose up` workflow.
This is the recommended way to run the full stack — it's what CI/reviewers
will exercise and what the acceptance criteria are based on.

## Working on the backend alone

```bash
cd apps/api-spring
```

The app reads its configuration entirely from environment variables (see
`src/main/resources/application.yml`) and will fail to start if
`DB_HOST`/`DB_PORT`/`DB_NAME`/`DB_USER`/`DB_PASSWORD` or the
`RABBITMQ_*` equivalents aren't set — there is no silent fallback (e.g. no
H2 in-memory database). The simplest way to get real Postgres/RabbitMQ
without running the whole stack:

```bash
docker compose up -d postgres rabbitmq
```

Then export the same variables docker-compose would have injected (see
`.env.example`) with `DB_HOST=localhost` / `RABBITMQ_HOST=localhost`, and
run:

```bash
./mvnw spring-boot:run
```

Run the backend tests with:

```bash
./mvnw test
```

The tests are web-slice tests (`@WebMvcTest`) — they don't need Postgres or
RabbitMQ running.

## Working on the frontend alone

```bash
cd apps/web-angular
npm install
npm start          # ng serve
npm test           # unit tests
npm run build      # production build
```

`ng serve` on its own doesn't have anything to talk to at `/api`. Either:

- run the backend too (see above) and start the dev server with
  `npm start -- --proxy-config proxy.conf.json`, which proxies `/api/*` to
  `http://localhost:8080`, or
- run the whole stack via Docker Compose and open it through Nginx instead.

## Adding a new backend module

Domain logic isn't implemented yet, but the package layout under
`com.fdmultimedia.api` (`auth`, `users`, `workspaces`, `accounts`, `robots`,
`assets`, `jobs`, `workers`, `publishing`, `analytics`, `revenue`, `shared`)
is where it should land. See [ARCHITECTURE.md](ARCHITECTURE.md) for what
each package is for.

## Database migrations

Migrations live in `apps/api-spring/src/main/resources/db/migration` and
run automatically on startup via Flyway. Add a new
`V<next-number>__description.sql` file — never edit a migration that has
already shipped.
