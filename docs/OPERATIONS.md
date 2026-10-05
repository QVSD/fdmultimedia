# FDM Multimedia Operations Runbook

This runbook covers the Phase 17P Docker Compose deployment. It describes bounded recovery procedures; it is not an automatic failover system. Commands assume the repository root and an operator-authored `.env`. Never paste secrets, cookies, presigned URLs, dumps or media into tickets or source control.

## Service Model

The expected stack is PostgreSQL, RabbitMQ, MinIO, API, web and nginx. PostgreSQL is authoritative for domain records, distributed Jobs, leases, audit trails and HTTP sessions. MinIO is authoritative for private media objects. RabbitMQ is optional transport; PostgreSQL Jobs remain the durable work queue.

API replicas are stateless apart from bounded per-instance scheduler telemetry. Spring Session JDBC shares browser authentication and CSRF state through PostgreSQL. `X-FDM-API-Instance` identifies the serving replica for diagnostics. A replica restart must not log out a user while PostgreSQL remains available.

## Health And Operations

- `GET /api/health`: compatibility application health.
- `GET /api/actuator/health/liveness`: public process liveness only.
- `GET /api/actuator/health/readiness`: public readiness including PostgreSQL.
- `GET /api/actuator/health`: authenticated aggregate health.
- `GET /api/operations/status`: authenticated, workspace-scoped operational summary.

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

Phase 17P does not provide Kubernetes manifests, a metrics/alerting vendor, automatic database promotion, multi-region replication, point-in-time recovery configuration, object-store replication, or zero-downtime schema compatibility guarantees for arbitrary future migrations. Backup frequency, retention, RPO/RTO, encryption keys, off-site copies and provider credentials remain deployment-owner responsibilities. Phase 17Q is not started.
