# Development

## Prerequisites

- Docker + Docker Compose (v2) — for the full stack.
- Java 21 — for working on `apps/api-spring` outside Docker. Set
  `JAVA_HOME` to the JDK directory when running the Maven Wrapper on Windows.
- Node.js 22+ and npm — for working on `apps/web-angular` outside Docker.

## Running everything

See the root [README.md](../README.md) for the `docker compose up` workflow.
This is the recommended way to run the full stack — it's what CI/reviewers
will exercise and what the acceptance criteria are based on.

For a fresh development database, `.env` can provide bootstrap values for the
initial owner, workspace, and optional second user. The bootstrap runs only
with `SPRING_PROFILES_ACTIVE=dev`, hashes passwords with BCrypt, and is
idempotent. It can also create one development worker credential; only the
BCrypt secret hash is stored.

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

On Windows shells, use `mvnw.cmd` instead of `./mvnw`:

```powershell
mvnw.cmd spring-boot:run
```

Run the backend tests with:

```bash
./mvnw test
```

or on Windows:

```powershell
mvnw.cmd test
```

The current backend tests are lightweight unit and web-slice tests; they don't
need Postgres or RabbitMQ running.

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

## Authentication and CSRF

The browser uses secure server-side session authentication. Angular sends
same-origin API requests to `/api/*`; Nginx forwards them to Spring Boot.
Spring Security issues an `HttpOnly` session cookie and a readable
`XSRF-TOKEN` cookie. Angular mirrors that CSRF token in the `X-XSRF-TOKEN`
header for protected mutating requests such as logout.

Worker agent endpoints under `/api/worker-agent/**` use `Authorization:
WorkerToken <credential-id>.<secret>` instead of browser sessions. CSRF is
ignored only for those machine endpoints and remains enabled for session APIs.

Job creation/list/detail endpoints under `/api/jobs` are normal browser
session APIs. Keep CSRF enabled for mutating human requests and always derive
workspace scope from the authenticated membership.

## Working on the worker agent

```bash
cd workers/java-agent
../../apps/api-spring/mvnw -f pom.xml test
../../apps/api-spring/mvnw -f pom.xml package
```

On Windows PowerShell:

```powershell
..\..\apps\api-spring\mvnw.cmd -f pom.xml test
..\..\apps\api-spring\mvnw.cmd -f pom.xml package
```

Run it against the Docker stack with the development worker credential from
`.env`:

```powershell
$env:FDM_API_BASE_URL = "http://localhost:8080/api"
$env:FDM_WORKER_TOKEN = "$env:BOOTSTRAP_WORKER_CREDENTIAL_ID.$env:BOOTSTRAP_WORKER_CREDENTIAL_SECRET"
java -jar target\worker-agent-0.1.0-SNAPSHOT.jar
```

Useful worker environment variables:

- `FDM_WORKER_NAME` — friendly name reported to the Compute and Jobs pages.
- `FDM_WORKER_ID_FILE` — local file that stores the generated installation ID.
- `FDM_WORKER_HEARTBEAT_SECONDS` — heartbeat interval, default 10 seconds.
- `FDM_WORKER_JOB_POLL_SECONDS` — job polling interval, default 3 seconds.

The Phase 4 worker registers, heartbeats, polls for a job, starts it, executes
only `SYSTEM_TEST`, and reports success or failure. `SYSTEM_TEST` is limited to
a bounded message and sleep duration; it is not a generic command runner.

Manual distributed execution check:

1. Start the Docker stack and log in through `http://localhost:8080`.
2. Leave the worker stopped and create a `SYSTEM_TEST` job from Jobs; it should
   stay `QUEUED`.
3. Start the worker agent; the job should move through `ASSIGNED`, `RUNNING`,
   and `SUCCEEDED`.
4. Create several jobs; each should complete once with the same workspace.
5. Stop the worker during a longer job and restart it after the lease expires;
   the job should retry until `maxAttempts`, then either succeed or fail.
