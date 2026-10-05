# FDM Multimedia Operations Runbook

This runbook covers the Phase 17P Docker Compose deployment and the Phase 17Q operations control plane. It describes bounded recovery procedures; it is not an automatic failover system. Commands assume the repository root and an operator-authored `.env`. Never paste secrets, cookies, presigned URLs, dumps or media into tickets or source control.

## Service Model

The expected stack is PostgreSQL, RabbitMQ, MinIO, API, web and nginx. PostgreSQL is authoritative for domain records, distributed Jobs, leases, audit trails and HTTP sessions. MinIO is authoritative for private media objects. RabbitMQ is optional transport; PostgreSQL Jobs remain the durable work queue.

API replicas are stateless apart from bounded per-instance scheduler telemetry. Spring Session JDBC shares browser authentication and CSRF state through PostgreSQL. `X-FDM-API-Instance` identifies the serving replica for diagnostics. A replica restart must not log out a user while PostgreSQL remains available.

## Health And Operations

- `GET /api/health`: compatibility application health.
- `GET /api/actuator/health/liveness`: public process liveness only.
- `GET /api/actuator/health/readiness`: public readiness including PostgreSQL.
- `GET /api/actuator/health`: authenticated aggregate health.
- `GET /api/operations/status`: workspace-scoped operational summary for OWNER and ADMIN users.
- `GET /api/operations/overview|workers|jobs|schedulers|publishing|incidents` and `POST /api/operations/incidents/{id}/acknowledge`: the Phase 17Q operations control plane (see below).

MinIO is intentionally not a readiness member. Its state appears in aggregate health and operations status; storage operations have bounded call timeouts and fail without taking the database-backed control plane out of service. RabbitMQ is reported as optional because Jobs are durable in PostgreSQL.

Docker health checks use readiness through nginx/API. During shutdown, readiness is removed before the bounded graceful-drain period. Alert on sustained readiness failure, repeated scheduler failure state, growing queued/leased Jobs, offline Workers, or object storage DOWN. The repository does not install an alerting backend.

## Scheduler Inventory

All operations are class A: safe to run on every replica. The operation endpoint reports their local last start/completion/success/failure, duration and counts.

| Operation | Bound | Multi-replica invariant |
| --- | ---: | --- |
| publish-schedule-dispatch | 50 | `FOR UPDATE SKIP LOCKED`; one schedule per transaction |
| publication-analytics | 100 | claim token and lease; unique publication/bucket |
| robot-automation | 60 by default (10 dispatch + 50 reconciliation) | configured limits; row locks and idempotent run reconciliation |
| autonomous-proposals | 100 | policy row lock and unique opportunity fingerprint |
| adaptive-execution | 100 | authorization row lock and unique logical attempt |
| post-change-safety | 100 | per-revision advisory lock and unique recommendation |
| adaptive-memory | 100 | per-Robot advisory lock and unique source event |
| scheduling-decision-retention | 10000 | bounded idempotent delete |
| job-lease-recovery | on demand | claim-time row locks with `SKIP LOCKED` |
| spring-session-cleanup | framework | conditional expiry delete |

`job-lease-recovery` and `spring-session-cleanup` run inside the framework/claim transaction and are reported as `ON_DEMAND_NOT_TIMED`. Per-instance telemetry is intentionally ephemeral. The database constraints and domain rows are the correctness/audit source. On context close the tracker rejects new scheduled batches; already-running units keep their existing transactional isolation.

## Graceful Shutdown And Crash Recovery

The API uses graceful server and scheduler shutdown with a 30-second Spring lifecycle bound. Compose grants 35 seconds. Nginx retries only connection/timeout/502/503/504 failures for retry-safe methods; it never opts non-idempotent requests into retry.

The Worker stops heartbeat and claim loops first, calls graceful executor shutdown, waits `FDM_WORKER_SHUTDOWN_GRACE_SECONDS` (default 25; 1-300), then interrupts remaining local tasks. A hard crash leaves an assigned/running Job leased. A later claim transaction compares the lease with PostgreSQL `CURRENT_TIMESTAMP`, returns the expired Job to the claimable set under row locks and continues the same logical Job. Never repair lease timestamps manually.

Recovery check:

1. Confirm the original Worker is no longer heartbeating.
2. Wait for the configured lease to expire.
3. Start or retain a compatible Worker.
4. Confirm the same Job id is reclaimed and reaches a terminal state.
5. Inspect attempt/error metadata; do not create a replacement Job unless the original is terminal and business policy requires it.

## Dependency Incidents

### PostgreSQL

Readiness must fail while PostgreSQL is unavailable. Liveness remains process-oriented. Shared sessions and all domain work are unavailable until the database returns. After recovery, verify readiness, Flyway version, an authenticated session, Job claiming and the operations endpoint. Hikari acquisition/validation timeouts bound failed requests.

### MinIO

Readiness remains available. Storage-backed endpoints fail within the SDK timeout while control-plane reads continue. Restore MinIO, verify the private bucket, then test an authorized presigned ranged GET. Do not make the bucket public as a recovery shortcut.

### Provider Or Worker

Provider calls and distributed Jobs retain their existing controlled failure and retry semantics. Do not replay an outcome-unknown social publication. A Worker can be replaced; expired leases are recovered from database time. Check capability compatibility before diagnosing a queued Job as stuck.

## Retry Ownership And Effective Bounds

No operation retries without a bound, and retries are never stacked across layers on purpose.

| Layer | Behavior | Effective bound |
| --- | --- | --- |
| Distributed Job | PostgreSQL row with a lease; expired lease returns it to the queue | `JOB_LEASE_DURATION` (30 s) per attempt, at most `max_attempts` (default 3) attempts |
| Publication analytics | Claim with token and lease; a retryable provider failure schedules the next collection after a fixed 1 h backoff | one attempt per claim, per-bucket uniqueness, never a tight loop |
| Object storage (S3 SDK) | SDK default retry inside one call | `apiCallAttemptTimeout` 5 s, `apiCallTimeout` 10 s per call, so storage calls fail in at most 10 s |
| nginx to API | retries a failed idempotent request on another replica after a connection error, timeout or 502/503/504 | at most 4 upstream tries, `proxy_connect_timeout` 3 s; non-idempotent methods are never retried |
| Schedulers and reconcilers | fixed-delay or cron cadence; failure of one unit is logged and the batch continues | bounded batch sizes (table above); next attempt at the next tick |
| Social publication | an outcome-unknown publication is never replayed automatically | surfaced for a person; do not republish blindly |

A request that cannot reach PostgreSQL is answered by `DatabaseUnavailableFilter` with a clean `503` and `Retry-After: 5`; it logs one bounded warning per 30 seconds and never a stack trace per request. Unauthenticated requests never create a stored session (the saved-request cache is disabled), so anonymous traffic cannot grow the session table.

## Verified Failure Behavior

Acceptance observations (Docker Compose, four API replicas behind nginx): sixty authenticated requests were spread evenly over four replicas with zero 401; a valid CSRF mutation succeeded on every replica while a missing or forged token returned 403; restarting the replica that served login did not log the user out; a logged-out session was rejected by every replica; stopping two of four replicas under continuous authenticated traffic produced 517 of 517 successful responses; with PostgreSQL stopped, readiness returned 503 and liveness 200, requests failed with a clean 503, and the same session worked after PostgreSQL returned without restarting any API; with MinIO stopped, metadata endpoints and readiness stayed healthy while media endpoints failed within the SDK timeout and recovered within seconds of MinIO returning; a Worker killed with SIGKILL mid-job had the same Job reclaimed by another Worker after lease expiry (attempt 2 of 3, one logical result), and a Worker stopped with SIGTERM finished its in-flight Job before exiting; four API instances started concurrently against a V43 database applied V44 exactly once.

## PostgreSQL Backup

Keep backups outside the checkout and on separately protected storage. The script uses PostgreSQL custom format, excludes owner/privilege replay and excludes live Spring Session row data.

```powershell
$env:POSTGRES_DB = '<database>'
$env:POSTGRES_USER = '<database-user>'
.\scripts\backup-postgres.ps1 -OutputDirectory 'D:\fdm-backups'
```

Protect and rotate the resulting `.dump`; test it regularly. The script does not read or store a password. Docker supplies the already-configured database authentication inside the PostgreSQL container.

MinIO media is not present in this dump. Configure private bucket versioning/replication or provider-native object backup, retain encryption and access controls, and test object restoration alongside the database drill.

## PostgreSQL Restore Drill

Restore only to a new, explicitly named database first. The confirmation parameter must match exactly. The script refuses PostgreSQL system databases, the container's primary database and the operator's configured `POSTGRES_DB`; `-ReplaceTarget` is only for a disposable drill target.

```powershell
$env:POSTGRES_USER = '<database-user>'
.\scripts\restore-postgres.ps1 `
  -InputFile 'D:\fdm-backups\fdmultimedia-example.dump' `
  -TargetDatabase 'fdm_restore_drill' `
  -ConfirmTargetDatabase 'fdm_restore_drill'
```

Required verification before any promotion:

1. Query `flyway_schema_history` and confirm every migration through V44 succeeded.
2. Compare representative source/restored counts and foreign-key chains: workspaces/users, media assets, Jobs, RobotRuns/outputs, Drafts, schedules/publications and adaptive audit records.
3. Confirm `spring_session` and `spring_session_attributes` are empty. Restore always invalidates browser sessions.
4. Start an isolated API against the restored database and verify readiness plus authenticated login.
5. Restore/sample the corresponding private MinIO objects and verify database object keys resolve.
6. Record duration, dump checksum, row checks and operator. Remove the disposable database after the drill.

Promotion to replace a production database is deliberately not automated by these scripts. Use an approved maintenance/change process with a separate final backup, application quiescence, rollback plan and explicit database routing change.

## Multi-Replica Acceptance

1. Start at least four API replicas behind nginx.
2. Log in once and record only the distinct `X-FDM-API-Instance` values, never the cookie.
3. Repeat authenticated reads until more than one replica responds.
4. Perform one valid CSRF-protected mutation and confirm it succeeds regardless of replica.
5. Restart the login-serving replica; confirm the same shared session continues.
6. Log out; confirm all replicas reject the invalidated session.
7. Start replicas concurrently against a V43 database and confirm Flyway records one successful V44 row.

## Security And Limitations

Cookies remain HttpOnly for the session, readable only for the CSRF token, Secure in production and SameSite Lax. Session fixation protection rotates the id at login. Operations APIs require a normal authenticated user and remain workspace-scoped; Worker credentials cannot use browser administration endpoints.

Phase 17P does not provide Kubernetes manifests, a metrics/alerting vendor, automatic database promotion, multi-region replication, point-in-time recovery configuration, object-store replication, or zero-downtime schema compatibility guarantees for arbitrary future migrations. Backup frequency, retention, RPO/RTO, encryption keys, off-site copies and provider credentials remain deployment-owner responsibilities. The Phase 17Q control plane builds on these probes.

## Operations Control Plane

The **Operations** page (`/operations`) and `GET /api/operations/overview|workers|jobs|schedulers|publishing|incidents` are for workspace OWNER and ADMIN users. A MEMBER receives 403. Data is workspace-scoped; dependency and scheduler facts are platform-level and carry no tenant data. Nothing here changes platform state except `POST /api/operations/incidents/{id}/acknowledge`, which records that a person has seen an incident.

### Reading the overview

| Overall status | Meaning |
| --- | --- |
| `HEALTHY` | no WARNING or CRITICAL incident is active (INFO incidents never degrade) |
| `DEGRADED` | at least one WARNING incident is active |
| `ACTION_REQUIRED` | at least one CRITICAL incident is active |

Component status is `HEALTHY`, `DEGRADED`, `UNAVAILABLE` or `UNKNOWN`. `UNKNOWN` means not configured or not yet reported, never "probably fine". Workers are `ONLINE` (heartbeat within two intervals), `STALE` (missed heartbeats but inside `app.workers.offline-threshold`) or `OFFLINE`. The oldest queued Job age is `null` when nothing is queued. Schedulers are one logical row each (not one per replica) with their configured cadence and a stale threshold of `max(3 x cadence, 2 min)`.

### Incident catalog

| Key | Severity | Raised when | Suggested action |
| --- | --- | --- | --- |
| `DEPENDENCY:POSTGRES:UNAVAILABLE` | CRITICAL | PostgreSQL probe fails or times out | check the PostgreSQL container and connectivity |
| `DEPENDENCY:MINIO:UNAVAILABLE` | CRITICAL | MinIO probe fails or times out | check MinIO health and credentials; media import, clips and publishing are blocked |
| `DEPENDENCY:RABBITMQ:UNAVAILABLE` | INFO | broker unreachable (optional transport) | check the broker only if you rely on it |
| `WORKERS:NONE_ONLINE` | WARNING, CRITICAL if Jobs are queued | Workers exist but none is ONLINE/STALE | start or restart a Worker |
| `WORKERS:NONE_REGISTERED` | INFO, WARNING if Jobs are queued | no Worker has ever registered | register a Worker |
| `WORKERS:PARTIAL` | INFO | some Workers are stale or were seen offline within 24 h | check the listed Workers |
| `JOBS:BACKLOG` | WARNING over 5 min, CRITICAL over 30 min | oldest queued Job age | confirm Workers support the queued types |
| `JOBS:LEASE_EXPIRED` | WARNING | Jobs hold an expired lease | recovered on the next claim; confirm a Worker is online |
| `JOBS:FAILURE_BURST` | WARNING | at least 3 Jobs failed in the last hour | inspect the shared failure category |
| `SCHEDULER:{name}:STALE` | WARNING, CRITICAL for `publish-schedule-dispatch` | no success within the stale threshold | confirm an API replica is running; check logs |
| `SCHEDULER:{name}:FAILED` | WARNING | newest completion failed and no recent success | check the failure code and logs |
| `PUBLISHING:OVERDUE` | WARNING over 5 min, CRITICAL over 30 min | a due schedule has not been dispatched | check the publish scheduler and a PUBLISH_MEDIA Worker |
| `PUBLISHING:OUTCOME_UNKNOWN` | CRITICAL | a publication outcome is ambiguous | verify on the provider before any retry; the platform never retries it |
| `PUBLISHING:FAILURE_BURST` | WARNING | at least 3 publications failed in the last hour | inspect the shared category; verify credentials |
| `PUBLISHING:PROVIDER:{PROVIDER}:FAILING` | WARNING | at least 3 failures with no success since | reconnect or verify the account |
| `AUTOMATION:ROLLBACK:{id}` | WARNING | a rollback recommendation is OPEN | review it in the Robot's adaptive lifecycle |

### Configuration

All properties live under `app.operations` and are clamped to safe bounds: `probe-timeout` (750 ms, 100 ms to 5 s), `probe-cache-ttl` (10 s, 1 s to 5 min), `backlog-warning` (5 min), `backlog-critical` (30 min), `overdue-warning`, `overdue-critical`, `recent-window` (24 h), `failure-burst` (3), `failure-burst-window` (1 h), `stale-cadence-multiplier` (3), `stale-floor` (2 min), `incident-resolved-retention` (30 d), `scheduler-status-retention` (7 d), `incident-interval-ms` (30 s). Do not raise thresholds to make a page green; fix the cause.

### Procedures

1. `ACTION_REQUIRED`: open the incident, follow its suggested action, then acknowledge it so colleagues know it is being handled. Acknowledging does not clear it; it clears when the condition does.
2. Dependency verdicts can lag by up to the cache TTL plus the probe bound; use the manual Refresh button, which still reads the cached verdict, and wait one TTL before concluding that a recovery did not register.
3. After scaling API replicas, restart nginx so it re-resolves the upstream; replicas that were replaced stay visible in `reportingInstances` until the stale window passes.
4. A resolved incident stays in history for 30 days; a returning condition opens a new incident with a new first-observed time.

### Verified behavior

Acceptance observations (Docker Compose, Phase 17Q, one and four API replicas behind nginx):

- Latency, MinIO healthy: `overview` p50 21.9 ms, p95 27.2 ms, max 51.5 ms over 60 calls; `/api/operations/status` p50 21.6 ms, p95 32.8 ms. After recovery `overview` p50 14.8 ms, p95 26.9 ms, max 29 ms over 100 calls.
- Latency, MinIO stopped (the 17P gap was about 4.3 s for `/api/operations/status`): 75 `overview` calls one second apart (several cache expiries) gave p50 17.4 ms, p95 768 ms, max 796 ms with none above 1 s; the same 75 `/api/operations/status` calls gave p50 16.3 ms, p95 30.1 ms, max 769 ms with none above 1 s. The few slow calls are exactly the ones that land on an expired entry and wait for the 750 ms probe bound; all other calls read the cached verdict. Targets: under 500 ms normally, under 1 s with MinIO down (investigate above 1.5 s).
- MinIO outage with the page open: the page showed `DEPENDENCY:MINIO:UNAVAILABLE` (CRITICAL) and `Action required` within one auto-refresh cycle with no reload, and returned to `Healthy` after MinIO was started, with the incident resolved and kept in history. One active row existed for the key throughout.
- Worker outage: after a hard kill the Worker was reported `STALE` and then `OFFLINE` after 30 s, `WORKERS:NONE_ONLINE` was WARNING with an empty queue and CRITICAL once a Job was queued, and restarting the Worker drained the Job (one attempt) and cleared the incident.
- Fixtures: a stale hourly scheduler raised `SCHEDULER:adaptive-memory:STALE` and cleared when its timestamps were restored; a QUEUED Job of an unsupported type reported oldest age 600 s (WARNING) then 2400 s (CRITICAL) and cleared when deleted; an ambiguous publication raised `PUBLISHING:OUTCOME_UNKNOWN` with the guidance to verify on the provider; an existing rollback recommendation set to OPEN raised `AUTOMATION:ROLLBACK:{id}` with its subject and cleared when restored. All fixtures were reverted and verified.
- Four replicas: sixty requests were answered evenly by four instances with one incident-key set, one incident row per key, eleven logical scheduler rows (the fast schedulers reporting from four instances) over the per-instance rows; an acknowledgement posted to one replica was visible on all four, and the incident id, acknowledgement and first-observed time survived `docker compose restart api`.
- Security: unauthenticated calls returned 401; a MEMBER received 403 on every endpoint and on acknowledge; a POST without a CSRF header or with a forged one returned 403; unknown incident id 404; malformed id 400; an operator of another workspace saw zero Workers, Jobs and publications of this workspace, got 404 for its incident and Job, and its responses never contained this workspace's id. Response bodies contained no URL, signature, password, secret, cookie, session value, JDBC string, stack trace, payload, caption or Persona text.
- SQL cross-check: Job, Worker, publishing and adaptive-automation counts equal an independent SQL count for every field compared (22 of 22), the scheduler inventory is eleven logical rows, and there are no duplicate active incident keys or lifecycle violations.
- Logging: no WARN or ERROR line and no stack trace were written during a 75 s MinIO outage, and the API wrote no log line at all in a 60 s idle window.
- PostgreSQL outage: liveness stayed 200 and readiness 503; the operations calls failed with a clean 503 after about 10 s (two 5 s connection timeouts, since sessions and workspace lookups need the database) and the same browser session worked again once PostgreSQL returned. With the page open it kept the last good data, showed `Status temporarily unavailable. Showing data from N min ago.`, retried only the overview (not the five detail panels) with exponential back-off from the refresh interval up to 120 s, and recovered by itself after PostgreSQL was started. The control plane adds at most one bounded WARN line per 30 s reconciler tick; the pre-existing Hibernate, scheduler and health-indicator loggers still write their own warnings per failed request or tick during a database outage.
- Worker credentials do not open the control plane: a Worker bearer token on any `/api/operations/*` endpoint, including acknowledge, returned 401.
- Migration: V44 to V45 on a data-bearing database restored from a V44 dump applied V45 once and left business row counts unchanged; a fresh database applied V1 to V45; both V45 tables, six new indexes and the lifecycle, acknowledgement, severity, state and duration constraints, the one-active-key index and the workspace cascade were verified against the database.

The control plane does not provide PagerDuty, e-mail, SMS or Slack notification, Prometheus or Grafana export, tracing, Kubernetes integration, restart buttons, shell, SQL console, environment or secret viewers, arbitrary Job mutation, blind publication retry or any new adaptive behavior. Phase 17R is not started.
